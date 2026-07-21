package com.rar.hearth.ha

import com.rar.hearth.data.InMemorySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HaWebSocketTest {

    @Test
    fun wsUrlConversion() {
        assertEquals("ws://ha.local:8123/api/websocket", wsUrl("http://ha.local:8123"))
        assertEquals("wss://ha.example.com/api/websocket", wsUrl("https://ha.example.com"))
    }

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(2_000L, backoffMs(0))
        assertEquals(4_000L, backoffMs(1))
        assertEquals(32_000L, backoffMs(4))
        assertEquals(60_000L, backoffMs(5))
        assertEquals(60_000L, backoffMs(20))
    }

    @Test
    fun nextBackoffStaysWithinEqualJitterBounds() {
        val rnd = kotlin.random.Random(42) // seeded -> deterministic
        repeat(500) {
            for (attempt in 0..8) {
                val ceil = backoffMs(attempt)
                val v = nextBackoffMs(attempt, rnd)
                assertTrue("attempt=$attempt v=$v ceil=$ceil", v in (ceil / 2)..ceil)
            }
        }
    }

    /** Fake HA server: performs auth handshake, acks subscribe_entities, pushes one state. */
    private fun haServerListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2025.1.0"}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2025.1.0"}""")
                "subscribe_entities" -> {
                    val id = obj["id"]!!.jsonPrimitive.int
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    webSocket.send("""{"id":$id,"type":"event","event":{"a":{"sensor.outside_temperature":{"s":"15.6","a":{"unit_of_measurement":"°C"}}}}}""")
                }
                "get_states" -> {
                    val id = obj["id"]!!.jsonPrimitive.int
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"sensor.outside_temperature","state":"15.6","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Outside Temperature"}}]}""")
                }
            }
        }
    }

    @Test
    fun failsPendingRequestWhenSocketDropsBeforeResult() = runBlocking {
        MockWebServer().use { server ->
            // Server auths OK but closes the socket on get_states without ever sending a result.
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"auth_required","ha_version":"2025.1.0"}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2025.1.0"}""")
                        "get_states" -> webSocket.close(1000, null)
                    }
                }
            }))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"
                accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start()
                try {
                    withTimeout(10_000) { ws.request("get_states") }
                    fail("expected request to fail when socket drops")
                } catch (e: IOException) {
                    // expected: pending request failed on disconnect
                }
            } finally {
                ws.stop(); scope.cancel()
            }
        }
    }

    @Test
    fun requestReturnsResultPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"; accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start()
                val result = withTimeout(10_000) { ws.request("get_states") }!!
                assertEquals("sensor.outside_temperature",
                    result.jsonArray[0].jsonObject["entity_id"]!!.jsonPrimitive.contentOrNull)
            } finally { ws.stop(); scope.cancel() }
        }
    }

    @Test
    fun subscribeRoutesEventsByCommandId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"; accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start()
                val received = CompletableDeferred<JsonObject>()
                withTimeout(10_000) {
                    ws.subscribe("subscribe_entities",
                        buildJsonObject { putJsonArray("entity_ids") { add("sensor.outside_temperature") } }
                    ) { event -> if (!received.isCompleted) received.complete(event) }
                }
                val event = withTimeout(10_000) { received.await() }
                assertTrue(event.containsKey("a"))
            } finally { ws.stop(); scope.cancel() }
        }
    }
}
