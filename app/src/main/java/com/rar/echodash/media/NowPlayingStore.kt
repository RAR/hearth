package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Read-side snapshot of the on-device player merged with an optional HA companion media_player
 * entity. See NowPlayingState fields. localArt is embedded ID3/tag artwork bytes; artUrl is the RAW
 * entity_picture string (ArtFetcher resolves relative paths against the HA base URL).
 */
data class NowPlayingState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val localArt: ByteArray? = null,
    val volume: Int = 90,
    val canSkip: Boolean = false,
    /** True when the active source is the SendSpin endpoint (routes transport controls to it). */
    val sendspin: Boolean = false,
    /** Music Assistant muted this player (SendSpin source only; the engine path never mutes). */
    val muted: Boolean = false,
    /** SendSpin track is loading (new metadata shown, audio not flowing yet) -- rings the play/pause control. */
    val loading: Boolean = false,
    /** Track length in ms; 0 = unknown (radio/ICY) -> the takeover draws no progress bar. */
    val durationMs: Long = 0,
    /** Last sampled playback position in ms. */
    val positionMs: Long = 0,
    /** Epoch-ms wall-clock instant [positionMs] was sampled at; 0 = don't extrapolate. */
    val positionAtMs: Long = 0,
    /** Seek is offered (companion media_player with the SEEK feature + a known duration only). */
    val canSeek: Boolean = false,
    /** Group repeat mode from SendSpin controller state: "off"|"one"|"all"; null = unknown. */
    val repeatMode: String? = null,
    /** Group shuffle from SendSpin controller state; null = unknown. */
    val shuffle: Boolean? = null,
    /** Repeat/shuffle commands the server advertises (gate the toggles' visibility). */
    val canRepeat: Boolean = false,
    val canShuffle: Boolean = false,
) {
    /**
     * Position to display at [nowMs], extrapolating past the last sample. While playing with a real
     * sample instant, advance [positionMs] by the wall-clock elapsed since [positionAtMs]; otherwise
     * show the raw [positionMs] (paused = frozen, positionAtMs==0 = "don't extrapolate"). Clamped to
     * the track length when known. Playback speed is ignored on purpose: Music Assistant music is
     * always 1x.
     */
    fun displayedPositionMs(nowMs: Long): Long {
        val base = if (playing && positionAtMs > 0) positionMs + (nowMs - positionAtMs) else positionMs
        return if (durationMs > 0) base.coerceIn(0, durationMs) else base
    }

    // ByteArray in a data class defaults to identity equals/hashCode; override so StateFlow dedups by
    // content and tests compare by content. The progress fields are part of the identity too -- omit
    // them and a position-only change would be deduped away and the bar would freeze.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingState) return false
        return active == other.active && playing == other.playing && title == other.title &&
            artist == other.artist && album == other.album && artUrl == other.artUrl &&
            volume == other.volume && canSkip == other.canSkip && sendspin == other.sendspin &&
            muted == other.muted && loading == other.loading &&
            durationMs == other.durationMs && positionMs == other.positionMs &&
            positionAtMs == other.positionAtMs && canSeek == other.canSeek &&
            repeatMode == other.repeatMode && shuffle == other.shuffle &&
            canRepeat == other.canRepeat && canShuffle == other.canShuffle &&
            (localArt?.contentEquals(other.localArt ?: ByteArray(0)) ?: (other.localArt == null))
    }

    override fun hashCode(): Int {
        var r = active.hashCode()
        r = 31 * r + playing.hashCode()
        r = 31 * r + (title?.hashCode() ?: 0)
        r = 31 * r + (artist?.hashCode() ?: 0)
        r = 31 * r + (album?.hashCode() ?: 0)
        r = 31 * r + (artUrl?.hashCode() ?: 0)
        r = 31 * r + (localArt?.contentHashCode() ?: 0)
        r = 31 * r + volume
        r = 31 * r + canSkip.hashCode()
        r = 31 * r + sendspin.hashCode()
        r = 31 * r + muted.hashCode()
        r = 31 * r + loading.hashCode()
        r = 31 * r + durationMs.hashCode()
        r = 31 * r + positionMs.hashCode()
        r = 31 * r + positionAtMs.hashCode()
        r = 31 * r + canSeek.hashCode()
        r = 31 * r + (repeatMode?.hashCode() ?: 0)
        r = 31 * r + (shuffle?.hashCode() ?: 0)
        r = 31 * r + canRepeat.hashCode()
        r = 31 * r + canShuffle.hashCode()
        return r
    }
}

