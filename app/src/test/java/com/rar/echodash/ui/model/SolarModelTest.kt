package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun solarCardFullData() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battSoc = "sensor.soc", battPower = "sensor.batt")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3200", "W"),
            "sensor.load" to st("sensor.load", "1400", "W"),
            "sensor.grid" to st("sensor.grid", "-1800", "W"),
            "sensor.soc" to st("sensor.soc", "78.4", "%"),
            "sensor.batt" to st("sensor.batt", "-200", "W"),
        )
        val card = solarCard(cfg, entities)!!
        assertEquals("3.2 kW", card.pvText)
        assertEquals(78, card.socPct)
        assertEquals(BattFlow.CHARGING, card.battFlow)
        assertEquals("1.4 kW", card.homeText)
        assertEquals("1.8 kW", card.gridText)
        assertEquals(false, card.gridImporting) // negative grid = exporting
    }

    @Test
    fun solarCardGridImportDirection() {
        val cfg = SolarConfig(load = "sensor.load", grid = "sensor.grid")
        val entities = mapOf(
            "sensor.load" to st("sensor.load", "900", "W"),
            "sensor.grid" to st("sensor.grid", "450", "W"),
        )
        val card = solarCard(cfg, entities)!!
        assertEquals("900 W", card.homeText)
        assertEquals("450 W", card.gridText)
        assertEquals(true, card.gridImporting) // positive grid = importing
    }

    @Test
    fun battFlowSignAndDeadband() {
        val cfg = SolarConfig(battSoc = "sensor.soc", battPower = "sensor.batt")
        fun cardWith(state: String, unit: String) = solarCard(cfg, mapOf(
            "sensor.soc" to st("sensor.soc", "50", "%"),
            "sensor.batt" to st("sensor.batt", state, unit),
        ))!!
        assertEquals(BattFlow.CHARGING, cardWith("-200", "W").battFlow)
        assertEquals(BattFlow.IDLE, cardWith("-20", "W").battFlow)     // inside deadband
        assertEquals(BattFlow.IDLE, cardWith("30", "W").battFlow)     // inside deadband
        assertEquals(BattFlow.DISCHARGING, cardWith("500", "W").battFlow)
        assertEquals(BattFlow.CHARGING, cardWith("-0.2", "kW").battFlow)   // kW unit-aware
        assertEquals(BattFlow.DISCHARGING, cardWith("0.4", "kW").battFlow)
    }

    @Test
    fun solarCardMissingPiecesDegrade() {
        // No pv sensor: card still produced, header has no output text.
        val noPv = solarCard(SolarConfig(load = "sensor.load"),
            mapOf("sensor.load" to st("sensor.load", "800", "W")))!!
        assertNull(noPv.pvText)
        assertEquals("800 W", noPv.homeText)
        assertNull(noPv.gridText)
        // Non-numeric SOC: no gauge, card still produced.
        val badSoc = solarCard(SolarConfig(battSoc = "sensor.soc"),
            mapOf("sensor.soc" to st("sensor.soc", "unknown", "%")))
        assertNotNull(badSoc)
        assertNull(badSoc!!.socPct)
        assertNull(badSoc.homeText)
        assertNull(badSoc.gridText)
        assertEquals(BattFlow.IDLE, badSoc.battFlow)
        // SOC clamped to 0..100.
        val over = solarCard(SolarConfig(battSoc = "sensor.soc"),
            mapOf("sensor.soc" to st("sensor.soc", "104", "%")))!!
        assertEquals(100, over.socPct)
    }

    @Test
    fun solarCardNullWhenNothingResolves() {
        assertNull(solarCard(SolarConfig(), emptyMap()))
        // Configured but entity absent from the map:
        assertNull(solarCard(SolarConfig(pv = "sensor.pv"), emptyMap()))
        // battPower alone does not make a card:
        assertNull(solarCard(SolarConfig(battPower = "sensor.batt"),
            mapOf("sensor.batt" to st("sensor.batt", "-500", "W"))))
    }
}
