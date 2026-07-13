package com.rar.echodash.vaca

/**
 * Decides when resume() must re-open the stream instead of trusting the paused
 * connection: servers (Music Assistant's proxy, icecast relays) drop the HTTP
 * socket of a long-paused client, and ExoPlayer reads that FIN as a clean
 * end-of-stream — the resumed buffer plays out, then the session dies with no
 * error. Any pause of STALE_MS or longer is treated as stale.
 */
class ResumePolicy(private val nowMs: () -> Long) {
    private var pausedAtMs: Long? = null

    /** Playback paused. Repeated pauses keep the FIRST timestamp. */
    fun onPause() { if (pausedAtMs == null) pausedAtMs = nowMs() }

    /** Playback (re)started — play or resume. */
    fun onPlay() { pausedAtMs = null }

    /** True when the current pause has lasted long enough that the socket may be dead. */
    fun isStale(): Boolean = pausedAtMs?.let { nowMs() - it >= STALE_MS } ?: false

    companion object { const val STALE_MS = 60_000L }
}
