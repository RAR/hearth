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
}
