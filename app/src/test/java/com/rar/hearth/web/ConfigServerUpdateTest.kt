package com.rar.hearth.web

import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.ha.AuthManager
import com.rar.hearth.notify.PushNotificationStore
import com.rar.hearth.update.UpdateStage
import com.rar.hearth.update.UpdateStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class ConfigServerUpdateTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var base: String

    private val startCalls = mutableListOf<String>()
    private var startResult = true
    private var status = UpdateStatus()

    private fun tempDir(): File =
        File.createTempFile("cfgupd", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        server = ConfigServer(
            port = 0,
            store = ConfigStore(tempDir()),
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            notifyToken = { "testtoken" },
            deviceName = { "Hearth (Pixel 1234)" },
            setDeviceName = { },
            pushStore = PushNotificationStore(),
            entitiesJson = { "[]" },
            setup = SetupCoordinator(
                AuthManager(InMemorySettingsStore(), OkHttpClient()), onConfigured = {}),
            configured = { true },
            connState = { "CONNECTED" },
            appVersion = { "0.2.514+abc1234" },
            appVersionCode = { 514 },
            startUpdate = { url -> startCalls += url; startResult },
            updateStatus = { status },
            previewChime = { _, _ -> },
            previewEarcon = { },
            assetReader = { null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() = server.stop()

    private fun cookie(): String =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"123456"}""".toRequestBody(json)).build()).execute().use {
            it.header("Set-Cookie")!!.substringBefore(";")
        }

    private fun get(path: String, c: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", c).build()).execute()

    private fun post(path: String, c: String, body: String) =
        http.newCall(Request.Builder().url("$base$path").header("Cookie", c)
            .post(body.toRequestBody(json)).build()).execute()

    @Test
    fun statusCarriesTheVersionCodeSoTheBrowserCanCompare() {
        val c = cookie()
        get("/api/status", c).use { r ->
            assertEquals(200, r.code)
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            assertEquals(514, o["appVersionCode"]!!.jsonPrimitive.content.toInt())
            assertEquals("0.2.514+abc1234", o["appVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun postStartsAnUpdateAndPassesTheUrlThrough() {
        val c = cookie()
        val url = "https://github.com/RAR/hearth/releases/download/v0.2.515/hearth.apk"
        post("/api/update", c, """{"url":"$url"}""").use { r ->
            assertEquals(200, r.code)
        }
        assertEquals(listOf(url), startCalls)
    }

    @Test
    fun postRejectsADisallowedUrlWith400WithoutCallingTheUpdater() {
        // ConfigServer checks isAllowedApkUrl itself now (defense in depth alongside
        // ApkUpdater.start()'s own check), so a disallowed URL never reaches the updater and is
        // told apart from "an update is already running" -- a security rejection, not a
        // concurrency one.
        val c = cookie()
        post("/api/update", c, """{"url":"https://evil.example/x.apk"}""").use { r ->
            assertEquals(400, r.code)
        }
        assertTrue("updater must not be called for a disallowed url", startCalls.isEmpty())
    }

    @Test
    fun postReports409WhenAnUpdateIsAlreadyInFlight() {
        // An allowlisted URL that the updater itself refuses (busy) is a concurrency rejection,
        // not a security one -- it must surface as 409, distinct from the disallowed-url 400.
        startResult = false
        val c = cookie()
        val url = "https://github.com/RAR/hearth/releases/download/v0.2.515/hearth.apk"
        post("/api/update", c, """{"url":"$url"}""").use { r ->
            assertEquals(409, r.code)
        }
        assertEquals(listOf(url), startCalls)
    }

    @Test
    fun postRejectsAMissingOrMalformedBodyWithoutCallingTheUpdater() {
        val c = cookie()
        post("/api/update", c, """{"nope":1}""").use { r -> assertEquals(400, r.code) }
        post("/api/update", c, """not json""").use { r -> assertEquals(400, r.code) }
        assertTrue("updater must not be called for a malformed request", startCalls.isEmpty())
    }

    @Test
    fun getReportsTheCurrentStage() {
        status = UpdateStatus(
            stage = UpdateStage.DOWNLOADING, versionName = "0.2.515+def", progressPct = 37)
        val c = cookie()
        get("/api/update", c).use { r ->
            assertEquals(200, r.code)
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            assertEquals("downloading", o["state"]!!.jsonPrimitive.content)
            assertEquals(37, o["progressPct"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun updateEndpointsRequireASession() {
        // An unauthenticated LAN client must not be able to trigger an install.
        http.newCall(Request.Builder().url("$base/api/update")
            .post("""{"url":"https://github.com/RAR/hearth/releases/download/v1/x.apk"}"""
                .toRequestBody(json)).build()).execute().use { r ->
            assertEquals(401, r.code)
        }
        assertTrue(startCalls.isEmpty())
    }
}
