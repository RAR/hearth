package com.rar.echodash.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashConfigTest {

    @Test
    fun roundTripsThroughJson() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall"),
                solar = SolarConfig(pv = "sensor.pv"),
                lightGroups = listOf(LightGroup("Lights", listOf("light.k"))),
            ),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
    }

    @Test
    fun defaultsFillMissingFieldsAndUnknownKeysIgnored() {
        val cfg = decodeConfig("""{"version":1,"whatIsThis":true,"home":{"photoFolder":"nas"}}""")
        assertEquals(1, cfg.version)
        assertEquals("nas", cfg.home.photoFolder)
        assertEquals(60, cfg.home.idleReturnSeconds)       // default
        assertEquals(ClockFormat.AUTO, cfg.home.clockFormat) // default
        assertEquals(0.5, cfg.panelOptions.thermostatStep, 0.0)
        assertTrue(cfg.panels.lights.enabled)
    }

    @Test
    fun clampsOutOfRangeNumbers() {
        val cfg = DashConfig(
            home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 999, slideshowSeconds = 9999),
            panelOptions = PanelOptions(thermostatStep = 12.0, forecastDays = 9, sensorDecimals = 8),
        ).clamped()
        assertEquals(15, cfg.home.idleReturnSeconds)   // floor 15
        assertEquals(500, cfg.home.photoCacheCap)      // ceil 500
        assertEquals(5.0, cfg.panelOptions.thermostatStep, 0.0) // ceil 5.0
        assertEquals(5, cfg.panelOptions.forecastDays)  // ceil 5
        assertEquals(3, cfg.panelOptions.sensorDecimals) // ceil 3
        assertEquals(3600, cfg.home.slideshowSeconds)   // ceil 3600

        val low = DashConfig(
            home = HomeSettings(idleReturnSeconds = 9000, photoCacheCap = 1, slideshowSeconds = 1),
            panelOptions = PanelOptions(thermostatStep = 0.0, forecastDays = 0, sensorDecimals = -1),
        ).clamped()
        assertEquals(3600, low.home.idleReturnSeconds)
        assertEquals(5, low.home.photoCacheCap)
        assertEquals(0.1, low.panelOptions.thermostatStep, 0.0001)
        assertEquals(1, low.panelOptions.forecastDays)
        assertEquals(0, low.panelOptions.sensorDecimals)
        assertEquals(10, low.home.slideshowSeconds)     // floor 10
    }

    @Test
    fun clampedStripsBlankClimateIds() {
        val cfg = DashConfig(
            entities = Entities(climate = listOf("climate.hall", "", "  ")),
        ).clamped()
        assertEquals(listOf("climate.hall"), cfg.entities.climate)
    }

    @Test
    fun clampedStripsBlankLightGroupEntities() {
        val cfg = DashConfig(
            entities = Entities(
                lightGroups = listOf(LightGroup("Kitchen", listOf("light.k", "", " "))),
            ),
        ).clamped()
        assertEquals(listOf(LightGroup("Kitchen", listOf("light.k"))), cfg.entities.lightGroups)
    }

    @Test
    fun clampedDropsUnnamedLightGroupsThatBecomeEmpty() {
        val cfg = DashConfig(
            entities = Entities(
                lightGroups = listOf(
                    LightGroup("", listOf("", " ")), // blank name, all entities blank -> dropped
                    LightGroup("Kept", listOf("")),   // named, entities become empty -> kept
                    LightGroup("", emptyList()),      // blank name, already empty -> dropped
                ),
            ),
        ).clamped()
        assertEquals(listOf(LightGroup("Kept", emptyList())), cfg.entities.lightGroups)
    }

    @Test
    fun clampedMapsBlankSingleSlotsToNull() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "  ",
                weather = "",
                solar = SolarConfig(pv = "sensor.pv", load = "", grid = "  ", pvToday = null, loadToday = "x"),
            ),
        ).clamped()
        assertEquals(null, cfg.entities.tempSensor)
        assertEquals(null, cfg.entities.weather)
        assertEquals("sensor.pv", cfg.entities.solar.pv)
        assertEquals(null, cfg.entities.solar.load)
        assertEquals(null, cfg.entities.solar.grid)
        assertEquals(null, cfg.entities.solar.pvToday)
        assertEquals("x", cfg.entities.solar.loadToday)
    }

    @Test
    fun referencedEntityIdsCollectsEverySlotDistinct() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall", "climate.hall"),
                solar = SolarConfig(pv = "sensor.pv", grid = "sensor.grid"),
                lightGroups = listOf(
                    LightGroup("A", listOf("light.k", "sensor.t")), // sensor.t dup with tempSensor
                    LightGroup("B", listOf("light.l")),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.t", "weather.home", "climate.hall", "sensor.pv", "sensor.grid", "light.k", "light.l"),
            cfg.referencedEntityIds(),
        )
    }
}
