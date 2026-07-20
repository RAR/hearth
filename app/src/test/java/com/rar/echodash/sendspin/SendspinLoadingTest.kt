package com.rar.echodash.sendspin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure loading predicate that drives the now-playing takeover's
 * play/pause spinner ring. Loading spans two phases: (a) a genuine new-track metadata
 * swap while the OLD stream is still playing ([awaitingStreamStart]), and (b) a FRESH
 * stream buffering to first playback ([startingStream], a latch cleared on first flowing
 * audio). It is off in steady playback and while paused. Pure so it is JVM-testable
 * without the device-bound endpoint.
 */
class SendspinLoadingTest {

    @Test
    fun no_track_is_never_loading() {
        // No track -> no spinner, regardless of the other inputs.
        assertFalse(
            sendspinLoading(
                hasTrack = false, playWhenReady = true,
                awaitingStreamStart = true, startingStream = true,
            ),
        )
    }

    @Test
    fun paused_is_never_loading() {
        // Paused (playWhenReady=false) -> no spinner, even mid-swap or mid-buffer.
        assertFalse(
            sendspinLoading(
                hasTrack = true, playWhenReady = false,
                awaitingStreamStart = true, startingStream = true,
            ),
        )
    }

    @Test
    fun phase_a_awaiting_stream_start_is_loading() {
        // New metadata shown while the OLD stream is still playing (phase a), no fresh-stream
        // buffering latch yet.
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true,
                awaitingStreamStart = true, startingStream = false,
            ),
        )
    }

    @Test
    fun phase_b_starting_stream_is_loading() {
        // A brand-new stream buffering to first playback (phase b), metadata phase already cleared.
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true,
                awaitingStreamStart = false, startingStream = true,
            ),
        )
    }

    @Test
    fun both_phases_flagged_is_loading() {
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true,
                awaitingStreamStart = true, startingStream = true,
            ),
        )
    }

    @Test
    fun steady_playback_is_not_loading() {
        // Audio flowing, no pending swap and the fresh-stream latch cleared -> no ring. This is the
        // steady state a mid-stream re-anchor dip must NOT disturb: the latch (not a live buffering
        // read) stays false, so the ring never reappears once playback settled.
        assertFalse(
            sendspinLoading(
                hasTrack = true, playWhenReady = true,
                awaitingStreamStart = false, startingStream = false,
            ),
        )
    }
}
