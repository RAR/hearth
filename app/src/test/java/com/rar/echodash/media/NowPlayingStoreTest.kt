package com.rar.echodash.media

import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.JsonObject
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

    private fun assertArrayEqualsOrNull(expected: ByteArray, actual: ByteArray?) {
        assertTrue("expected bytes present", actual != null && actual.contentEquals(expected))
    }
}
