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

/** Who is holding a duck claim -- see [MediaBridge.setDucked]. */
enum class DuckSource { ANNOUNCE, VOICE, DOORBELL }

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
    restoredDucking: Int? = null,
    private val persistDucking: (Int) -> Unit = {},
    private val sendSettings: (JsonObject) -> Unit = {},
    private val sendStatus: (JsonObject) -> Unit,
    // Fan the ducking fraction out to the SendSpin endpoint too (single fraction authority; the
    // fraction math stays here in applyDucking). SendSpin ducks its own AudioTrack gain.
    private val duckSendspin: (Float) -> Unit = {},
    // Starting a local URL stops SendSpin (one audio source at a time; reverse mutual-exclusion).
    private val onStartUrl: () -> Unit = {},
    // ...and when that local URL session ENDS -- explicit stop, natural end-of-media, or a terminal
    // player error -- re-arm SendSpin so the device rejoins its Music Assistant group. The symmetric
    // other half of the mutual exclusion: onStartUrl tore SendSpin down and nothing else re-arms it.
    // NOT fired on pause -- a paused radio session is still the active local source, so SendSpin must
    // stay down.
    private val onUrlEnded: () -> Unit = {},
) {
    // active/playing/volumePercent are written from both the VACA server thread
    // (handleAction) and the Android main thread (engine callbacks); @Volatile
    // gives cross-thread visibility without needing a lock for these simple flags.
    @Volatile private var volumePercent = 90 // HA media player default volume_level 0.9
    // 0..10 scale; restored from device-local prefs, else the integration default of 1.
    private var duckingVolume = restoredDucking?.coerceIn(0, 10) ?: 1
    // Ducking is claim-based: each source holds/releases its OWN claim and music stays ducked
    // while ANY claim is active. A plain boolean was last-writer-wins -- a TTS announce ending
    // during the doorbell popup would restore full volume under the still-visible popup.
    // synchronized(duckClaims): claims arrive from the VACA server thread and the main thread,
    // and the mutation + the any-claim derivation must be atomic together.
    private val duckClaims = mutableSetOf<DuckSource>()
    private var ducked = false // derived under the duckClaims lock: any claim active
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
            onUrlEnded() // natural end / terminal error -> let SendSpin rejoin its MA group
        }
    }

    /** Returns true when [action] was a media action (handled here). */
    fun handleAction(action: String, payload: JsonElement?): Boolean = when (action) {
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                onStartUrl()          // stop SendSpin -- one audio source at a time
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
            onUrlEnded() // explicit stop -> let SendSpin rejoin its MA group
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

    /** Settings this bridge owns, for the session-start feedback snapshot. */
    fun currentSettings(): JsonObject = buildJsonObject { put("ducking_volume", duckingVolume) }

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
            persistDucking(duckingVolume)
            // Echo the applied value so HA's number entity gets state (it stays unknown otherwise).
            // Unconditional receipt confirmation — the integration treats feedback as idempotent
            // state, not a service call, so re-echoing an unchanged value can't loop.
            sendSettings(currentSettings())
        }
        if (changed) { _ui.update { it.copy(volume = volumePercent) }; pushEngine() }
    }

    fun setDucked(source: DuckSource, ducked: Boolean) {
        synchronized(duckClaims) {
            if (ducked) duckClaims.add(source) else duckClaims.remove(source)
            this.ducked = duckClaims.isNotEmpty()
        }
        applyDucking()
    }

    private fun applyVolume() {
        engine.setVolume(volumePercent / 100f)
        // The stream index is coarser than percent, and a request that lands on the current
        // index fires no VOLUME_CHANGED broadcast to correct us — read back the quantized
        // value so our state can never drift from the real system volume.
        volumePercent = engine.currentVolumePercent()
    }

    private fun applyDucking() {
        val f = if (ducked) duckingVolume / 10f else 1f
        engine.setDucking(f)
        duckSendspin(f)
    }

    private fun payloadVolume(payload: JsonElement?): Int? =
        ((payload as? JsonObject)?.get("volume") as? JsonPrimitive)?.doubleOrNull?.toInt()

    private fun payloadUrl(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
}
