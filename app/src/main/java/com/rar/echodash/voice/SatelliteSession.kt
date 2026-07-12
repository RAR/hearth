package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    data class Overlay(val state: VoiceOverlayState) : SatelliteAction
    data class Timers(val state: TimersUiState) : SatelliteAction
}

/**
 * Pure protocol/state machine for the always-streaming Wyoming voice satellite.
 * All decisions live here; the server and UI just obey the returned actions.
 * No Android or coroutine imports so it runs in plain-JVM tests.
 */
class SatelliteSession(private val appVersion: String) {

    private var streaming = false
    private var micTimestampMs = 0L
    private var dismissAtMs: Long? = null
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
        "run-satellite" -> {
            streaming = true
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(runPipelineEvent()),
                SatelliteAction.Send(WyomingEvent("streaming-started")),
                SatelliteAction.StartMic,
            )
        }
        "pause-satellite" -> {
            streaming = false
            listOf(SatelliteAction.StopMic, SatelliteAction.Send(WyomingEvent("streaming-stopped")))
        }
        "detection" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)))
        "transcript" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, textOf(event))))
        "synthesize" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        "audio-start" -> listOf(
            SatelliteAction.PlaybackStart(
                rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
            ),
        )
        "audio-chunk" -> listOf(SatelliteAction.PlaybackChunk(event.payload))
        "audio-stop" -> listOf(SatelliteAction.PlaybackStop)
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

    fun onMicChunk(pcm: ByteArray): List<SatelliteAction> {
        if (!streaming || pcm.isEmpty()) return emptyList()
        val ts = micTimestampMs
        micTimestampMs += pcm.size.toLong() * 1000L / (AUDIO_WIDTH.toLong() * AUDIO_CHANNELS * AUDIO_RATE)
        return listOf(SatelliteAction.Send(audioChunkEvent(pcm, ts)))
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
        dismissAtMs = nowMs + DISMISS_MS
        return listOf(SatelliteAction.Send(WyomingEvent("played")))
    }

    fun onTimerAlertDismissed(nowMs: Long): List<SatelliteAction> {
        alert = null
        alertSilenceAtMs = null
        return listOf(SatelliteAction.Timers(timersState(nowMs)))
    }

    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Voice overlay auto-dismiss (~4 s after playback).
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
        micTimestampMs = 0L
        dismissAtMs = null
        overlay = VoiceOverlayState()
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

    private fun runPipelineEvent() = WyomingEvent(
        "run-pipeline",
        buildJsonObject {
            put("start_stage", "wake")
            put("end_stage", "tts")
            put("restart_on_end", true)
        },
    )

    private fun pongEvent(text: String?) = WyomingEvent(
        "pong",
        buildJsonObject { if (text != null) put("text", text) else put("text", JsonNull) },
    )

    private fun infoEvent(): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) putJsonArray(key) {}
            putJsonObject("satellite") {
                put("name", SATELLITE_NAME)
                putJsonObject("attribution") {
                    put("name", SATELLITE_NAME)
                    put("url", "https://github.com/rar/echo-dashboard")
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
        const val SATELLITE_NAME = "Echo Dashboard"
        const val AUDIO_RATE = 16000
        const val AUDIO_WIDTH = 2
        const val AUDIO_CHANNELS = 1
        const val DISMISS_MS = 4000L
        const val ALERT_SILENCE_MS = 60000L
    }
}
