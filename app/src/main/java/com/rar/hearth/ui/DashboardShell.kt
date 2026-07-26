package com.rar.hearth.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import com.rar.hearth.camera.StreamResolver
import com.rar.hearth.config.DashConfig
import com.rar.hearth.ha.ConnState
import com.rar.hearth.ha.EntityState
import com.rar.hearth.ha.RegistryIndex
import com.rar.hearth.sendspin.MaLibraryState
import com.rar.hearth.sendspin.musicassistant.MaQueueItem
import com.rar.hearth.ui.model.CalendarEvent
import com.rar.hearth.ui.model.claudeUsageCard
import com.rar.hearth.ui.model.NotificationItem
import com.rar.hearth.ui.model.PUSH_KEY_PREFIX
import com.rar.hearth.ui.model.aqiPill
import com.rar.hearth.ui.model.autoDismissCutoff
import com.rar.hearth.ui.model.autoDismissKeys
import com.rar.hearth.ui.model.evCards
import com.rar.hearth.ui.model.lightSections
import com.rar.hearth.ui.model.mergeNotifications
import com.rar.hearth.ui.model.notifSeverityOf
import com.rar.hearth.ui.model.nwsNotifications
import com.rar.hearth.ui.model.QuickButton
import com.rar.hearth.ui.model.quickButtons
import com.rar.hearth.ui.model.solarCard
import com.rar.hearth.ui.model.solarFlowGraph
import com.rar.hearth.ui.model.thermostats
import com.rar.hearth.ui.model.rainPill
import com.rar.hearth.ui.model.currentItemOf
import com.rar.hearth.ui.model.upNextOf
import com.rar.hearth.ui.model.weatherPill
import com.rar.hearth.ui.panels.CalendarPanel
import com.rar.hearth.ui.panels.CamerasPanel
import com.rar.hearth.ui.panels.ClimatePanel
import com.rar.hearth.ui.panels.LightsPanel
import com.rar.hearth.ui.panels.MediaPanel
import com.rar.hearth.ui.panels.SolarPanel
import com.rar.hearth.ui.panels.WeatherPanel
import com.rar.hearth.media.ArtBitmaps
import com.rar.hearth.media.MaThumbs
import com.rar.hearth.media.NowPlayingState
import com.rar.hearth.sendspin.MaLibrary
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
    onQuickButton: (QuickButton) -> Unit = {},
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    onMediaSeek: (Long) -> Unit,
    library: MaLibrary?,
    thumbs: MaThumbs?,
    onBrowse: () -> Unit,
    onTakeoverDismiss: () -> Unit = {},
    onTakeoverRestore: () -> Unit = {},
    // Now-playing card pause-grace state, owned by App (survives the Crossfade disposal that
    // remounts this HOME branch on every view switch); see App.kt's comment beside manualDismissed.
    miniPausedSinceMs: Long = 0L,
    onMediaCycleRepeat: () -> Unit = {},
    onMediaToggleShuffle: () -> Unit = {},
    onMediaSetRepeat: (String) -> Unit = {},
    onMediaSetShuffle: (Boolean) -> Unit = {},
    onFavoriteToggle: (MaQueueItem?) -> Unit = {},
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
    // Up-next line state, owned here (not in NowPlayingState): it comes from the MA API poll, a
    // different producer than SendspinEndpoint. library is a process-lifetime dependency, so its
    // null-ness is fixed for this composition -- the guarded collect below is stable across
    // recompositions.
    var upNext by remember { mutableStateOf<MaQueueItem?>(null) }
    // Current-track favorite state, from the same poll as upNext. favVersion bumps re-run the
    // poll immediately after a heart tap so the lit state catches up in ~one round-trip.
    var favState by remember { mutableStateOf<MaQueueItem?>(null) }
    var favVersion by remember { mutableIntStateOf(0) }
    // Bumped when the takeover's up-next line is tapped; threaded to MusicBrowser to open the
    // queue overlay on first composition in the MEDIA view. Starts at 0 (never-requested).
    var openQueueSignal by remember { mutableIntStateOf(0) }
    // One-shot: the signal only auto-opens the queue for the MEDIA entry the up-next tap itself
    // triggered. Reset after leaving MEDIA so later manual visits land on the browser as usual.
    LaunchedEffect(current) {
        if (current != DashView.MEDIA && openQueueSignal > 0) openQueueSignal = 0
    }
    // deps.maLibrary is a process-lifetime constant (App.kt:295) -- this branch never flips, so the
    // collectAsStateWithLifecycle call stays structurally stable across recompositions (it is kept
    // out of a conditional expression -- the composable is called only inside the stable branch).
    val maConnected = if (library != null) {
        val maState by library.state.collectAsStateWithLifecycle()
        maState is MaLibraryState.Connected
    } else false
    // Poll the queue while the takeover is up on a SendSpin source with a live MA socket. Keyed on
    // nowPlaying.title so a track advance restarts the poll and refreshes the line immediately;
    // any fetch failure (or a null next item) sets null -- the takeover is glanceable, not a
    // diagnostics surface. The gate going false clears the line.
    LaunchedEffect(takeoverVisible, nowPlaying.sendspin, nowPlaying.title, maConnected, favVersion) {
        if (!(takeoverVisible && nowPlaying.sendspin && library != null && maConnected)) {
            upNext = null
            favState = null
            return@LaunchedEffect
        }
        while (true) {
            val q = library.queue().getOrNull()
            upNext = q?.let { upNextOf(it) }
            favState = q?.let { currentItemOf(it) }
            delay(10_000)
        }
    }
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
    // Keyed on a minute tick (not just entities/config) so alerts drop off once their displayed
    // end time passes even in a quiet house with no HA state churn to trigger recomposition.
    val minuteTick by rememberMinuteTicker()
    val nwsItems = remember(entities, config.notifications, minuteTick) {
        nwsNotifications(
            config.notifications.nwsAlerts,
            notifSeverityOf(config.notifications.nwsMinSeverity),
            entities,
            minuteTick,
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
                    val solarGraph = remember(entities, config.entities.solar) {
                        solarFlowGraph(config.entities.solar, entities)
                    }
                    val quickBtns = remember(entities, config.entities.quickButtons) {
                        quickButtons(config.entities.quickButtons, entities)
                    }
                    val is24 = clockIs24(
                        config.home.clockFormat, DateFormat.is24HourFormat(LocalContext.current),
                    )
                    // Keyed on entities like evCards: the usage sensors repoll every ~5 min, and
                    // the only now-dependent piece is the today-vs-weekday reset label, so a tick
                    // of its own would buy nothing.
                    val usageCard = remember(entities, config.entities.claudeUsage, is24) {
                        claudeUsageCard(
                            config.entities.claudeUsage, entities,
                            System.currentTimeMillis(), ZoneId.systemDefault(), is24,
                        )
                    }
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        slideshowSeconds = config.home.slideshowSeconds,
                        pill = pill,
                        aqi = aqi,
                        rain = rain,
                        evs = evs,
                        solar = solar,
                        solarGraph = solarGraph,
                        quickButtons = quickBtns,
                        onQuickButton = onQuickButton,
                        notifications = notifications,
                        onDismiss = dismissKey,
                        claudeUsage = usageCard,
                        // CONFIG presence, not current card visibility, so the notification width
                        // never jumps when a card fades in/out. ids() is public on both configs.
                        reserveCardColumn = config.entities.evs.isNotEmpty() || config.entities.solar.ids().isNotEmpty(),
                        reserveUsageCard = config.entities.claudeUsage.configured(),
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
                        onMediaStop = onMediaStop,
                        onMediaNext = onMediaNext,
                        onMediaPrev = onMediaPrev,
                        onMediaVolume = onMediaVolume,
                        onMediaSeek = onMediaSeek,
                        onBrowse = onBrowse,
                        onTakeoverDismiss = onTakeoverDismiss,
                        onTakeoverRestore = onTakeoverRestore,
                        miniPausedSinceMs = miniPausedSinceMs,
                        onMediaCycleRepeat = onMediaCycleRepeat,
                        onMediaToggleShuffle = onMediaToggleShuffle,
                        // Heart shows on a SendSpin source with a live MA socket (companion sources
                        // can't be resolved by MA). favVersion bump = immediate refetch after a tap.
                        favorite = favState?.favorite,
                        showFavorite = nowPlaying.sendspin && maConnected,
                        onToggleFavorite = { onFavoriteToggle(favState); favVersion++ },
                        upNext = upNext,
                        onUpNextTap = {
                            openQueueSignal++
                            onSelect(DashView.MEDIA)
                        },
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
                    openQueueSignal = openQueueSignal,
                    onSetRepeat = onMediaSetRepeat,
                    onSetShuffle = onMediaSetShuffle,
                    onFavoriteToggle = onFavoriteToggle,
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
                    val graph = remember(entities, config.entities.solar) { solarFlowGraph(config.entities.solar, entities) }
                    SolarPanel(graph)
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
