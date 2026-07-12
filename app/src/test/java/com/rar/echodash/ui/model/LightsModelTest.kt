package com.rar.echodash.ui.model

import com.rar.echodash.config.LightGroup
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LightsModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, a: String = "{}") = EntityState(id, state, attrs(a), 0L)

    private val reg = parseEntityRegistry(Json.parseToJsonElement(
        """[
          {"entity_id":"light.hall","labels":[],"original_name":"Hall"},
          {"entity_id":"light.sofa","labels":[],"original_name":"Sofa"},
          {"entity_id":"switch.fan","labels":[],"original_name":"Fan"}
        ]"""
    ))

    @Test
    fun sectionsPreserveConfiguredOrderNamesAndTileState() {
        val groups = listOf(
            LightGroup("Lights", listOf("light.hall")),
            LightGroup("Living Room", listOf("light.sofa", "switch.fan")),
        )
        val entities = mapOf(
            "light.hall" to st("light.hall", "on"),
            "light.sofa" to st("light.sofa", "off"),
            "switch.fan" to st("switch.fan", "unavailable"),
        )
        val sections = lightSections(groups, reg, entities)
        assertEquals(listOf("Lights", "Living Room"), sections.map { it.title })
        assertEquals("Hall", sections[0].tiles[0].name)
        assertEquals(true, sections[0].tiles[0].on)
        assertEquals(false, sections[1].tiles[0].on)          // sofa off
        assertEquals(false, sections[1].tiles[1].available)   // fan unavailable
        assertEquals("switch", sections[1].tiles[1].domain)
    }

    @Test
    fun missingEntityStillProducesTileByIdUnavailable() {
        val sections = lightSections(listOf(LightGroup("G", listOf("light.ghost"))), reg, emptyMap())
        assertEquals("light.ghost", sections[0].tiles[0].name)
        assertEquals(false, sections[0].tiles[0].available)
    }
}
