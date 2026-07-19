package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingCodec
import com.rar.echodash.vaca.WyomingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        override fun onPlaybackAbort() { calls.put("pb-abort") }
        override fun onOverlay(state: VoiceOverlayState) { calls.put(state) }
        override fun onTimers(state: TimersUiState) { calls.put(state) }
        override fun onEarcon(kind: EarconKind) { calls.put(kind) }
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
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", name = { "Test Sat" }, out = out)
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

    @Test fun bareDescribeProbeDoesNotDisplaceActiveConnection() {
        TestClient(server.boundPort).use { ha ->
            ha.send(WyomingEvent("run-satellite"))
            ha.read(); ha.read()          // run-pipeline, streaming-started
            assertEquals("start-mic", out.next())
            // Zeroconf-style probe (HA's wyoming config_flow on every mDNS refresh):
            // connects, describes, leaves. Must be answered without displacing HA.
            TestClient(server.boundPort).use { probe ->
                probe.send(WyomingEvent("describe"))
                assertEquals("info", probe.read()!!.type)
            }
            // The original connection is still the active satellite: mic audio still flows.
            server.submitMicChunk(ByteArray(960) { 3 })
            val chunk = ha.read()!!
            assertEquals("audio-chunk", chunk.type)
            assertEquals(960, chunk.payload.size)
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

    private fun awaitBind() {
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("server did not bind", server.boundPort > 0)
    }

    @Test fun localWakeDetectionStreamsToServer() {
        server.stop()
        // Always-fire head so the 17th chunk (past the 16-chunk warm-up) triggers.
        val det = WakeDetector(
            melspec = WakeDetector.TfGraph { FloatArray(256) },
            embedding = WakeDetector.TfGraph { FloatArray(96) },
            heads = listOf(WakeDetector.Head("alexa", WakeDetector.TfGraph { floatArrayOf(0.9f) }, thresholdPct = 50)),
            nowMs = { 0L },
        )
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", name = { "Test Sat" }, out = out)
        server.start(localWake = true, detector = det, wakeWord = "alexa")
        awaitBind()
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("run-satellite"))
            assertEquals("streaming-stopped", c.read()!!.type)   // localWake run-satellite re-arms
            assertEquals("start-mic", out.next())
            // Feed 17 whole chunks in one submission; the detector accumulates and fires.
            server.submitMicChunk(ByteArray(17 * 1280 * 2) { 1 })
            val detection = c.read()!!
            assertEquals("detection", detection.type)
            assertEquals("alexa", detection.data["name"]!!.jsonPrimitive.content)
            assertEquals("run-pipeline", c.read()!!.type)
            assertEquals("streaming-started", c.read()!!.type)
        }
    }

    @Test fun stopHeadSilencesRingingTimerAlarm() {
        server.stop()
        // Two heads: the primary never fires (score 0); the "stop" head fires past warm-up.
        val det = WakeDetector(
            melspec = WakeDetector.TfGraph { FloatArray(256) },
            embedding = WakeDetector.TfGraph { FloatArray(96) },
            heads = listOf(
                WakeDetector.Head("okay_nabu", WakeDetector.TfGraph { floatArrayOf(0f) }, thresholdPct = 50),
                WakeDetector.Head(SatelliteServer.STOP_HEAD, WakeDetector.TfGraph { floatArrayOf(0.9f) },
                    SatelliteServer.STOP_THRESHOLD_PCT),
            ),
            nowMs = { 0L },
        )
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", name = { "Test Sat" }, out = out)
        server.start(localWake = true, detector = det, wakeWord = "okay_nabu")
        awaitBind()
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("run-satellite"))   // arm mic (DETECTING) so chunks feed the detector
            assertEquals("streaming-stopped", c.read()!!.type)
            assertEquals("start-mic", out.next())
            // Ring an alarm.
            c.send(WyomingEvent("timer-started", jsonOf("""{"id":"t1","total_seconds":1,"name":"Tea"}""")))
            c.send(WyomingEvent("timer-finished", jsonOf("""{"id":"t1"}""")))
            assertTrue("alarm never rang", out.awaitTimers { it.alert != null })
            // Say "stop" (no wake word): the stop head fires and the alert clears. Had the stop
            // routed through the wake path instead, onStopDetected would never run, the alert would
            // stay up (ticks keep re-emitting it), and this await would time out.
            server.submitMicChunk(ByteArray(17 * 1280 * 2) { 1 })
            assertTrue("alarm was not silenced", out.awaitTimers { it.alert == null })
        }
    }

    private fun jsonOf(s: String) = Json.parseToJsonElement(s).jsonObject

    /** Drain [RecordingOut] until a Timers state matches [pred] (or 5 s elapses). */
    private fun RecordingOut.awaitTimers(pred: (TimersUiState) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val item = calls.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (item is TimersUiState && pred(item)) return true
        }
        return false
    }
}
