package com.rar.hearth.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PinTest {
    @Test
    fun sixDigitsWithLeadingZerosAllowed() {
        repeat(200) {
            val pin = generatePin(Random(it.toLong()))
            assertEquals(6, pin.length)
            assertTrue(pin.all { c -> c.isDigit() })
        }
    }

    @Test
    fun notifyTokenIs32LowercaseHex() {
        repeat(50) {
            val t = generateNotifyToken()
            assertEquals(32, t.length)
            assertTrue(t.all { c -> c in '0'..'9' || c in 'a'..'f' })
        }
    }

    @Test
    fun customPinAcceptsFourToEightDigits() {
        assertTrue(isValidCustomPin("1234"))
        assertTrue(isValidCustomPin("123456"))
        assertTrue(isValidCustomPin("12345678"))
    }

    @Test
    fun customPinRejectsWrongLengthOrNonDigits() {
        assertFalse(isValidCustomPin("123"))        // too short (3)
        assertFalse(isValidCustomPin("123456789"))  // too long (9)
        assertFalse(isValidCustomPin("12ab34"))     // letters
        assertFalse(isValidCustomPin(""))           // empty
        assertFalse(isValidCustomPin("12 34"))      // embedded space
    }
}
