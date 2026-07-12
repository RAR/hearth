package com.rar.echodash.web

import com.rar.echodash.config.ConfigJson
import com.rar.echodash.config.ConfigStore
import com.rar.echodash.config.DashConfig
import com.rar.echodash.config.decodeConfig
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    private val entitiesJson: () -> String,
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

        if (uri.startsWith("/api/")) {
            if (!authed(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized")
            return when {
                uri == "/api/config" && method == Method.GET ->
                    ok(ConfigJson.json.encodeToString(DashConfig.serializer(), store.config.value))
                uri == "/api/config" && method == Method.PUT -> handlePutConfig(session)
                uri == "/api/entities" && method == Method.GET -> ok(entitiesJson())
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
        else -> "application/octet-stream"
    }

    companion object {
        private val STATUS_429 = object : Response.IStatus {
            override fun getRequestStatus(): Int = 429
            override fun getDescription(): String = "429 Too Many Requests"
        }
    }
}
