package com.rar.echodash.ui.model

import com.rar.echodash.voice.TimerChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTakeoverModelTest {
    private fun chip(id: String, name: String = "", remainingSec: Long = 60, active: Boolean = true) =
        TimerChip(id, name, remainingSec, active)

    // 1. empty -> hidden; adding one -> visible with the voice name as label.
    @Test
    fun emptyHiddenThenVisibleWithVoiceName() {
        val m = TimerTakeoverModel()
        assertEquals(emptyList<TakeoverTimer>(), m.update(emptyList()))
        assertFalse(m.visible)
        val out = m.update(listOf(chip("a", name = "Pasta", remainingSec = 120)))
        assertTrue(m.visible)
        assertEquals(listOf(TakeoverTimer("a", "Pasta", 120, true)), out)
    }

    // 2. blank name -> duration fallback; rename overrides; rename pruned when the id disappears.
    @Test
    fun fallbackLabelRenameOverrideAndPrune() {
        val m = TimerTakeoverModel()
        assertEquals("10 min timer", m.update(listOf(chip("a", remainingSec = 600))).single().label)
        m.rename("a", "Eggs")
        assertEquals("Eggs", m.update(listOf(chip("a", remainingSec = 600))).single().label)
        m.update(listOf(chip("b", remainingSec = 600)))  // "a" gone -> its rename is pruned
        assertEquals("10 min timer", m.update(listOf(chip("a", remainingSec = 600))).single().label)
    }

    // 3. dismiss hides; same timers stay hidden; a NEW id re-shows (old ids stay marked).
    @Test
    fun dismissHidesUntilNewId() {
        val m = TimerTakeoverModel()
        m.update(listOf(chip("a")))
        assertTrue(m.visible)
        m.dismiss()
        assertFalse(m.visible)
        m.update(listOf(chip("a")))                 // same timer still hidden
        assertFalse(m.visible)
        m.update(listOf(chip("a"), chip("b")))      // new id b re-shows
        assertTrue(m.visible)
    }

    // 4. all timers gone -> dismiss + rename state reset; the next timer shows fresh.
    @Test
    fun clearingResetsTransientState() {
        val m = TimerTakeoverModel()
        m.update(listOf(chip("a", name = "Pasta")))
        m.rename("a", "Eggs")
        m.dismiss()
        assertFalse(m.visible)
        m.update(emptyList())                        // all gone -> reset
        val out = m.update(listOf(chip("a", name = "Pasta")))
        assertTrue(m.visible)                        // not still dismissed
        assertEquals("Pasta", out.single().label)    // rename gone
    }

    // 5. paused timer keeps its remaining seconds and active=false passes through.
    @Test
    fun pausedPassesThrough() {
        val m = TimerTakeoverModel()
        val out = m.update(listOf(chip("a", name = "Tea", remainingSec = 45, active = false)))
        assertEquals(TakeoverTimer("a", "Tea", 45, false), out.single())
    }

    // 6a. countdown formatter matches TimerChips semantics.
    @Test
    fun formatTimerMatchesChipSemantics() {
        assertEquals("0:45", formatTimer(45))
        assertEquals("10:05", formatTimer(605))
        assertEquals("1:01:01", formatTimer(3661))
    }

    // 6b. duration fallback label buckets.
    @Test
    fun defaultTimerLabelBuckets() {
        assertEquals("10 min timer", defaultTimerLabel(600))
        assertEquals("45 sec timer", defaultTimerLabel(45))
        assertEquals("1 hr 1 min timer", defaultTimerLabel(3661))
    }
}
