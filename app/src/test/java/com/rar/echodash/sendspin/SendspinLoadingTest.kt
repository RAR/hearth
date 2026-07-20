package com.rar.echodash.sendspin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure loading predicate that drives the now-playing takeover's play/pause
 * spinner ring. The ring is on only while a fresh stream is buffering to first playback
 * ([startingStream], armed at onStreamStart and cleared on first flowing audio) and we intend to
 * play. It is keyed on the stream lifecycle, NOT on metadata (MA pre-announces the next track's
 * title/art seconds before the current track ends, so a metadata trigger would stick). Pure so it
 * is JVM-testable without the device-bound endpoint.
 */
class SendspinLoadingTest {

    @Test
    fun no_track_is_never_loading() {
        // No track -> no spinner, regardless of the other inputs.
        assertFalse(sendspinLoading(hasTrack = false, playWhenReady = true, startingStream = true))
    }

    @Test
    fun paused_is_never_loading() {
        // Paused (playWhenReady=false) -> no spinner, even mid-buffer.
        assertFalse(sendspinLoading(hasTrack = true, playWhenReady = false, startingStream = true))
    }

    @Test
    fun starting_stream_is_loading() {
        // A fresh stream buffering to first playback -> ring on.
        assertTrue(sendspinLoading(hasTrack = true, playWhenReady = true, startingStream = true))
    }

    @Test
    fun steady_playback_is_not_loading() {
        // Latch cleared once real audio flows -> no ring during steady playback (and a metadata
        // pre-announce of the next track never re-arms it, since the ring is not metadata-driven).
        assertFalse(sendspinLoading(hasTrack = true, playWhenReady = true, startingStream = false))
    }
}
