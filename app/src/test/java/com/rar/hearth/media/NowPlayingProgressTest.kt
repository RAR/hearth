package com.rar.hearth.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure-helper tests for the takeover progress bar: extrapolation, formatting, and dedup identity. */
class NowPlayingProgressTest {

    // ---- displayedPositionMs ----

    @Test fun playingExtrapolatesFromSampleInstant() {
        val s = NowPlayingState(playing = true, positionMs = 30_000, positionAtMs = 1_000_000,
            durationMs = 210_000)
        // 5s of wall-clock after the sample -> 30s + 5s.
        assertEquals(35_000L, s.displayedPositionMs(1_005_000))
    }

    @Test fun pausedPositionIsFrozen() {
        val s = NowPlayingState(playing = false, positionMs = 30_000, positionAtMs = 1_000_000,
            durationMs = 210_000)
        // Not playing -> no extrapolation regardless of nowMs.
        assertEquals(30_000L, s.displayedPositionMs(9_999_999))
    }

    @Test fun zeroPositionAtMeansRawPosition() {
        val s = NowPlayingState(playing = true, positionMs = 30_000, positionAtMs = 0,
            durationMs = 210_000)
        // positionAtMs == 0 -> "don't extrapolate", show the raw sample even while playing.
        assertEquals(30_000L, s.displayedPositionMs(9_999_999))
    }

    @Test fun extrapolationClampsToDuration() {
        val s = NowPlayingState(playing = true, positionMs = 200_000, positionAtMs = 1_000_000,
            durationMs = 210_000)
        // 20s past a 200s sample = 220s, clamped to the 210s track length.
        assertEquals(210_000L, s.displayedPositionMs(1_020_000))
    }

    @Test fun noDurationLeavesExtrapolationUnclamped() {
        val s = NowPlayingState(playing = true, positionMs = 30_000, positionAtMs = 1_000_000,
            durationMs = 0)
        // durationMs == 0 -> no upper clamp (radio has no length).
        assertEquals(35_000L, s.displayedPositionMs(1_005_000))
    }

    // ---- formatTrackTime ----

    @Test fun formatsMinutesSecondsUnderAnHour() {
        assertEquals("0:00", formatTrackTime(0))
        assertEquals("0:00", formatTrackTime(-5_000))       // negative collapses
        assertEquals("0:00", formatTrackTime(500))          // sub-second collapses
        assertEquals("0:05", formatTrackTime(5_000))
        assertEquals("1:05", formatTrackTime(65_000))
        assertEquals("9:59", formatTrackTime(599_000))
    }

    @Test fun formatsHoursAtOrOverAnHour() {
        assertEquals("1:00:00", formatTrackTime(3_600_000))
        assertEquals("1:01:01", formatTrackTime(3_661_000))
        assertEquals("2:00:05", formatTrackTime(7_205_000))
    }

    // ---- equals/hashCode: the new fields are part of the dedup identity ----

    @Test fun statesDifferingOnlyInPositionAreNotEqual() {
        val a = NowPlayingState(active = true, title = "T", positionMs = 10_000)
        val b = a.copy(positionMs = 11_000)
        assertNotEquals("a position-only change must not be deduped away", a, b)
    }

    @Test fun eachNewFieldBreaksEquality() {
        val base = NowPlayingState(active = true, title = "T")
        assertNotEquals(base, base.copy(durationMs = 1))
        assertNotEquals(base, base.copy(positionMs = 1))
        assertNotEquals(base, base.copy(positionAtMs = 1))
        assertNotEquals(base, base.copy(canSeek = true))
    }

    @Test fun equalStatesShareHashCode() {
        val a = NowPlayingState(active = true, title = "T", durationMs = 210_000,
            positionMs = 42_000, positionAtMs = 1_000_000, canSeek = true)
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
