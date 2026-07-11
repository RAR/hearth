package com.rar.echodash.vaca

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Playback engine abstraction over ExoPlayer. Calls may arrive on any thread. */
interface MediaEngine {
    fun play(url: String)
    fun resume()
    fun pause()
    fun stop()
    fun setVolume(fraction: Float)
    var onPlayingChanged: ((Boolean) -> Unit)?
}

/** Read-side snapshot of the on-device player for the Media panel. */
data class MediaUiState(
    val playing: Boolean = false,
    val nowPlaying: String = "Nothing playing",
    val volume: Int = 90,
)

/**
 * Drives the HA media_player entity: play-media/play/pause/stop/set-volume
 * actions, music_volume + ducking_volume settings, playing-state status.
 */
class MediaBridge(
    private val engine: MediaEngine,
    private val sendStatus: (JsonObject) -> Unit,
) {
    private var volumePercent = 90 // HA media player default volume_level 0.9
    private var duckingVolume = 1  // 1..10 scale, integration default
    private var ducked = false

    private val _ui = MutableStateFlow(MediaUiState(volume = volumePercent))
    val ui: StateFlow<MediaUiState> = _ui

    init {
        engine.onPlayingChanged = { playing ->
            _ui.update { it.copy(playing = playing) }
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", playing) }
            })
        }
    }

    /** Returns true when [action] was a media action (handled here). */
    fun handleAction(action: String, payload: JsonElement?): Boolean = when (action) {
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                engine.play(url)
                _ui.update { it.copy(nowPlaying = url, volume = volumePercent) }
            } else {
                _ui.update { it.copy(volume = volumePercent) }
            }
            true
        }
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
        "pause" -> { engine.pause(); true }
        "stop" -> {
            engine.stop()
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            true
        }
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
        else -> false
    }

    fun applySettings(settings: JsonObject) {
        var changed = false
        (settings["music_volume"] as? JsonPrimitive)?.intOrNull?.let {
            volumePercent = (it.coerceIn(0, 10)) * 10
            changed = true
        }
        (settings["ducking_volume"] as? JsonPrimitive)?.intOrNull?.let {
            duckingVolume = it.coerceIn(0, 10)
            changed = true
        }
        if (changed) { applyVolume(); _ui.update { it.copy(volume = volumePercent) } }
    }

    fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        applyVolume()
    }

    private fun applyVolume() {
        val base = volumePercent / 100f
        val fraction = if (ducked) base * (duckingVolume / 10f) else base
        engine.setVolume(fraction.coerceIn(0f, 1f))
    }

    private fun payloadVolume(payload: JsonElement?): Int? =
        ((payload as? JsonObject)?.get("volume") as? JsonPrimitive)?.doubleOrNull?.toInt()

    private fun payloadUrl(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
}
