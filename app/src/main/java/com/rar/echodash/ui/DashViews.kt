package com.rar.echodash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.rar.echodash.ui.model.WeatherIcon

/** The six rail destinations, top-to-bottom. */
enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, WEATHER, SOLAR }

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
    DashView.WEATHER -> Icons.Outlined.WbCloudy
    DashView.SOLAR -> Icons.Outlined.SolarPower
}
