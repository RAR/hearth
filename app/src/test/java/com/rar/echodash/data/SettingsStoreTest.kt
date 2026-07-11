package com.rar.echodash.data

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
        s.temperatureEntityId = "sensor.outside_temperature"
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("at", s.accessToken)
        assertEquals(123L, s.accessTokenExpiresAt)
        assertEquals("rt", s.refreshToken)
        assertEquals("sensor.outside_temperature", s.temperatureEntityId)
    }

    @Test
    fun clearAuthKeepsUrlAndEntity() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        s.temperatureEntityId = "sensor.x"
        s.clearAuth()
        assertNull(s.accessToken)
        assertEquals(0L, s.accessTokenExpiresAt)
        assertNull(s.refreshToken)
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("sensor.x", s.temperatureEntityId)
    }

    @Test
    fun vacaSettingsSurviveClearAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.vacaSettingsJson = """{"screen_brightness":40}"""
        s.clearAuth()
        assertEquals("""{"screen_brightness":40}""", s.vacaSettingsJson)
    }
}
