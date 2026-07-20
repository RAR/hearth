package com.rar.hearth.ui.model

import com.rar.hearth.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AqiModelTest {
    private fun sensor(state: String, updatedMs: Long = 1_000L) =
        mapOf("sensor.aqi" to EntityState("sensor.aqi", state, JsonObject(emptyMap()), updatedMs))

    @Test
    fun bandsFollowEpaBreakpoints() {
        assertEquals(AqiBand.GOOD, aqiBand(0))
        assertEquals(AqiBand.GOOD, aqiBand(50))
        assertEquals(AqiBand.MODERATE, aqiBand(51))
        assertEquals(AqiBand.MODERATE, aqiBand(100))
        assertEquals(AqiBand.UNHEALTHY_SENSITIVE, aqiBand(101))
        assertEquals(AqiBand.UNHEALTHY_SENSITIVE, aqiBand(150))
        assertEquals(AqiBand.UNHEALTHY, aqiBand(151))
        assertEquals(AqiBand.UNHEALTHY, aqiBand(200))
        assertEquals(AqiBand.VERY_UNHEALTHY, aqiBand(201))
        assertEquals(AqiBand.VERY_UNHEALTHY, aqiBand(300))
        assertEquals(AqiBand.HAZARDOUS, aqiBand(301))
    }

    @Test
    fun pillReadsValueAndBand() {
        val pill = aqiPill("sensor.aqi", sensor("42"))!!
        assertEquals(42, pill.value)
        assertEquals(AqiBand.GOOD, pill.band)
    }

    @Test
    fun pillTruncatesDecimalStates() {
        assertEquals(87, aqiPill("sensor.aqi", sensor("87.6"))!!.value)
    }

    @Test
    fun pillHiddenWhenUnsetMissingOrNonNumeric() {
        assertNull(aqiPill(null, sensor("42")))
        assertNull(aqiPill("sensor.other", sensor("42")))
        assertNull(aqiPill("sensor.aqi", sensor("unavailable")))
    }
}
