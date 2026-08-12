package com.rar.hearth.config

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

/** One home-screen card's placement: whether it renders, and where in the column it sits. */
@Serializable
data class HomeCardConfig(val enabled: Boolean = true, val order: Int = 0)

/**
 * The right-hand home card column, in user-controlled order.
 *
 * Defaults are exactly the sequence the column rendered before this block existed -- now-playing,
 * EV 1, EV 2, solar, quick buttons, nothing hidden -- so a config saved by an older build
 * deserializes to an identical layout.
 *
 * EV rows are POSITIONAL: `ev1` is `entities.evs[0]`, not a particular car. Swapping which car
 * occupies which slot leaves the ordering attached to the slot.
 */
@Serializable
data class HomeCards(
    val nowPlaying: HomeCardConfig = HomeCardConfig(true, 1),
    val ev1: HomeCardConfig = HomeCardConfig(true, 2),
    val ev2: HomeCardConfig = HomeCardConfig(true, 3),
    val solar: HomeCardConfig = HomeCardConfig(true, 4),
    val quickButtons: HomeCardConfig = HomeCardConfig(true, 5),
) {
    /** The five cards in DECLARATION order -- which is also the tie-break for equal `order`s. */
    fun slots(): List<HomeCardConfig> = listOf(nowPlaying, ev1, ev2, solar, quickButtons)

    /**
     * Rewrite `order` to a dense 1..5 following the current sort, breaking ties by declaration
     * order. Idempotent. Keeps hand-edited or half-saved configs from accumulating sparse or
     * duplicated values, which would make the web UI's swap arrows behave unpredictably.
     * `enabled` is never touched.
     */
    fun clamped(): HomeCards {
        val ranked = slots().withIndex().sortedWith(compareBy({ it.value.order }, { it.index }))
        val orders = IntArray(slots().size)
        ranked.forEachIndexed { rank, iv -> orders[iv.index] = rank + 1 }
        return HomeCards(
            nowPlaying = nowPlaying.copy(order = orders[0]),
            ev1 = ev1.copy(order = orders[1]),
            ev2 = ev2.copy(order = orders[2]),
            solar = solar.copy(order = orders[3]),
            quickButtons = quickButtons.copy(order = orders[4]),
        )
    }
}

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

/**
 * The Claude subscription-usage card's sensors, as published by the `hass_claude_usage` integration.
 *
 * Every slot is optional: the card renders whatever it can read and drops the rest, because the
 * integration's own sensors come and go (a bucket the API stops reporting — e.g. a model you haven't
 * used this week — goes `unavailable` rather than disappearing). Reset slots are timestamp sensors
 * paired with their percentage; a percentage with no reset sensor simply renders without the suffix.
 */
@Serializable
data class ClaudeUsageConfig(
    val session: String? = null,
    val sessionReset: String? = null,
    val week: String? = null,
    val weekReset: String? = null,
    val pace: String? = null,
) {
    fun ids(): List<String> = listOfNotNull(session, sessionReset, week, weekReset, pace)

    /** True once either percentage is set — the reset and pace slots are garnish on their own. */
    fun configured(): Boolean = session != null || week != null
}

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
    val claudeUsage: ClaudeUsageConfig = ClaudeUsageConfig(),
)

@Serializable
data class HomeSettings(
    val idleReturnSeconds: Int = 60,
    val clockFormat: ClockFormat = ClockFormat.AUTO,
    val slideshowEnabled: Boolean = true,
    val photoFolder: String = "echo-frame",
    /**
     * How many not-yet-displayed photos to keep prefetched. This is a buffer, not a library: a
     * photo is deleted shortly after it is shown, so on-disk residency is roughly this plus the
     * short back-swipe history. Replaces the old photoCacheCap, which sized a rotating cache that
     * WAS the visible universe and so guaranteed repeats. Old configs carrying photoCacheCap parse
     * fine and fall back to this default -- ConfigJson sets ignoreUnknownKeys.
     */
    val photoBufferDepth: Int = 20,
    /** How often to re-browse the HA folder for newly added photos. */
    val photoSyncIntervalMinutes: Int = 360,
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
    val followUpEnabled: Boolean = false,
    /** Dump the ~10 s of mic audio preceding each wake fire to filesDir/wake-captures (see
     *  [com.rar.hearth.voice.WakeAudioRing]). Off by default: it writes room audio to flash, and
     *  is meant to be switched on only while investigating a specific false positive. */
    val captureOnWake: Boolean = false,
    /**
     * Score floor (percent) for capturing, deliberately BELOW [wakeThreshold].
     *
     * Capture is decoupled from firing: the score is computed on every chunk regardless of the
     * threshold, so we can collect near-misses without triggering a wake session for each one.
     * Audio scoring 40-70 is the most useful training material there is — hard negatives sitting
     * right at the decision boundary — and gathering it this way costs the user no interruptions.
     * Only consulted when [captureOnWake] is on.
     */
    val captureThreshold: Int = 40,
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
        captureThreshold = captureThreshold.coerceIn(10, 95),
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
        serverAddress = serverAddress.trim().let { if (isValidSendspinAddress(it)) it else "" },
        maToken = maToken.trim(),
        maUser = maUser.trim(),
    )
}

/**
 * Validate a manual SendSpin / Music Assistant server address. Accepts blank (= fall back to mDNS
 * discovery) or a plausible `host` / `host:port`: host non-empty with no scheme, slash, or
 * whitespace; optional port in 1..65535. Deliberately lenient (no DNS resolution) — the goal is only
 * to reject obviously-malformed input so [SendspinConfig.clamped] blanks it to the safe discovery
 * default instead of churning the transport against a bad address. Pure so it is JVM-unit-testable.
 */
internal fun isValidSendspinAddress(s: String): Boolean {
    if (s.isBlank()) return true
    if (s.contains("://") || s.contains('/') || s.any { it.isWhitespace() }) return false
    val host = s.substringBefore(':')
    val port = s.substringAfter(':', "")
    if (host.isEmpty()) return false
    if (port.isNotEmpty() && port.toIntOrNull() !in 1..65535) return false
    return true
}

/** The whole device configuration; one versioned document persisted at filesDir/config.json. */
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val homeCards: HomeCards = HomeCards(),
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
        addAll(entities.claudeUsage.ids())
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
            homeCards = homeCards.clamped(),
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
                // Same blank-slot trim as every other picker: the web config writes "" for a
                // cleared field, and a "" entity id would be watched and never resolve.
                claudeUsage = entities.claudeUsage.let { c ->
                    fun clean(s: String?) = s?.trim()?.ifBlank { null }
                    ClaudeUsageConfig(
                        session = clean(c.session),
                        sessionReset = clean(c.sessionReset),
                        week = clean(c.week),
                        weekReset = clean(c.weekReset),
                        pace = clean(c.pace),
                    )
                },
            ),
            home = home.copy(
                idleReturnSeconds = home.idleReturnSeconds.coerceIn(15, 3600),
                photoBufferDepth = home.photoBufferDepth.coerceIn(5, 100),
                photoSyncIntervalMinutes = home.photoSyncIntervalMinutes.coerceIn(15, 1440),
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
