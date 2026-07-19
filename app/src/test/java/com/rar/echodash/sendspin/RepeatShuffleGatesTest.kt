package com.rar.echodash.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure toggle-visibility gate derived from the SendSpin controller's advertised
 * `supported_commands`. Lives beside [SendspinEndpoint] (its only consumer), like PlaybackIntent.
 */
class RepeatShuffleGatesTest {

    @Test
    fun nullSetIsOptimisticBothTrue() {
        val g = repeatShuffleGates(null)
        assertEquals(true, g.canRepeat)
        assertEquals(true, g.canShuffle)
    }

    @Test
    fun partialRepeatOnlySet() {
        // Only a subset of repeat modes advertised, no shuffle command.
        val g = repeatShuffleGates(listOf("play", "pause", "repeat_all"))
        assertEquals(true, g.canRepeat)
        assertEquals(false, g.canShuffle)
    }

    @Test
    fun shuffleUnshuffleSet() {
        // "unshuffle" alone still enables the shuffle toggle; no repeat command present.
        val g = repeatShuffleGates(listOf("play", "unshuffle"))
        assertEquals(false, g.canRepeat)
        assertEquals(true, g.canShuffle)
    }

    @Test
    fun unrelatedCommandsNeither() {
        val g = repeatShuffleGates(listOf("play", "pause", "next", "previous", "volume"))
        assertEquals(false, g.canRepeat)
        assertEquals(false, g.canShuffle)
    }
}
