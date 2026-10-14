package com.rar.hearth.ha

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WsIncoming {
    data object AuthRequired : WsIncoming
    data object AuthOk : WsIncoming
    data class AuthInvalid(val message: String) : WsIncoming
    /** A subscription event; [id] matches the subscribe command id, [event] is its inner "event" object. */
    data class Event(val id: Int, val event: JsonObject) : WsIncoming
    data class Result(val id: Int, val success: Boolean, val result: JsonElement?) : WsIncoming
    data class Unknown(val type: String) : WsIncoming
}

object WsParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Just the routing fields; decoding into this skips the body without building a tree. */
    @Serializable
    private data class FrameHead(val id: Int = -1, val type: String = "")

    /**
     * The command id if [text] is a `result` frame, else null -- read without materializing the
     * frame as a JsonElement, so a multi-MB result can be routed raw (see HaClient.requestRaw).
     */
    fun peekResultId(text: String): Int? =
        runCatching { json.decodeFromString<FrameHead>(text) }.getOrNull()
            ?.takeIf { it.type == "result" }?.id

    fun parse(text: String): WsIncoming {
        val obj = json.parseToJsonElement(text).jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> WsIncoming.AuthRequired
            "auth_ok" -> WsIncoming.AuthOk
            "auth_invalid" -> WsIncoming.AuthInvalid(
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "auth failed"
            )
            "event" -> WsIncoming.Event(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                event = obj["event"]?.jsonObject ?: JsonObject(emptyMap()),
            )
            "result" -> WsIncoming.Result(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                result = obj["result"],
            )
            else -> WsIncoming.Unknown(type ?: "?")
        }
    }
}
