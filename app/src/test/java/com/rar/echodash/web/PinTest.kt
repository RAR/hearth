package com.rar.echodash.web

import org.junit.Assert.assertEquals
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
}
