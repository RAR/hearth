package com.rar.echodash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.NotificationItem
import com.rar.echodash.ui.model.PUSH_KEY_PREFIX
import com.rar.echodash.ui.model.aqiPill
import com.rar.echodash.ui.model.autoDismissCutoff
import com.rar.echodash.ui.model.autoDismissKeys
import com.rar.echodash.ui.model.evCards
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.mergeNotifications
import com.rar.echodash.ui.model.notifSeverityOf
import com.rar.echodash.ui.model.nwsNotifications
import com.rar.echodash.ui.model.solarCard
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
import com.rar.echodash.ui.model.rainPill
import com.rar.echodash.ui.model.weatherPill
import com.rar.echodash.ui.panels.CalendarPanel
import com.rar.echodash.ui.panels.CamerasPanel
import com.rar.echodash.ui.panels.ClimatePanel
import com.rar.echodash.ui.panels.LightsPanel
import com.rar.echodash.ui.panels.MediaPanel
import com.rar.echodash.ui.panels.SolarPanel
import com.rar.echodash.ui.panels.WeatherPanel
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.MaThumbs
import com.rar.echodash.media.NowPlayingState
import com.rar.echodash.sendspin.MaLibrary
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
    library: MaLibrary?,
    thumbs: MaThumbs?,
    onBrowse: () -> Unit,
    fetchForecast: suspend (String) -> JsonElement?,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
    streamResolver: StreamResolver,
    nightActive: Boolean = false,
    onNightWake: () -> Unit = {},
    pushed: List<NotificationItem> = emptyList(),
    onPushDismiss: (String) -> Unit = {},
    calendarEvents: List<CalendarEvent> = emptyList(),
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = config.entities.weather
    val views = remember(config.panels, config.entities.cameras) {
        railViews(config.panels, config.entities.cameras.isNotEmpty())
    }
    var railReveals by remember { mutableStateOf(0) }
    // Process-lifetime notification dismissals. Held here (NOT inside the Crossfade HOME branch) so
    // the set survives view switches and takeover unmounts; a dismissed alert returns only if NWS
    // reissues it under a new ID or the app restarts.
    var dismissedKeys by remember { mutableStateOf(setOf<String>()) }

    // Notification derivation lives at shell scope (not the HOME branch) so the auto-dismiss clock
    // keeps running while another panel is up or the takeover hides the area.
    val nwsItems = remember(entities, config.notifications) {
        nwsNotifications(
            config.notifications.nwsAlerts,
            notifSeverityOf(config.notifications.nwsMinSeverity),
            entities,
            System.currentTimeMillis(),
        )
    }
    val allNotifications = remember(pushed, nwsItems) { mergeNotifications(pushed, nwsItems) }
    val notifications = allNotifications.filter { it.key !in dismissedKeys }
    val dismissKey: (String) -> Unit = { key ->
        if (key.startsWith(PUSH_KEY_PREFIX)) onPushDismiss(key.removePrefix(PUSH_KEY_PREFIX))
        else dismissedKeys = dismissedKeys + key
    }
    // Prune dismissed keys no longer present so the set can't grow unboundedly.
    // Only while the sensor reports a numeric count: when it's unavailable (HA
    // restarting) an empty list means "unknown", not "no alerts" — pruning then
    // would resurrect dismissed-but-still-active alerts once the sensor recovers.
    // Keyed on the NWS-only list: pushed keys never enter dismissedKeys (removal from
    // the store IS their dismissal), so they must not participate in this guard.
    val nwsLive = config.notifications.nwsAlerts
        ?.let { entities[it]?.state?.toIntOrNull() } != null
    LaunchedEffect(nwsItems, nwsLive) {
        if (!nwsLive) return@LaunchedEffect
        val present = nwsItems.mapTo(HashSet()) { it.key }
        val pruned = dismissedKeys intersect present
        if (pruned != dismissedKeys) dismissedKeys = pruned
    }
    // Auto-dismiss low-severity rows after the configured dwell. First-seen times are process
    // memory only; a row that leaves the list (dismissed, expired, NWS resolved) forgets its clock.
    val firstSeen = remember { HashMap<String, Long>() }
    LaunchedEffect(notifications) {
        val now = System.currentTimeMillis()
        firstSeen.keys.retainAll(notifications.mapTo(HashSet()) { it.key })
        notifications.forEach { firstSeen.putIfAbsent(it.key, now) }
    }
    val autoCutoff = autoDismissCutoff(config.notifications.autoDismiss)
    LaunchedEffect(notifications, autoCutoff, config.notifications.autoDismissSeconds) {
        if (autoCutoff == null) return@LaunchedEffect
        val timeoutMs = config.notifications.autoDismissSeconds * 1000L
        while (true) {
            autoDismissKeys(notifications, autoCutoff, firstSeen, timeoutMs, System.currentTimeMillis())
                .forEach(dismissKey)
            delay(5_000)
        }
    }

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
        Crossfade(
            targetState = current,
            animationSpec = tween(300),
            label = "view",
            // On any panel, a left-to-right swipe goes back home. The detector sits on the
            // PARENT of the panel content, so interactive children (volume/brightness sliders,
            // scrolling lists) consume their own drags first and are unaffected; only leftover
            // rightward swipes on passive areas trigger it. Inert on HOME (the home view has
            // its own photo-swipe gesture).
            modifier = Modifier.pointerInput(current) {
                if (current == DashView.HOME) return@pointerInput
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx > 60.dp.toPx()) onSelect(DashView.HOME) },
                ) { _, dragAmount -> dx += dragAmount }
            },
        ) { view ->
            when (view) {
                DashView.HOME -> {
                    val pill = remember(entities, config.entities, config.panelOptions.sensorDecimals) {
                        weatherPill(config.entities.tempSensor, config.entities.weather, entities,
                            System.currentTimeMillis(), config.panelOptions.sensorDecimals)
                    }
                    val aqi = remember(entities, config.entities) {
                        aqiPill(config.entities.aqiSensor, entities)
                    }
                    val rain = remember(entities, config.entities) {
                        rainPill(config.entities.rainEvent, entities)
                    }
                    val evs = remember(entities, config.entities.evs) {
                        evCards(config.entities.evs, entities, System.currentTimeMillis())
                    }
                    val solar = remember(entities, config.entities.solar) {
                        solarCard(config.entities.solar, entities)
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
                        onDismiss = dismissKey,
                        calendarEvents = calendarEvents,
                        onOpenCalendar = { onSelect(DashView.CALENDAR) },
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
                        onBrowse = onBrowse,
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
                    library = library, thumbs = thumbs,
                )
                DashView.CALENDAR -> CalendarPanel(
                    events = calendarEvents,
                    hasCalendars = config.entities.calendars.isNotEmpty(),
                    clockFormat = config.home.clockFormat,
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

        // The rail always auto-hides (no longer an option). Hidden, it comes back ONLY via a
        // leftward swipe from the right edge (the strip below) — ordinary touches don't pop it
        // up — shows for RAIL_HIDE_MS, then slides away again.
        var railVisible by remember { mutableStateOf(true) }
        LaunchedEffect(railReveals) {
            railVisible = true
            delay(RAIL_HIDE_MS)
            railVisible = false
        }
        if (!railVisible) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(24.dp)
                    .pointerInput(Unit) {
                        var triggered = false
                        detectHorizontalDragGestures(
                            onDragStart = { triggered = false },
                        ) { change, dragAmount ->
                            change.consume()
                            if (!triggered && dragAmount < 0) {
                                triggered = true
                                railReveals++
                            }
                        }
                    },
            ) {
                // Faint handle hinting that the rail lives just off-screen to the right.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 5.dp)
                        .width(4.dp)
                        .height(48.dp)
                        .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                )
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
