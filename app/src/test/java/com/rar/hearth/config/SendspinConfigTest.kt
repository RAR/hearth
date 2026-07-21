package com.rar.hearth.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test fun clampBlanksMalformedAddress() {
        // Obviously-malformed values fall back to mDNS discovery (blank) rather than churning.
        assertEquals("", SendspinConfig(serverAddress = "http://ma.local").clamped().serverAddress)
        assertEquals("", SendspinConfig(serverAddress = "host:99999").clamped().serverAddress)
        assertEquals("", SendspinConfig(serverAddress = "a b").clamped().serverAddress)
        // A plausible host:port survives.
        assertEquals("ma.local:8095", SendspinConfig(serverAddress = "ma.local:8095").clamped().serverAddress)
    }
    @Test fun isValidSendspinAddressAcceptsHostAndHostPort() {
        assertTrue(isValidSendspinAddress(""))              // blank = mDNS
        assertTrue(isValidSendspinAddress("10.0.0.5"))      // bare host
        assertTrue(isValidSendspinAddress("10.0.0.5:8927")) // host:port
        assertTrue(isValidSendspinAddress("ma.local:1"))    // min port
        assertTrue(isValidSendspinAddress("ma.local:65535"))// max port
    }
    @Test fun isValidSendspinAddressRejectsMalformed() {
        assertFalse(isValidSendspinAddress("http://x"))     // scheme
        assertFalse(isValidSendspinAddress("host/path"))    // slash
        assertFalse(isValidSendspinAddress("a b"))          // whitespace
        assertFalse(isValidSendspinAddress(":8095"))        // empty host
        assertFalse(isValidSendspinAddress("host:0"))       // port below range
        assertFalse(isValidSendspinAddress("host:70000"))   // port above range
        assertFalse(isValidSendspinAddress("host:abc"))     // non-numeric port
    }
    @Test fun dashConfigClampedRunsSendspinClamp() {
        val d = DashConfig(sendspin = SendspinConfig(syncDelayMs = -9000)).clamped()
        assertEquals(-2000, d.sendspin.syncDelayMs)
    }
    @Test fun decodeOfEmptyObjectYieldsBlankMaFields() {
        // Back-compat: device configs saved before the MA account fields existed must decode.
        val c = ConfigJson.json.decodeFromString(SendspinConfig.serializer(), "{}")
        assertEquals("", c.maToken)
        assertEquals("", c.maUser)
    }
    @Test fun clampTrimsMaFields() {
        val c = SendspinConfig(maToken = "  tok-1  ", maUser = "  Andrew  ").clamped()
        assertEquals("tok-1", c.maToken)
        assertEquals("Andrew", c.maUser)
    }
}
