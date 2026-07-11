package com.rar.echodash.ha

import com.rar.echodash.data.InMemorySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
    fun connectsAuthenticatesSubscribesAndReceivesReading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"
                accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope) { 42L }
            try {
                ws.start("sensor.outside_temperature")
                val reading = withTimeout(10_000) { ws.reading.first { it != null } }!!
                assertEquals("15.6", reading.value)
                assertEquals("°C", reading.unit)
                assertEquals(42L, reading.updatedAtMs)
                assertEquals(ConnState.CONNECTED, ws.connectionState.value)
            } finally {
                ws.stop(); scope.cancel()
            }
        }
    }

    @Test
    fun fetchesTemperatureSensorsViaGetStates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
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
                ws.start(null)
                val sensors = withTimeout(10_000) { ws.fetchTemperatureSensors() }
                assertEquals(1, sensors.size)
                assertEquals("sensor.outside_temperature", sensors[0].entityId)
            } finally {
                ws.stop(); scope.cancel()
            }
        }
    }
}
