package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClimateModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun derivesThermostatFromAttributesWithConfiguredStep() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[{"entity_id":"climate.hall","labels":[],"original_name":"Hall"}]"""
        ))
        val entities = mapOf("climate.hall" to EntityState("climate.hall", "heat",
            attrs("""{"current_temperature":19.5,"temperature":21.0,"min_temp":7.0,"max_temp":30.0,
                      "hvac_action":"heating","hvac_modes":["off","heat","cool"]}"""), 0L))
        // a non-climate id in the list is ignored
        val t = thermostats(listOf("climate.hall", "sensor.notclimate"), reg, entities, step = 1.0).single()
        assertEquals("Hall", t.name)
        assertEquals(19.5, t.current!!, 0.001)
        assertEquals(21.0, t.target!!, 0.001)
        assertEquals(7.0, t.minTemp, 0.001)
        assertEquals(30.0, t.maxTemp, 0.001)
        assertEquals(1.0, t.step, 0.001)          // from config
        assertEquals("heating", t.hvacAction)
        assertEquals(listOf("off", "heat", "cool"), t.hvacModes)
        assertEquals("heat", t.mode)
        assertEquals(true, t.available)
    }

    @Test
    fun debouncerAccumulatesTapsIntoOneClampedCommit() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 20.0, min = 7.0, max = 22.0)
        repeat(5) { d.nudge(+1) }
        assertEquals(22.0, d.displayTarget(), 0.001)
        assertEquals(0, commits.size)
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(22.0), commits)
        d.cancel()
    }

    @Test
    fun debouncerHonorsConfiguredStep() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 20.0, min = 7.0, max = 30.0, step = 1.0)
        d.nudge(+1); d.nudge(+1)                  // 20 -> 21 -> 22 with step 1.0
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(22.0), commits)
        d.cancel()
    }
}
