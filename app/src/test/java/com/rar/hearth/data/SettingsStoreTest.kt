package com.rar.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun roundTripsAllFields() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("at", s.accessToken)
        assertEquals(123L, s.accessTokenExpiresAt)
        assertEquals("rt", s.refreshToken)
    }

    @Test
    fun clearAuthKeepsUrl() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        s.clearAuth()
        assertNull(s.accessToken)
        assertEquals(0L, s.accessTokenExpiresAt)
        assertNull(s.refreshToken)
        assertEquals("http://ha.local:8123", s.baseUrl)
    }

    @Test
    fun vacaSettingsSurviveClearAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.vacaSettingsJson = """{"screen_brightness":40}"""
        s.clearAuth()
        assertEquals("""{"screen_brightness":40}""", s.vacaSettingsJson)
    }

    @Test
    fun configPinPersistsAcrossClearAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.configPin = "042100"
        assertEquals("042100", s.configPin)
        s.accessToken = "at"; s.refreshToken = "rt"
        s.clearAuth()
        assertEquals("042100", s.configPin) // the PIN is not auth; it survives logout
    }

    @Test
    fun authClientIdRoundTripsAndClearsWithAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.authClientId = "http://10.0.0.5:8080/"
        assertEquals("http://10.0.0.5:8080/", s.authClientId)
        s.accessToken = "at"; s.refreshToken = "rt"
        s.clearAuth()
        assertNull(s.authClientId)                        // clientId is auth material; cleared on logout
        assertEquals("http://ha.local:8123", s.baseUrl)   // base url is kept
    }
}
