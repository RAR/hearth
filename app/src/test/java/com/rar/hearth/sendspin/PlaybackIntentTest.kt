package com.rar.hearth.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure playback-state intent extracted from
 * [SendspinEndpoint.applyServerPlaybackState]: a server `playback_state` string (plus whether the
 * local player is draining) maps to the action the endpoint then applies.
 */
class PlaybackIntentTest {

    @Test fun playing_resumes() {
        assertEquals(PlaybackAction.RESUME, playbackIntent("playing", isDraining = false))
    }

    @Test fun paused_keeps_buffer() {
        assertEquals(PlaybackAction.PAUSE_KEEP_BUFFER, playbackIntent("paused", isDraining = false))
    }

    @Test fun stopped_while_draining_resumes_from_draining() {
        assertEquals(PlaybackAction.RESUME_FROM_DRAINING, playbackIntent("stopped", isDraining = true))
    }

    @Test fun stopped_without_buffer_clears_and_pauses() {
        assertEquals(PlaybackAction.CLEAR_AND_PAUSE, playbackIntent("stopped", isDraining = false))
    }

    @Test fun idle_matches_stopped_when_draining() {
        assertEquals(PlaybackAction.RESUME_FROM_DRAINING, playbackIntent("idle", isDraining = true))
    }

    @Test fun idle_matches_stopped_without_buffer() {
        assertEquals(PlaybackAction.CLEAR_AND_PAUSE, playbackIntent("idle", isDraining = false))
    }

    @Test fun unknown_state_is_ignored() {
        assertEquals(PlaybackAction.IGNORE, playbackIntent("buffering", isDraining = false))
        assertEquals(PlaybackAction.IGNORE, playbackIntent("", isDraining = true))
    }

    @Test fun state_match_is_case_insensitive() {
        // applyServerPlaybackState matches with equals(ignoreCase = true), so mixed case still maps.
        assertEquals(PlaybackAction.RESUME, playbackIntent("Playing", isDraining = false))
        assertEquals(PlaybackAction.PAUSE_KEEP_BUFFER, playbackIntent("PAUSED", isDraining = false))
        assertEquals(PlaybackAction.CLEAR_AND_PAUSE, playbackIntent("Stopped", isDraining = false))
        assertEquals(PlaybackAction.RESUME_FROM_DRAINING, playbackIntent("IDLE", isDraining = true))
    }

    @Test fun draining_is_consulted_only_for_stopped_or_idle() {
        // playing/paused ignore the draining flag entirely.
        assertEquals(PlaybackAction.RESUME, playbackIntent("playing", isDraining = true))
        assertEquals(PlaybackAction.PAUSE_KEEP_BUFFER, playbackIntent("paused", isDraining = true))
    }
}
