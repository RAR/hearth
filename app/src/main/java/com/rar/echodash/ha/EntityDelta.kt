package com.rar.echodash.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Apply one subscribe_entities compressed event (a / c / r) to the current map, returning a new map. */
fun applyEntitiesEvent(
    current: Map<String, EntityState>,
    event: JsonObject,
    receivedAtMs: Long,
): Map<String, EntityState> {
    val next = LinkedHashMap(current)

    (event["a"] as? JsonObject)?.forEach { (id, v) ->
        next[id] = fullState(id, v.jsonObject, receivedAtMs)
    }

    (event["r"] as? JsonArray)?.forEach { el ->
        (el as? JsonPrimitive)?.contentOrNull?.let { next.remove(it) }
    }

    (event["c"] as? JsonObject)?.forEach { (id, v) ->
        val diff = v.jsonObject
        val prev = next[id]
        val plus = diff["+"] as? JsonObject
        val minus = diff["-"] as? JsonObject
        val newState = (plus?.get("s") as? JsonPrimitive)?.contentOrNull ?: prev?.state ?: "unknown"
        val attrs = LinkedHashMap<String, JsonElement>(prev?.attributes ?: JsonObject(emptyMap()))
        (plus?.get("a") as? JsonObject)?.forEach { (k, av) -> attrs[k] = av }
        (minus?.get("a") as? JsonArray)?.forEach { rem ->
            (rem as? JsonPrimitive)?.contentOrNull?.let { attrs.remove(it) }
        }
        val luMs = (plus?.get("lu") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
        next[id] = EntityState(id, newState, JsonObject(attrs), luMs ?: receivedAtMs)
    }

    return next
}

private fun fullState(id: String, o: JsonObject, receivedAtMs: Long): EntityState {
    val luMs = (o["lu"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
    return EntityState(
        entityId = id,
        state = (o["s"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
        attributes = (o["a"] as? JsonObject) ?: JsonObject(emptyMap()),
        lastUpdatedMs = luMs ?: receivedAtMs,
    )
}
