package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityDeltaTest {
    private fun event(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun addSnapshotUsesLuWhenPresent() {
        val out = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"light.kitchen":{"s":"on","a":{"friendly_name":"Kitchen"},"lu":1720000000.5}}}"""
        ), receivedAtMs = 999L)
        val e = out.getValue("light.kitchen")
        assertEquals("on", e.state)
        assertEquals("Kitchen", e.attr("friendly_name"))
        assertEquals(1720000000500L, e.lastUpdatedMs)
    }

    @Test
    fun changeMergesAttrsAndRemovesListedKeys() {
        val base = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"climate.hall":{"s":"heat","a":{"current_temperature":19.0,"preset":"eco"},"lu":1.0}}}"""
        ), 0L)
        val out = applyEntitiesEvent(base, event(
            """{"c":{"climate.hall":{"+":{"s":"cool","a":{"current_temperature":21.0}},"-":{"a":["preset"]}}}}"""
        ), receivedAtMs = 500L)
        val e = out.getValue("climate.hall")
        assertEquals("cool", e.state)
        assertEquals(21.0, e.attrDouble("current_temperature")!!, 0.001)
        assertNull(e.attr("preset"))
        assertEquals(500L, e.lastUpdatedMs) // no lu in delta -> receivedAtMs
    }

    @Test
    fun removalDropsEntity() {
        val base = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"light.a":{"s":"on","a":{}},"light.b":{"s":"off","a":{}}}}"""
        ), 0L)
        val out = applyEntitiesEvent(base, event("""{"r":["light.a"]}"""), 0L)
        assertNull(out["light.a"])
        assertEquals("off", out.getValue("light.b").state)
    }
}
