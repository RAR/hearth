package com.rar.echodash.media

import org.junit.Assert.assertEquals
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
}
