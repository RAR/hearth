package com.rar.echodash.config

import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedingTest {
    private fun reg(s: String) = parseEntityRegistry(Json.parseToJsonElement(s))

    @Test
    fun seedsEverySlotFromLabels() {
        val cfg = seedConfig(reg(
            """[
              {"entity_id":"sensor.temp","labels":["echo-temp"]},
              {"entity_id":"weather.home","labels":["echo-weather"]},
              {"entity_id":"climate.hall","labels":["echo-climate"]},
              {"entity_id":"sensor.notclimate","labels":["echo-climate"]},
              {"entity_id":"sensor.pv","labels":["echo-solar-pv"]},
              {"entity_id":"sensor.load","labels":["echo-solar-load"]},
              {"entity_id":"sensor.grid","labels":["echo-solar-grid"]},
              {"entity_id":"sensor.pvtoday","labels":["echo-solar-pv-today"]},
              {"entity_id":"sensor.loadtoday","labels":["echo-solar-load-today"]}
            ]"""
        ))
        assertEquals("sensor.temp", cfg.entities.tempSensor)
        assertEquals("weather.home", cfg.entities.weather)
        assertEquals(listOf("climate.hall"), cfg.entities.climate) // only climate.* kept
        assertEquals("sensor.pv", cfg.entities.solar.pv)
        assertEquals("sensor.load", cfg.entities.solar.load)
        assertEquals("sensor.grid", cfg.entities.solar.grid)
        assertEquals("sensor.pvtoday", cfg.entities.solar.pvToday)
        assertEquals("sensor.loadtoday", cfg.entities.solar.loadToday)
    }

    @Test
    fun seedsLightGroupsBareFirstThenTitleCasedAlphabetical() {
        val cfg = seedConfig(reg(
            """[
              {"entity_id":"light.k","labels":["echo-lights"]},
              {"entity_id":"switch.lamp","labels":["echo-lights"]},
              {"entity_id":"light.tv","labels":["echo-lights-living-room"]},
              {"entity_id":"light.bed","labels":["echo-lights-bedroom"]}
            ]"""
        ))
        assertEquals(
            listOf("Lights", "Bedroom", "Living Room"),
            cfg.entities.lightGroups.map { it.name },
        )
        assertEquals(listOf("light.k", "switch.lamp"), cfg.entities.lightGroups[0].entities)
        assertEquals(listOf("light.bed"), cfg.entities.lightGroups[1].entities)
        assertEquals(listOf("light.tv"), cfg.entities.lightGroups[2].entities)
    }

    @Test
    fun emptyRegistryYieldsDefaults() {
        val cfg = seedConfig(reg("""[]"""))
        assertEquals(DashConfig(), cfg)
    }
}
