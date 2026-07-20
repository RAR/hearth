package com.rar.hearth.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneGeneratorTest {

    private val rate = 22050

    /** Max absolute sample value in a buffer (peak amplitude). */
    private fun peak(buf: ShortArray): Int = buf.maxOf { abs(it.toInt()) }

    /** Assert an actual length is within one frame of the expected cycle length (spec: +/-1). */
    private fun assertNearLen(expected: Int, buf: ShortArray) {
        assertTrue("expected ~$expected frames, got ${buf.size}", abs(buf.size - expected) <= 1)
    }

    @Test
    fun eachPresetRendersExpectedCycleLength() {
        // Derivations (integer math, rate=22050):
        //   twotone: 2*(rate*200/1000) + rate                 = 2*4410 + 22050 = 30870
        //   beeps:   3*(rate*120/1000) + 2*(rate*80/1000) + rate = 7938 + 3528 + 22050 = 33516
        //   chime:   2*(rate*350/1000) + (rate*1600/1000)     = 15434 + 35280 = 50714
        //   trill:   rate + (rate*600/1000)                   = 22050 + 13230 = 35280
        assertNearLen(30870, ToneGenerator.render("twotone", 100, rate))
        assertNearLen(33516, ToneGenerator.render("beeps", 100, rate))
        assertNearLen(50714, ToneGenerator.render("chime", 100, rate))
        assertNearLen(35280, ToneGenerator.render("trill", 100, rate))
    }

    @Test
    fun everyPresetIsNonEmpty() {
        for (t in listOf("twotone", "beeps", "chime", "trill")) {
            assertTrue("$t was empty", ToneGenerator.render(t, 80, rate).isNotEmpty())
        }
    }

    @Test
    fun volumeZeroRendersSilenceForEveryPreset() {
        for (t in listOf("twotone", "beeps", "chime", "trill")) {
            val buf = ToneGenerator.render(t, 0, rate)
            assertTrue("$t at volume 0 was not silent", buf.all { it.toInt() == 0 })
        }
    }

    @Test
    fun volume100PeaksNearSixtyPercentHeadroom() {
        val p = peak(ToneGenerator.render("twotone", 100, rate))
        // amp at v=100 is 0.6*Short.MAX_VALUE = 19660.2; truncation keeps the peak just under it,
        // and a dense sine gets close to the crest.
        assertTrue("peak $p too low", p >= (0.58 * Short.MAX_VALUE).toInt())
        assertTrue("peak $p exceeds headroom", p <= (0.6 * Short.MAX_VALUE).toInt() + 1)
    }

    @Test
    fun amplitudeScalesWithVolume() {
        val p0 = peak(ToneGenerator.render("twotone", 0, rate))
        val p50 = peak(ToneGenerator.render("twotone", 50, rate))
        val p100 = peak(ToneGenerator.render("twotone", 100, rate))
        assertEquals(0, p0)
        assertTrue("expected p50 between 0 and p100", p50 in 1 until p100)
    }

    @Test
    fun unknownToneFallsBackToTwotoneIdentically() {
        val fallback = ToneGenerator.render("not-a-real-tone", 100, rate)
        val twotone = ToneGenerator.render("twotone", 100, rate)
        assertTrue(fallback.contentEquals(twotone))
    }

    @Test
    fun earconLengthsAndSilence() {
        val rate = 16000
        val wake = ToneGenerator.earcon("wake", 80, rate)
        val done = ToneGenerator.earcon("done", 80, rate)
        val preview = ToneGenerator.earcon("preview", 80, rate)
        assertEquals(rate * 70 / 1000 + rate * 90 / 1000, wake.size)
        assertEquals(rate * 100 / 1000 + rate * 120 / 1000, done.size)
        assertEquals(wake.size + rate * 150 / 1000 + done.size, preview.size)
        // Audible at volume 80, pure silence at 0, unknown kind falls back to wake.
        assertTrue(wake.any { it.toInt() != 0 })
        assertTrue(ToneGenerator.earcon("wake", 0, rate).all { it.toInt() == 0 })
        assertEquals(wake.size, ToneGenerator.earcon("bogus", 80, rate).size)
    }

    @Test
    fun rateParameterIsRespected() {
        // twotone @8000: 2*(8000*200/1000) + 8000 = 3200 + 8000 = 11200
        assertNearLen(11200, ToneGenerator.render("twotone", 100, 8000))
        assertTrue(ToneGenerator.render("twotone", 100, 8000).size <
            ToneGenerator.render("twotone", 100, rate).size)
    }
}
