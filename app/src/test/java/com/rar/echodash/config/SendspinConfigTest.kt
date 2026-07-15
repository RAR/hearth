package com.rar.echodash.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinConfigTest {
    @Test fun clampsSyncDelayAndTrimsAddress() {
        val c = SendspinConfig(enabled = true, syncDelayMs = 9000, serverAddress = "  10.0.0.5:8927  ").clamped()
        assertEquals(2000, c.syncDelayMs)     // clamped to +/-2000
        assertEquals("10.0.0.5:8927", c.serverAddress)
    }
    @Test fun blankAddressStaysBlank() {
        assertEquals("", SendspinConfig(serverAddress = "   ").clamped().serverAddress)
    }
    @Test fun dashConfigClampedRunsSendspinClamp() {
        val d = DashConfig(sendspin = SendspinConfig(syncDelayMs = -9000)).clamped()
        assertEquals(-2000, d.sendspin.syncDelayMs)
    }
}
