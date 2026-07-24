package com.rar.hearth.web

import com.rar.hearth.config.ConfigJson
import com.rar.hearth.config.ConfigStore
import com.rar.hearth.config.DashConfig
import com.rar.hearth.config.VoiceSettings
import com.rar.hearth.config.decodeConfig
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Embedded LAN config server (NanoHTTPD). Serves the config page from [assetReader], a JSON API for
 * the DashConfig + entity picker, and PIN login. All /api/ routes except login require the session
 * cookie. Pure Java under the hood, so this runs in plain-JVM tests on an ephemeral port (port 0).
 */
class ConfigServer(
    port: Int = 8080,
    private val store: ConfigStore,
    private val sessions: SessionManager,
    private val pin: () -> String,
    private val notifyToken: () -> String,
    private val deviceName: () -> String,
    private val setDeviceName: (String?) -> Unit,
    private val setPin: (String) -> Unit = {},
    private val resetPin: () -> String = { "" },
    private val pushStore: com.rar.hearth.notify.PushNotificationStore,
    private val entitiesJson: () -> String,
    private val setup: SetupCoordinator,
    private val configured: () -> Boolean,
    private val connState: () -> String,
    private val haUrl: () -> String? = { null },
    private val disconnect: () -> Unit = {},
    private val lux: () -> Int? = { null },
    private val sendspinStatus: () -> String = { "disconnected" },
    // Diagnostics: the rolling on-disk log. Defaults keep tests that don't care
    // about logging free of the wiring.
    private val appVersion: () -> String = { "" },
    private val logText: (Int) -> String = { "" },
    private val logSizeBytes: () -> Long = { 0L },
    private val clearLog: () -> Unit = {},
    // MA sign-in bridge: exchanges credentials for a token device-side and persists it before
    // returning the display name. Defaults keep App.kt compiling until Task 6 wires MaLibrary.
    private val maSignIn: suspend (username: String, password: String) -> Result<String> =
        { _, _ -> Result.failure(IllegalStateException("not wired")) },
    private val maSignOut: () -> Unit = {},
    private val previewChime: (String, Int) -> Unit,
    private val previewEarcon: (Int) -> Unit,
    private val assetReader: (String) -> ByteArray?,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response =
        try {
            route(session)
        } catch (e: Exception) {
            android.util.Log.w("ConfigServer", "serve failed", e)
            error(Response.Status.INTERNAL_ERROR, "server error")
        }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri == "/api/login" && method == Method.POST) return handleLogin(session)
        if (uri == "/api/notify" && method == Method.POST) return handleNotify(session)
        if (uri == "/api/notify/clear" && method == Method.POST) return handleNotifyClear(session)
        if (uri == "/api/hello" && method == Method.GET) return handleHello()

        if (uri.startsWith("/api/")) {
            if (!authed(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized")
            return when {
                uri == "/api/config" && method == Method.GET ->
                    ok(ConfigJson.json.encodeToString(DashConfig.serializer(), store.config.value))
                uri == "/api/config" && method == Method.PUT -> handlePutConfig(session)
                uri == "/api/entities" && method == Method.GET -> ok(entitiesJson())
                uri == "/api/status" && method == Method.GET -> handleStatus()
                uri == "/api/disconnect" && method == Method.POST -> handleDisconnect()
                uri == "/api/name" && method == Method.PUT -> handlePutName(session)
                uri == "/api/pin" && method == Method.PUT -> handlePutPin(session)
                uri == "/api/pin/reset" && method == Method.POST -> handlePinReset()
                uri == "/api/setup/begin" && method == Method.POST -> handleSetupBegin(session)
                uri == "/api/setup/complete" && method == Method.POST -> handleSetupComplete(session)
                uri == "/api/voice/preview-chime" && method == Method.POST -> handlePreviewChime(session)
                uri == "/api/voice/preview-wake" && method == Method.POST -> handlePreviewWake(session)
                uri == "/api/log" && method == Method.GET -> handleLog(session)
                uri == "/api/log/clear" && method == Method.POST -> handleLogClear(session)
                uri == "/api/sendspin/login" && method == Method.POST -> handleMaLogin(session)
                uri == "/api/sendspin/logout" && method == Method.POST -> handleMaLogout()
                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        }

        if (method == Method.GET) {
            val path = if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')
            return asset(path)
        }
        return error(Response.Status.NOT_FOUND, "not found")
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val pinInput = runCatching {
            (ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject)["pin"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: ""
        return when (val r = sessions.login(pinInput, pin())) {
            is LoginResult.Ok -> ok("""{"ok":true}""").apply {
                addHeader("Set-Cookie", "session=${r.token}; Path=/; HttpOnly")
            }
            LoginResult.Invalid -> error(Response.Status.UNAUTHORIZED, "invalid pin")
            is LoginResult.LockedOut -> json(STATUS_429, buildJsonObject {
                put("error", "locked out"); put("retryAfter", r.retryAfterSeconds)
            }.toString())
        }
    }

    private fun handlePutConfig(session: IHTTPSession): Response {
        val body = readBody(session)
        val parsed = runCatching { decodeConfig(body) }.getOrElse {
            return error(Response.Status.BAD_REQUEST, "invalid config: ${it.message ?: "malformed"}")
        }
        val stored = store.update(parsed)
        return ok(ConfigJson.json.encodeToString(DashConfig.serializer(), stored))
    }

    private fun handleStatus(): Response =
        ok(buildJsonObject {
            put("configured", configured())
            put("connState", connState())
            put("haUrl", haUrl())        // stored HA base URL, or JSON null when unset
            put("lux", lux())            // int, or JSON null when no sensor reading yet
            put("notifyToken", notifyToken())
            put("deviceName", deviceName())
            put("pin", pin())
            put("sendspin", sendspinStatus())
            put("appVersion", appVersion())   // git-derived build string, same value HA shows as sw_version
            put("logBytes", logSizeBytes())   // drives the config page's "Device log (N KB)" line
        }.toString())

    /** Session-gated (see route()). Clears auth device-side and returns the device to setup. */
    private fun handleDisconnect(): Response {
        disconnect()
        return ok("""{"ok":true}""")
    }

    private fun handlePutName(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val raw = obj["name"]?.jsonPrimitive?.contentOrNull   // JSON null / missing -> null -> default
        setDeviceName(clampDeviceName(raw))
        return ok(buildJsonObject { put("name", deviceName()) }.toString())
    }

    /**
     * Clamp a raw device name to storage form, or null to reset to the computed default.
     * Order (per spec): trim; strip ASCII control chars (< 0x20 and 0x7F); collapse whitespace
     * runs to single spaces; truncate to 40; trim again. Empty after clamping (or null input) -> null.
     */
    private fun clampDeviceName(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        s = buildString { for (c in s) if (c.code >= 0x20 && c.code != 0x7F) append(c) }
        s = s.replace(Regex("\\s+"), " ")
        if (s.length > 40) s = s.substring(0, 40)
        s = s.trim()
        return s.ifEmpty { null }
    }

    /** Pre-auth: the device name + configured flag for the login card. Name is not a secret. */
    private fun handleHello(): Response =
        ok(buildJsonObject {
            put("name", deviceName())
            put("configured", configured())
        }.toString())

    /** Gated: set a custom 4–8 digit PIN. Does NOT clear the session cookie. */
    private fun handlePutPin(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val raw = obj["pin"]?.jsonPrimitive?.contentOrNull ?: ""
        if (!isValidCustomPin(raw)) return error(Response.Status.BAD_REQUEST, "invalid pin")
        setPin(raw)
        return ok(buildJsonObject { put("pin", raw) }.toString())
    }

    /** Gated: replace the PIN with a fresh random one and return it. */
    private fun handlePinReset(): Response {
        val newPin = resetPin()
        return ok(buildJsonObject { put("pin", newPin) }.toString())
    }

    /** Constant-time check of the `Authorization: Bearer <token>` header against the notify token. */
    private fun bearerAuthed(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false // NanoHTTPD lowercases keys
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return false
        val provided = header.substring(prefix.length).trim()
        val expected = notifyToken()
        if (expected.isBlank()) return false
        return java.security.MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun handleNotify(session: IHTTPSession): Response {
        if (!bearerAuthed(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized")
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return error(Response.Status.BAD_REQUEST, "missing title")
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonPrimitive?.contentOrNull
        val severity = obj["severity"]?.jsonPrimitive?.contentOrNull
        // timeout <= 0 or absent -> null (persistent); the store also guards this.
        val timeout = obj["timeout"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val effectiveId = pushStore.post(id, title, message, severity, timeout, System.currentTimeMillis())
        return ok(buildJsonObject { put("ok", true); put("id", effectiveId) }.toString())
    }

    private fun handleNotifyClear(session: IHTTPSession): Response {
        if (!bearerAuthed(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized")
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        if (obj["all"]?.jsonPrimitive?.booleanOrNull == true) {
            pushStore.clearAll()
            return ok("""{"ok":true}""")
        }
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank()) return error(Response.Status.BAD_REQUEST, "missing id")
        pushStore.clear(id) // clearing an unknown id is still ok:true (idempotent)
        return ok("""{"ok":true}""")
    }

    private fun handlePreviewChime(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }.getOrNull()
        val saved = store.config.value.voice
        val tone = obj?.get("tone")?.jsonPrimitive?.contentOrNull ?: saved.timerTone
        val volume = obj?.get("volume")?.jsonPrimitive?.intOrNull ?: saved.timerVolume
        val norm = VoiceSettings(timerTone = tone, timerVolume = volume).clamped()
        previewChime(norm.timerTone, norm.timerVolume)
        return ok("""{"ok":true}""")
    }

    private fun handlePreviewWake(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }.getOrNull()
        val saved = store.config.value.voice
        val volume = obj?.get("volume")?.jsonPrimitive?.intOrNull ?: saved.wakeSoundVolume
        val norm = VoiceSettings(wakeSoundVolume = volume).clamped()
        previewEarcon(norm.wakeSoundVolume)
        return ok("""{"ok":true}""")
    }

    /**
     * Session-gated: the retained device log as plain text, newest lines last.
     * `?limit=<bytes>` caps the response (default 256 KiB, hard ceiling 2 MiB) so a
     * browser on the LAN can't be handed an unbounded body. Served as a download so
     * it can be attached to a bug report rather than only read in the tab.
     */
    private fun handleLog(session: IHTTPSession): Response {
        val requested = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: DEFAULT_LOG_LIMIT
        val limit = requested.coerceIn(1, MAX_LOG_LIMIT)
        val body = logText(limit)
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body).apply {
            addHeader("Content-Disposition", "attachment; filename=\"hearth-log.txt\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    /** Session-gated: discard the retained log and report the (now zero) size. */
    private fun handleLogClear(session: IHTTPSession): Response {
        // Drain the request body even though it is unused: NanoHTTPD leaves an
        // unconsumed body in the socket, which misframes the next request on a
        // keep-alive connection.
        readBody(session)
        clearLog()
        return ok(buildJsonObject { put("ok", true); put("bytes", logSizeBytes()) }.toString())
    }

    private fun handleMaLogin(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        // The username is trimmed (copy-paste whitespace); the password is passed through
        // verbatim and exists only in this request scope — never logged, never persisted here.
        val username = obj["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (username.isBlank() || password.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "missing credentials")
        }
        // runBlocking is fine here: we're on a NanoHTTPD worker thread, and the MA auth handshake
        // has a 10s upstream timeout, so the request returns promptly instead of pinning the worker.
        val result = runBlocking { maSignIn(username, password) }
        return result.fold(
            // Display name only — the token travels to the browser solely as part of the persisted
            // config document (GET /api/config), never in the login response.
            onSuccess = { userName ->
                ok(buildJsonObject { put("ok", true); put("userName", userName) }.toString())
            },
            onFailure = { e ->
                json(STATUS_502, buildJsonObject {
                    put("ok", false); put("error", e.message ?: "sign-in failed")
                }.toString())
            },
        )
    }

    private fun handleMaLogout(): Response {
        maSignOut()
        return ok("""{"ok":true}""")
    }

    private fun handleSetupBegin(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val haUrl = obj["haUrl"]?.jsonPrimitive?.contentOrNull ?: ""
        val clientId = obj["clientId"]?.jsonPrimitive?.contentOrNull ?: ""
        if (clientId.isBlank()) return error(Response.Status.BAD_REQUEST, "missing clientId")
        return when (val r = setup.begin(haUrl, clientId)) {
            is BeginResult.Ok -> ok(buildJsonObject { put("authorizeUrl", r.authorizeUrl) }.toString())
            is BeginResult.Invalid -> error(Response.Status.BAD_REQUEST, r.message)
        }
    }

    private fun handleSetupComplete(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val code = obj["code"]?.jsonPrimitive?.contentOrNull ?: ""
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: ""
        return when (val r = setup.complete(code, state)) {
            CompleteResult.Ok -> ok("""{"ok":true}""")
            is CompleteResult.BadState -> error(Response.Status.BAD_REQUEST, r.message)
            is CompleteResult.ExchangeFailed -> error(STATUS_502, r.message)
        }
    }

    private fun authed(session: IHTTPSession): Boolean = sessions.isValidSession(sessionToken(session))

    private fun sessionToken(session: IHTTPSession): String? =
        session.headers["cookie"]?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("session=") }?.substringAfter("session=")

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        files["postData"]?.let { return it }
        // NanoHTTPD quirk: PUT bodies are saved to a temp file whose PATH is stored under "content"
        return files["content"]?.let { f -> runCatching { java.io.File(f).readText() }.getOrNull() } ?: ""
    }

    private fun asset(path: String): Response {
        // Defense-in-depth: NanoHTTPD percent-decodes the URI but never normalizes ".." segments,
        // so a raw request path is handed to us (and would be handed to assetReader) verbatim.
        // Reject traversal here rather than trusting whatever assetReader is injected.
        if (path.isBlank() || path.split('/').any { it == ".." }) {
            return error(Response.Status.NOT_FOUND, "not found")
        }
        val bytes = assetReader(path) ?: return error(Response.Status.NOT_FOUND, "not found")
        return newFixedLengthResponse(Response.Status.OK, mimeOf(path), ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "no-cache") } // assets change on app update; force revalidation
    }

    private fun ok(body: String): Response = json(Response.Status.OK, body)

    private fun error(status: Response.IStatus, reason: String): Response =
        json(status, buildJsonObject { put("error", reason) }.toString())

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".ttf") -> "font/ttf"
        else -> "application/octet-stream"
    }

    companion object {
        // Default covers the full retained log (FileLog keeps 1 MB × 2) so a plain
        // download gets everything; the ceiling leaves headroom without being unbounded.
        private const val DEFAULT_LOG_LIMIT = 2 * 1024 * 1024
        private const val MAX_LOG_LIMIT = 4 * 1024 * 1024

        private val STATUS_429 = object : Response.IStatus {
            override fun getRequestStatus(): Int = 429
            override fun getDescription(): String = "429 Too Many Requests"
        }
        private val STATUS_502 = object : Response.IStatus {
            override fun getRequestStatus(): Int = 502
            override fun getDescription(): String = "502 Bad Gateway"
        }
    }
}
