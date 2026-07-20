package com.rar.hearth.web

import com.rar.hearth.ha.EntityState
import com.rar.hearth.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityListTest {
    @Test
    fun rendersIdNameDomainAndStateForEveryEntity() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[
              {"entity_id":"light.kitchen","labels":[],"name":"Kitchen","original_name":"Kitchen"},
              {"entity_id":"climate.hall","labels":[],"name":null,"original_name":null}
            ]"""
        ))
        val states = mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on",
                Json.parseToJsonElement("{}") as JsonObject, 0L),
        )
        val arr = Json.parseToJsonElement(buildEntityListJson(reg, states)) as JsonArray
        assertEquals(2, arr.size)
        val kitchen = arr[0].jsonObject
        assertEquals("light.kitchen", kitchen["id"]!!.jsonPrimitive.content)
        assertEquals("Kitchen", kitchen["name"]!!.jsonPrimitive.content)
        assertEquals("light", kitchen["domain"]!!.jsonPrimitive.content)
        assertEquals("on", kitchen["state"]!!.jsonPrimitive.content)
        // climate.hall has no live state and no registry name -> falls back to id + "unavailable"
        val hall = arr[1].jsonObject
        assertEquals("climate.hall", hall["name"]!!.jsonPrimitive.content)
        assertEquals("unavailable", hall["state"]!!.jsonPrimitive.content)
    }
}
