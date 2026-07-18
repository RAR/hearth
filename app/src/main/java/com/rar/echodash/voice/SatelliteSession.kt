package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Which acknowledgment chirp to play. */
enum class EarconKind { WAKE, DONE }

/** Actions the pure session asks the outside world to perform. */
sealed interface SatelliteAction {
    data class Send(val event: WyomingEvent) : SatelliteAction
    data object StartMic : SatelliteAction
    data object StopMic : SatelliteAction
    data class PlaybackStart(val rate: Int, val width: Int, val channels: Int) : SatelliteAction
    data class PlaybackChunk(val pcm: ByteArray) : SatelliteAction {
        override fun equals(other: Any?) = other is PlaybackChunk && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }
    data object PlaybackStop : SatelliteAction
    data object PlaybackAbort : SatelliteAction
    data class Overlay(val state: VoiceOverlayState) : SatelliteAction
    data class Timers(val state: TimersUiState) : SatelliteAction
    data class Earcon(val kind: EarconKind) : SatelliteAction

    /** Feed a raw mic PCM chunk to the on-device wake detector (localWake only). */
    data class FeedDetector(val pcm: ByteArray) : SatelliteAction {
        override fun equals(other: Any?) = other is FeedDetector && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }

    /** Reset the on-device wake detector back to warm-up (localWake only). */
    data object ResetDetector : SatelliteAction
}

/**
 * Pure protocol/state machine for the Wyoming voice satellite.
 *
 * [localWake] = false preserves the original always-streaming behavior (HA runs the wake stage);
 * this is the silent fallback and is byte-for-byte identical to the pre-wake-word implementation.
 *
 * [localWake] = true turns this into a wake-streaming satellite (per wyoming-satellite's
 * WakeStreamingSatellite): run-satellite arms an on-device detector (StartMic, no run-pipeline);
 * mic chunks flow to the detector as FeedDetector actions; [onWakeDetected] (called by the server
 * when the detector fires) emits detection -> run-pipeline(asr..tts) -> streaming-started and
 * begins streaming; transcript/error/run-satellite stop streaming and re-arm, pause-satellite
 * stops streaming and turns the mic off. While a TTS response plays (audio-start .. onPlaybackFinished)
 * detecting-state mic chunks are dropped entirely (anti-self-trigger).
 *
 * No Android or coroutine imports so it runs in plain-JVM tests. All threading lives in the server.
 */
