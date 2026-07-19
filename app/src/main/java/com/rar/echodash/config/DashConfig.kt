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
    val calendar: PanelConfig = PanelConfig(true, 7),
)

@Serializable
data class SolarConfig(
    val pv: String? = null,
    val load: String? = null,
    val grid: String? = null,
    val pvToday: String? = null,
    val loadToday: String? = null,
    val battSoc: String? = null,   // home battery % sensor
    val battPower: String? = null, // battery power W/kW; negative = charging (evcc convention)
    val gridImportToday: String? = null, // kWh energy sensor, grid → house today
    val gridExportToday: String? = null, // kWh energy sensor, house → grid today
    val battInToday: String? = null,     // kWh energy sensor, charged into battery today
    val battOutToday: String? = null,    // kWh energy sensor, discharged from battery today
    val arrays: List<SolarArrayConfig> = emptyList(), // up to 4 per-array PV power sensors
) {
    fun ids(): List<String> = listOfNotNull(
        pv, load, grid, pvToday, loadToday, battSoc, battPower,
        gridImportToday, gridExportToday, battInToday, battOutToday,
    ) + arrays.mapNotNull { it.power }
}

/** One per-array/string PV power sensor. [name] blank falls back to "A".."D" by slot index. */
@Serializable
data class SolarArrayConfig(
    val name: String = "",
    val power: String? = null,
)

