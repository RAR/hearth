package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String) =
        EntityState(id, state, attrs("""{"unit_of_measurement":"$unit"}"""), 0L)

    @Test
    fun formatsWattsAndKwAndGridSignAndToday() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            pvToday = "sensor.pvday", loadToday = "sensor.loadday")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3500", "W"),
            "sensor.load" to st("sensor.load", "800", "W"),
            "sensor.grid" to st("sensor.grid", "-1200", "W"),
            "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
        )
        val flow = solarFlow(cfg, entities)
        assertEquals("3.5 kW", flow.pv!!.watts)
        assertEquals("800 W", flow.home!!.watts)
        assertEquals("1.2 kW", flow.grid!!.watts)
        assertEquals(false, flow.gridImporting)
        assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", flow.todayLine)
    }

    @Test
    fun noGridSensorGivesTwoNodeFlowAndPartialToday() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", pvToday = "sensor.pvday")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "1000", "W"),
            "sensor.load" to st("sensor.load", "1500", "W"),
            "sensor.pvday" to st("sensor.pvday", "5.0", "kWh"),
        )
        val flow = solarFlow(cfg, entities)
        assertNull(flow.grid)
        assertNull(flow.gridImporting)
        assertEquals("1.0 kW", flow.pv!!.watts)
        assertEquals("Today: 5.0 kWh produced", flow.todayLine)
    }
}
