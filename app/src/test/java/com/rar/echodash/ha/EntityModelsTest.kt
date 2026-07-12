package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityModelsTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun keepsOnlyEchoLabelsLowercasedAndGroupsEntities() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.kitchen","labels":["Echo-Lights","other"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"light.lamp","labels":["echo-lights-living-room"],"name":"Reading Lamp","original_name":"Lamp"},
              {"entity_id":"sensor.pv","labels":["echo-solar-pv"],"name":null,"original_name":null},
              {"entity_id":"light.no_labels","labels":[],"name":null,"original_name":"Nope"}
            ]"""
        ))
        assertEquals(listOf("light.kitchen"), reg.labelToEntities["echo-lights"])
        assertEquals(listOf("light.lamp"), reg.labelToEntities["echo-lights-living-room"])
        assertEquals(listOf("sensor.pv"), reg.labelToEntities["echo-solar-pv"])
        assertEquals(null, reg.labelToEntities["other"])
        assertEquals(listOf("light.kitchen", "light.lamp", "sensor.pv"), reg.allEntityIds)
    }

    @Test
    fun normalizesUnderscoreSlugifiedLabelIdsToHyphenated() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"climate.hall","labels":["echo_climate"],"name":null,"original_name":"Hall"},
              {"entity_id":"light.lamp","labels":["echo_lights_living_room"],"name":null,"original_name":"Lamp"}
            ]"""
        ))
        assertEquals(listOf("climate.hall"), reg.labelToEntities["echo-climate"])
        assertEquals(listOf("light.lamp"), reg.labelToEntities["echo-lights-living-room"])
    }

    @Test
    fun displayNamePrefersRegistryNameThenFriendlyThenId() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.lamp","labels":["echo-lights"],"name":"Reading Lamp","original_name":"Lamp"},
              {"entity_id":"light.kitchen","labels":["echo-lights"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"light.plain","labels":["echo-lights"],"name":null,"original_name":null}
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
              {"entity_id":"light.kitchen","labels":["echo-lights"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"switch.fan","labels":[],"name":"Desk Fan","original_name":"Fan"},
              {"entity_id":"climate.hall","labels":[],"name":null,"original_name":null}
            ]"""
        ))
        // allEntities includes entities with NO echo labels (the picker needs the full list)
        assertEquals(listOf("light.kitchen", "switch.fan", "climate.hall"), reg.allEntities.map { it.id })
        assertEquals(listOf("light", "switch", "climate"), reg.allEntities.map { it.domain })
        assertEquals("Desk Fan", reg.allEntities[1].name)
        // registryNames now covers unlabeled entities too
        assertEquals("Desk Fan", reg.registryNames["switch.fan"])
        // labelToEntities stays echo-only (seeding contract unchanged)
        assertEquals(listOf("light.kitchen"), reg.labelToEntities["echo-lights"])
        assertEquals(null, reg.labelToEntities["other"])
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
