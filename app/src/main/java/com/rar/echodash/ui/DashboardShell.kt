package com.rar.echodash.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.aqiPill
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.weatherPill
import com.rar.echodash.ui.panels.ClimatePanel
import com.rar.echodash.ui.panels.LightsPanel
import com.rar.echodash.ui.panels.MediaPanel
import com.rar.echodash.ui.panels.SolarPanel
import com.rar.echodash.ui.panels.WeatherPanel
import com.rar.echodash.vaca.MediaUiState
import java.io.File
import kotlinx.serialization.json.JsonElement

@Composable
fun DashboardShell(
    current: DashView,
    onSelect: (DashView) -> Unit,
    config: DashConfig,
    entities: Map<String, EntityState>,
    registry: RegistryIndex,
    connState: ConnState,
    photos: List<File>,
    mediaUi: MediaUiState,
    onToggle: (String) -> Unit,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    fetchForecast: suspend (String) -> JsonElement?,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = config.entities.weather
    val views = remember(config.panels) { railViews(config.panels) }

    Box(
        Modifier
            .fillMaxSize()
            // Report every touch without consuming it, so panels still receive their gestures.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onInteraction()
                    }
                }
            }
    ) {
        Crossfade(targetState = current, animationSpec = tween(300), label = "view") { view ->
            when (view) {
                DashView.HOME -> {
                    val pill = remember(entities, config.entities, config.panelOptions.sensorDecimals) {
                        weatherPill(config.entities.tempSensor, config.entities.weather, entities,
                            System.currentTimeMillis(), config.panelOptions.sensorDecimals)
                    }
                    val aqi = remember(entities, config.entities) {
                        aqiPill(config.entities.aqiSensor, entities, System.currentTimeMillis())
                    }
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        pill = pill,
                        aqi = aqi,
                        clockFormat = config.home.clockFormat,
                        connState = connState,
                        configUrl = configUrl,
                        configPin = configPin,
                        onLogout = onLogout,
                    )
                }
                DashView.LIGHTS -> {
                    val sections = remember(entities, registry, config.entities.lightGroups) {
                        lightSections(config.entities.lightGroups, registry, entities)
                    }
                    LightsPanel(sections, connected, onToggle)
                }
                DashView.CLIMATE -> {
                    val list = remember(entities, registry, config.entities.climate, config.panelOptions.thermostatStep) {
                        thermostats(config.entities.climate, registry, entities, config.panelOptions.thermostatStep)
                    }
                    ClimatePanel(list, connected, onSetTemperature, onSetHvacMode)
                }
                DashView.MEDIA -> MediaPanel(mediaUi, onMediaPlay, onMediaPause, onMediaStop, onMediaVolume)
                DashView.WEATHER -> WeatherPanel(
                    weather = weatherEntityId?.let { entities[it] },
                    weatherEntityId = weatherEntityId,
                    forecastDays = config.panelOptions.forecastDays,
                    sensorDecimals = config.panelOptions.sensorDecimals,
                    fetchForecast = fetchForecast,
                )
                DashView.SOLAR -> {
                    val flow = remember(entities, config.entities.solar) { solarFlow(config.entities.solar, entities) }
                    SolarPanel(flow)
                }
            }
        }

        IconRail(
            current = current,
            views = views,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
    }
}
