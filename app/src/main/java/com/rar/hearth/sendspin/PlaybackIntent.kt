package com.rar.hearth.sendspin

/**
 * The local audio action a server-broadcast `playback_state` maps to. Extracted from
 * [SendspinEndpoint.applyServerPlaybackState] as a pure decision so it is unit-testable off-device;
 * the side effects (player resume/pause/clearBuffer, sendSpin.play, the playWhenReady write, and the
 * now-playing publish) stay in the endpoint. See `PlaybackIntentTest`.
 */
enum class PlaybackAction {
    /** Server says playing: set the intent to playing and resume the player. */
    RESUME,

    /** Server says paused: clear the intent but KEEP the buffer for a seamless resume. */
    PAUSE_KEEP_BUFFER,

    /**
     * Server says stopped/idle while the player is DRAINING (mid-reconnect with a live buffer): ask
     * the server to resume rather than discard the buffer. Does NOT change the play/pause intent.
     */
    RESUME_FROM_DRAINING,

    /** Server says stopped/idle with no live buffer: clear the intent, drop the buffer, and pause. */
    CLEAR_AND_PAUSE,

    /** Unknown state: leave everything untouched. */
    IGNORE,
}

/**
 * Map a Music Assistant / SendSpin `playback_state` string onto a [PlaybackAction].
 *
 * Preserves [SendspinEndpoint.applyServerPlaybackState]'s exact normalization: a case-INSENSITIVE
 * exact match (no trimming). Music Assistant uses "stopped" as its pause signal (it does not emit
 * "paused"), so stopped/idle clears + pauses UNLESS [isDraining] (mid-reconnect with a live buffer),
 * where we resume instead. Any unknown string is ignored (intent left untouched).
 *
 * @param isDraining whether the local player is in the DRAINING state. Only consulted for
 *   stopped/idle; when there is no player this is false, yielding [PlaybackAction.CLEAR_AND_PAUSE].
 */
fun playbackIntent(rawState: String, isDraining: Boolean): PlaybackAction = when {
    rawState.equals("playing", ignoreCase = true) -> PlaybackAction.RESUME
    rawState.equals("paused", ignoreCase = true) -> PlaybackAction.PAUSE_KEEP_BUFFER
    rawState.equals("stopped", ignoreCase = true) ||
        rawState.equals("idle", ignoreCase = true) ->
        if (isDraining) PlaybackAction.RESUME_FROM_DRAINING else PlaybackAction.CLEAR_AND_PAUSE
    else -> PlaybackAction.IGNORE
}
