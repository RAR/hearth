package com.rar.echodash.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleReturnTimerTest {

    @Test
    fun firesAfterTimeoutOnNonHomeView() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(1, returns)
        timer.cancel()
    }

    @Test
    fun interactionResetsTheCountdown() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(59_000); runCurrent()
        timer.onInteraction()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, returns)          // reset kept it alive
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, returns)
        timer.cancel()
    }

    @Test
    fun homeViewIsExemptAndCancelsPending() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(30_000); runCurrent()
        timer.onViewChanged(isHome = true)   // back to Home cancels
        advanceTimeBy(60_000); runCurrent()
        timer.onInteraction()                // interaction on Home does nothing
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, returns)
        timer.cancel()
    }

    @Test
    fun replacedTimerCancelledBeforeFiringDoesNotRun() = runTest {
        // Simulates a mid-session config change: the old timer is armed, then replaced.
        // Its cancel() must be called (as the Compose DisposableEffect does on key change)
        // so its pending job never fires, even once the original timeout has elapsed.
        var oldReturns = 0
        val oldTimer = IdleReturnTimer(this, timeoutMs = 60_000) { oldReturns++ }
        oldTimer.onViewChanged(isHome = false)
        advanceTimeBy(30_000); runCurrent()

        oldTimer.cancel() // replacement timer takes over; old one is disposed

        var newReturns = 0
        val newTimer = IdleReturnTimer(this, timeoutMs = 15_000) { newReturns++ }
        newTimer.onViewChanged(isHome = false)

        advanceTimeBy(60_000); runCurrent() // well past the old timer's original 60s timeout
        assertEquals(0, oldReturns)         // old timer's pending fire never ran
        assertEquals(1, newReturns)         // new timer fired on its own (shorter) timeout

        newTimer.cancel()
    }
}
