package com.rar.echodash.sendspin

import com.rar.echodash.sendspin.sendspin.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure loading predicate that drives the now-playing takeover's
 * play/pause spinner ring. Loading spans two phases: (a) a genuine new-track metadata
 * swap while the OLD stream is still PLAYING ([awaitingStreamStart]), and (b) the new
 * stream started but is still buffering (INITIALIZING/WAITING_FOR_START). It is off in
 * steady playback and while paused. Pure so it is JVM-testable without the device-bound
 * endpoint.
 */
class SendspinLoadingTest {

    @Test
    fun no_track_is_never_loading() {
        // No track -> no spinner, regardless of the other inputs.
        assertFalse(
            sendspinLoading(
                hasTrack = false, playWhenReady = true, awaitingStreamStart = true,
                playbackState = PlaybackState.INITIALIZING,
            ),
        )
    }

    @Test
    fun paused_is_never_loading() {
        // Paused (playWhenReady=false) -> no spinner, even mid-swap or mid-buffer.
        assertFalse(
            sendspinLoading(
                hasTrack = true, playWhenReady = false, awaitingStreamStart = true,
                playbackState = PlaybackState.WAITING_FOR_START,
            ),
        )
    }

    @Test
    fun phase_a_awaiting_stream_start_is_loading_even_over_playing_state() {
        // New metadata shown while the OLD stream is still PLAYING: the flag wins over the
        // stale PLAYING state.
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = true,
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }

    @Test
    fun phase_a_awaiting_stream_start_before_any_audio_state_is_loading() {
        // Metadata swap before the audio-state callback has fired at all (null state).
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = true,
                playbackState = null,
            ),
        )
    }

    @Test
    fun phase_b_initializing_is_loading() {
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = false,
                playbackState = PlaybackState.INITIALIZING,
            ),
        )
    }

    @Test
    fun phase_b_waiting_for_start_is_loading() {
        assertTrue(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = false,
                playbackState = PlaybackState.WAITING_FOR_START,
            ),
        )
    }

    @Test
    fun steady_playing_is_not_loading() {
        // Audio flowing, no pending swap -> no ring.
        assertFalse(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = false,
                playbackState = PlaybackState.PLAYING,
            ),
        )
    }

    @Test
    fun draining_is_not_loading() {
        // Reconnect buffer-play (DRAINING) is not a fresh-track load -> no ring.
        assertFalse(
            sendspinLoading(
                hasTrack = true, playWhenReady = true, awaitingStreamStart = false,
                playbackState = PlaybackState.DRAINING,
            ),
        )
    }
}
