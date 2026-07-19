package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingStoreTest {

    /** Build a media_player EntityState from attribute pairs. */
    private fun entity(vararg attrs: Pair<String, String>): EntityState =
        EntityState(
            entityId = "media_player.ma",
            state = "playing",
            attributes = buildJsonObject { attrs.forEach { (k, v) -> put(k, v) } },
            lastUpdatedMs = 0L,
        )

    private fun emptyEntity(): EntityState =
        EntityState("media_player.ma", "idle", JsonObject(emptyMap()), 0L)

    /** Build a media_player EntityState with arbitrary (incl. numeric) attributes. */
    private fun mediaEntity(build: JsonObjectBuilder.() -> Unit): EntityState =
        EntityState("media_player.ma", "playing", buildJsonObject(build), 0L)

    @Test
    fun icySplitsOnFirstSeparator() {
        assertEquals("Daft Punk" to "Get Lucky", NowPlayingStore.parseIcy("Daft Punk - Get Lucky"))
    }

    @Test
    fun icyWithNoSeparatorIsAllTitle() {
        assertEquals(null to "Radio Paradise", NowPlayingStore.parseIcy("Radio Paradise"))
    }

    @Test
    fun icyWithMultipleSeparatorsSplitsOnFirst() {
        assertEquals("A" to "B - C", NowPlayingStore.parseIcy("A - B - C"))
    }

    @Test
    fun icyBlankOrNullIsNullPair() {
        assertEquals(null to null, NowPlayingStore.parseIcy("   "))
        assertEquals(null to null, NowPlayingStore.parseIcy(null))
    }

    @Test
    fun localIcyDrivesTitleAndArtistWhenNoEntity() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Miles Davis - So What", null)
        val v = s.state.value
        assertTrue(v.active)
        assertTrue(v.playing)
        assertEquals("So What", v.title)
        assertEquals("Miles Davis", v.artist)
        assertNull(v.album)
        assertFalse(v.canSkip)
    }

    @Test
    fun entityTitleBeatsLocalMetadata() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Local Artist - Local Title", byteArrayOf(1, 2, 3))
        s.onEntity(entity(
            "media_title" to "Real Title",
            "media_artist" to "Real Artist",
            "media_album_name" to "Real Album",
            "entity_picture" to "/api/media_player_proxy/media_player.ma?token=abc",
        ))
        val v = s.state.value
        assertEquals("Real Title", v.title)
        assertEquals("Real Artist", v.artist)
        assertEquals("Real Album", v.album)
        assertEquals("/api/media_player_proxy/media_player.ma?token=abc", v.artUrl)
        assertNull("entity art wins so localArt must be ignored", v.localArt)
        assertTrue(v.canSkip)
    }

    @Test
    fun fallsBackToLocalWhenEntityTitleBlankOrNull() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Local Artist - Local Title", null)
        // blank media_title -> entity does not win
        s.onEntity(entity("media_title" to "  "))
        assertEquals("Local Title", s.state.value.title)
        assertEquals("Local Artist", s.state.value.artist)
        assertFalse(s.state.value.canSkip)
        // null entity -> still local
        s.onEntity(null)
        assertEquals("Local Title", s.state.value.title)
        assertFalse(s.state.value.canSkip)
    }

    @Test
    fun artFollowsTextPrecedence() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Track", byteArrayOf(9, 9, 9))
        // no entity -> local art present, no url
        assertNull(s.state.value.artUrl)
        assertArrayEqualsOrNull(byteArrayOf(9, 9, 9), s.state.value.localArt)
        // entity wins -> url present, local art dropped
        s.onEntity(entity("media_title" to "Real", "entity_picture" to "/p.jpg"))
        assertEquals("/p.jpg", s.state.value.artUrl)
        assertNull(s.state.value.localArt)
    }

    @Test
    fun canSkipIsFalseWhenEntityUnconfigured() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onLocalMeta("Track", null)
        assertFalse(s.state.value.canSkip)
    }

    @Test
    fun canSkipTrueOnlyWithNonBlankEntityTitle() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onEntity(emptyEntity())
        assertFalse(s.state.value.canSkip)
        s.onEntity(entity("media_title" to "Song"))
        assertTrue(s.state.value.canSkip)
    }

    @Test
    fun pauseKeepsActiveButClearsPlaying() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 55)
        s.onLocalMeta("Some Title", null)
        s.onEngine(active = true, playing = false, volume = 55)
        val v = s.state.value
        assertTrue("paused still shows the player", v.active)
        assertFalse(v.playing)
        assertEquals("Some Title", v.title)
    }

    @Test
    fun stopClearsMetadataButKeepsVolume() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 42)
        s.onLocalMeta("Artist - Title", byteArrayOf(1))
        s.onEntity(entity("media_title" to "Real", "entity_picture" to "/p.jpg"))
        s.onEngine(active = false, playing = false, volume = 42)
        val v = s.state.value
        assertFalse(v.active)
        assertFalse(v.playing)
        assertNull(v.title)
        assertNull(v.artist)
        assertNull(v.album)
        assertNull(v.artUrl)
        assertNull(v.localArt)
        assertEquals("volume survives stop", 42, v.volume)
    }

    @Test
    fun volumeTracksEngine() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 30)
        assertEquals(30, s.state.value.volume)
        s.onEngine(active = true, playing = true, volume = 88)
        assertEquals(88, s.state.value.volume)
    }

    // ---- Progress + seek: companion media_player entity branch ----

    @Test
    fun entityProgressParsesSecondsToMsAndTimestamp() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onEntity(mediaEntity {
            put("media_title", "Song")
            put("media_duration", 210)          // seconds -> 210_000 ms
            put("media_position", 42.5)         // fractional seconds -> 42_500 ms
            put("media_position_updated_at", "2026-07-18T00:00:00+00:00")
            put("supported_features", 3)        // PAUSE | SEEK
        })
        val v = s.state.value
        assertEquals(210_000L, v.durationMs)
        assertEquals(42_500L, v.positionMs)
        assertEquals(1784332800000L, v.positionAtMs) // 2026-07-18T00:00:00Z
        assertTrue("SEEK bit set + duration -> seekable", v.canSeek)
    }

    @Test
    fun entityBadTimestampYieldsZeroPositionAt() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onEntity(mediaEntity {
            put("media_title", "Song")
            put("media_duration", 100)
            put("media_position", 10)
            put("media_position_updated_at", "not-a-timestamp")
            put("supported_features", 2)
        })
        assertEquals("unparseable updated_at -> 0 (don't extrapolate)", 0L, s.state.value.positionAtMs)
    }

    @Test
    fun entityCanSeekRequiresSeekBitAndDuration() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        // SEEK bit set (2) but no duration -> not seekable (a bar with no end can't map a drag).
        s.onEntity(mediaEntity {
            put("media_title", "Song"); put("supported_features", 2)
        })
        assertFalse(s.state.value.canSeek)
        // SEEK bit set + duration -> seekable.
        s.onEntity(mediaEntity {
            put("media_title", "Song"); put("supported_features", 2); put("media_duration", 120)
        })
        assertTrue(s.state.value.canSeek)
        // SEEK bit clear (VOLUME_SET=4) even with duration -> not seekable.
        s.onEntity(mediaEntity {
            put("media_title", "Song"); put("supported_features", 4); put("media_duration", 120)
        })
        assertFalse(s.state.value.canSeek)
    }

    @Test
    fun entityWithoutProgressAttrsIsAllZeros() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        s.onEntity(entity("media_title" to "Song")) // no duration/position/features
        val v = s.state.value
        assertEquals(0L, v.durationMs)
        assertEquals(0L, v.positionMs)
        assertEquals(0L, v.positionAtMs)
        assertFalse(v.canSeek)
    }

    @Test
    fun icyRadioBranchHasNoProgressOrSeek() {
        val s = NowPlayingStore()
        s.onEngine(active = true, playing = true, volume = 70)
        // No entity title -> ICY/local branch: no duration means no bar, never seekable.
        s.onLocalMeta("Radio Paradise - Some Track", null)
        val v = s.state.value
        assertEquals(0L, v.durationMs)
        assertEquals(0L, v.positionMs)
        assertEquals(0L, v.positionAtMs)
        assertFalse(v.canSeek)
    }

    @Test
    fun parseUpdatedAtHandlesOffsetsAndFailures() {
        assertEquals(1784332800000L, NowPlayingStore.parseUpdatedAt("2026-07-18T00:00:00Z"))
        assertEquals(1784332800000L, NowPlayingStore.parseUpdatedAt("2026-07-18T00:00:00+00:00"))
        assertEquals(1784332800789L, NowPlayingStore.parseUpdatedAt("2026-07-18T00:00:00.789+00:00"))
        assertEquals(0L, NowPlayingStore.parseUpdatedAt(null))
        assertEquals(0L, NowPlayingStore.parseUpdatedAt("   "))
        assertEquals(0L, NowPlayingStore.parseUpdatedAt("garbage"))
    }

    private fun assertArrayEqualsOrNull(expected: ByteArray, actual: ByteArray?) {
        assertTrue("expected bytes present", actual != null && actual.contentEquals(expected))
    }
}
