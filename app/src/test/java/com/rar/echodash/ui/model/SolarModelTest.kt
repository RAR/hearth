package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String) =
        EntityState(id, state, attrs("""{"unit_of_measurement":"$unit"}"""), 0L)

    private val regAll = parseEntityRegistry(Json.parseToJsonElement(
        """[
          {"entity_id":"sensor.pv","labels":["echo-solar-pv"]},
          {"entity_id":"sensor.load","labels":["echo-solar-load"]},
          {"entity_id":"sensor.grid","labels":["echo-solar-grid"]},
          {"entity_id":"sensor.pvday","labels":["echo-solar-pv-today"]},
          {"entity_id":"sensor.loadday","labels":["echo-solar-load-today"]}
        ]"""
    ))

    @Test
    fun formatsWattsAndKwAndGridSignAndToday() {
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3500", "W"),
            "sensor.load" to st("sensor.load", "800", "W"),
            "sensor.grid" to st("sensor.grid", "-1200", "W"),
            "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
        )
        val flow = buildSolarFlow(regAll, entities)
        assertEquals("3.5 kW", flow.pv!!.watts)
        assertEquals("800 W", flow.home!!.watts)
        assertEquals("1.2 kW", flow.grid!!.watts)     // magnitude only; sign is separate
        assertEquals(false, flow.gridImporting)       // -1200 => exporting
        assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", flow.todayLine)
    }

    @Test
    fun noGridSensorGivesTwoNodeFlowAndPartialToday() {
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "1000", "W"),
            "sensor.load" to st("sensor.load", "1500", "W"),
            "sensor.pvday" to st("sensor.pvday", "5.0", "kWh"),
        )
        val flow = buildSolarFlow(regAll, entities)
        assertNull(flow.grid)
        assertNull(flow.gridImporting)
        assertEquals("1.0 kW", flow.pv!!.watts)
        assertEquals("Today: 5.0 kWh produced", flow.todayLine)
    }
}
