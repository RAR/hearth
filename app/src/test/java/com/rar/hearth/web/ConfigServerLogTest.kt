package com.rar.hearth.web

import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.diag.FileLog
import com.rar.hearth.ha.AuthManager
import com.rar.hearth.notify.PushNotificationStore
import java.io.File
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The /api/log surface: session-gated read, bounded body, and clear. */
class ConfigServerLogTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var logDir: File
    private lateinit var fileLog: FileLog
    private lateinit var base: String

    private fun tempDir(): File =
        File.createTempFile("cfglog", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        logDir = tempDir()
        fileLog = FileLog(logDir, "log.txt")
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
            setup = SetupCoordinator(AuthManager(InMemorySettingsStore(), OkHttpClient()), onConfigured = {}),
            configured = { false },
            connState = { "OFFLINE" },
            logText = { limit -> fileLog.tail(limit) },
            logSizeBytes = { fileLog.sizeBytes() },
            clearLog = { fileLog.clear() },
            previewChime = { _, _ -> },
            previewEarcon = { },
            assetReader = { null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        server.stop()
        logDir.deleteRecursively()
    }

    private fun cookie(): String =
        http.newCall(
            Request.Builder().url("$base/api/login")
                .post("""{"pin":"123456"}""".toRequestBody(json)).build()
        ).execute().use { it.header("Set-Cookie")!!.substringBefore(";") }

    private fun getLog(query: String = "", cookie: String? = null): okhttp3.Response =
        http.newCall(
            Request.Builder().url("$base/api/log$query")
                .apply { cookie?.let { header("Cookie", it) } }.build()
        ).execute()

    @Test
    fun logRequiresSession() {
        getLog().use { assertEquals(401, it.code) }
    }

    @Test
    fun clearRequiresSession() {
        http.newCall(
            Request.Builder().url("$base/api/log/clear").post("{}".toRequestBody(json)).build()
        ).execute().use { assertEquals(401, it.code) }
    }

    @Test
    fun returnsRetainedLinesAsPlainTextAttachment() {
        fileLog.append("hello from the device")
        val c = cookie()
        getLog(cookie = c).use { r ->
            assertEquals(200, r.code)
            assertTrue(r.header("Content-Type")!!.startsWith("text/plain"))
            assertTrue(r.header("Content-Disposition")!!.contains("hearth-log.txt"))
            assertTrue(r.body!!.string().contains("hello from the device"))
        }
    }

    @Test
    fun emptyLogIsAnEmptyBodyNotAnError() {
        getLog(cookie = cookie()).use { r ->
            assertEquals(200, r.code)
            assertEquals("", r.body!!.string())
        }
    }

    @Test
    fun limitCapsTheBodyAndKeepsTheNewestLines() {
        repeat(500) { fileLog.append("line-$it") }
        getLog("?limit=100", cookie()).use { r ->
            val body = r.body!!.string()
            assertTrue("got ${body.length} bytes", body.length <= 100)
            assertTrue("newest line must survive", body.contains("line-499"))
        }
    }

    @Test
    fun limitIsClampedSoAGarbageValueCannotRequestAnUnboundedBody() {
        repeat(100) { fileLog.append("line-$it") }
        // Above the ceiling, zero, and negative must all still produce a valid response.
        for (q in listOf("?limit=999999999", "?limit=0", "?limit=-5", "?limit=abc")) {
            getLog(q, cookie()).use { r -> assertEquals("limit=$q", 200, r.code) }
        }
    }

    @Test
    fun clearEmptiesTheLog() {
        fileLog.append("something to forget")
        val c = cookie()
        http.newCall(
            Request.Builder().url("$base/api/log/clear")
                .post("{}".toRequestBody(json)).header("Cookie", c).build()
        ).execute().use { r ->
            assertEquals(200, r.code)
            assertTrue(r.body!!.string().contains("\"bytes\":0"))
        }
        getLog(cookie = c).use { r -> assertEquals("", r.body!!.string()) }
    }

    @Test
    fun statusReportsTheRetainedSize() {
        val c = cookie()
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", c).build())
            .execute().use { r -> assertTrue(r.body!!.string().contains("\"logBytes\":0")) }
        fileLog.append("x".repeat(100))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", c).build())
            .execute().use { r ->
                val body = r.body!!.string()
                assertTrue(body, body.contains("\"logBytes\":101"))
                assertFalse(body.contains("\"logBytes\":0,"))
            }
    }
}
