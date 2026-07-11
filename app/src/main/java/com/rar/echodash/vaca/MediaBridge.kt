package com.rar.echodash.vaca

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

    init {
        engine.onPlayingChanged = { playing ->
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
            payloadUrl(payload)?.let { engine.play(it) }
            true
        }
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            true
        }
        "pause" -> { engine.pause(); true }
        "stop" -> { engine.stop(); true }
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
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
        if (changed) applyVolume()
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
