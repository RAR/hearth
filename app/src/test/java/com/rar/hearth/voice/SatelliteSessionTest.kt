package com.rar.hearth.voice

import com.rar.hearth.vaca.WyomingEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteSessionTest {

    private fun session() = SatelliteSession(appVersion = "9.9", name = { "Test Sat" })
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
        assertEquals("Test Sat", sat["name"]!!.jsonPrimitive.content)
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
        val s = SatelliteSession("1.0", name = { "Test Sat" })
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
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.THINKING, "turn on the light"),
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
    fun stopDuringRingSilencesAlarmExactlyLikeADismiss() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        val a = s.onStopDetected(nowMs = 11_000)
        assertNull(timers(a).alert)                                   // alert cleared
        assertEquals(1, a.filterIsInstance<SatelliteAction.Timers>().size)  // Timers emitted
    }

    @Test
    fun stopWithNoRingIsIgnored() {
        // No alarm ringing: a stray "stop" detection is harmless (scoped activation).
        assertTrue(session().onStopDetected(nowMs = 0).isEmpty())
        // A live but not-yet-finished timer is not a ring either.
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10}"""), nowMs = 0)
        assertTrue(s.onStopDetected(nowMs = 1_000).isEmpty())
    }

    @Test
    fun wakeDuringRingSilencesAlarmAndStillStartsThePipeline() {
        // "OK Ember, stop" instinct: the wake head fires first and hands the mic to HA, so the
        // bare-"stop" head can never see the utterance — the wake itself must silence the ring
        // (observed live 2026-07-18: alarm blared over the whole session).
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        val a = s.onWakeDetected("ok_ember", nowMs = 11_000)
        assertNull(timers(a).alert)                                    // ring silenced
        assertTrue(sends(a).map { it.type }.contains("run-pipeline"))  // session still runs
    }

    @Test
    fun stopTranscriptAfterAlarmSilencingWakeIsSwallowedQuietly() {
        // "OK Ember, stop" while ringing: the wake silenced the ring; the "stop" transcript
        // must not reach the LLM's UI path (observed live: TTS answered "no device is playing").
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        s.onWakeDetected("ok_ember", nowMs = 11_000)
        val a = s.onEvent(event("transcript", """{"text":"Stop."}"""), nowMs = 12_000)
        assertEquals(VoiceOverlayState(), s.overlay)                              // no THINKING
        assertTrue(a.filterIsInstance<SatelliteAction.Earcon>().isEmpty())        // no earcon
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))        // still re-arms
        // The suppressed run's response never surfaces.
        assertTrue(s.onEvent(event("synthesize", """{"text":"No device is playing"}""")).isEmpty())
    }

    @Test
    fun commandTranscriptAfterAlarmSilencingWakeRunsNormally() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        s.onWakeDetected("ok_ember", nowMs = 11_000)
        s.onEvent(event("transcript", """{"text":"set another timer for 5 minutes"}"""), nowMs = 12_000)
        assertEquals(VoiceOverlayPhase.THINKING, s.overlay.phase)
    }

    @Test
    fun stopTranscriptWithoutAlarmStaysARealCommand() {
        // Music playing, no alarm: "OK Ember, stop" must reach the pipeline UI as usual.
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("ok_ember", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"stop"}"""), nowMs = 1_000)
        assertEquals(VoiceOverlayPhase.THINKING, s.overlay.phase)
    }

    @Test
    fun errorAfterAlarmSilencingWakeDismissesQuietlyNotFailed() {
        // Bare "OK Ember" to hush the ring, then silence -> STT timeout error: no FAILED flash.
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        s.onWakeDetected("ok_ember", nowMs = 11_000)
        val a = s.onEvent(event("error"), nowMs = 20_000)
        assertEquals(VoiceOverlayState(), s.overlay)
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))        // re-armed
        // A later, unrelated error still fails loudly.
        s.onWakeDetected("ok_ember", nowMs = 30_000)
        s.onEvent(event("error"), nowMs = 31_000)
        assertEquals(VoiceOverlayPhase.FAILED, s.overlay.phase)
    }

    @Test
    fun stopPhraseNormalizationMatchesVariants() {
        for (t in listOf("stop", "Stop.", "STOP!", " stop it ", "Cancel the timer", "Thank you.")) {
            assertTrue(t, SatelliteSession.isStopPhrase(t))
        }
        for (t in listOf("stop the music", "set a timer", "", "stopwatch")) {
            assertFalse(t, SatelliteSession.isStopPhrase(t))
        }
    }

    @Test
    fun wakeWithNoRingEmitsNoTimersAction() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        assertTrue(s.onWakeDetected("ok_ember", nowMs = 0).filterIsInstance<SatelliteAction.Timers>().isEmpty())
    }

    @Test
    fun legacyDetectionEventDuringRingSilencesAlarm() {
        // HA-side wake devices get the same behavior via the legacy detection event.
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        val a = s.onEvent(event("detection"), nowMs = 11_000)
        assertNull(timers(a).alert)
    }

    @Test
    fun stopNeverStartsAPipeline() {
        val s = session()
        s.onEvent(event("timer-started", """{"id":"t1","total_seconds":10,"name":"X"}"""), nowMs = 0)
        s.onEvent(event("timer-finished", """{"id":"t1"}"""), nowMs = 10_000)
        val a = s.onStopDetected(nowMs = 11_000)
        assertFalse(sends(a).map { it.type }.contains("run-pipeline"))
        assertFalse(sends(a).map { it.type }.contains("detection"))
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

    private fun wakeSession() = SatelliteSession(appVersion = "9.9", name = { "Test Sat" }, localWake = true)

    @Test
    fun localWakeRunSatelliteArmsMicWithoutRunPipeline() {
        val a = wakeSession().onEvent(event("run-satellite"))
        assertTrue(a.contains(SatelliteAction.StartMic))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        val types = sends(a).map { it.type }
        assertFalse(types.contains("run-pipeline"))
        assertFalse(types.contains("streaming-started"))
        assertTrue(types.contains("streaming-stopped"))
    }

    @Test
    fun localWakeMicChunkFeedsDetectorWhileDetecting() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onMicChunk(ByteArray(960) { 5 })
        assertEquals(1, a.size)
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 5 }), a.first())
    }

    @Test
    fun onWakeDetectedEmitsDetectionThenRunPipelineThenStreamingStarted() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onWakeDetected("alexa", nowMs = 0)
        val ev = sends(a)
        assertEquals("detection", ev[0].type)
        assertEquals("alexa", ev[0].data["name"]!!.jsonPrimitive.content)
        assertEquals("run-pipeline", ev[1].type)
        assertEquals("asr", ev[1].data["start_stage"]!!.jsonPrimitive.content)
        assertEquals("tts", ev[1].data["end_stage"]!!.jsonPrimitive.content)
        assertEquals(false, ev[1].data["restart_on_end"]!!.jsonPrimitive.boolean)
        assertEquals("streaming-started", ev[2].type)
        val earconIdx = a.indexOfFirst { it is SatelliteAction.Earcon }
        val overlayIdx = a.indexOfFirst { it is SatelliteAction.Overlay }
        assertTrue(earconIdx in 0 until overlayIdx)                       // earcon before overlay
        assertEquals(SatelliteAction.Earcon(EarconKind.WAKE), a[earconIdx])
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (a[overlayIdx] as SatelliteAction.Overlay).state)
    }

    @Test
    fun onWakeDetectedDroppedUnlessDetecting() {
        val s = wakeSession()
        // Before run-satellite (IDLE): stale detection is dropped.
        assertEquals(emptyList<SatelliteAction>(), s.onWakeDetected("alexa", 0))
        // While paused: dropped, and a later mic chunk still goes nowhere.
        s.onEvent(event("run-satellite"))
        s.onEvent(event("pause-satellite"))
        assertEquals(emptyList<SatelliteAction>(), s.onWakeDetected("alexa", 0))
        assertEquals(emptyList<SatelliteAction>(), s.onMicChunk(ByteArray(960) { 1 }))
        // While already streaming: a second in-flight detection is dropped.
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        assertEquals(emptyList<SatelliteAction>(), s.onWakeDetected("alexa", 1))
    }

    @Test
    fun localWakeMicChunkStreamsAudioAfterDetection() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val e = sends(s.onMicChunk(ByteArray(960) { 7 })).single()
        assertEquals("audio-chunk", e.type)
        assertArrayEquals(ByteArray(960) { 7 }, e.payload)
    }

    @Test
    fun localWakeTranscriptStopsStreamingResetsDetectorAndReArms() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val a = s.onEvent(event("transcript", """{"text":"hi"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.DONE), a.first())
        assertTrue(a.any { it is SatelliteAction.Overlay })
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        // Back to detecting: the next mic chunk feeds the detector again.
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())
    }

    @Test
    fun localWakeErrorStopsStreamingAndReArms() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val a = s.onEvent(event("error", """{"text":"boom"}"""))
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())
    }

    @Test
    fun localWakePauseStopsMicAndDropsMicChunks() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onEvent(event("pause-satellite"))
        assertTrue(a.contains(SatelliteAction.StopMic))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // paused -> dropped
    }

    @Test
    fun localWakeDropsDetectorFeedDuringTtsWindow() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}"""))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // dropped: TTS playing
        s.onEvent(event("audio-stop"))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // still within the window
        s.onPlaybackFinished(1000)
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())                  // resumes after playback
    }

    @Test
    fun localWakeInfoAdvertisesBundledWakeModels() {
        val info = sends(wakeSession().onEvent(event("describe"))).single()
        val wake = info.data["wake"]!!.jsonArray()
        assertEquals(1, wake.size)
        val models = wake.first().jsonObject["models"]!!.jsonArray()
        assertEquals(4, models.size)
        val phrases = models.map { it.jsonObject["phrase"]!!.jsonPrimitive.content }
        assertTrue(phrases.contains("Okay Nabu"))
        assertTrue(phrases.contains("Hey Jarvis"))
        assertTrue(phrases.contains("Alexa"))
        assertTrue(phrases.contains("Ok Ember"))
    }

    @Test
    fun legacyModeInfoWakeStaysEmpty() {
        val info = sends(SatelliteSession("9.9", name = { "Test Sat" }).onEvent(event("describe"))).single()
        assertTrue(info.data["wake"]!!.jsonArray().isEmpty())
    }

    // ---- voice watchdog / thinking / failed ----

    @Test
    fun watchdogFiresFromListeningToFailedThenHides() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 1_000)                       // LISTENING, watchdog @ 31_000
        assertTrue(s.onTick(nowMs = 30_999).none { it is SatelliteAction.Overlay }) // before deadline
        val fired = s.onTick(nowMs = 31_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (fired.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(sends(fired).map { it.type }.contains("streaming-stopped"))       // streaming stopped
        assertTrue(fired.contains(SatelliteAction.ResetDetector))                    // detector re-armed
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),                     // +3 s -> HIDDEN
            (s.onTick(nowMs = 34_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun watchdogInThinkingReArmedByTranscriptFires() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 5_000)   // LISTENING, watchdog @ 35_000
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 10_000) // THINKING, re-armed @ 40_000
        assertTrue(s.onTick(nowMs = 35_000).none { it is SatelliteAction.Overlay }) // old deadline passed harmlessly
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (s.onTick(nowMs = 40_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun responseWithoutPlaybackHidesQuietlyAtWatchdog() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)
        s.onEvent(event("synthesize", """{"text":"Answer"}"""), nowMs = 2_000) // RESPONSE, watchdog @ 32_000
        assertEquals(VoiceOverlayPhase.RESPONSE, s.overlay.phase)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),                 // quiet hide, no FAILED text
            (s.onTick(nowMs = 32_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertEquals(VoiceOverlayPhase.HIDDEN, s.overlay.phase)
    }

    @Test
    fun errorDuringThinkingShowsFailedThenAutoHides() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)  // THINKING
        val err = s.onEvent(event("error", """{"text":"boom"}"""), nowMs = 2_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (err.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(s.onTick(nowMs = 4_999).none { it is SatelliteAction.Overlay })  // before +3 s
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (s.onTick(nowMs = 5_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun midRunRunSatelliteFailsButAtStartArmsNormally() {
        val s = session()
        // Session start (phase HIDDEN): run-satellite arms as today, no FAILED overlay.
        val start = s.onEvent(event("run-satellite"), nowMs = 0)
        assertTrue(start.none { it is SatelliteAction.Overlay })
        assertTrue(sends(start).map { it.type }.contains("run-pipeline"))
        // Mid-run (THINKING): a fresh run-satellite means HA abandoned the pipeline -> FAILED.
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)  // THINKING
        val mid = s.onEvent(event("run-satellite"), nowMs = 2_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (mid.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun tapDuringResponseAbortsPlaybackHidesAndUngatesMic() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 0)        // -> DETECTING, THINKING
        s.onEvent(event("synthesize", """{"text":"Answer"}"""), nowMs = 0)    // RESPONSE
        s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}"""), nowMs = 0) // ttsActive
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())              // mic gated during playback
        val tap = s.onOverlayTapped(nowMs = 100)
        assertTrue(tap.contains(SatelliteAction.PlaybackAbort))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (tap.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isNotEmpty())           // mic un-gated after abort
    }

    @Test
    fun tapDuringThinkingSuppressesRunButCompletesPipeline() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 0)        // THINKING (wakeState DETECTING)
        val tap = s.onOverlayTapped(nowMs = 0)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (tap.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertFalse(tap.contains(SatelliteAction.PlaybackAbort))              // nothing playing yet
        // The run's late events are swallowed:
        assertTrue(s.onEvent(event("synthesize", """{"text":"late"}""")).isEmpty())
        assertTrue(s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}""")).isEmpty())
        assertTrue(s.onEvent(event("audio-chunk", null, ByteArray(8) { 1 })).isEmpty())
        // ...but audio-stop still completes HA's pipeline:
        assertEquals("played", sends(s.onEvent(event("audio-stop"))).single().type)
        // Next wake clears suppression and behaves normally:
        val wake = s.onWakeDetected("alexa", nowMs = 0)
        assertTrue(sends(wake).map { it.type }.contains("run-pipeline"))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (wake.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }
}
