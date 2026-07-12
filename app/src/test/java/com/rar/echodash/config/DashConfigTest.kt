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
            home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 999),
            panelOptions = PanelOptions(thermostatStep = 12.0, forecastDays = 9),
        ).clamped()
        assertEquals(15, cfg.home.idleReturnSeconds)   // floor 15
        assertEquals(500, cfg.home.photoCacheCap)      // ceil 500
        assertEquals(5.0, cfg.panelOptions.thermostatStep, 0.0) // ceil 5.0
        assertEquals(5, cfg.panelOptions.forecastDays)  // ceil 5

        val low = DashConfig(
            home = HomeSettings(idleReturnSeconds = 9000, photoCacheCap = 1),
            panelOptions = PanelOptions(thermostatStep = 0.0, forecastDays = 0),
        ).clamped()
        assertEquals(3600, low.home.idleReturnSeconds)
        assertEquals(5, low.home.photoCacheCap)
        assertEquals(0.1, low.panelOptions.thermostatStep, 0.0001)
        assertEquals(1, low.panelOptions.forecastDays)
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
