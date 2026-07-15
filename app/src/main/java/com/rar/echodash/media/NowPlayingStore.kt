package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
) {
    // ByteArray in a data class defaults to identity equals/hashCode; override so StateFlow dedups by
    // content and tests compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingState) return false
        return active == other.active && playing == other.playing && title == other.title &&
            artist == other.artist && album == other.album && artUrl == other.artUrl &&
            volume == other.volume && canSkip == other.canSkip &&
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
        return r
    }
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
                   album: String?, artworkData: ByteArray?, volume: Int) {
        sendspinActive = active
        sendspinPlaying = playing
        sendspinTitle = title?.takeIf { it.isNotBlank() }
        sendspinArtist = artist?.takeIf { it.isNotBlank() }
        sendspinAlbum = album?.takeIf { it.isNotBlank() }
        sendspinArt = artworkData
        sendspinVolume = volume
        recompute()
    }

    private fun recompute() {
        // SendSpin owns audio (mutually exclusive with the engine) -> its metadata wins.
        if (sendspinActive) {
            _state.value = NowPlayingState(
                active = true, playing = sendspinPlaying,
                title = sendspinTitle, artist = sendspinArtist, album = sendspinAlbum,
                artUrl = null, localArt = sendspinArt, volume = sendspinVolume, canSkip = true,
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
