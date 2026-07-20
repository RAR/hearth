package com.rar.hearth.vaca

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface VacaIncoming {
    data object Describe : VacaIncoming
    data object CapabilitiesRequest : VacaIncoming
    data class Ping(val text: String?) : VacaIncoming
    data object RunSatellite : VacaIncoming
    data class SettingsChanged(val settings: JsonObject) : VacaIncoming
    data class Action(val action: String, val payload: JsonElement?) : VacaIncoming
    data class AudioStart(val rate: Int, val width: Int, val channels: Int) : VacaIncoming
    data class AudioChunk(val pcm: ByteArray) : VacaIncoming {
        override fun equals(other: Any?) = other is AudioChunk && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }
    data object AudioStop : VacaIncoming
    data class Unknown(val type: String) : VacaIncoming
}

object VacaParser {
    fun parse(event: WyomingEvent): VacaIncoming = when (event.type) {
        "describe" -> VacaIncoming.Describe
        "capabilities" -> VacaIncoming.CapabilitiesRequest
        "ping" -> VacaIncoming.Ping((event.data["text"] as? JsonPrimitive)?.contentOrNull)
        "run-satellite" -> VacaIncoming.RunSatellite
        "audio-start" -> VacaIncoming.AudioStart(
            rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
            width = event.data["width"]?.jsonPrimitive?.int ?: 2,
            channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
        )
        "audio-chunk" -> VacaIncoming.AudioChunk(event.payload)
        "audio-stop" -> VacaIncoming.AudioStop
        "custom-event" -> parseCustom(event.data)
        else -> VacaIncoming.Unknown(event.type)
    }

    private fun parseCustom(data: JsonObject): VacaIncoming {
        val eventType = (data["event_type"] as? JsonPrimitive)?.contentOrNull
        return when (eventType) {
            // HA flattens custom event data: keys sit beside event_type
            "settings" -> VacaIncoming.SettingsChanged(
                data["settings"] as? JsonObject ?: JsonObject(emptyMap())
            )
            "action" -> VacaIncoming.Action(
                action = (data["action"] as? JsonPrimitive)?.contentOrNull ?: "",
                payload = data["payload"]?.takeIf { it !is JsonNull },
            )
            else -> VacaIncoming.Unknown("custom-event/$eventType")
        }
    }
}

object VacaOutgoing {
    fun info(appVersion: String, name: String): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
                putJsonArray(key) {}
            }
            putJsonObject("satellite") {
                put("name", name)
                putJsonObject("attribution") {
                    put("name", name)
                    put("url", "https://github.com/RAR/hearth")
                }
                put("installed", true)
                put("description", "Native Home Assistant dashboard")
                put("version", appVersion)
                put("area", JsonNull)
                put("has_vad", false)
                putJsonArray("active_wake_words") {}
                put("max_active_wake_words", 0)
                put("supports_trigger", false)
            }
        }
        return WyomingEvent("info", data)
    }

    fun capabilities(caps: JsonObject) = WyomingEvent("capabilities", caps)

    fun pong(text: String?) = WyomingEvent("pong", buildJsonObject {
        if (text != null) put("text", text) else put("text", JsonNull)
    })

    // Device->HA custom events nest their body under "data" (the integration's
    // CustomEvent.from_event reads event.data["data"]) — asymmetric with HA->device.
    fun settingsFeedback(settings: JsonObject) = WyomingEvent(
        "custom-event",
        buildJsonObject {
            put("event_type", "settings")
            putJsonObject("data") { put("settings", settings) }
        },
    )

    fun status(status: JsonObject) = WyomingEvent(
        "custom-event",
        buildJsonObject {
            put("event_type", "status")
            put("data", status)
        },
    )

    fun played() = WyomingEvent("played")

    fun buildCapabilities(
        appVersion: String,
        hasLightSensor: Boolean,
        maxMusicVolume: Int = 10,
        maxNotificationVolume: Int = 10,
    ): JsonObject = buildJsonObject {
        put("app_version", appVersion)
        put("has_battery", false)
        put("has_front_camera", false)
        put("has_dnd", false)
        putJsonArray("sensors") {
            if (hasLightSensor) addJsonObject { put("type", 5) }
        }
        putJsonObject("audio") {
            put("max_music_volume", maxMusicVolume)
            put("max_notification_volume", maxNotificationVolume)
        }
    }
}
