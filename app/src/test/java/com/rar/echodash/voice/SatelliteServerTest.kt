package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingCodec
import com.rar.echodash.vaca.WyomingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SatelliteServerTest {
    private class RecordingOut : SatelliteServer.Out {
        val calls = LinkedBlockingQueue<Any>()
        override fun onStartMic() { calls.put("start-mic") }
        override fun onStopMic() { calls.put("stop-mic") }
        override fun onPlaybackStart(rate: Int, width: Int, channels: Int) { calls.put("pb-start") }
        override fun onPlaybackChunk(pcm: ByteArray) { calls.put("pb-chunk") }
        override fun onPlaybackStop() { calls.put("pb-stop") }
        override fun onOverlay(state: VoiceOverlayState) { calls.put(state) }
        override fun onTimers(state: TimersUiState) { calls.put(state) }
        fun next(): Any? = calls.poll(5, TimeUnit.SECONDS)
    }
    private class TestClient(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port)
        val input: InputStream = socket.getInputStream().buffered()
        val output: OutputStream = socket.getOutputStream().buffered()
        fun send(e: WyomingEvent) = WyomingCodec.write(e, output)
        fun read(): WyomingEvent? = WyomingCodec.read(input)
        override fun close() = socket.close()
    }

    private lateinit var scope: CoroutineScope
    private lateinit var out: RecordingOut
    private lateinit var server: SatelliteServer

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        out = RecordingOut()
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", out = out)
        server.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("server did not bind", server.boundPort > 0)
    }
    @After fun tearDown() { server.stop(); scope.cancel() }

    @Test fun describeRepliesInfo() {
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("describe"))
            val info = c.read()!!
            assertEquals("info", info.type)
            assertEquals(true, info.data["satellite"]!!.jsonObject["installed"]!!.toString().contains("true"))
        }
    }

    @Test fun runSatelliteEmitsRunPipelineAndStartsMic() {
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("run-satellite"))
            val e1 = c.read()!!  // run-pipeline
            assertEquals("run-pipeline", e1.type)
            val e2 = c.read()!!  // streaming-started
            assertEquals("streaming-started", e2.type)
            assertEquals("start-mic", out.next())
        }
    }

    @Test fun pingRepliesPong() {
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("ping", Json.parseToJsonElement("""{"text":"z"}""").jsonObject))
            assertEquals("pong", c.read()!!.type)
        }
    }

    @Test fun micChunkAfterRunSatelliteReachesActiveSocket() {
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("run-satellite"))
            c.read(); c.read()            // run-pipeline, streaming-started
            assertEquals("start-mic", out.next())
            server.submitMicChunk(ByteArray(960) { 3 })
            val chunk = c.read()!!
            assertEquals("audio-chunk", chunk.type)
            assertEquals(960, chunk.payload.size)
        }
    }

    @Test fun disconnectStopsMicAndServerAcceptsNext() {
        val c = TestClient(server.boundPort)
        c.send(WyomingEvent("run-satellite"))
        c.read(); c.read()
        assertEquals("start-mic", out.next())
        c.close()
        // stop-mic arrives after disconnect (overlay-hidden also emitted)
        var sawStop = false
        repeat(4) { if (out.next() == "stop-mic") sawStop = true }
        assertTrue(sawStop)
        TestClient(server.boundPort).use { fresh ->
            fresh.send(WyomingEvent("describe"))
            assertEquals("info", fresh.read()!!.type)
        }
    }

    @Test fun survivesGarbageConnection() {
        Socket("127.0.0.1", server.boundPort).use { g ->
            g.getOutputStream().apply { write("not wyoming\n".toByteArray()); flush() }
        }
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("describe"))
            assertNotNull(c.read())
        }
    }
}
