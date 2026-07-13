package com.rar.echodash.vaca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun notStaleWhenNeverPaused() {
        var now = 0L
        val policy = ResumePolicy { now }
        now = 10_000_000
        assertFalse(policy.isStale())
    }

    @Test
    fun notStaleWithinAMinuteOfPause() {
        var now = 0L
        val policy = ResumePolicy { now }
        policy.onPause()
        now = 59_999
        assertFalse(policy.isStale())
    }

    @Test
    fun staleAtSixtySeconds() {
        var now = 0L
        val policy = ResumePolicy { now }
        policy.onPause()
        now = 60_000
        assertTrue(policy.isStale())
    }

    @Test
    fun playClearsStaleness() {
        var now = 0L
        val policy = ResumePolicy { now }
        policy.onPause()
        now = 120_000
        policy.onPlay()
        assertFalse(policy.isStale())
    }

    @Test
    fun repeatedPauseKeepsFirstTimestamp() {
        var now = 0L
        val policy = ResumePolicy { now }
        policy.onPause()
        now = 50_000
        policy.onPause()
        now = 60_000
        assertTrue(policy.isStale())
    }

    @Test
    fun pauseAfterPlayStartsFreshClock() {
        var now = 0L
        val policy = ResumePolicy { now }
        policy.onPause()
        now = 10_000
        policy.onPlay()
        now = 30_000
        policy.onPause()
        now = 80_000
        assertFalse(policy.isStale())
        now = 90_000
        assertTrue(policy.isStale())
    }
}