/**
 * Format a track time (ms) for the takeover's progress labels: `m:ss` below an hour, `h:mm:ss`
 * at/over. Negative or zero (and sub-second) collapse to "0:00". Pure so it is unit-testable.
 */
fun formatTrackTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Merges three inputs into one [state]: the device engine (active/playing/volume), local ExoPlayer
 * metadata (ICY StreamTitle text or embedded tag artwork), and an optional HA companion media_player
 * entity. Pure JVM (only kotlinx + the pure EntityState) so it is unit-testable. The engine's active
 * flag is the master gate: when inactive, no metadata is exposed regardless of the entity. Inputs
 * arrive on different threads (VACA server thread, ExoPlayer main-thread callbacks, Compose
 * collectors), so the mutators are synchronized.
 */
class NowPlayingStore {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state

    private var engineActive = false
    private var enginePlaying = false
    private var engineVolume = 90
    private var localTitle: String? = null
    private var localArt: ByteArray? = null
    private var entity: EntityState? = null

    // SendSpin (Music Assistant) is a fourth input, mutually exclusive with the ExoPlayer engine
    // (reverse-exclusion in the endpoint guarantees only one owns audio). While a SendSpin stream is
    // active it wins in recompute() regardless of engine/entity state.
    private var sendspinActive = false
    private var sendspinPlaying = false
    private var sendspinTitle: String? = null
    private var sendspinArtist: String? = null
    private var sendspinAlbum: String? = null
    private var sendspinArt: ByteArray? = null
    private var sendspinVolume = 90
    private var sendspinMuted = false // display only; the endpoint handles the audio-side mute
    private var sendspinLoading = false // display only; rings the play/pause control while loading
    private var sendspinDurationMs = 0L
    private var sendspinPositionMs = 0L
    private var sendspinPositionAtMs = 0L
    private var sendspinRepeatMode: String? = null
    private var sendspinShuffle: Boolean? = null
    private var sendspinCanRepeat = false
    private var sendspinCanShuffle = false

    @Synchronized
    fun onEngine(active: Boolean, playing: Boolean, volume: Int) {
        engineActive = active
        enginePlaying = playing
        engineVolume = volume
        // Going inactive (stop/error) drops stale local metadata so the next session starts clean.
        if (!active) { localTitle = null; localArt = null }
        recompute()
    }

    @Synchronized
    fun onLocalMeta(icyOrTagTitle: String?, artworkData: ByteArray?) {
        localTitle = icyOrTagTitle?.takeIf { it.isNotBlank() }
        localArt = artworkData
        recompute()
    }

    @Synchronized
    fun onEntity(entity: EntityState?) {
        this.entity = entity
        recompute()
    }

    @Synchronized
    fun onSendspin(active: Boolean, playing: Boolean, title: String?, artist: String?,
                   album: String?, artworkData: ByteArray?, volume: Int,
                   muted: Boolean = false,
                   // Defaulted so every existing deactivation call (onSendspin(false, ...)) stays
                   // correct: a cleared takeover shows no spinner.
                   loading: Boolean = false,
                   // Progress default to 0 so every existing deactivation call (onSendspin(false, ...))
                   // stays correct: a cleared takeover reports no progress.
                   durationMs: Long = 0, positionMs: Long = 0, positionAtMs: Long = 0,
                   // Toggle state from the SendSpin controller push. Defaults keep every existing
                   // deactivation call (onSendspin(false, ...)) clearing them to unknown/hidden.
                   repeatMode: String? = null, shuffle: Boolean? = null,
                   canRepeat: Boolean = false, canShuffle: Boolean = false) {
        sendspinActive = active
        sendspinPlaying = playing
        sendspinTitle = title?.takeIf { it.isNotBlank() }
        sendspinArtist = artist?.takeIf { it.isNotBlank() }
        sendspinAlbum = album?.takeIf { it.isNotBlank() }
        sendspinArt = artworkData
        sendspinVolume = volume
        sendspinMuted = muted
        sendspinLoading = loading
        sendspinDurationMs = durationMs
        sendspinPositionMs = positionMs
        sendspinPositionAtMs = positionAtMs
        sendspinRepeatMode = repeatMode
        sendspinShuffle = shuffle
        sendspinCanRepeat = canRepeat
        sendspinCanShuffle = canShuffle
        recompute()
    }

