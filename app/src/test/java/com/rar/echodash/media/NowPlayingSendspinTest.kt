package com.rar.echodash.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSendspinTest {
    @Test fun sendspinMetadataPopulatesState() {
        val store = NowPlayingStore()
        store.onSendspin(active = true, playing = true, title = "Song", artist = "Artist",
            album = "Album", artworkData = byteArrayOf(1, 2, 3), volume = 55)
        val s = store.state.value
        assertTrue(s.active); assertTrue(s.playing)
        assertEquals("Song", s.title); assertEquals("Artist", s.artist)
        assertEquals("Album", s.album); assertEquals(55, s.volume)
    }

    @Test fun inactiveSendspinClears() {
        val store = NowPlayingStore()
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55)
        store.onSendspin(false, false, null, null, null, null, 55)
        assertEquals(false, store.state.value.active)
    }

    @Test fun sendspinOverridesActiveEngine() {
        val store = NowPlayingStore()
        store.onEngine(true, true, 80)
        store.onSendspin(true, true, "S", "A", "Al", null, 60)
        val s = store.state.value
        assertTrue(s.active)
        assertEquals("S", s.title)
        assertEquals(60, s.volume)
    }

    @Test fun mutedFlowsThroughOnSendspinIntoState() {
        val store = NowPlayingStore()
        // Callers that don't pass muted (the omitted param) must default to unmuted.
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55)
        assertEquals(false, store.state.value.muted)
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55, muted = true)
        assertTrue(store.state.value.muted)
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55, muted = false)
        assertEquals(false, store.state.value.muted)
    }

    @Test fun enginePathStaysUnmuted() {
        val store = NowPlayingStore()
        store.onEngine(true, true, 80)
        // Only the SendSpin source carries a mute; the local ExoPlayer path never sets it.
        assertEquals(false, store.state.value.muted)
    }

    @Test fun sendspinProgressPassesThroughButNeverSeekable() {
        val store = NowPlayingStore()
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55,
            durationMs = 210_000, positionMs = 42_000, positionAtMs = 1_000_000)
        val s = store.state.value
        assertEquals(210_000L, s.durationMs)
        assertEquals(42_000L, s.positionMs)
        assertEquals(1_000_000L, s.positionAtMs)
        // MA has no seek command -> the SendSpin bar is always display-only.
        assertFalse(s.canSeek)
    }

    @Test fun deactivateResetsProgressToZeroViaDefaults() {
        val store = NowPlayingStore()
        store.onSendspin(true, true, "Song", "Artist", "Album", null, 55,
            durationMs = 210_000, positionMs = 42_000, positionAtMs = 1_000_000)
        // The existing deactivation call passes no progress -> the param defaults zero it out.
        store.onSendspin(false, false, null, null, null, null, 55)
        val s = store.state.value
        assertFalse(s.active)
        assertEquals(0L, s.durationMs)
        assertEquals(0L, s.positionMs)
        assertEquals(0L, s.positionAtMs)
    }
}
