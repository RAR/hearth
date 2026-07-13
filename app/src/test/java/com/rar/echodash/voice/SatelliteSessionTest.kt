package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteSessionTest {

    private fun session() = SatelliteSession(appVersion = "9.9")
    private fun event(type: String, json: String? = null, payload: ByteArray = ByteArray(0)) =
        WyomingEvent(type, json?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap()), payload)
    private inline fun <reified T> List<SatelliteAction>.only(): T {
        assertEquals("expected exactly one action, got $this", 1, size)
        return first() as T
    }
    private fun sends(a: List<SatelliteAction>) = a.filterIsInstance<SatelliteAction.Send>().map { it.event }

    @Test
    fun describeRepliesInfoWithInstalledSatellite() {
        val info = sends(session().onEvent(event("describe"))).single()
        assertEquals("info", info.type)
        val sat = info.data["satellite"]!!.jsonObject
        assertEquals("Echo Dashboard", sat["name"]!!.jsonPrimitive.content)
        assertEquals(true, sat["installed"]!!.jsonPrimitive.boolean)
        assertEquals("9.9", sat["version"]!!.jsonPrimitive.content)
        // no local services advertised
        for (k in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
            assertTrue(info.data[k]!!.jsonArray().isEmpty())
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArray() =
        (this as kotlinx.serialization.json.JsonArray)

    @Test
    fun runSatelliteStartsMicAndSendsRunPipeline() {
        val a = session().onEvent(event("run-satellite"))
        assertTrue(a.contains(SatelliteAction.StartMic))
        val types = sends(a).map { it.type }
        assertTrue(types.contains("run-pipeline"))
        assertTrue(types.contains("streaming-started"))
        val rp = sends(a).first { it.type == "run-pipeline" }
        assertEquals("wake", rp.data["start_stage"]!!.jsonPrimitive.content)
        assertEquals("tts", rp.data["end_stage"]!!.jsonPrimitive.content)
        assertEquals(true, rp.data["restart_on_end"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun pingRepliesPongCopyingText() {
        val pong = sends(session().onEvent(event("ping", """{"text":"k7"}"""))).single()
        assertEquals("pong", pong.type)
        assertEquals("k7", pong.data["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun detectionAndTranscriptEmitEarconsBeforeOverlay() {
        val s = SatelliteSession("1.0")
        val wake = s.onEvent(event("detection", """{"name":"ok_nabu"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.WAKE), wake.first())
        assertTrue(wake.last() is SatelliteAction.Overlay)
        val done = s.onEvent(event("transcript", """{"text":"turn on the light"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.DONE), done.first())
        assertTrue(done.last() is SatelliteAction.Overlay)
    }

    @Test
    fun detectionTranscriptSynthesizeDriveOverlay() {
        val s = session()
        s.onEvent(event("run-satellite"))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (s.onEvent(event("detection", """{"name":"ok_nabu"}""")).last() as SatelliteAction.Overlay).state)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, "turn on the light"),
            (s.onEvent(event("transcript", """{"text":"turn on the light"}""")).last() as SatelliteAction.Overlay).state)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, "Okay"),
            (s.onEvent(event("synthesize", """{"text":"Okay"}""")).last() as SatelliteAction.Overlay).state)
    }

    @Test
    fun ttsAudioRoutesToPlaybackAndPlayedAfterFinish() {
        val s = session()
        s.onEvent(event("run-satellite"))
        assertEquals(SatelliteAction.PlaybackStart(22050, 2, 1),
            s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}""")).only())
        val chunk = s.onEvent(event("audio-chunk", """{"rate":22050,"width":2,"channels":1}""", ByteArray(8) { 1 }))
            .only<SatelliteAction.PlaybackChunk>()
        assertArrayEquals(ByteArray(8) { 1 }, chunk.pcm)
        assertEquals(SatelliteAction.PlaybackStop, s.onEvent(event("audio-stop")).only())
        // played is emitted only after playback actually finishes
        val played = sends(s.onPlaybackFinished(nowMs = 1_000)).single()
        assertEquals("played", played.type)
    }

    @Test
    fun overlayAutoDismissesFourSecondsAfterPlayback() {
        val s = session()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("synthesize", """{"text":"Done"}"""))
        s.onPlaybackFinished(nowMs = 10_000)
        assertEquals(VoiceOverlayPhase.RESPONSE, s.overlay.phase)
        assertTrue(s.onTick(nowMs = 13_999).none { it is SatelliteAction.Overlay }) // before deadline
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (s.onTick(nowMs = 14_000).single() as SatelliteAction.Overlay).state) // at deadline
        assertEquals(VoiceOverlayPhase.HIDDEN, s.overlay.phase)
    }

    @Test
    fun pauseSatelliteStopsMic() {
        val s = session()
        s.onEvent(event("run-satellite"))
        val a = s.onEvent(event("pause-satellite"))
        assertTrue(a.contains(SatelliteAction.StopMic))
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
    }

    @Test
    fun disconnectStopsMicAndHidesOverlay() {
        val s = session()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("detection", """{"name":"x"}"""))
        val a = s.onDisconnected()
        assertTrue(a.contains(SatelliteAction.StopMic))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (a.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun micChunkEmitsAudioChunkOnlyWhileStreaming() {
        val s = session()
        assertTrue(s.onMicChunk(ByteArray(960)).isEmpty()) // not streaming yet
        s.onEvent(event("run-satellite"))
        val e = sends(s.onMicChunk(ByteArray(960) { 7 })).single()
        assertEquals("audio-chunk", e.type)
        assertEquals(16000, e.data["rate"]!!.jsonPrimitive.int)
        assertEquals(2, e.data["width"]!!.jsonPrimitive.int)
        assertEquals(1, e.data["channels"]!!.jsonPrimitive.int)
        assertArrayEquals(ByteArray(960) { 7 }, e.payload)
    }

    @Test
    fun micErrorEmitsErrorEvent() {
        val e = sends(session().onMicError()).single()
        assertEquals("error", e.type)
        assertTrue(e.data["text"]!!.jsonPrimitive.content.isNotBlank())
    }

    // ---- timers ----
    private fun timers(a: List<SatelliteAction>) =
        (a.last { it is SatelliteAction.Timers } as SatelliteAction.Timers).state

    @Test
    fun timerStartedAddsChip() {
        val st = timers(session().onEvent(event("timer-started", """{"id":"t1","total_seconds":300,"name":"Pasta"}"""), nowMs = 0))
        assertEquals(1, st.chips.size)
        assertEquals("t1", st.chips[0].id)
        assertEquals("Pasta", st.chips[0].name)
        assertEquals(300L, st.chips[0].remainingSec)
        assertTrue(st.chips[0].active)
    }

    @Test
    fun countdownMathAgainstClock() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
        assertEquals(240L, timers(s.onTick(nowMs = 60_000)).chips[0].remainingSec)
    }

    @Test
    fun pauseFreezesAndResumeReAnchors() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
        s.onEvent(event("timer-updated", """{"id":"t1","is_active":false,"total_seconds":240}"""), nowMs = 60_000)
        val frozen = timers(s.onTick(nowMs = 120_000)).chips[0]
        assertEquals(240L, frozen.remainingSec) // frozen while paused
        assertEquals(false, frozen.active)
        s.onEvent(event("timer-updated", """{"id":"t1","is_active":true,"total_seconds":240}"""), nowMs = 120_000)
        assertEquals(210L, timers(s.onTick(nowMs = 150_000)).chips[0].remainingSec)
    }

    @Test
    fun cancelRemovesChip() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
        assertTrue(timers(s.onEvent(event("timer-cancelled", """{"id":"t1"}"""), nowMs = 1_000)).chips.isEmpty())
    }

    @Test
    fun finishedRaisesAlertRemovesChipAndAutoSilences() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300,"name":"Tea"}"""), nowMs = 0)
        val fin = timers(s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 300_000))
        assertTrue(fin.chips.isEmpty())
        assertEquals("Tea", fin.alert!!.label)
        assertEquals("Tea", timers(s.onTick(nowMs = 330_000)).alert!!.label) // still alerting before 60 s
        assertNull(timers(s.onTick(nowMs = 360_000)).alert)                   // auto-silenced at +60 s
    }

    @Test
    fun dismissClearsAlert() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        assertNull(timers(s.onTimerAlertDismissed(nowMs = 11_000)).alert)
    }

    @Test
    fun multipleConcurrentTimers() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"a","total_seconds":120}"""), nowMs = 0)
        s.onEvent(event("timer-started", """{"id":"b","total_seconds":300,"name":"Eggs"}"""), nowMs = 0)
        assertEquals(2, timers(s.onTick(nowMs = 0)).chips.size)
    }

    @Test
    fun timersSurviveDisconnectAndReconnect() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":300}"""), nowMs = 0)
        s.onDisconnected()
        assertEquals(240L, timers(s.onTick(nowMs = 60_000)).chips[0].remainingSec)
        s.onConnected()
        assertEquals(210L, timers(s.onTick(nowMs = 90_000)).chips[0].remainingSec)
    }
}