    private fun recompute() {
        // SendSpin owns audio (mutually exclusive with the engine) -> its metadata wins.
        if (sendspinActive) {
            _state.value = NowPlayingState(
                active = true, playing = sendspinPlaying,
                title = sendspinTitle, artist = sendspinArtist, album = sendspinAlbum,
                // Next/prev route to the companion media_player entity, not SendSpin -- skip
                // controls would hit the wrong player, so transport controls are out of scope.
                artUrl = null, localArt = sendspinArt, volume = sendspinVolume, canSkip = true, sendspin = true,
                muted = sendspinMuted,
                loading = sendspinLoading,
                durationMs = sendspinDurationMs, positionMs = sendspinPositionMs,
                positionAtMs = sendspinPositionAtMs,
                // SendSpin/MA has no seek command (supported_commands never carries one), so the bar
                // is display-only here -- never draggable.
                canSeek = false,
                repeatMode = sendspinRepeatMode, shuffle = sendspinShuffle,
                canRepeat = sendspinCanRepeat, canShuffle = sendspinCanShuffle,
            )
            return
        }
        val entityTitle = entity?.attr("media_title")?.takeIf { it.isNotBlank() }
        val canSkip = entityTitle != null
        if (!engineActive) {
            _state.value = NowPlayingState(volume = engineVolume, canSkip = canSkip)
            return
        }
        _state.value = if (entityTitle != null) {
            // media_duration/media_position are in SECONDS (may be fractional); scale to ms.
            val durationMs = entity?.attrDouble("media_duration")?.let { (it * 1000).toLong() } ?: 0L
            val positionMs = entity?.attrDouble("media_position")?.let { (it * 1000).toLong() } ?: 0L
            // media_position_updated_at is an ISO-8601 instant (with offset). Any parse failure -> 0
            // ("don't extrapolate"), never a throw.
            val positionAtMs = parseUpdatedAt(entity?.attr("media_position_updated_at"))
            // Seek is offered only when the player advertises the SEEK feature AND the length is
            // known (a bar with no end can't map a drag to a position).
            val features = entity?.attrDouble("supported_features")?.toInt() ?: 0
            NowPlayingState(
                active = true,
                playing = enginePlaying,
                title = entityTitle,
                artist = entity?.attr("media_artist")?.takeIf { it.isNotBlank() },
                album = entity?.attr("media_album_name")?.takeIf { it.isNotBlank() },
                artUrl = entity?.attr("entity_picture")?.takeIf { it.isNotBlank() },
                localArt = null,
                volume = engineVolume,
                canSkip = true,
                durationMs = durationMs,
                positionMs = positionMs,
                positionAtMs = positionAtMs,
                canSeek = (features and MEDIA_FEATURE_SEEK) != 0 && durationMs > 0,
            )
        } else {
            val (artist, title) = parseIcy(localTitle)
            NowPlayingState(
                active = true,
                playing = enginePlaying,
                title = title,
                artist = artist,
                album = null,
                artUrl = null,
                localArt = localArt,
                volume = engineVolume,
                canSkip = false,
            )
        }
    }

    companion object {
        // HA MediaPlayerEntityFeature.SEEK is the flag with value 2 (not bit index 2); a player
        // supports seeking when this bit is set in its supported_features bitmask.
        private const val MEDIA_FEATURE_SEEK = 2

        /**
         * Parse HA's `media_position_updated_at` (ISO-8601 instant, e.g. "…T12:34:56.789+00:00")
         * to epoch ms. Blank/null or any unparseable value -> 0, meaning "don't extrapolate" (the
         * displayed position then just shows the raw sample). Never throws.
         */
        fun parseUpdatedAt(raw: String?): Long {
            val s = raw?.takeIf { it.isNotBlank() } ?: return 0L
            return runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
        }

        /**
         * Split an ICY/tag "Artist - Title" string on the FIRST " - " (artist left, title right).
         * No separator -> the whole string is the title, artist null. Blank/null -> (null, null).
         */
        fun parseIcy(raw: String?): Pair<String?, String?> {
            val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null to null
            val idx = s.indexOf(" - ")
            if (idx < 0) return null to s
            val artist = s.substring(0, idx).trim().takeIf { it.isNotBlank() }
            val title = s.substring(idx + 3).trim().takeIf { it.isNotBlank() } ?: s
            return artist to title
        }
    }
}
