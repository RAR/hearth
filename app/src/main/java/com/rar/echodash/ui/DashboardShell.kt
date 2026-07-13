package com.rar.echodash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.aqiPill
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.weatherPill
import com.rar.echodash.ui.panels.CamerasPanel
import com.rar.echodash.ui.panels.ClimatePanel
import com.rar.echodash.ui.panels.LightsPanel
import com.rar.echodash.ui.panels.MediaPanel
import com.rar.echodash.ui.panels.SolarPanel
import com.rar.echodash.ui.panels.WeatherPanel
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** How long the icon rail stays on screen after a touch while the now-playing takeover is up. */
private const val RAIL_HIDE_MS = 8_000L

@Composable
fun DashboardShell(
    current: DashView,
    onSelect: (DashView) -> Unit,
    config: DashConfig,
    entities: Map<String, EntityState>,
    registry: RegistryIndex,
    connState: ConnState,
    photos: List<File>,
    nowPlaying: NowPlayingState,
    art: ArtBitmaps?,
    takeoverVisible: Boolean,
    onToggle: (String) -> Unit,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    fetchForecast: suspend (String) -> JsonElement?,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
    streamResolver: StreamResolver,
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = config.entities.weather
    val views = remember(config.panels, config.entities.cameras) {
        railViews(config.panels, config.entities.cameras.isNotEmpty())
    }
    var railTouches by remember { mutableStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            // Report every touch without consuming it, so panels still receive their gestures.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        onInteraction()
                        if (event.type == PointerEventType.Press) railTouches++
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
                        slideshowSeconds = config.home.slideshowSeconds,
                        pill = pill,
                        aqi = aqi,
                        clockFormat = config.home.clockFormat,
                        connState = connState,
                        configUrl = configUrl,
                        configPin = configPin,
                        onLogout = onLogout,
                        nowPlaying = nowPlaying,
                        art = art,
                        takeoverVisible = takeoverVisible,
                        onMediaPlay = onMediaPlay,
                        onMediaPause = onMediaPause,
                        onMediaNext = onMediaNext,
                        onMediaPrev = onMediaPrev,
                        onMediaVolume = onMediaVolume,
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
                DashView.MEDIA -> MediaPanel(
                    nowPlaying, art, onMediaPlay, onMediaPause, onMediaStop,
                    onMediaNext, onMediaPrev, onMediaVolume,
                )
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
                DashView.CAMERAS -> CamerasPanel(config.entities.cameras, streamResolver)
            }
        }

        // While the home now-playing takeover is showing, the rail slides away so the player
        // owns the full width; any touch slides it back in, and it hides again after RAIL_HIDE_MS.
        val takeover = current == DashView.HOME && takeoverVisible
        var railVisible by remember { mutableStateOf(true) }
        LaunchedEffect(takeover, railTouches) {
            if (takeover) {
                railVisible = true
                delay(RAIL_HIDE_MS)
                railVisible = false
            } else {
                railVisible = true
            }
        }
        AnimatedVisibility(
            visible = railVisible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            IconRail(
                current = current,
                views = views,
                onSelect = onSelect,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
