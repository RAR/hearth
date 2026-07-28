package com.rar.hearth.web

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun concurrentWrongPinLoginsProduceConsistentLockoutState() {
        val m = mgr()
        val threadCount = 8
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = java.util.concurrent.ConcurrentLinkedQueue<LoginResult>()

        try {
            repeat(threadCount) {
                executor.submit {
                    ready.countDown()
                    start.await()
                    results += m.login("000000", "123456")
                    done.countDown()
                }
            }
            ready.await()
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }

        assertEquals(threadCount, results.size)
        val invalidCount = results.count { it == LoginResult.Invalid }
        val lockedOutCount = results.count { it is LoginResult.LockedOut }
        assertEquals(0, results.count { it is LoginResult.Ok })
        // No failure was lost to a race: every attempt is accounted for as either a pre-lockout
        // Invalid or a LockedOut response (the 5th failure and everything after, since the clock
        // is fixed and the lockout window never elapses during the test).
        assertEquals(threadCount, invalidCount + lockedOutCount)
        assertEquals(4, invalidCount)
        assertEquals(threadCount - 4, lockedOutCount)
        // Manager must now be locked out regardless of PIN correctness.
        val afterLockout = m.login("123456", "123456")
        assertTrue(afterLockout is LoginResult.LockedOut)
    }

    @Test
    fun defaultConstructorProducesDifferentTokensAcrossInstances() {
        // The default RNG must be a CSPRNG, not the fixed-seed test path: two independently
        // constructed managers must not derive the same token for the same PIN. This doesn't
        // (and can't) test statistical quality, only that we're not on the deterministic seam.
        val a = SessionManager(clock = { now })
        val b = SessionManager(clock = { now })
        val tokenA = (a.login("123456", "123456") as LoginResult.Ok).token
        val tokenB = (b.login("123456", "123456") as LoginResult.Ok).token
        assertTrue(tokenA != tokenB)
    }

    @Test
    fun perClientLockoutDoesNotAffectOtherClients() {
        val m = mgr()
        repeat(5) { m.login("000000", "123456", "1.1.1.1") }
        val lockedOutA = m.login("123456", "123456", "1.1.1.1")
        assertTrue(lockedOutA is LoginResult.LockedOut)

        // A different address still gets its own budget.
        val okB = m.login("123456", "123456", "2.2.2.2")
        assertTrue(okB is LoginResult.Ok)
    }

    @Test
    fun globalBackstopFiresWhenFailuresAreSpreadAcrossManyAddresses() {
        val m = mgr()
        // 4 failures per address (below the per-client threshold of 5) from 13 different
        // addresses = 52 consecutive failures overall, tripping the 50-failure global backstop
        // well before any single address reaches its own 5-attempt lockout.
        var result: LoginResult = LoginResult.Invalid
        outer@ for (i in 0 until 13) {
            for (j in 0 until 4) {
                result = m.login("000000", "123456", "10.0.0.$i")
                if (result is LoginResult.LockedOut) break@outer
            }
        }
        assertTrue(result is LoginResult.LockedOut)
        assertEquals(60L, (result as LoginResult.LockedOut).retryAfterSeconds)

        // Every address is now locked out, including ones that never individually failed.
        val untouchedAddress = m.login("123456", "123456", "192.168.0.1")
        assertTrue(untouchedAddress is LoginResult.LockedOut)
    }

    @Test
    fun nullOrBlankAddressIsTreatedAsASingleBucket() {
        val m = mgr()
        repeat(4) { m.login("000000", "123456", null) }
        val fifth = m.login("000000", "123456", "")
        assertTrue(fifth is LoginResult.LockedOut)
    }

    @Test
    fun perClientMapDoesNotGrowWithoutBound() {
        val m = mgr()
        // Successful logins from many more distinct addresses than the map's cap: each is
        // immediately evictable (zero failures, no lockout), so the map must stay bounded rather
        // than growing forever.
        repeat(500) { i -> m.login("123456", "123456", "203.0.113.$i") }
        assertTrue(m.trackedClientCountForTest() <= 256)
    }
}
