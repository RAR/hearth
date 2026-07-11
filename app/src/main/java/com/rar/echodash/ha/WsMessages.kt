package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WsIncoming {
    data object AuthRequired : WsIncoming
    data object AuthOk : WsIncoming
    data class AuthInvalid(val message: String) : WsIncoming
    data class EntityUpdate(val states: Map<String, EntityPatch>) : WsIncoming
    data class Result(val id: Int, val success: Boolean, val result: JsonElement?) : WsIncoming
    data class Unknown(val type: String) : WsIncoming
}

/** Partial entity state from a subscribe_entities event; null field = unchanged. */
data class EntityPatch(val state: String?, val unit: String?, val friendlyName: String?)

/** Full entity state from get_states. */
data class EntityState(
    val entityId: String,
    val state: String,
    val unit: String?,
    val friendlyName: String?,
)

object WsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): WsIncoming {
        val obj = json.parseToJsonElement(text).jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> WsIncoming.AuthRequired
            "auth_ok" -> WsIncoming.AuthOk
            "auth_invalid" -> WsIncoming.AuthInvalid(
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "auth failed"
            )
            "event" -> parseEntityEvent(obj)
            "result" -> WsIncoming.Result(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                result = obj["result"],
            )
            else -> WsIncoming.Unknown(type ?: "?")
        }
    }

    private fun parseEntityEvent(obj: JsonObject): WsIncoming {
        val event = obj["event"]?.jsonObject ?: return WsIncoming.Unknown("event")
        val patches = mutableMapOf<String, EntityPatch>()
        event["a"]?.jsonObject?.forEach { (id, v) -> patches[id] = patchOf(v.jsonObject) }
        event["c"]?.jsonObject?.forEach { (id, v) ->
            v.jsonObject["+"]?.jsonObject?.let { patches[id] = patchOf(it) }
        }
        return WsIncoming.EntityUpdate(patches)
    }

    private fun patchOf(e: JsonObject): EntityPatch {
        val attrs = e["a"]?.jsonObject
        return EntityPatch(
            state = e["s"]?.jsonPrimitive?.contentOrNull,
            unit = attrs?.get("unit_of_measurement")?.jsonPrimitive?.contentOrNull,
            friendlyName = attrs?.get("friendly_name")?.jsonPrimitive?.contentOrNull,
        )
    }

    fun temperatureSensors(result: JsonElement): List<EntityState> =
        result.jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.startsWith("sensor.")) return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            if (attrs["device_class"]?.jsonPrimitive?.contentOrNull != "temperature") return@mapNotNull null
            EntityState(
                entityId = id,
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "?",
                unit = attrs["unit_of_measurement"]?.jsonPrimitive?.contentOrNull,
                friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull,
            )
        }
}
