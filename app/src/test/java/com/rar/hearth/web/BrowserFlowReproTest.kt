package com.rar.hearth.web

import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.InMemorySettingsStore
import com.rar.hearth.ha.AuthManager
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.Socket
import kotlin.random.Random

/**
 * Reproduces the browser's real login flow: a single keep-alive connection carrying
 * an unauthenticated GET (the page's boot-time tryLoad), then POST /api/login, then
 * an authenticated GET — the sequence a browser performs, unlike OkHttp-per-call tests.
 */
class BrowserFlowReproTest {
    private lateinit var server: ConfigServer
    private lateinit var store: ConfigStore

    private fun tempDir(): File =
        File.createTempFile("cfgbrowser", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        store = ConfigStore(tempDir())
        server = ConfigServer(
            port = 0,
            store = store,
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            notifyToken = { "testtoken" },
            deviceName = { "Hearth" },
            setDeviceName = { },
            pushStore = com.rar.hearth.notify.PushNotificationStore(),
            entitiesJson = { "[]" },
            setup = SetupCoordinator(AuthManager(InMemorySettingsStore(), OkHttpClient()), onConfigured = {}),
            configured = { false },
            connState = { "OFFLINE" },
            previewChime = { _, _ -> },
            previewEarcon = { },
            assetReader = { null },
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** Reads one HTTP/1.1 response (status + headers + content-length body). */
    private fun readResponse(inp: InputStream): Pair<Int, Map<String, String>> {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val b = inp.read()
            check(b != -1) { "EOF during headers after: $header" }
            header.append(b.toChar())
        }
        val lines = header.toString().split("\r\n").filter { it.isNotEmpty() }
        val code = lines[0].split(" ")[1].toInt()
        val headers = lines.drop(1).associate {
            val i = it.indexOf(':')
            it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(body, off, len - off)
            check(n != -1) { "EOF during body" }
            off += n
        }
        return code to headers
    }

    @Test
    fun browserKeepAliveLoginFlowAuthenticates() {
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val inp = sock.getInputStream().buffered()
            fun send(req: String) { out.write(req.toByteArray()); out.flush() }

            // 1. what tryLoad() does on page load, before any login
            send("GET /api/config HTTP/1.1\r\nHost: x\r\n\r\n")
            val (c1, _) = readResponse(inp)
            assertEquals(401, c1)

            // 2. POST /api/login on the SAME connection
            val body = """{"pin":"123456"}"""
            send(
                "POST /api/login HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n$body"
            )
            val (c2, h2) = readResponse(inp)
            assertEquals(200, c2)
            val cookie = h2["set-cookie"]?.substringBefore(";")
            assertTrue("login response carried no Set-Cookie: $h2", cookie != null && cookie.startsWith("session="))

            // 3. authenticated GET on the SAME connection
            send("GET /api/config HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nConnection: close\r\n\r\n")
            val (c3, _) = readResponse(inp)
            assertEquals(200, c3)
        }
    }
}
