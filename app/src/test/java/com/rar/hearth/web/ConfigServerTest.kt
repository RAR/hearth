package com.rar.hearth.web

import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.ha.AuthManager
import com.rar.hearth.notify.PushNotificationStore
import com.rar.hearth.ui.model.NotifSeverity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import kotlin.random.Random

class ConfigServerTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var store: ConfigStore
    private lateinit var pushStore: PushNotificationStore
    private lateinit var base: String
    private val requestedAssetPaths = mutableListOf<String>()
    private val previewCalls = mutableListOf<Pair<String, Int>>()
    private var customName: String? = null
    private val setNameCalls = mutableListOf<String?>()
    private val defaultName = "Hearth (Pixel 1234)"
    private val maSignInCalls = mutableListOf<Pair<String, String>>()
    private var maSignInResult: Result<String> = Result.success("Andrew")
    private var maSignOutCalls = 0

    private fun tempDir(): File =
        File.createTempFile("cfgserver", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        store = ConfigStore(tempDir())
        pushStore = PushNotificationStore()
        server = ConfigServer(
            port = 0,
            store = store,
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            notifyToken = { "testtoken" },
            deviceName = { customName ?: defaultName },
            setDeviceName = { v -> setNameCalls += v; customName = v },
            pushStore = pushStore,
            entitiesJson = { """[{"id":"light.k","name":"K","domain":"light","state":"on"}]""" },
            setup = SetupCoordinator(AuthManager(InMemorySettingsStore(), OkHttpClient()), onConfigured = {}),
            configured = { false },
            connState = { "OFFLINE" },
            lux = { 42 },
            maSignIn = { username, password -> maSignInCalls += username to password; maSignInResult },
            maSignOut = { maSignOutCalls++ },
            previewChime = { tone, volume -> previewCalls += tone to volume },
            previewEarcon = { },
            assetReader = { path ->
                requestedAssetPaths += path
                if (path == "index.html") "<html>ok</html>".toByteArray() else null
            },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    /**
     * Sends a raw HTTP/1.1 request line verbatim over a socket. OkHttp's own HttpUrl normalizes
     * (and even double-decodes) ".." path segments before a request is ever built, so it cannot be
     * used to reach the server with a literal traversal path on the wire -- this is the only way to
     * pin what NanoHTTPD itself actually receives and hands to route().
     */
    private fun rawRequest(requestLine: String): String {
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.getOutputStream().write(
                "$requestLine HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
            )
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @After
    fun tearDown() { server.stop() }

    private fun login(pin: String): okhttp3.Response =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"$pin"}""".toRequestBody(json)).build()).execute()

    private fun cookieFrom(resp: okhttp3.Response): String =
        resp.header("Set-Cookie")!!.substringBefore(";") // "session=<token>"

    @Test
    fun apiRequiresSessionCookie() {
        http.newCall(Request.Builder().url("$base/api/config").build()).execute().use { r ->
            assertEquals(401, r.code)
        }
    }

    @Test
    fun wrongPinReturns401() {
        login("000000").use { r -> assertEquals(401, r.code) }
    }

    @Test
    fun loginGetPutEntitiesRoundTrip() {
        val cookie = login("123456").use { r ->
            assertEquals(200, r.code)
            cookieFrom(r)
        }

        // GET config
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"version\":1"))
            }

        // PUT config (valid) -> 200 stored, persisted
        val putBody = """{"version":1,"home":{"photoFolder":"nas","photoCacheCap":9000}}"""
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie)
            .put(putBody.toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                val text = r.body!!.string()
                assertTrue(text.contains("\"photoFolder\":\"nas\""))
                assertTrue(text.contains("\"photoCacheCap\":500")) // clamped
            }
        assertEquals("nas", store.config.value.home.photoFolder)

        // GET entities
        http.newCall(Request.Builder().url("$base/api/entities").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"light.k\""))
            }
    }

    @Test
    fun putInvalidBodyReturns400AndLeavesConfigUntouched() {
        val cookie = cookieFrom(login("123456"))
        val before = store.config.value
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie)
            .put("{ not valid json".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
                assertTrue(r.body!!.string().contains("\"error\""))
            }
        assertEquals(before, store.config.value) // untouched
    }

    @Test
    fun fiveWrongPinsLockOutWith429() {
        repeat(4) { login("000000").use { assertEquals(401, it.code) } }
        login("000000").use { assertEquals(429, it.code) }
        // even the correct pin is refused during lockout
        login("123456").use { assertEquals(429, it.code) }
    }

    @Test
    fun rootServesIndexAsset() {
        http.newCall(Request.Builder().url("$base/").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertEquals("<html>ok</html>", r.body!!.string())
        }
    }

    @Test
    fun missingAssetReturns404() {
        http.newCall(Request.Builder().url("$base/nope.js").build()).execute().use { r ->
            assertEquals(404, r.code)
        }
    }

    @Test
    fun traversalPathIsRejectedWithoutReachingAssetReader() {
        // Literal ".." in the request line: OkHttp's HttpUrl normalizes (and even double-decodes)
        // dot segments before a request can be built, so a raw socket is used to send the exact
        // wire bytes NanoHTTPD receives -- percent-encoding isn't even necessary since NanoHTTPD
        // does no dot-segment normalization on its single percent-decode pass.
        val response = rawRequest("GET /../secret")
        assertTrue("expected 404, got: ${response.lineSequence().first()}", response.startsWith("HTTP/1.1 404"))
        assertFalse(requestedAssetPaths.any { it.split('/').any { seg -> seg == ".." } })
    }

    @Test
    fun putToLoginAndPostToConfigAreNotConfusable() {
        val before = store.config.value

        http.newCall(Request.Builder().url("$base/api/login")
            .put("""{"pin":"123456"}""".toRequestBody(json)).build()).execute().use { r ->
                assertTrue(r.code == 401 || r.code == 404)
                assertTrue(r.code != 200)
            }
        // The route rejects unauthenticated /api/ requests before reading the body, so a reused
        // keep-alive connection would carry the unread PUT body into the next request line and
        // corrupt it. Evict the pooled connection so the POST below starts on a fresh socket.
        http.connectionPool.evictAll()

        val putBody = """{"version":1,"home":{"photoFolder":"nas","photoCacheCap":9000}}"""
        http.newCall(Request.Builder().url("$base/api/config")
            .post(putBody.toRequestBody(json)).build()).execute().use { r ->
                assertTrue(r.code == 401 || r.code == 404)
                assertTrue(r.code != 200)
            }

        assertEquals(before, store.config.value)
    }

    @Test
    fun localIpAddressIsNullOrIpv4() {
        val ip = localIpAddress()
        assertTrue(ip == null || ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
        assertNotNull(server) // touch server so the test is meaningful even if no LAN IP is present
    }

    @Test
    fun previewChimeRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime")
            .post("""{"tone":"beeps","volume":50}""".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(401, r.code) }
        assertTrue(previewCalls.isEmpty())
    }

    @Test
    fun previewChimeClampsAndFires() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime").header("Cookie", cookie)
            .post("""{"tone":"nope","volume":250}""".toRequestBody(json)).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"ok\":true"))
            }
        assertEquals(listOf("argon" to 100), previewCalls) // unknown->argon, 250->100
    }

    @Test
    fun previewChimeDefaultsToSavedConfigWhenFieldsOmitted() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime").header("Cookie", cookie)
            .post("{}".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(200, r.code) }
        // saved config is default VoiceSettings: argon @ 80
        assertEquals(listOf("argon" to 80), previewCalls)
    }

    @Test
    fun statusIncludesLuxReading() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                val body = r.body!!.string()
                assertTrue(body.contains("\"lux\":42"))
                assertTrue(body.contains("\"connState\":\"OFFLINE\""))
            }
    }

    @Test
    fun notifyMissingTokenReturns401() {
        http.newCall(Request.Builder().url("$base/api/notify")
            .post("""{"title":"Hi"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyWrongTokenReturns401() {
        http.newCall(Request.Builder().url("$base/api/notify").header("Authorization", "Bearer nope")
            .post("""{"title":"Hi"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyValidPostStoresItemAndReturnsId() {
        http.newCall(Request.Builder().url("$base/api/notify").header("Authorization", "Bearer testtoken")
            .post("""{"title":"Laundry done","message":"go get it","severity":"warning","id":"chores"}"""
                .toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                val body = r.body!!.string()
                assertTrue(body.contains("\"ok\":true"))
                assertTrue(body.contains("\"id\":\"chores\""))
            }
        val item = pushStore.items.value.single()
        assertEquals("chores", item.id)
        assertEquals("Laundry done", item.title)
        assertEquals("go get it", item.message)
        assertEquals(NotifSeverity.WARNING, item.severity)
    }

    @Test
    fun notifyBlankTitleReturns400() {
        http.newCall(Request.Builder().url("$base/api/notify").header("Authorization", "Bearer testtoken")
            .post("""{"title":"   "}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyMalformedBodyReturns400() {
        http.newCall(Request.Builder().url("$base/api/notify").header("Authorization", "Bearer testtoken")
            .post("{ not json".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyClearByIdRemovesItem() {
        pushStore.post("chores", "T", null, null, null, System.currentTimeMillis())
        http.newCall(Request.Builder().url("$base/api/notify/clear").header("Authorization", "Bearer testtoken")
            .post("""{"id":"chores"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"ok\":true"))
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyClearAllRemovesEverything() {
        pushStore.post("a", "A", null, null, null, 0L)
        pushStore.post("b", "B", null, null, null, 0L)
        http.newCall(Request.Builder().url("$base/api/notify/clear").header("Authorization", "Bearer testtoken")
            .post("""{"all":true}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun notifyClearUnknownIdStillOk() {
        http.newCall(Request.Builder().url("$base/api/notify/clear").header("Authorization", "Bearer testtoken")
            .post("""{"id":"ghost"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"ok\":true"))
            }
    }

    @Test
    fun notifyClearNeitherIdNorAllReturns400() {
        http.newCall(Request.Builder().url("$base/api/notify/clear").header("Authorization", "Bearer testtoken")
            .post("{}".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
            }
    }

    @Test
    fun notifyTokenDoesNotAuthorizeConfig() {
        http.newCall(Request.Builder().url("$base/api/config").header("Authorization", "Bearer testtoken")
            .build()).execute().use { r -> assertEquals(401, r.code) }
    }

    @Test
    fun sessionCookieDoesNotAuthorizeNotify() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/notify").header("Cookie", cookie)
            .post("""{"title":"Hi"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(pushStore.items.value.isEmpty())
    }

    @Test
    fun statusIncludesNotifyToken() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"notifyToken\":\"testtoken\""))
            }
    }

    @Test
    fun renameStripsControlCharsAndCollapsesWhitespace() {
        val cookie = cookieFrom(login("123456"))
        // JSON-escaped NUL ( ) and DEL () are decoded to real control chars by the
        // parser, then stripped; the run of spaces collapses to one. Written as \\u.... so the
        // wire bytes are the JSON escape, not a raw control char (the parser is not lenient).
        val body = "{\"name\":\"  My\\u0000Kitchen\\u007f   Hearth  \"}"
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put(body.toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"MyKitchen Hearth\""))
            }
        assertEquals("MyKitchen Hearth", setNameCalls.last())
        assertEquals("MyKitchen Hearth", customName)
    }

    @Test
    fun renameTruncatesToFortyChars() {
        val cookie = cookieFrom(login("123456"))
        val fifty = "A".repeat(50)
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("""{"name":"$fifty"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"${"A".repeat(40)}\""))
            }
        assertEquals("A".repeat(40), setNameCalls.last())
    }

    @Test
    fun renameEmptyResetsToDefault() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("""{"name":"    "}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"Hearth (Pixel 1234)\""))
            }
        assertNull(setNameCalls.last())   // setter received null = reset to default
    }

    @Test
    fun renameMissingNameResetsToDefault() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("{}".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"Hearth (Pixel 1234)\""))
            }
        assertNull(setNameCalls.last())
    }

    @Test
    fun renameMalformedBodyReturns400() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("{ not json".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
            }
        assertTrue(setNameCalls.isEmpty())
    }

    @Test
    fun renameRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/name")
            .put("""{"name":"Study"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(setNameCalls.isEmpty())
    }

    @Test
    fun statusIncludesDeviceName() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"deviceName\":\"Hearth (Pixel 1234)\""))
            }
    }

    @Test
    fun maLoginRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/sendspin/login")
            .post("""{"username":"andrew","password":"secret"}""".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(401, r.code) }
        assertTrue(maSignInCalls.isEmpty())
    }

    @Test
    fun maLogoutRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/sendspin/logout")
            .post("{}".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(401, r.code) }
        assertEquals(0, maSignOutCalls)
    }

    @Test
    fun maLoginReturnsUserNameOnlyAndPassesPasswordVerbatim() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/sendspin/login").header("Cookie", cookie)
            .post("""{"username":"  andrew ","password":" s3cret! "}""".toRequestBody(json)).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                // Exact body: display name only — the token must never travel in the login response.
                assertEquals("""{"ok":true,"userName":"Andrew"}""", r.body!!.string())
            }
        // Username is trimmed (copy-paste whitespace); the password goes through verbatim.
        assertEquals(listOf("andrew" to " s3cret! "), maSignInCalls)
    }

    @Test
    fun maLoginFailureReturns502WithError() {
        maSignInResult = Result.failure(Exception("MA server unreachable"))
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/sendspin/login").header("Cookie", cookie)
            .post("""{"username":"andrew","password":"pw"}""".toRequestBody(json)).build())
            .execute().use { r ->
                assertEquals(502, r.code)
                val body = r.body!!.string()
                assertTrue(body.contains("\"ok\":false"))
                assertTrue(body.contains("MA server unreachable"))
            }
    }

    @Test
    fun maLoginMalformedBodyReturns400() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/sendspin/login").header("Cookie", cookie)
            .post("{ not json".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(400, r.code) }
        assertTrue(maSignInCalls.isEmpty())
    }

    @Test
    fun maLoginMissingCredentialsReturns400() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/sendspin/login").header("Cookie", cookie)
            .post("""{"username":"   "}""".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(400, r.code) }
        assertTrue(maSignInCalls.isEmpty())
    }

    @Test
    fun maLogoutInvokesSignOutAndReturnsOk() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/sendspin/logout").header("Cookie", cookie)
            .post("{}".toRequestBody(json)).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"ok\":true"))
            }
        assertEquals(1, maSignOutCalls)
    }
}
