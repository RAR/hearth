package com.rar.echodash.web

import com.rar.echodash.config.ConfigStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ConfigServerTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var store: ConfigStore
    private lateinit var base: String

    private fun tempDir(): File =
        File.createTempFile("cfgserver", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        store = ConfigStore(tempDir())
        server = ConfigServer(
            port = 0,
            store = store,
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            entitiesJson = { """[{"id":"light.k","name":"K","domain":"light","state":"on"}]""" },
            assetReader = { path -> if (path == "index.html") "<html>ok</html>".toByteArray() else null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
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
    fun localIpAddressIsNullOrIpv4() {
        val ip = localIpAddress()
        assertTrue(ip == null || ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
        assertNotNull(server) // touch server so the test is meaningful even if no LAN IP is present
    }
}
