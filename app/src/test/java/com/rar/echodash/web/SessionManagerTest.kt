package com.rar.echodash.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionManagerTest {
    private var now = 1_000L
    private fun mgr() = SessionManager(clock = { now }, random = Random(7))

    @Test
    fun correctPinIssuesTokenThatValidates() {
        val m = mgr()
        val r = m.login("123456", "123456")
        assertTrue(r is LoginResult.Ok)
        val token = (r as LoginResult.Ok).token
        assertTrue(m.isValidSession(token))
        assertFalse(m.isValidSession("nope"))
        assertFalse(m.isValidSession(null))
    }

    @Test
    fun wrongPinReturnsInvalidWithoutLockoutUntilFifth() {
        val m = mgr()
        repeat(4) { assertEquals(LoginResult.Invalid, m.login("000000", "123456")) }
        // 5th consecutive failure triggers the lockout
        val fifth = m.login("000000", "123456")
        assertTrue(fifth is LoginResult.LockedOut)
        assertEquals(60L, (fifth as LoginResult.LockedOut).retryAfterSeconds)
    }

    @Test
    fun lockoutRejectsEvenCorrectPinUntilItExpires() {
        val m = mgr()
        repeat(5) { m.login("000000", "123456") } // now locked out at t=1000
        val duringLockout = m.login("123456", "123456")
        assertTrue(duringLockout is LoginResult.LockedOut)

        now += 60_000L + 1 // lockout window elapses
        val afterLockout = m.login("123456", "123456")
        assertTrue(afterLockout is LoginResult.Ok)
    }

    @Test
    fun successResetsTheFailureCounter() {
        val m = mgr()
        repeat(4) { m.login("000000", "123456") }
        assertTrue(m.login("123456", "123456") is LoginResult.Ok) // resets counter
        repeat(4) { assertEquals(LoginResult.Invalid, m.login("000000", "123456")) } // no lockout yet
    }
}
