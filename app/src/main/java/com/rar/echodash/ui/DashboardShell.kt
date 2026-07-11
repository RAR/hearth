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
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.buildLightGroups
import com.rar.echodash.ui.model.buildSolarFlow
import com.rar.echodash.ui.model.thermostatStates
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
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = registry.labelToEntities["echo-weather"]?.firstOrNull()

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
                    val pill = remember(entities, registry) { weatherPill(registry, entities, System.currentTimeMillis()) }
                    HomeView(photos = photos, pill = pill, connState = connState, onLogout = onLogout)
                }
                DashView.LIGHTS -> {
                    val groups = remember(entities, registry) { buildLightGroups(registry, entities) }
                    LightsPanel(groups, connected, onToggle)
                }
                DashView.CLIMATE -> {
                    val thermostats = remember(entities, registry) { thermostatStates(registry, entities) }
                    ClimatePanel(thermostats, connected, onSetTemperature, onSetHvacMode)
                }
                DashView.MEDIA -> MediaPanel(mediaUi, onMediaPlay, onMediaPause, onMediaStop, onMediaVolume)
                DashView.WEATHER -> WeatherPanel(
                    weather = weatherEntityId?.let { entities[it] },
                    weatherEntityId = weatherEntityId,
                    fetchForecast = fetchForecast,
                )
                DashView.SOLAR -> {
                    val flow = remember(entities, registry) { buildSolarFlow(registry, entities) }
                    SolarPanel(flow)
                }
            }
        }

        IconRail(
            current = current,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
    }
}
