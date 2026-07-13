package com.rar.echodash.night

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeControllerTest {

    private fun enabled(c: NightModeController, threshold: Int = 10) = c.onConfig(true, threshold)

    @Test
    fun entersAfterLuxBelowThresholdFor30sNotBefore() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0)
        c.onLux(5f, 29_999)
        assertFalse("not before 30s", c.nightActive.value)
        c.onLux(5f, 30_000)
        assertTrue("enters at 30s", c.nightActive.value)
    }

    @Test
    fun sampleAtOrAboveThresholdDuringDwellResets() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0)
        c.onLux(10f, 10_000)   // == entry threshold clears belowSince
        c.onLux(5f, 10_001)
        c.onLux(5f, 39_999)    // only 29,998 ms since the reset
        assertFalse(c.nightActive.value)
        c.onLux(5f, 40_001)
        assertTrue(c.nightActive.value)
    }

    @Test
    fun exitsAfterLuxAboveExitFor10sButNotOn5sSpike() {
        val c = NightModeController()
        enabled(c)                       // threshold 10, exit = max(20, 20) = 20
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onLux(50f, 31_000)             // spike up
        c.onLux(5f, 36_000)              // back to dark within 5 s
        assertTrue("spike shorter than 10s holds night", c.nightActive.value)
        c.onLux(50f, 40_000)
        c.onLux(50f, 50_000)             // 10 s continuously >= exit
        assertFalse(c.nightActive.value)
    }

    @Test
    fun deadBandHoldsStateBothDirections() {
        val c = NightModeController()
        enabled(c)                       // entry 10, exit 20, dead band [10,20)
        c.onLux(15f, 0)
        c.onLux(15f, 60_000)
        assertFalse("dead-band never enters", c.nightActive.value)
        c.onLux(5f, 61_000); c.onLux(5f, 91_000)
        assertTrue(c.nightActive.value)
        c.onLux(15f, 92_000)
        c.onLux(15f, 120_000)
        assertTrue("dead-band never exits", c.nightActive.value)
    }

    @Test
    fun touchExitsImmediatelyAndBlocksReentryFor60s() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onUserInteraction(30_000)
        assertFalse("touch exits immediately", c.nightActive.value)
        c.onTick(89_999)                 // 59,999 ms after touch
        assertFalse("still within 60s hold", c.nightActive.value)
        c.onTick(90_000)                 // 60,000 ms after touch, room stayed dark
        assertTrue("re-enters right after hold expires", c.nightActive.value)
    }

    @Test
    fun overrideExitsImmediatelyAndReentersWhenCleared() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onOverride(true, 31_000)
        assertFalse("override exits immediately", c.nightActive.value)
        c.onOverride(false, 32_000)
        assertTrue("override cleared in dark room re-enters immediately", c.nightActive.value)
    }

    @Test
    fun disabledNeverEntersAndDisablingExits() {
        val c = NightModeController()
        c.onConfig(false, 10)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertFalse("disabled never enters", c.nightActive.value)
        c.onConfig(true, 10)
        c.onLux(5f, 31_000); c.onLux(5f, 61_000)
        assertTrue(c.nightActive.value)
        c.onConfig(false, 10)
        assertFalse("disabling while active exits", c.nightActive.value)
    }

    @Test
    fun thresholdChangeResetsDwellClocks() {
        val c = NightModeController()
        enabled(c, 10)
        c.onLux(5f, 0)
        c.onLux(5f, 29_000)              // 29 s of dwell accumulated
        c.onConfig(true, 8)              // threshold change resets belowSince
        assertFalse(c.nightActive.value)
        c.onLux(5f, 30_000)              // new dwell starts here
        c.onLux(5f, 59_000)
        assertFalse("dwell restarted after threshold change", c.nightActive.value)
        c.onLux(5f, 60_000)
        assertTrue(c.nightActive.value)
    }

    @Test
    fun noLuxSamplesNeverEnters() {
        val c = NightModeController()
        enabled(c)
        c.onTick(0)
        c.onTick(30_000)
        c.onTick(120_000)
        assertFalse(c.nightActive.value)
    }

    @Test
    fun screenGlowInDeadBandDoesNotStrandScreenBrightAfterTouch() {
        // The waking screen's own glow can lift the sensor into the dead band [threshold, exit).
        // That must NOT clear the dark latch, or the screen re-lights the sensor forever and
        // night never returns.
        val c = NightModeController()
        enabled(c)                       // entry 10, exit 20
        c.onLux(3f, 0); c.onLux(3f, 30_000)
        assertTrue(c.nightActive.value)
        c.onUserInteraction(30_000)      // wake -> screen glow raises ambient into the dead band
        assertFalse(c.nightActive.value)
        c.onLux(14f, 40_000)
        c.onLux(14f, 80_000)
        c.onTick(90_000)                 // touch-hold expired; latch must have survived the glow
        assertTrue("dead-band glow while woken must not block re-entry", c.nightActive.value)
    }

    @Test
    fun tickingRunsWhileLatchedOrSuppressedOnly() {
        val c = NightModeController()
        enabled(c)
        assertFalse("fully off -> no ticker", c.ticking.value)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue("active -> ticker", c.ticking.value)
        c.onUserInteraction(31_000)
        assertTrue("touch-hold with dark latch -> ticker", c.ticking.value)
        c.onLux(50f, 32_000)
        c.onLux(50f, 42_000)             // 10 s sustained light clears the latch
        c.onTick(91_001)                 // touch-hold also expired
        assertFalse("bright room, hold expired -> no ticker", c.ticking.value)
    }

    @Test
    fun tickingArmsDuringEntryDwellSoASilentSensorStillLatches() {
        // Lights flicked off: one dark sample arrives, then the on-change sensor goes silent.
        // The entry dwell must arm the ticker so onTick alone completes the entry.
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0)
        assertTrue("entry dwell must arm the ticker", c.ticking.value)
        c.onTick(30_000)
        assertTrue("ticker completes entry with no further lux events", c.nightActive.value)
    }
}
