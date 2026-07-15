package com.rar.echodash.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashDoneTest {
    @Test fun staysUpBeforeMinEvenIfConnected() {
        assertFalse(splashDone(elapsedMs = 300, connected = true, minMs = 700, maxMs = 2000))
    }

    @Test fun dismissesAfterMinOnceConnected() {
        assertTrue(splashDone(elapsedMs = 800, connected = true, minMs = 700, maxMs = 2000))
    }

    @Test fun staysUpAfterMinWhileNotConnected() {
        assertFalse(splashDone(elapsedMs = 1500, connected = false, minMs = 700, maxMs = 2000))
    }

    @Test fun dismissesAtCapEvenIfNeverConnected() {
        assertTrue(splashDone(elapsedMs = 2000, connected = false, minMs = 700, maxMs = 2000))
    }

    @Test fun capWinsPastBoundaryWhileDisconnected() {
        assertTrue(splashDone(elapsedMs = 2001, connected = false))
    }
}
