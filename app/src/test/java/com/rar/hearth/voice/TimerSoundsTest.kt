package com.rar.hearth.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerSoundsTest {

    @Test
    fun systemTonesMapToAssetPaths() {
        assertEquals("sounds/alarm_argon.ogg", TimerSounds.assetPath("argon"))
        assertEquals("sounds/alarm_oxygen.ogg", TimerSounds.assetPath("oxygen"))
        assertEquals("sounds/alarm_krypton.ogg", TimerSounds.assetPath("krypton"))
        assertEquals("sounds/alarm_timer.ogg", TimerSounds.assetPath("timer"))
        assertEquals("sounds/alarm_beep.ogg", TimerSounds.assetPath("beep"))
        assertEquals("sounds/alarm_helium.ogg", TimerSounds.assetPath("helium"))
        assertEquals("sounds/alarm_cyan.ogg", TimerSounds.assetPath("cyan"))
    }

    @Test
    fun synthesizedTonesHaveNoAsset() {
        assertNull(TimerSounds.assetPath("twotone"))
        assertNull(TimerSounds.assetPath("beeps"))
        assertNull(TimerSounds.assetPath("chime"))
        assertNull(TimerSounds.assetPath("trill"))
    }

    @Test
    fun unknownKeyHasNoAsset() {
        assertNull(TimerSounds.assetPath("wobble"))
        assertNull(TimerSounds.assetPath(""))
    }
}