@Serializable
data class EvConfig(
    val name: String = "",
    val plugged: String? = null,  // entity whose truthy state shows the card (cable connected)
    val charging: String? = null, // entity whose truthy state shows the card + drives the animation
    val soc: String? = null,      // battery % sensor
    val limit: String? = null,    // vehicle charge-limit % sensor/number
    val power: String? = null,    // charge power sensor (W or kW, unit-aware)
    val energy: String? = null,   // session energy sensor (Wh or kWh, unit-aware)
    val eta: String? = null,      // time-to-finish sensor (minutes, H:MM:SS, or timestamp)
) {
    fun ids(): List<String> = listOfNotNull(plugged, charging, soc, limit, power, energy, eta)
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

/** A configured HA calendar. [entity] is a calendar.* id; [name] is the display name (blank ->
 *  entity-id tail, resolved in the model); [color] is one of [CalendarConfig.COLORS]. */
@Serializable
data class CalendarConfig(
    val entity: String = "",     // calendar.* entity id
    val name: String = "",       // display name; blank -> entity id tail
    val color: String = "blue",  // palette key
) {
    companion object {
        /** The eight recognized palette keys (ARGB values live in the model's calendarColorArgb). */
        val COLORS: Set<String> = setOf("blue", "green", "amber", "red", "purple", "teal", "orange", "pink")
    }
}

/** One home-screen quick button. [name] blank falls back to the entity's friendly_name (else the
 *  entity-id tail, resolved in the model). [entity] is a switch/light/input_boolean (toggles) or a
 *  button/script/scene (fires). */
@Serializable
data class QuickButtonConfig(
    val name: String = "",
    val entity: String? = null,
)

@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val aqiSensor: String? = null,
    val rainEvent: String? = null,  // event-rain total sensor; > 0 shows the home rain pill
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
    val cameras: List<CameraConfig> = emptyList(),
    val doorbells: List<DoorbellConfig> = emptyList(),
    val evs: List<EvConfig> = emptyList(),
    val calendars: List<CalendarConfig> = emptyList(),
    val quickButtons: List<QuickButtonConfig> = emptyList(),
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
data class VoiceSettings(
    val enabled: Boolean = false,
    val timerTone: String = "argon",
    val timerVolume: Int = 80,
    val wakeSoundVolume: Int = 80,
    val wakeWord: String = "okay_nabu",
    val wakeThreshold: Int = 50,
) {
    /** Normalize the timer-alarm fields: trim + unknown/blank tone falls to "argon",
     *  volumes coerced into 0..100. Wake word clamps to the bundled set (unknown -> okay_nabu);
     *  wake threshold (score * 100) coerced into 10..95. Shared by DashConfig.clamped and the
     *  preview endpoint. */
    fun clamped(): VoiceSettings = copy(
        timerTone = timerTone.trim().let { if (it in TONES) it else "argon" },
        timerVolume = timerVolume.coerceIn(0, 100),
        wakeSoundVolume = wakeSoundVolume.coerceIn(0, 100),
        wakeWord = wakeWord.trim().let { if (it in WAKE_WORDS) it else "okay_nabu" },
        wakeThreshold = wakeThreshold.coerceIn(10, 95),
    )

    companion object {
        /** The eleven recognized timer-alarm tone ids: 7 bundled system alarms + 4 synthesized. */
        val TONES: Set<String> = setOf(
            "argon", "oxygen", "krypton", "timer", "beep", "helium", "cyan",
            "twotone", "beeps", "chime", "trill",
        )

        /** The bundled on-device wake-word model ids ("ok_ember" is the user-trained Hearth word). */
        val WAKE_WORDS: Set<String> = setOf("okay_nabu", "hey_jarvis", "alexa", "ok_ember")
    }
}

@Serializable
data class MediaSettings(
    val companionEntity: String? = null,
    val pausedDismissSeconds: Int = 60,
) {
    /** Trim the companion entity id; blank -> null (unconfigured). Clamp the paused-dismiss delay. */
    fun clamped(): MediaSettings = copy(
        companionEntity = companionEntity?.trim()?.ifBlank { null },
        pausedDismissSeconds = pausedDismissSeconds.coerceIn(5, 3600),
    )
}

@Serializable
data class NightSettings(
    val enabled: Boolean = false,
    val thresholdLux: Int = 10,
    val brightness: Int = 0,      // 0 = minimum backlight (window-brightness floor 0.001, ~1/255)
) {
    fun clamped(): NightSettings = copy(
        thresholdLux = thresholdLux.coerceIn(1, 1000),
        brightness = brightness.coerceIn(0, 100),
    )
}

@Serializable
data class NotificationsConfig(
    val nwsAlerts: String? = null,          // NWS alerts sensor entity id (nws_alerts integration)
    val nwsMinSeverity: String = "minor",   // "minor" | "moderate" | "severe"
    val autoDismiss: String = "off",        // auto-dismiss rows at or below: "off" | "info" | "warning" | "critical"
    val autoDismissSeconds: Int = 300,      // how long a row stays before auto-dismissal
) {
    /** Trim the sensor id (blank -> null), clamp the min-severity to the valid set (default minor),
     *  the auto-dismiss level to its set (default off), and the auto-dismiss delay into 10..7200 s. */
    fun clamped(): NotificationsConfig = copy(
        nwsAlerts = nwsAlerts?.trim()?.ifBlank { null },
        nwsMinSeverity = nwsMinSeverity.trim().lowercase().let { if (it in MIN_SEVERITIES) it else "minor" },
        autoDismiss = autoDismiss.trim().lowercase().let { if (it in AUTO_DISMISS_LEVELS) it else "off" },
        autoDismissSeconds = autoDismissSeconds.coerceIn(10, 7200),
    )

    companion object {
        /** The three recognized minimum-severity ids. */
        val MIN_SEVERITIES: Set<String> = setOf("minor", "moderate", "severe")

        /** The recognized auto-dismiss levels ("at or below" cutoffs; "off" disables). */
        val AUTO_DISMISS_LEVELS: Set<String> = setOf("off", "info", "warning", "critical")
    }
}

@Serializable
data class SendspinConfig(
    val enabled: Boolean = false,
    val syncDelayMs: Int = 0,          // per-player fixed-latency offset for tuning
    val serverAddress: String = "",    // optional manual MA server host:port; blank = mDNS discovery
    val maToken: String = "",          // MA API access token from device-side sign-in; blank = signed out
    val maUser: String = "",           // MA display name for the config card's signed-in line
) {
    fun clamped(): SendspinConfig = copy(
        syncDelayMs = syncDelayMs.coerceIn(-2000, 2000),
        serverAddress = serverAddress.trim(),
        maToken = maToken.trim(),
        maUser = maUser.trim(),
    )
}

/** The whole device configuration; one versioned document persisted at filesDir/config.json. */
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val entities: Entities = Entities(),
    val home: HomeSettings = HomeSettings(),
    val panelOptions: PanelOptions = PanelOptions(),
    val voice: VoiceSettings = VoiceSettings(),
    val media: MediaSettings = MediaSettings(),
    val night: NightSettings = NightSettings(),
    val notifications: NotificationsConfig = NotificationsConfig(),
    val sendspin: SendspinConfig = SendspinConfig(),
) {
    /** Every entity id referenced anywhere, first-seen order, de-duplicated (EntityHub watched set). */
    fun referencedEntityIds(): List<String> = buildList {
        entities.tempSensor?.let { add(it) }
        entities.weather?.let { add(it) }
        entities.aqiSensor?.let { add(it) }
        entities.rainEvent?.let { add(it) }
        addAll(entities.climate)
        addAll(entities.solar.ids())
        entities.lightGroups.forEach { addAll(it.entities) }
        entities.cameras.forEach { c -> c.entity?.let { add(it) } }
        entities.doorbells.forEach { d -> d.trigger?.let { add(it) } }
        entities.evs.forEach { addAll(it.ids()) }
        entities.quickButtons.forEach { qb -> qb.entity?.let { add(it) } }
        media.companionEntity?.let { add(it) }
        notifications.nwsAlerts?.let { add(it) }
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
        val cleanedEvs = entities.evs
            .map { ev ->
                ev.copy(
                    name = ev.name.trim(),
                    plugged = ev.plugged?.trim()?.ifBlank { null },
                    charging = ev.charging?.trim()?.ifBlank { null },
                    soc = ev.soc?.trim()?.ifBlank { null },
                    limit = ev.limit?.trim()?.ifBlank { null },
                    power = ev.power?.trim()?.ifBlank { null },
                    energy = ev.energy?.trim()?.ifBlank { null },
                    eta = ev.eta?.trim()?.ifBlank { null },
                )
            }
            .filter { it.name.isNotBlank() || it.ids().isNotEmpty() }
            .take(2)
        val cleanedCalendars = entities.calendars
            .map { c ->
                c.copy(
                    entity = c.entity.trim(),
                    name = c.name.trim(),
                    color = c.color.trim().lowercase().let { if (it in CalendarConfig.COLORS) it else "blue" },
                )
            }
            .filter { it.entity.isNotBlank() }
            .take(6)
        return copy(
            version = 1,
            entities = entities.copy(
                tempSensor = entities.tempSensor?.trim()?.ifBlank { null },
                weather = entities.weather?.trim()?.ifBlank { null },
                aqiSensor = entities.aqiSensor?.trim()?.ifBlank { null },
                rainEvent = entities.rainEvent?.trim()?.ifBlank { null },
                climate = entities.climate.filter { it.isNotBlank() },
                solar = entities.solar.copy(
                    pv = entities.solar.pv?.trim()?.ifBlank { null },
                    load = entities.solar.load?.trim()?.ifBlank { null },
                    grid = entities.solar.grid?.trim()?.ifBlank { null },
                    pvToday = entities.solar.pvToday?.trim()?.ifBlank { null },
                    loadToday = entities.solar.loadToday?.trim()?.ifBlank { null },
                    battSoc = entities.solar.battSoc?.trim()?.ifBlank { null },
                    battPower = entities.solar.battPower?.trim()?.ifBlank { null },
                    gridImportToday = entities.solar.gridImportToday?.trim()?.ifBlank { null },
                    gridExportToday = entities.solar.gridExportToday?.trim()?.ifBlank { null },
                    battInToday = entities.solar.battInToday?.trim()?.ifBlank { null },
                    battOutToday = entities.solar.battOutToday?.trim()?.ifBlank { null },
                    arrays = entities.solar.arrays
                        .map { it.copy(name = it.name.trim(), power = it.power?.trim()?.ifBlank { null }) }
                        .filter { it.name.isNotBlank() || it.power != null }
                        .take(4),
                ),
                lightGroups = entities.lightGroups
                    .map { it.copy(entities = it.entities.filter { id -> id.isNotBlank() }) }
                    .filter { it.entities.isNotEmpty() || it.name.isNotBlank() },
                cameras = cleanedCameras,
                doorbells = cleanedDoorbells,
                evs = cleanedEvs,
                calendars = cleanedCalendars,
                // Trim both fields, drop slots with no entity (a name alone is useless), cap at 4.
                quickButtons = entities.quickButtons
                    .map { it.copy(name = it.name.trim(), entity = it.entity?.trim()?.ifBlank { null }) }
                    .filter { it.entity != null }
                    .take(4),
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
            voice = voice.clamped(),
            media = media.clamped(),
            night = night.clamped(),
            notifications = notifications.clamped(),
            sendspin = sendspin.clamped(),
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
