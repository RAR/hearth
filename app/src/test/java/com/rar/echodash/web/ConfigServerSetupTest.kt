package com.rar.echodash.web

import com.rar.echodash.config.ConfigStore
import com.rar.echodash.data.InMemorySettingsStore
import com.rar.echodash.ha.AuthManager
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ConfigServerSetupTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var ha: FakeHa
    private lateinit var settings: InMemorySettingsStore
    private lateinit var base: String

    /** Throwaway HA token endpoint that always issues tokens. */
    private class FakeHa : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}""")
        }
    }

    private fun tempDir(): File =
        File.createTempFile("cfgsetup", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        ha = FakeHa(); ha.start()
        settings = InMemorySettingsStore()
        val auth = AuthManager(settings, OkHttpClient())
        val setup = SetupCoordinator(auth, onConfigured = {})
        server = ConfigServer(
            port = 0,
            store = ConfigStore(tempDir()),
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            entitiesJson = { "[]" },
            setup = setup,
            configured = { settings.refreshToken != null },
            connState = { "OFFLINE" },
            assetReader = { null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() { server.stop(); ha.stop() }

    private fun haUrl() = "http://127.0.0.1:${ha.listeningPort}"

    private fun login(): String =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"123456"}""".toRequestBody(json)).build())
            .execute().use { it.header("Set-Cookie")!!.substringBefore(";") }

    private fun post(path: String, body: String, cookie: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", cookie)
            .post(body.toRequestBody(json)).build()).execute()

    @Test
    fun statusRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/status").build()).execute().use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun setupRoutesRequireSession() {
        post("/api/setup/begin", """{"haUrl":"x","clientId":"y"}""", "session=nope").use {
            assertEquals(401, it.code)
        }
        // The 401 path above returns before the request body is read, leaving unread bytes on the
        // socket; evict the pooled connection so the next call starts on a fresh socket (same quirk
        // documented in ConfigServerTest.putToLoginAndPostToConfigAreNotConfusable).
        http.connectionPool.evictAll()
        post("/api/setup/complete", """{"code":"x","state":"y"}""", "session=nope").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun statusReportsConfiguredFalseThenTrueAcrossFullFlow() {
        val cookie = login()
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                val body = r.body!!.string()
                assertTrue(body.contains("\"configured\":false"))
                assertTrue(body.contains("\"connState\":\"OFFLINE\""))
            }
        val authorizeUrl = post("/api/setup/begin",
            """{"haUrl":"${haUrl()}","clientId":"http://c/"}""", cookie).use {
                assertEquals(200, it.code)
                it.body!!.string().substringAfter("\"authorizeUrl\":\"").substringBefore("\"")
            }
        val state = authorizeUrl.substringAfter("state=")
        post("/api/setup/complete", """{"code":"CODE","state":"$state"}""", cookie).use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("\"ok\":true"))
        }
        assertEquals("RT", settings.refreshToken)
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r -> assertTrue(r.body!!.string().contains("\"configured\":true")) }
    }

    @Test
    fun completeWithBadStateReturns400() {
        val cookie = login()
        post("/api/setup/begin", """{"haUrl":"${haUrl()}","clientId":"http://c/"}""", cookie).close()
        post("/api/setup/complete", """{"code":"CODE","state":"bogus"}""", cookie).use {
            assertEquals(400, it.code)
        }
    }

    @Test
    fun beginWithInvalidUrlReturns400() {
        val cookie = login()
        post("/api/setup/begin", """{"haUrl":"   ","clientId":"http://c/"}""", cookie).use {
            assertEquals(400, it.code)
        }
    }
}
