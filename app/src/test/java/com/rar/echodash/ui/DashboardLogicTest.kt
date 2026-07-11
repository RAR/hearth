package com.rar.echodash.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLogicTest {
    @Test
    fun freshReadingIsNotStale() {
        assertFalse(isStale(nowMs = 1_000_000L, updatedAtMs = 1_000_000L - 14 * 60_000L))
    }

    @Test
    fun oldReadingIsStale() {
        assertTrue(isStale(nowMs = 1_000_000L + 16 * 60_000L, updatedAtMs = 1_000_000L))
    }

    @Test
    fun missingReadingIsNotStale() {
        assertFalse(isStale(nowMs = 1_000_000L, updatedAtMs = null))
    }
}
