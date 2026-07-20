package com.rar.hearth.vaca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VacaServerTest {

    private class RecordingListener : VacaServer.Listener {
        val events = LinkedBlockingQueue<Any>()
        override fun onSessionStarted() { events.put("session-started") }
        override fun onSettings(settings: JsonObject) { events.put(settings) }
        override fun onAction(action: String, payload: JsonElement?) { events.put(action to payload) }
        override fun onAudioStart(rate: Int, width: Int, channels: Int) { events.put("audio-start") }
        override fun onAudioChunk(pcm: ByteArray) { events.put("audio-chunk") }
        override fun onAudioStop() { events.put("audio-stop") }
        override fun onSessionEnded() { events.put("session-ended") }
        fun next(): Any? = events.poll(5, TimeUnit.SECONDS)
    }

    private class TestClient(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port)
        val input: InputStream = socket.getInputStream().buffered()
        val output: OutputStream = socket.getOutputStream().buffered()
        fun send(event: WyomingEvent) = WyomingCodec.write(event, output)
        fun read(): WyomingEvent? = WyomingCodec.read(input)
        override fun close() { socket.close() }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var listener: RecordingListener
    private lateinit var server: VacaServer

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listener = RecordingListener()
        server = VacaServer(
            scope = scope,
            port = 0, // ephemeral for tests
            infoEvent = { VacaOutgoing.info("0.2", "Test Device") },
            capabilitiesEvent = { VacaOutgoing.capabilities(VacaOutgoing.buildCapabilities("0.2", hasLightSensor = false)) },
            listener = listener,
        )
        server.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("server did not bind", server.boundPort > 0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test
    fun answersDescribeWithInfoAndCapabilitiesRequest() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("describe"))
            val info = client.read()!!
            assertEquals("info", info.type)
            assertEquals(true,
                info.data["satellite"]!!.jsonObject["installed"]!!.jsonPrimitive.boolean)

            client.send(WyomingEvent("capabilities"))
            val caps = client.read()!!
            assertEquals("capabilities", caps.type)
            assertEquals("0.2", caps.data["app_version"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun respondsPongToPing() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("ping", Json.parseToJsonElement("""{"text":"k1"}""").jsonObject))
            val pong = client.read()!!
            assertEquals("pong", pong.type)
            assertEquals("k1", pong.data["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun dispatchesSettingsAndActionsAfterRunSatellite() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())

            client.send(WyomingEvent("custom-event",
                Json.parseToJsonElement("""{"event_type":"settings","settings":{"screen_brightness":30}}""").jsonObject))
            val settings = listener.next() as JsonObject
            assertEquals(30, settings["screen_brightness"]!!.jsonPrimitive.int)

            client.send(WyomingEvent("custom-event",
                Json.parseToJsonElement("""{"event_type":"action","action":"refresh","payload":null}""").jsonObject))
            @Suppress("UNCHECKED_CAST")
            val action = listener.next() as Pair<String, JsonElement?>
            assertEquals("refresh", action.first)
            assertNull(action.second)
        }
    }

    @Test
    fun sendStatusReachesActiveSessionAndIsNoopWithoutOne() = runBlocking {
        // no session yet: must not throw
        server.sendStatus(buildJsonObject { put("x", 1) })

        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())
            server.sendStatus(buildJsonObject {
                put("sensors", buildJsonObject { put("light", 7) })
            })
            val e = client.read()!!
            assertEquals("custom-event", e.type)
            assertEquals("status", e.data["event_type"]!!.jsonPrimitive.content)
            assertEquals(7, e.data["data"]!!.jsonObject["sensors"]!!.jsonObject["light"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun signalsSessionEndOnDisconnectAndAcceptsNewConnections() {
        val client = TestClient(server.boundPort)
        client.send(WyomingEvent("run-satellite"))
        assertEquals("session-started", listener.next())
        client.close()
        assertEquals("session-ended", listener.next())

        // server still alive for a fresh session (HA reconnects every 10s)
        TestClient(server.boundPort).use { fresh ->
            fresh.send(WyomingEvent("describe"))
            assertEquals("info", fresh.read()!!.type)
        }
    }

    @Test
    fun survivesGarbageConnection() {
        Socket("127.0.0.1", server.boundPort).use { garbage ->
            garbage.getOutputStream().apply {
                write("this is not wyoming\n".toByteArray())
                flush()
            }
        }
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("describe"))
            assertNotNull(client.read())
        }
    }

    @Test
    fun routesAudioEventsToListener() {
        TestClient(server.boundPort).use { client ->
            client.send(WyomingEvent("run-satellite"))
            assertEquals("session-started", listener.next())
            client.send(WyomingEvent("audio-start",
                Json.parseToJsonElement("""{"rate":22050,"width":2,"channels":1,"timestamp":0}""").jsonObject))
            client.send(WyomingEvent("audio-chunk",
                Json.parseToJsonElement("""{"rate":22050,"width":2,"channels":1}""").jsonObject, ByteArray(64)))
            client.send(WyomingEvent("audio-stop"))
            assertEquals("audio-start", listener.next())
            assertEquals("audio-chunk", listener.next())
            assertEquals("audio-stop", listener.next())
        }
    }
}
