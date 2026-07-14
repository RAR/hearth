package com.rar.echodash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.config.Panels
import com.rar.echodash.ui.model.WeatherIcon
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The rail destinations, top-to-bottom. */
enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, CALENDAR, WEATHER, SOLAR, CAMERAS }

/** Material icon for a weather condition (used by the Home pill and the Weather panel). */
fun weatherIcon(icon: WeatherIcon): ImageVector = when (icon) {
    WeatherIcon.SUNNY -> Icons.Outlined.WbSunny
    WeatherIcon.CLEAR_NIGHT -> Icons.Outlined.NightsStay
    WeatherIcon.PARTLY_CLOUDY -> Icons.Outlined.WbCloudy
    WeatherIcon.CLOUDY -> Icons.Outlined.Cloud
    WeatherIcon.RAIN -> Icons.Outlined.WaterDrop
    WeatherIcon.SNOW -> Icons.Outlined.AcUnit
    WeatherIcon.STORM -> Icons.Outlined.Thunderstorm
    WeatherIcon.FOG -> Icons.Outlined.Dehaze
    WeatherIcon.WIND -> Icons.Outlined.Air
    WeatherIcon.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** Material icon for each rail destination. */
fun railIcon(view: DashView): ImageVector = when (view) {
    DashView.HOME -> Icons.Outlined.Home
    DashView.LIGHTS -> Icons.Outlined.Lightbulb
    DashView.CLIMATE -> Icons.Outlined.Thermostat
    DashView.MEDIA -> Icons.Outlined.MusicNote
    DashView.CALENDAR -> Icons.Outlined.CalendarMonth
    DashView.WEATHER -> Icons.Outlined.WbCloudy
    DashView.SOLAR -> Icons.Outlined.SolarPower
    DashView.CAMERAS -> Icons.Outlined.Videocam
}

/** The rail destinations: HOME first, then enabled panels ordered by their configured `order`.
 * Cameras appears only when its panel is enabled AND at least one camera is configured. */
fun railViews(panels: Panels, camerasConfigured: Boolean = false): List<DashView> {
    val configured = listOf(
        DashView.LIGHTS to panels.lights,
        DashView.CLIMATE to panels.climate,
        DashView.MEDIA to panels.media,
        DashView.WEATHER to panels.weather,
        DashView.SOLAR to panels.solar,
        DashView.CAMERAS to panels.cameras,
    ).filter { (view, cfg) ->
        cfg.enabled && (view != DashView.CAMERAS || camerasConfigured)
    }.sortedBy { it.second.order }.map { it.first }
    // Calendar is always available (no panel toggle); it's appended last so panel reordering
    // never shifts it. The panel itself shows a hint when no calendars are configured.
    return listOf(DashView.HOME) + configured + DashView.CALENDAR
}

/** True when the clock should use 24-hour time (AUTO follows the system setting). */
fun clockIs24(format: ClockFormat, systemIs24: Boolean): Boolean = when (format) {
    ClockFormat.AUTO -> systemIs24
    ClockFormat.H12 -> false
    ClockFormat.H24 -> true
}

/** Home-screen date line, e.g. "Sunday, July 12th". */
fun dateLine(millis: Long, locale: Locale = Locale.getDefault()): String {
    val day = Calendar.getInstance(locale).apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)
    return SimpleDateFormat("EEEE, MMMM", locale).format(Date(millis)) + " $day${ordinalSuffix(day)}"
}

internal fun ordinalSuffix(day: Int): String = when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}
