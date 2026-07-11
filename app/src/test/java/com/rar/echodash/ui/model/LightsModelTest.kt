package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightsModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, a: String = "{}") =
        EntityState(id, state, attrs(a), 0L)

    @Test
    fun ungroupedFirstThenAlphabeticalGroupsWithTitleCasing() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[
              {"entity_id":"light.hall","labels":["echo-lights"],"original_name":"Hall"},
              {"entity_id":"light.sofa","labels":["echo-lights-living-room"],"original_name":"Sofa"},
              {"entity_id":"switch.fan","labels":["echo-lights-bedroom"],"original_name":"Fan"}
            ]"""
        ))
        val entities = mapOf(
            "light.hall" to st("light.hall", "on"),
            "light.sofa" to st("light.sofa", "off"),
            "switch.fan" to st("switch.fan", "unavailable"),
        )
        val groups = buildLightGroups(reg, entities)
        assertEquals(listOf(null, "Bedroom", "Living Room"), groups.map { it.title })
        assertEquals("Hall", groups[0].tiles[0].name)
        assertEquals(true, groups[0].tiles[0].on)
        assertEquals(false, groups[2].tiles[0].on)          // Living Room / Sofa off
        assertEquals(false, groups[1].tiles[0].available)   // Bedroom / Fan unavailable
        assertEquals("switch", groups[1].tiles[0].domain)
    }

    @Test
    fun entityInMultipleLabelsAppearsInEachGroup() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[{"entity_id":"light.lamp","labels":["echo-lights-a","echo-lights-b"],"original_name":"Lamp"}]"""
        ))
        val groups = buildLightGroups(reg, mapOf("light.lamp" to st("light.lamp", "on")))
        assertEquals(listOf("A", "B"), groups.map { it.title })
        assertEquals("Lamp", groups[0].tiles[0].name)
        assertEquals("Lamp", groups[1].tiles[0].name)
        assertNull(groups.firstOrNull { it.title == null })
    }
}
