package com.rar.echodash.vaca

import com.rar.echodash.media.NowPlayingStore
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

    /** Set the device's system media volume; fraction 0..1 of max. */
    fun setVolume(fraction: Float)

    /** Set the playback engine's own gain — used ONLY for ducking; 0..1. */
    fun setDucking(fraction: Float)

    /** Current system media volume as a percent 0..100. */
    fun currentVolumePercent(): Int

    /** System media volume changed (e.g. hardware buttons); percent 0..100. */
    var onVolumeChanged: ((Int) -> Unit)?

    var onPlayingChanged: ((Boolean) -> Unit)?

    /** Local metadata callback: ICY StreamTitle (or tag title) and embedded artwork bytes. */
    var onMeta: ((title: String?, artworkData: ByteArray?) -> Unit)?

    /** Playback reached a terminal state on its own: player error or natural end of the media. */
    var onEnded: (() -> Unit)?
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
 *
 * `volume` is the device's SYSTEM media volume (AudioManager STREAM_MUSIC, what
 * the hardware buttons control) — set-volume and music_volume drive it, and it
 * tracks hardware-button changes via the engine's onVolumeChanged callback.
 * Ducking is a separate engine-side gain applied only while ducked.
 */
class MediaBridge(
    private val engine: MediaEngine,
    private val nowPlaying: NowPlayingStore,
    private val sendStatus: (JsonObject) -> Unit,
) {
    // active/playing/volumePercent are written from both the VACA server thread
    // (handleAction) and the Android main thread (engine callbacks); @Volatile
    // gives cross-thread visibility without needing a lock for these simple flags.
    @Volatile private var volumePercent = 90 // HA media player default volume_level 0.9
    private var duckingVolume = 1  // 1..10 scale, integration default
    private var ducked = false
    @Volatile private var active = false   // engine has media loaded (play-media until stop/error)
    @Volatile private var playing = false  // mirrors the engine isPlaying callback

    /** Push the current engine snapshot into the NowPlayingStore. */
    private fun pushEngine() = nowPlaying.onEngine(active, playing, volumePercent)

    private val _ui = MutableStateFlow(MediaUiState(volume = volumePercent))
    val ui: StateFlow<MediaUiState> = _ui

    init {
        // Seed from the system: the slider should start at the device's real media volume,
        // and the engine gain starts ungated (1f) since we are not ducking yet.
        volumePercent = engine.currentVolumePercent()
        _ui.update { it.copy(volume = volumePercent) }
        applyDucking()
        pushEngine()

        // Hardware buttons / other apps moved the system volume: reflect it in our state.
        // Do NOT call applyVolume() here — the system volume is already what changed.
        engine.onVolumeChanged = { pct ->
            volumePercent = pct
            _ui.update { it.copy(volume = pct) }
            pushEngine()
        }
        engine.onPlayingChanged = { isPlaying ->
            playing = isPlaying
            // A stale onEnded for a just-finished track can race a new play-media on the
            // VACA thread and clear `active` after it was set for the new session. Engine
            // callbacks are serialized on the main thread, so this later onPlayingChanged(true)
            // for the new track corrects that: actually playing always implies media is loaded.
            if (isPlaying) active = true
            _ui.update { it.copy(playing = isPlaying) }
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", isPlaying) }
            })
            pushEngine()
        }
        engine.onMeta = { title, artworkData -> nowPlaying.onLocalMeta(title, artworkData) }
        // Player error or natural end-of-media: without this the home takeover would stay up
        // forever showing a dead player (spec: engine error -> active=false -> takeover dismisses).
        engine.onEnded = {
            active = false
            playing = false
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            pushEngine()
        }
    }

    /** Returns true when [action] was a media action (handled here). */
    fun handleAction(action: String, payload: JsonElement?): Boolean = when (action) {
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                active = true
                engine.play(url)
                _ui.update { it.copy(nowPlaying = url, volume = volumePercent) }
            } else {
                _ui.update { it.copy(volume = volumePercent) }
            }
            pushEngine()
            true
        }
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            _ui.update { it.copy(volume = volumePercent) }
            pushEngine()
            true
        }
        "pause" -> { engine.pause(); true }
        "stop" -> {
            active = false
            playing = false
            engine.stop()
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            pushEngine()
            true
        }
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            _ui.update { it.copy(volume = volumePercent) }
            pushEngine()
            true
        }
        else -> false
    }

    fun applySettings(settings: JsonObject) {
        var changed = false
        (settings["music_volume"] as? JsonPrimitive)?.intOrNull?.let {
            volumePercent = (it.coerceIn(0, 10)) * 10
            applyVolume()
            changed = true
        }
        (settings["ducking_volume"] as? JsonPrimitive)?.intOrNull?.let {
            duckingVolume = it.coerceIn(0, 10)
            applyDucking()
            changed = true
        }
        if (changed) { _ui.update { it.copy(volume = volumePercent) }; pushEngine() }
    }

    fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        applyDucking()
    }

    private fun applyVolume() = engine.setVolume(volumePercent / 100f)

    private fun applyDucking() = engine.setDucking(if (ducked) duckingVolume / 10f else 1f)

    private fun payloadVolume(payload: JsonElement?): Int? =
        ((payload as? JsonObject)?.get("volume") as? JsonPrimitive)?.doubleOrNull?.toInt()

    private fun payloadUrl(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
}
