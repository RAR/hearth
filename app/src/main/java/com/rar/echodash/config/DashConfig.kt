package com.rar.echodash.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ClockFormat { AUTO, H12, H24 }

@Serializable
data class PanelConfig(val enabled: Boolean = true, val order: Int = 0)

@Serializable
data class Panels(
    val lights: PanelConfig = PanelConfig(true, 1),
    val climate: PanelConfig = PanelConfig(true, 2),
    val media: PanelConfig = PanelConfig(true, 3),
    val weather: PanelConfig = PanelConfig(true, 4),
    val solar: PanelConfig = PanelConfig(true, 5),
    val cameras: PanelConfig = PanelConfig(false, 6),
)

@Serializable
data class SolarConfig(
    val pv: String? = null,
    val load: String? = null,
    val grid: String? = null,
    val pvToday: String? = null,
    val loadToday: String? = null,
) {
    fun ids(): List<String> = listOfNotNull(pv, load, grid, pvToday, loadToday)
}

@Serializable
data class LightGroup(val name: String, val entities: List<String> = emptyList())

/** A configured camera. Valid with an [rtspUrl] alone (raw go2rtc stream HA doesn't know) or an
 * [entity] alone (HLS-via-HA). [name] is the display name and the key doorbells reference. */
@Serializable
data class CameraConfig(
    val name: String = "",
    val entity: String? = null,
    val rtspUrl: String? = null,
)

/** A doorbell: [trigger] (binary_sensor.* or event.*) whose press shows the [camera] (a CameraConfig.name). */
@Serializable
data class DoorbellConfig(
    val trigger: String? = null,
    val camera: String = "",
)

@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val aqiSensor: String? = null,
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
    val cameras: List<CameraConfig> = emptyList(),
    val doorbells: List<DoorbellConfig> = emptyList(),
)

@Serializable
data class HomeSettings(
    val idleReturnSeconds: Int = 60,
    val clockFormat: ClockFormat = ClockFormat.AUTO,
    val slideshowEnabled: Boolean = true,
    val photoFolder: String = "echo-frame",
    val photoCacheCap: Int = 50,
    val slideshowSeconds: Int = 300,
)

@Serializable
data class PanelOptions(
    val thermostatStep: Double = 0.5,
    val forecastDays: Int = 5,
    val sensorDecimals: Int = 1,
    val doorbellPopupSeconds: Int = 30,
)

@Serializable
data class VoiceSettings(val enabled: Boolean = false)

/** The whole device configuration; one versioned document persisted at filesDir/config.json. */
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val entities: Entities = Entities(),
    val home: HomeSettings = HomeSettings(),
    val panelOptions: PanelOptions = PanelOptions(),
    val voice: VoiceSettings = VoiceSettings(),
) {
    /** Every entity id referenced anywhere, first-seen order, de-duplicated (EntityHub watched set). */
    fun referencedEntityIds(): List<String> = buildList {
        entities.tempSensor?.let { add(it) }
        entities.weather?.let { add(it) }
        entities.aqiSensor?.let { add(it) }
        addAll(entities.climate)
        addAll(entities.solar.ids())
        entities.lightGroups.forEach { addAll(it.entities) }
        entities.cameras.forEach { c -> c.entity?.let { add(it) } }
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
    }.distinct()

    /**
     * Coerce out-of-range numbers into their sane bounds and drop blank entity-id slots
     * left behind by the web config's free-text pickers (validation on save).
     */
    fun clamped(): DashConfig {
        val cleanedCameras = entities.cameras
            .map { c ->
                c.copy(
                    name = c.name.trim(),
                    entity = c.entity?.trim()?.ifBlank { null },
                    rtspUrl = c.rtspUrl?.trim()?.ifBlank { null },
                )
            }
            .filter { it.name.isNotBlank() && (it.entity != null || it.rtspUrl != null) }
        val cameraNames = cleanedCameras.map { it.name }.toSet()
        val cleanedDoorbells = entities.doorbells
            .map { it.copy(trigger = it.trigger?.trim()?.ifBlank { null }, camera = it.camera.trim()) }
            .filter { it.trigger != null && it.camera in cameraNames }
        return copy(
            version = 1,
            entities = entities.copy(
                tempSensor = entities.tempSensor?.trim()?.ifBlank { null },
                weather = entities.weather?.trim()?.ifBlank { null },
                aqiSensor = entities.aqiSensor?.trim()?.ifBlank { null },
                climate = entities.climate.filter { it.isNotBlank() },
                solar = entities.solar.copy(
                    pv = entities.solar.pv?.trim()?.ifBlank { null },
                    load = entities.solar.load?.trim()?.ifBlank { null },
                    grid = entities.solar.grid?.trim()?.ifBlank { null },
                    pvToday = entities.solar.pvToday?.trim()?.ifBlank { null },
                    loadToday = entities.solar.loadToday?.trim()?.ifBlank { null },
                ),
                lightGroups = entities.lightGroups
                    .map { it.copy(entities = it.entities.filter { id -> id.isNotBlank() }) }
                    .filter { it.entities.isNotEmpty() || it.name.isNotBlank() },
                cameras = cleanedCameras,
                doorbells = cleanedDoorbells,
            ),
            home = home.copy(
                idleReturnSeconds = home.idleReturnSeconds.coerceIn(15, 3600),
                photoCacheCap = home.photoCacheCap.coerceIn(5, 500),
                slideshowSeconds = home.slideshowSeconds.coerceIn(10, 3600),
            ),
            panelOptions = panelOptions.copy(
                thermostatStep = panelOptions.thermostatStep.coerceIn(0.1, 5.0),
                forecastDays = panelOptions.forecastDays.coerceIn(1, 5),
                sensorDecimals = panelOptions.sensorDecimals.coerceIn(0, 3),
                doorbellPopupSeconds = panelOptions.doorbellPopupSeconds.coerceIn(5, 120),
            ),
        )
    }
}

/** Shared JSON: tolerate unknown keys, always emit defaults so the stored document is complete. */
object ConfigJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/** Decode a config document; throws [kotlinx.serialization.SerializationException] on malformed input. */
fun decodeConfig(text: String): DashConfig = ConfigJson.json.decodeFromString(DashConfig.serializer(), text)
