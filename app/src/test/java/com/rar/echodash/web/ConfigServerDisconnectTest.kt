package com.rar.echodash.web

import com.rar.echodash.config.ConfigStore
import com.rar.echodash.data.InMemorySettingsStore
import com.rar.echodash.ha.AuthManager
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

class ConfigServerDisconnectTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var base: String

    // Test-controlled server state the ConfigServer callbacks read/mutate.
    private var haUrlValue: String? = null
    private var configuredFlag = true
    private var disconnectCount = 0

    private fun tempDir(): File =
        File.createTempFile("cfgdisc", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        val settings = InMemorySettingsStore()
        val setup = SetupCoordinator(AuthManager(settings, OkHttpClient()), onConfigured = {})
        server = ConfigServer(
            port = 0,
            store = ConfigStore(tempDir()),
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            notifyToken = { "testtoken" },
            deviceName = { "Hearth" },
            setDeviceName = { },
            pushStore = com.rar.echodash.notify.PushNotificationStore(),
            entitiesJson = { "[]" },
            setup = setup,
            configured = { configuredFlag },
            connState = { "CONNECTED" },
            haUrl = { haUrlValue },
            disconnect = { disconnectCount++; configuredFlag = false },
            previewChime = { _, _ -> },
            previewEarcon = { },
            assetReader = { null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() { server.stop() }

    private fun login(): String =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"123456"}""".toRequestBody(json)).build())
            .execute().use { it.header("Set-Cookie")!!.substringBefore(";") }

    private fun post(path: String, body: String, cookie: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", cookie)
            .post(body.toRequestBody(json)).build()).execute()

    private fun status(cookie: String): String =
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { it.body!!.string() }

    @Test
    fun statusHaUrlIsNullWhenUnsetAndStringWhenSet() {
        val cookie = login()
        assertTrue(status(cookie).contains("\"haUrl\":null"))
        haUrlValue = "http://homeassistant.local:8123"
        assertTrue(status(cookie).contains("\"haUrl\":\"http://homeassistant.local:8123\""))
    }

    @Test
    fun disconnectRequiresSession() {
        post("/api/disconnect", "", "session=nope").use {
            assertEquals(401, it.code)
        }
        assertEquals(0, disconnectCount)
    }

    @Test
    fun disconnectWithSessionInvokesCallbackAndFlipsConfigured() {
        val cookie = login()
        assertTrue(status(cookie).contains("\"configured\":true"))
        post("/api/disconnect", "", cookie).use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("\"ok\":true"))
        }
        assertEquals(1, disconnectCount)
        assertTrue(status(cookie).contains("\"configured\":false"))
    }
}
