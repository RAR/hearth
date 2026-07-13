package com.rar.echodash.night

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ambient-light night-clock state machine. Pure Kotlin (no Android imports) so it unit-tests on the
 * JVM like NowPlayingStore. All inputs arrive on the main thread (the sensor-callback hop plus
 * Compose effects), so no locking is required. Timing is timestamp-based: callers pass nowMs
 * (SystemClock.elapsedRealtime in production; a fake Long clock in tests).
 */
class NightModeController {
    private val _nightActive = MutableStateFlow(false)
    /** True while the dim night clock should own the screen. Threaded into DashboardShell. */
    val nightActive: StateFlow<Boolean> = _nightActive

    private val _ticking = MutableStateFlow(false)
    /**
     * True while App must run the 5 s re-evaluation ticker: night is active, or an otherwise-ready
     * entry is being suppressed by an override or the post-touch hold (so touch-hold expiry in a
     * silent, dark room still nudges a re-entry). False when the feature is off or fully idle.
     */
    val ticking: StateFlow<Boolean> = _ticking

    private var enabled = false
    private var thresholdLux = 10
    // The hysteresis memory: set after ENTER_DWELL below the threshold, cleared only after
    // EXIT_DWELL at/above the exit threshold. Dead-band samples (e.g. the woken screen's own
    // glow on the sensor) clear the dwell clocks but never the latch — touch/override "exits"
    // are suppressions of a still-set latch, so night returns when the suppression ends.
    private var darkLatch = false
    private var belowSinceMs: Long? = null
    private var aboveSinceMs: Long? = null
    private var lastTouchMs: Long? = null
    private var overrideActive = false
    private var lastNowMs = 0L

    fun onConfig(enabled: Boolean, thresholdLux: Int) {
        val thresholdChanged = thresholdLux != this.thresholdLux
        this.enabled = enabled
        this.thresholdLux = thresholdLux
        if (thresholdChanged) { belowSinceMs = null; aboveSinceMs = null; darkLatch = false }
        evaluate(lastNowMs)
    }

    fun onLux(lux: Float, nowMs: Long) {
        lastNowMs = nowMs
        belowSinceMs = if (lux < thresholdLux) belowSinceMs ?: nowMs else null
        aboveSinceMs = if (lux >= exitThreshold()) aboveSinceMs ?: nowMs else null
        evaluate(nowMs)
    }

    fun onOverride(active: Boolean, nowMs: Long) {
        lastNowMs = nowMs
        overrideActive = active
        evaluate(nowMs)
    }

    fun onUserInteraction(nowMs: Long) {
        lastNowMs = nowMs
        lastTouchMs = nowMs
        evaluate(nowMs)
    }

    fun onTick(nowMs: Long) {
        lastNowMs = nowMs
        evaluate(nowMs)
    }

    private fun evaluate(nowMs: Long) {
        if (enabled) {
            belowSinceMs?.let { if (!darkLatch && nowMs - it >= ENTER_DWELL_MS) darkLatch = true }
            aboveSinceMs?.let { if (darkLatch && nowMs - it >= EXIT_DWELL_MS) darkLatch = false }
        } else {
            darkLatch = false
        }
        val withinTouchHold = lastTouchMs?.let { nowMs - it < TOUCH_HOLD_MS } ?: false
        _nightActive.value = enabled && darkLatch && !overrideActive && !withinTouchHold
        _ticking.value = enabled && (darkLatch || overrideActive || withinTouchHold)
    }

    private fun exitThreshold(): Int = maxOf(thresholdLux * 2, thresholdLux + 10)

    companion object {
        const val ENTER_DWELL_MS = 30_000L
        const val EXIT_DWELL_MS = 10_000L
        const val TOUCH_HOLD_MS = 60_000L
    }
}
