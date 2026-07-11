package com.rar.echodash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLogicTest {
    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://ha.local:8123", normalizeBaseUrl("ha.local:8123"))
    }

    @Test
    fun keepsExplicitSchemeAndStripsTrailingSlash() {
        assertEquals("https://ha.example.com", normalizeBaseUrl("https://ha.example.com/"))
        assertEquals("http://192.168.1.10:8123", normalizeBaseUrl(" http://192.168.1.10:8123/ "))
    }

    @Test
    fun rejectsBlankAndNonHttpSchemes() {
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://ha.local"))
    }
}
