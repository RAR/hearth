package com.rar.echodash.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerGuardTest {

    @Test
    fun echoHardwareGetsMicpgaCommand() {
        val cmds = MixerGuard.mixerCommands(tinymixExists = true, hardware = "mt8163")
        assertEquals(1, cmds.size)
        assertEquals(
            listOf("/system/bin/tinymix", "ADC_A MICPGA Volume Ctrl", "64", "64"),
            cmds[0],
        )
    }

    @Test
    fun otherHardwareNoOps() {
        assertTrue(MixerGuard.mixerCommands(tinymixExists = true, hardware = "qcom").isEmpty())
        assertTrue(MixerGuard.mixerCommands(tinymixExists = true, hardware = "mt6789").isEmpty())
    }

    @Test
    fun missingTinymixNoOps() {
        assertTrue(MixerGuard.mixerCommands(tinymixExists = false, hardware = "mt8163").isEmpty())
    }
}
