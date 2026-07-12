package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityModelsTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun displayNamePrefersRegistryNameThenFriendlyThenId() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.lamp","name":"Reading Lamp","original_name":"Lamp"},
              {"entity_id":"light.kitchen","name":null,"original_name":"Kitchen"},
              {"entity_id":"light.plain","name":null,"original_name":null}
            ]"""
        ))
        val friendly = EntityState("light.plain", "on",
            Json.parseToJsonElement("""{"friendly_name":"Plain Light"}""").let { it as kotlinx.serialization.json.JsonObject }, 0L)
        assertEquals("Reading Lamp", reg.displayName("light.lamp", null))
        assertEquals("Kitchen", reg.displayName("light.kitchen", null))
        assertEquals("Plain Light", reg.displayName("light.plain", friendly))
        assertEquals("light.plain", reg.displayName("light.plain", null))
    }

    @Test
    fun capturesEveryRegistryEntityForThePicker() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.kitchen","name":null,"original_name":"Kitchen"},
              {"entity_id":"switch.fan","name":"Desk Fan","original_name":"Fan"},
              {"entity_id":"climate.hall","name":null,"original_name":null}
            ]"""
        ))
        assertEquals(listOf("light.kitchen", "switch.fan", "climate.hall"), reg.allEntities.map { it.id })
        assertEquals(listOf("light", "switch", "climate"), reg.allEntities.map { it.domain })
        assertEquals("Desk Fan", reg.allEntities[1].name)
        assertEquals("Desk Fan", reg.registryNames["switch.fan"])
    }

    @Test
    fun attributeAccessors() {
        val s = EntityState("climate.hall", "heat",
            Json.parseToJsonElement(
                """{"current_temperature":19.5,"hvac_modes":["off","heat"],"friendly_name":"Hall"}"""
            ) as kotlinx.serialization.json.JsonObject, 0L)
        assertEquals("Hall", s.attr("friendly_name"))
        assertEquals(19.5, s.attrDouble("current_temperature")!!, 0.001)
        assertEquals(listOf("off", "heat"), s.attrStringList("hvac_modes"))
        assertEquals(emptyList<String>(), s.attrStringList("missing"))
    }
}
