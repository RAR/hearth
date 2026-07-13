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
import com.rar.echodash.ui.model.evCards
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.notifSeverityOf
import com.rar.echodash.ui.model.nwsNotifications
import com.rar.echodash.ui.model.solarCard
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.rainPill
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
    nightActive: Boolean = false,
    onNightWake: () -> Unit = {},
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = config.entities.weather
    val views = remember(config.panels, config.entities.cameras) {
        railViews(config.panels, config.entities.cameras.isNotEmpty())
    }
    var railTouches by remember { mutableStateOf(0) }
    // Process-lifetime notification dismissals. Held here (NOT inside the Crossfade HOME branch) so
    // the set survives view switches and takeover unmounts; a dismissed alert returns only if NWS
    // reissues it under a new ID or the app restarts.
    var dismissedKeys by remember { mutableStateOf(setOf<String>()) }

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
                    val rain = remember(entities, config.entities) {
                        rainPill(config.entities.rainEvent, entities, System.currentTimeMillis())
                    }
                    val evs = remember(entities, config.entities.evs) {
                        evCards(config.entities.evs, entities, System.currentTimeMillis())
                    }
                    val solar = remember(entities, config.entities.solar) {
                        solarCard(config.entities.solar, entities)
                    }
                    val allNotifications = remember(entities, config.notifications) {
                        nwsNotifications(
                            config.notifications.nwsAlerts,
                            notifSeverityOf(config.notifications.nwsMinSeverity),
                            entities,
                            System.currentTimeMillis(),
                        )
                    }
                    val notifications = allNotifications.filter { it.key !in dismissedKeys }
                    // Prune dismissed keys no longer present so the set can't grow unboundedly.
                    // Only while the sensor reports a numeric count: when it's unavailable (HA
                    // restarting) an empty list means "unknown", not "no alerts" — pruning then
                    // would resurrect dismissed-but-still-active alerts once the sensor recovers.
                    val nwsLive = config.notifications.nwsAlerts
                        ?.let { entities[it]?.state?.toIntOrNull() } != null
                    LaunchedEffect(allNotifications, nwsLive) {
                        if (!nwsLive) return@LaunchedEffect
                        val present = allNotifications.mapTo(HashSet()) { it.key }
                        val pruned = dismissedKeys intersect present
                        if (pruned != dismissedKeys) dismissedKeys = pruned
                    }
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        slideshowSeconds = config.home.slideshowSeconds,
                        pill = pill,
                        aqi = aqi,
                        rain = rain,
                        evs = evs,
                        solar = solar,
                        notifications = notifications,
                        onDismiss = { key -> dismissedKeys = dismissedKeys + key },
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
        // The rail also auto-hides everywhere when the auto-hide option is on.
        val autoHide = (current == DashView.HOME && takeoverVisible) || config.panelOptions.autoHideRail
        var railVisible by remember { mutableStateOf(true) }
        LaunchedEffect(autoHide, railTouches) {
            if (autoHide) {
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

        NightClockOverlay(
            active = nightActive,
            clockFormat = config.home.clockFormat,
            onWake = onNightWake,
        )
    }
}
