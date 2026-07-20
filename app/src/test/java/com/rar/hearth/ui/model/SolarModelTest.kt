package com.rar.hearth.ui.model

import com.rar.hearth.config.SolarArrayConfig
import com.rar.hearth.config.SolarConfig
import com.rar.hearth.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String) =
        EntityState(id, state, attrs("""{"unit_of_measurement":"$unit"}"""), 0L)

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
    fun solarCardGridDeadbandIsBalanced() {
        val cfg = SolarConfig(grid = "sensor.grid")
        fun importing(state: String, unit: String) = solarCard(cfg,
            mapOf("sensor.grid" to st("sensor.grid", state, unit)))!!.gridImporting
        assertNull(importing("0", "W"))
        assertNull(importing("-30", "W"))      // inside deadband either direction
        assertNull(importing("0.05", "kW"))    // kW unit-aware: 50 W is inside
        assertEquals(true, importing("51", "W"))
        assertEquals(false, importing("-0.2", "kW"))
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

    @Test
    fun sunnySurplusDerivationAndNodeTexts() {
        // pv 5 kW, charge 1 kW (batt -1000), export 2 kW (grid -2000), load 2 kW.
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battPower = "sensor.batt", pvToday = "sensor.pvday", loadToday = "sensor.loadday")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "5000", "W"),
            "sensor.load" to st("sensor.load", "2000", "W"),
            "sensor.grid" to st("sensor.grid", "-2000", "W"),
            "sensor.batt" to st("sensor.batt", "-1000", "W"),
            "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
        ))!!
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 2000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
            ),
            g.edges,
        )
        assertEquals("5.0 kW", g.solarText)
        assertEquals("2.0 kW", g.homeText) // formatWatts rolls W magnitudes >= 1000 up to kW
        assertEquals("2.0 kW", g.gridText)
        assertEquals(BattFlow.CHARGING, g.battFlow)
        assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", g.todayLine)
    }

    @Test
    fun nightDischargeDerivation() {
        // pv 0, discharge 800 (batt +800), import 300 (grid +300).
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battPower = "sensor.batt")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "0", "W"),
            "sensor.load" to st("sensor.load", "1100", "W"),
            "sensor.grid" to st("sensor.grid", "300", "W"),
            "sensor.batt" to st("sensor.batt", "800", "W"),
        ))!!
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.BATTERY, FlowNodeId.HOME, 800.0),
                FlowEdge(FlowNodeId.GRID, FlowNodeId.HOME, 300.0),
            ),
            g.edges,
        )
        assertEquals(BattFlow.DISCHARGING, g.battFlow)
    }

    @Test
    fun gridAssistedChargeDerivation() {
        // pv 200, charge 1500 (batt -1500), import 1400 (grid +1400).
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battPower = "sensor.batt")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "200", "W"),
            "sensor.load" to st("sensor.load", "100", "W"),
            "sensor.grid" to st("sensor.grid", "1400", "W"),
            "sensor.batt" to st("sensor.batt", "-1500", "W"),
        ))!!
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 200.0),
                FlowEdge(FlowNodeId.GRID, FlowNodeId.BATTERY, 1300.0),
                FlowEdge(FlowNodeId.GRID, FlowNodeId.HOME, 100.0),
            ),
            g.edges,
        )
    }

    @Test
    fun deadbandEdgeThreshold() {
        // pv-only, no batt/grid: S→H = pvW. 49 W absent, 51 W present.
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load")
        val at49 = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "49", "W"),
            "sensor.load" to st("sensor.load", "49", "W"),
        ))!!
        assertEquals(emptyList<FlowEdge>(), at49.edges)
        val at51 = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "51", "W"),
            "sensor.load" to st("sensor.load", "51", "W"),
        ))!!
        assertEquals(listOf(FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 51.0)), at51.edges)
    }

    @Test
    fun noBatterySensorsHaveNoBatteryNodeOrEdges() {
        // pv 3 kW, export 1 kW (grid -1000), load 2 kW; no batt sensors.
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "3000", "W"),
            "sensor.load" to st("sensor.load", "2000", "W"),
            "sensor.grid" to st("sensor.grid", "-1000", "W"),
        ))!!
        assertNull(g.socPct)
        assertNull(g.battText)
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 1000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
            ),
            g.edges,
        )
        assertTrue(g.edges.none { it.from == FlowNodeId.BATTERY || it.to == FlowNodeId.BATTERY })
    }

    @Test
    fun noGridSensorHasNoGridNodeOrEdges() {
        // pv 3 kW, charge 1 kW (batt -1000); no grid sensor.
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", battPower = "sensor.batt")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "3000", "W"),
            "sensor.load" to st("sensor.load", "2000", "W"),
            "sensor.batt" to st("sensor.batt", "-1000", "W"),
        ))!!
        assertNull(g.gridText)
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
            ),
            g.edges,
        )
        assertTrue(g.edges.none { it.from == FlowNodeId.GRID || it.to == FlowNodeId.GRID })
    }

    @Test
    fun kwUnitSensorsScaleToWatts() {
        // Same topology as sunnySurplus but every sensor in kW (evcc style).
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battPower = "sensor.batt")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "5", "kW"),
            "sensor.load" to st("sensor.load", "2", "kW"),
            "sensor.grid" to st("sensor.grid", "-2", "kW"),
            "sensor.batt" to st("sensor.batt", "-1", "kW"),
        ))!!
        assertEquals(
            listOf(
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.BATTERY, 1000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.GRID, 2000.0),
                FlowEdge(FlowNodeId.SOLAR, FlowNodeId.HOME, 2000.0),
            ),
            g.edges,
        )
    }

    @Test
    fun negativePvClampsToZero() {
        // pv -100 (inverter standby), load only: no edges (pvW = 0), graph still non-null.
        val g = solarFlowGraph(SolarConfig(pv = "sensor.pv", load = "sensor.load"), mapOf(
            "sensor.pv" to st("sensor.pv", "-100", "W"),
            "sensor.load" to st("sensor.load", "800", "W"),
        ))!!
        assertEquals(emptyList<FlowEdge>(), g.edges)
        assertEquals("100 W", g.solarText) // formatWatts is magnitude-only
    }

    @Test
    fun gridTodayLineVariants() {
        val cfg = { imp: String?, exp: String? -> SolarConfig(pv = "sensor.pv",
            gridImportToday = imp?.let { "sensor.gi" }, gridExportToday = exp?.let { "sensor.ge" }) }
        fun line(imp: String?, exp: String?): String? {
            val e = buildMap {
                put("sensor.pv", st("sensor.pv", "0", "W"))
                imp?.let { put("sensor.gi", st("sensor.gi", it, "kWh")) }
                exp?.let { put("sensor.ge", st("sensor.ge", it, "kWh")) }
            }
            return solarFlowGraph(cfg(imp, exp), e)!!.gridTodayLine
        }
        assertEquals("↓ 3.2 kWh · ↑ 2.2 kWh", line("3.2", "2.2"))
        assertEquals("↓ 3.2 kWh", line("3.2", null))
        assertEquals("↑ 2.2 kWh", line(null, "2.2"))
        assertNull(line(null, null))
    }

    @Test
    fun battTodayLineVariants() {
        val cfg = { i: String?, o: String? -> SolarConfig(pv = "sensor.pv",
            battInToday = i?.let { "sensor.bi" }, battOutToday = o?.let { "sensor.bo" }) }
        fun line(i: String?, o: String?): String? {
            val e = buildMap {
                put("sensor.pv", st("sensor.pv", "0", "W"))
                i?.let { put("sensor.bi", st("sensor.bi", it, "kWh")) }
                o?.let { put("sensor.bo", st("sensor.bo", it, "kWh")) }
            }
            return solarFlowGraph(cfg(i, o), e)!!.battTodayLine
        }
        assertEquals("↓ 5.1 kWh · ↑ 4.2 kWh", line("5.1", "4.2")) // ↓ charged, ↑ discharged
        assertEquals("↓ 5.1 kWh", line("5.1", null))
        assertEquals("↑ 4.2 kWh", line(null, "4.2"))
        assertNull(line(null, null))
    }

    @Test
    fun arraysLineFormatsFallbackAndNull() {
        val cfg = SolarConfig(pv = "sensor.pv", arrays = listOf(
            SolarArrayConfig(power = "sensor.a"),
            SolarArrayConfig(power = "sensor.b"),
            SolarArrayConfig(power = "sensor.c"),
            SolarArrayConfig(power = "sensor.d"),
        ))
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "0", "W"),
            "sensor.a" to st("sensor.a", "447", "watts"),
            "sensor.b" to st("sensor.b", "768", "watts"),
            "sensor.c" to st("sensor.c", "395", "watts"),
            "sensor.d" to st("sensor.d", "276", "watts"),
        ))!!
        assertEquals("A 447 W · B 768 W · C 395 W · D 276 W", g.arraysLine)
        // No array sensors -> null.
        assertNull(solarFlowGraph(SolarConfig(pv = "sensor.pv"),
            mapOf("sensor.pv" to st("sensor.pv", "0", "W")))!!.arraysLine)
    }

    @Test
    fun gridToBatteryCapsAtImport() {
        // Disagreeing sensors: charge 5 kW but import only 100 W — the grid edge shows what the
        // grid actually delivers, not the uncovered charge (seen live 2026-07-17).
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            battPower = "sensor.batt")
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "0", "W"),
            "sensor.load" to st("sensor.load", "500", "W"),
            "sensor.grid" to st("sensor.grid", "100", "W"),
            "sensor.batt" to st("sensor.batt", "-5000", "W"),
        ))!!
        assertEquals(listOf(FlowEdge(FlowNodeId.GRID, FlowNodeId.BATTERY, 100.0)), g.edges)
    }

    @Test
    fun nonNumericStatesAreSkippedInDailyLines() {
        // A fresh utility_meter reports "unknown" until its source first ticks — render the
        // numeric side alone, never the literal "unknown kWh".
        val cfg = SolarConfig(pv = "sensor.pv", pvToday = "sensor.pvday", loadToday = "sensor.loadday",
            gridImportToday = "sensor.gi", gridExportToday = "sensor.ge",
            arrays = listOf(SolarArrayConfig(power = "sensor.a"), SolarArrayConfig(power = "sensor.b")))
        val g = solarFlowGraph(cfg, mapOf(
            "sensor.pv" to st("sensor.pv", "0", "W"),
            "sensor.pvday" to st("sensor.pvday", "5.8", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "unknown", "kWh"),
            "sensor.gi" to st("sensor.gi", "unknown", "kWh"),
            "sensor.ge" to st("sensor.ge", "unavailable", "kWh"),
            "sensor.a" to st("sensor.a", "447", "watts"),
            "sensor.b" to st("sensor.b", "unknown", "watts"),
        ))!!
        assertEquals("Today: 5.8 kWh produced", g.todayLine)
        assertNull(g.gridTodayLine)
        assertEquals("A 447 W", g.arraysLine)
    }

    @Test
    fun flowLapMsCurveAndClamps() {
        assertEquals(4000, flowLapMs(50.0))
        assertEquals(2600, flowLapMs(2025.0))
        assertEquals(1200, flowLapMs(4000.0))
        assertEquals(4000, flowLapMs(10.0))   // below range clamps up
        assertEquals(1200, flowLapMs(9000.0)) // above range clamps down
    }

    @Test
    fun nullWhenNothingResolvesAndNonNullWithPvAlone() {
        assertNull(solarFlowGraph(SolarConfig(), emptyMap()))
        // battPower alone never conjures a diagram (same rule as solarCard()).
        assertNull(solarFlowGraph(SolarConfig(battPower = "sensor.batt"),
            mapOf("sensor.batt" to st("sensor.batt", "-500", "W"))))
        val g = solarFlowGraph(SolarConfig(pv = "sensor.pv"),
            mapOf("sensor.pv" to st("sensor.pv", "1200", "W")))!!
        assertEquals("1.2 kW", g.solarText)
        assertNull(g.gridText)
    }
}