class SatelliteSession(
    private val appVersion: String,
    private val name: () -> String,
    private val localWake: Boolean = false,
) {

    private enum class WakeState { IDLE, DETECTING, STREAMING, PAUSED }
    private var wakeState = WakeState.IDLE

    private var streaming = false
    private var ttsActive = false
    private var micTimestampMs = 0L
    private var dismissAtMs: Long? = null
    private var watchdogAtMs: Long? = null
    private var suppressRun = false
    var overlay: VoiceOverlayState = VoiceOverlayState()
        private set

    // Timer state persists across connect/disconnect (device-local); reset() never touches it.
    private class TimerRec(
        val id: String,
        val name: String,
        var anchorRemainingSec: Long,
        var anchorMs: Long,
        var active: Boolean,
    )
    private val timers = LinkedHashMap<String, TimerRec>()
    private var alert: TimerAlert? = null
    private var alertSilenceAtMs: Long? = null

    fun onConnected(): List<SatelliteAction> {
        reset()
        return emptyList()
    }

    fun onDisconnected(): List<SatelliteAction> {
        reset()
        return listOf(SatelliteAction.StopMic, overlayAction(VoiceOverlayState()))
    }

    fun onEvent(event: WyomingEvent, nowMs: Long = 0L): List<SatelliteAction> = when (event.type) {
        "describe" -> listOf(SatelliteAction.Send(infoEvent()))
        "ping" -> listOf(SatelliteAction.Send(pongEvent((event.data["text"] as? JsonPrimitive)?.contentOrNull)))
        "run-satellite" -> if (overlay.phase == VoiceOverlayPhase.LISTENING || overlay.phase == VoiceOverlayPhase.THINKING) {
            // HA abandoned the pipeline mid-run (e.g. empty LLM response). Fail rather than re-arm silently.
            failActions(nowMs)
        } else if (localWake) {
            // Arm on-device detection: mic on, but no pipeline and no streaming yet.
            suppressRun = false
            wakeState = WakeState.DETECTING
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(WyomingEvent("streaming-stopped")),
                SatelliteAction.ResetDetector,
                SatelliteAction.StartMic,
            )
        } else {
            suppressRun = false
            streaming = true
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(runPipelineEvent()),
                SatelliteAction.Send(WyomingEvent("streaming-started")),
                SatelliteAction.StartMic,
            )
        }
        "pause-satellite" -> if (localWake) {
            wakeState = WakeState.PAUSED
            listOf(
                SatelliteAction.Send(WyomingEvent("streaming-stopped")),
                SatelliteAction.ResetDetector,
                SatelliteAction.StopMic,
            )
        } else {
            streaming = false
            listOf(SatelliteAction.StopMic, SatelliteAction.Send(WyomingEvent("streaming-stopped")))
        }
        "detection" -> {
            // Legacy/fallback: HA reports the wake word. In localWake HA never sends this.
            suppressRun = false
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(
                SatelliteAction.Earcon(EarconKind.WAKE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
            )
        }
        "transcript" -> {
            watchdogAtMs = nowMs + WATCHDOG_MS
            val base = listOf(
                SatelliteAction.Earcon(EarconKind.DONE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.THINKING, textOf(event))),
            )
            if (localWake) {
                wakeState = WakeState.DETECTING
                base + listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
            } else {
                base
            }
        }
        "error" -> failActions(nowMs)
        "synthesize" -> if (suppressRun) emptyList() else {
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        }
        "audio-start" -> if (suppressRun) emptyList() else {
            ttsActive = true
            watchdogAtMs = null
            listOf(
                SatelliteAction.PlaybackStart(
                    rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                    width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                    channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
                ),
            )
        }
        "audio-chunk" -> if (suppressRun) emptyList() else listOf(SatelliteAction.PlaybackChunk(event.payload))
        "audio-stop" -> if (suppressRun) listOf(SatelliteAction.Send(WyomingEvent("played"))) else listOf(SatelliteAction.PlaybackStop)
        "timer-started" -> {
            val id = strOf(event, "id")
            timers[id] = TimerRec(
                id = id,
                name = strOf(event, "name"),
                anchorRemainingSec = longOf(event, "total_seconds"),
                anchorMs = nowMs,
                active = true,
            )
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-updated" -> {
            timers[strOf(event, "id")]?.let { rec ->
                rec.anchorRemainingSec = longOf(event, "total_seconds")
                rec.anchorMs = nowMs
                rec.active = boolOf(event, "is_active", true)
            }
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-cancelled" -> {
            timers.remove(strOf(event, "id"))
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-finished" -> {
            val rec = timers.remove(strOf(event, "id"))
            alert = TimerAlert(label = rec?.name?.ifBlank { "Timer" } ?: "Timer")
            alertSilenceAtMs = nowMs + ALERT_SILENCE_MS
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        else -> emptyList()
    }

    /**
     * The on-device detector fired for wake word [name]. Emits, in wire order:
     * detection -> run-pipeline(asr..tts, restart_on_end=false) -> streaming-started,
     * then the local WAKE earcon and LISTENING overlay; begins streaming mic audio.
     *
     * Guarded on DETECTING: the detector thread runs inference outside the session lock, so a
     * detection that was already in flight when a pause/transcript/restart changed the state
     * must be dropped, not allowed to start a pipeline.
     */
    fun onWakeDetected(name: String, nowMs: Long): List<SatelliteAction> {
        if (wakeState != WakeState.DETECTING) return emptyList()
        wakeState = WakeState.STREAMING
        micTimestampMs = 0L
        suppressRun = false
        watchdogAtMs = nowMs + WATCHDOG_MS
        return listOf(
            SatelliteAction.Send(detectionEvent(name)),
            SatelliteAction.Send(runPipelineLocalEvent()),
            SatelliteAction.Send(WyomingEvent("streaming-started")),
            SatelliteAction.Earcon(EarconKind.WAKE),
            overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
        )
    }

    fun onMicChunk(pcm: ByteArray): List<SatelliteAction> {
        if (pcm.isEmpty()) return emptyList()
        if (localWake) {
            return when (wakeState) {
                WakeState.DETECTING -> if (ttsActive) emptyList() else listOf(SatelliteAction.FeedDetector(pcm))
                WakeState.STREAMING -> listOf(audioChunkAction(pcm))
                WakeState.IDLE, WakeState.PAUSED -> emptyList()
            }
        }
        if (!streaming) return emptyList()
        return listOf(audioChunkAction(pcm))
    }

    private fun audioChunkAction(pcm: ByteArray): SatelliteAction {
        val ts = micTimestampMs
        micTimestampMs += pcm.size.toLong() * 1000L / (AUDIO_WIDTH.toLong() * AUDIO_CHANNELS * AUDIO_RATE)
        return SatelliteAction.Send(audioChunkEvent(pcm, ts))
    }

    fun onMicError(): List<SatelliteAction> = listOf(
        SatelliteAction.Send(
            WyomingEvent(
                "error",
                buildJsonObject {
                    put("text", "microphone unavailable")
                    put("code", "mic_unavailable")
                },
            ),
        ),
    )

    fun onPlaybackFinished(nowMs: Long): List<SatelliteAction> {
        ttsActive = false
        dismissAtMs = nowMs + DISMISS_MS
        return listOf(SatelliteAction.Send(WyomingEvent("played")))
    }

    /**
     * The user tapped the voice pill. RESPONSE aborts playback and hides (mic un-gates via
     * ttsActive); THINKING hides and suppresses the rest of the in-flight run so HA's pipeline
     * still completes; FAILED hides immediately. LISTENING/HIDDEN/TRANSCRIPT are no-ops.
     */
    fun onOverlayTapped(nowMs: Long): List<SatelliteAction> = when (overlay.phase) {
        VoiceOverlayPhase.RESPONSE -> {
            ttsActive = false
            dismissAtMs = null
            watchdogAtMs = null
            listOf(SatelliteAction.PlaybackAbort, overlayAction(VoiceOverlayState()))
        }
        VoiceOverlayPhase.THINKING -> {
            suppressRun = true
            dismissAtMs = null
            watchdogAtMs = null
            listOf(overlayAction(VoiceOverlayState()))
        }
        VoiceOverlayPhase.FAILED -> {
            dismissAtMs = null
            watchdogAtMs = null
            listOf(overlayAction(VoiceOverlayState()))
        }
        else -> emptyList()
    }

    fun onTimerAlertDismissed(nowMs: Long): List<SatelliteAction> {
        alert = null
        alertSilenceAtMs = null
        return listOf(SatelliteAction.Timers(timersState(nowMs)))
    }

    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Watchdog: a stalled pipeline (no transcript, or answer text but no playback) must not
        // strand the pill. LISTENING/THINKING fail loudly; RESPONSE hides quietly.
        watchdogAtMs?.let {
            if (nowMs >= it) {
                watchdogAtMs = null
                when (overlay.phase) {
                    VoiceOverlayPhase.LISTENING, VoiceOverlayPhase.THINKING -> actions += failActions(nowMs)
                    VoiceOverlayPhase.RESPONSE -> actions += overlayAction(VoiceOverlayState())
                    else -> {}
                }
            }
        }
        // Voice overlay auto-dismiss (~4 s after playback, 3 s after a FAILED flash).
        dismissAtMs?.let { if (nowMs >= it) { dismissAtMs = null; actions += overlayAction(VoiceOverlayState()) } }
        // Timer alert auto-silence after 60 s.
        var timersChanged = false
        alertSilenceAtMs?.let { if (nowMs >= it) { alert = null; alertSilenceAtMs = null; timersChanged = true } }
        // Re-emit live timer state while any timer or alert is present (StateFlow dedups no-ops).
        if (timers.isNotEmpty() || alert != null || timersChanged) {
            actions += SatelliteAction.Timers(timersState(nowMs))
        }
        return actions
    }

    private fun timersState(nowMs: Long) = TimersUiState(
        chips = timers.values.map { TimerChip(it.id, it.name, it.remainingSec(nowMs), it.active) },
        alert = alert,
    )

    private fun TimerRec.remainingSec(nowMs: Long): Long =
        if (active) (anchorRemainingSec - (nowMs - anchorMs) / 1000L).coerceAtLeast(0L) else anchorRemainingSec

    private fun strOf(event: WyomingEvent, key: String): String =
        (event.data[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun longOf(event: WyomingEvent, key: String): Long =
        (event.data[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun boolOf(event: WyomingEvent, key: String, default: Boolean): Boolean =
        (event.data[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun reset() {
        streaming = false
        wakeState = WakeState.IDLE
        ttsActive = false
        micTimestampMs = 0L
        dismissAtMs = null
        watchdogAtMs = null
        suppressRun = false
        overlay = VoiceOverlayState()
    }

    /**
     * Fail the current run: show the "no response" pill for [FAILED_MS], then let the existing
     * dismiss path hide it. Mirrors the error cleanup (stop streaming, re-arm the local detector)
     * in localWake mode; a no-op cleanup otherwise. Clears the watchdog it was called from.
     */
    private fun failActions(nowMs: Long): List<SatelliteAction> {
        dismissAtMs = nowMs + FAILED_MS
        watchdogAtMs = null
        val cleanup = if (localWake) {
            wakeState = WakeState.DETECTING
            listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
        } else {
            emptyList()
        }
        return cleanup + overlayAction(VoiceOverlayState(VoiceOverlayPhase.FAILED, FAILED_TEXT))
    }

    private fun overlayAction(state: VoiceOverlayState): SatelliteAction.Overlay {
        overlay = state
        return SatelliteAction.Overlay(state)
    }

    private fun textOf(event: WyomingEvent): String =
        (event.data["text"] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun audioChunkEvent(pcm: ByteArray, timestampMs: Long) = WyomingEvent(
        "audio-chunk",
        buildJsonObject {
            put("rate", AUDIO_RATE)
            put("width", AUDIO_WIDTH)
            put("channels", AUDIO_CHANNELS)
            put("timestamp", timestampMs)
        },
        pcm,
    )

    /** Legacy/fallback pipeline: HA runs the wake stage and restarts on end. */
    private fun runPipelineEvent() = WyomingEvent(
        "run-pipeline",
        buildJsonObject {
            put("start_stage", "wake")
            put("end_stage", "tts")
            put("restart_on_end", true)
        },
    )

    /** Local-wake pipeline: HA skips its wake stage (start at asr) and does not restart. */
    private fun runPipelineLocalEvent() = WyomingEvent(
        "run-pipeline",
        buildJsonObject {
            put("start_stage", "asr")
            put("end_stage", "tts")
            put("restart_on_end", false)
        },
    )

    private fun detectionEvent(name: String) = WyomingEvent(
        "detection",
        buildJsonObject {
            put("name", name)
            put("timestamp", JsonNull)
        },
    )

    private fun pongEvent(text: String?) = WyomingEvent(
        "pong",
        buildJsonObject { if (text != null) put("text", text) else put("text", JsonNull) },
    )

    private fun infoEvent(): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "mic", "snd")) putJsonArray(key) {}
            putJsonArray("wake") {
                if (localWake) {
                    addJsonObject {
                        put("name", "openWakeWord")
                        putJsonObject("attribution") {
                            put("name", name())
                            put("url", "https://github.com/RAR/hearth")
                        }
                        put("installed", true)
                        put("description", "On-device openWakeWord")
                        put("version", JsonNull)
                        putJsonArray("models") {
                            for ((id, phrase) in WAKE_MODELS) {
                                addJsonObject {
                                    put("name", id)
                                    // description + attribution are REQUIRED on every model:
                                    // wyoming's WakeModel.from_dict indexes d["attribution"],
                                    // and a missing key crashes HA's satellite event loop into
                                    // a 3s reconnect flap (observed on-device 2026-07-13).
                                    put("description", phrase)
                                    put("phrase", phrase)
                                    putJsonObject("attribution") {
                                        put("name", "openWakeWord")
                                        put("url", "https://github.com/dscripka/openWakeWord")
                                    }
                                    put("installed", true)
                                    putJsonArray("languages") {}
                                    put("version", JsonNull)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("satellite") {
                put("name", name())
                putJsonObject("attribution") {
                    put("name", name())
                    put("url", "https://github.com/RAR/hearth")
                }
                put("installed", true)
                put("description", "Home Assistant voice satellite")
                put("version", appVersion)
                put("area", JsonNull)
                put("has_vad", false)
                putJsonArray("active_wake_words") {}
                put("max_active_wake_words", 0)
                put("supports_trigger", false)
            }
        }
        return WyomingEvent("info", data)
    }

    companion object {
        const val AUDIO_RATE = 16000
        const val AUDIO_WIDTH = 2
        const val AUDIO_CHANNELS = 1
        const val DISMISS_MS = 4000L
        const val ALERT_SILENCE_MS = 60000L
        const val WATCHDOG_MS = 30_000L
        const val FAILED_MS = 3_000L
        const val FAILED_TEXT = "No response — try again"

        /** The bundled wake-word model ids and their friendly phrases (HA display only).
         *  Must track DashConfig.VoiceSettings.WAKE_WORDS. */
        val WAKE_MODELS = listOf(
            "okay_nabu" to "Okay Nabu",
            "hey_jarvis" to "Hey Jarvis",
            "alexa" to "Alexa",
            "ok_ember" to "Ok Ember",
        )
    }
}
