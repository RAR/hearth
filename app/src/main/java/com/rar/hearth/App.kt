package com.rar.hearth

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.hearth.camera.DoorbellCoordinator
import com.rar.hearth.camera.DoorbellPopup
import com.rar.hearth.camera.PopupCommand
import com.rar.hearth.data.SettingsStore
import com.rar.hearth.ha.ConnState
import com.rar.hearth.ui.DashView
import com.rar.hearth.ui.DashboardShell
import com.rar.hearth.ui.DoorbellPopupView
import com.rar.hearth.ui.IdleReturnTimer
import com.rar.hearth.ui.KioskOverlays
import com.rar.hearth.ui.SetupScreen
import com.rar.hearth.ui.SplashScreen
import com.rar.hearth.ui.splashDone
import com.rar.hearth.ui.TimerChips
import com.rar.hearth.ui.TimerFinishedOverlay
import com.rar.hearth.ui.TimersTakeoverView
import com.rar.hearth.ui.VoiceOverlay
import com.rar.hearth.ui.WakeGlow
import com.rar.hearth.ui.model.CalendarEvent
import com.rar.hearth.ui.model.FavoriteAction
import com.rar.hearth.ui.model.TimerTakeoverModel
import com.rar.hearth.ui.model.favoriteToggleAction
import com.rar.hearth.ui.model.nextRepeatMode
import com.rar.hearth.ui.model.parseCalendarEvents
import com.rar.hearth.ui.model.pushedNotificationItems
import com.rar.hearth.ui.model.quickButtonService
import com.rar.hearth.ui.model.takeoverVisibleOf
import com.rar.hearth.ui.theme.HearthTheme
import com.rar.hearth.device.DuckSource
import com.rar.hearth.device.KioskController
import com.rar.hearth.voice.VoiceOverlayPhase
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The top-level UI: screen routing, the splash overlay, and the per-session state
// that has to be hoisted above the shell Crossfade to survive a view switch.
// The subsystem wiring it draws on lives in AppDeps.kt.

sealed interface Screen {
    data object Setup : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen =
    if (settings.refreshToken == null) Screen.Setup else Screen.Dashboard

@Composable
fun HearthApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()
    val configPin by deps.pinState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    LaunchedEffect(Unit) {
        deps.setupEvents.collect { screen = Screen.Dashboard }
    }

    LaunchedEffect(Unit) {
        deps.logoutEvents.collect { deps.ws.stop(); screen = Screen.Setup }
    }

    // Startup brand splash: cover the app with the logo + "Hearth" wordmark until HA connects
    // (or a short cap), then fade out. Continues the window-background splash the OS shows
    // pre-first-frame — same dark panel and logo — adding the text the window background can't draw.
    var showSplash by remember { mutableStateOf(true) }
    val connectedNow = rememberUpdatedState(connState == ConnState.CONNECTED)
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (!splashDone(System.currentTimeMillis() - start, connectedNow.value)) {
            delay(50)
        }
        showSplash = false
    }

    HearthTheme {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Setup -> SetupScreen(
                    configUrl = remember { deps.configUrl() },
                    configPin = configPin,
                )
                Screen.Dashboard -> {
                    LaunchedEffect(Unit) { deps.startDashboard() }
                    val entities by deps.entityHub.entities.collectAsStateWithLifecycle()
                    val registry by deps.entityHub.registry.collectAsStateWithLifecycle()
                    val photo by deps.photoStore.current.collectAsStateWithLifecycle()
                    val config by deps.configStore.config.collectAsStateWithLifecycle()
                    val nowPlayingState by deps.nowPlaying.state.collectAsStateWithLifecycle()
                    val art by deps.artFetcher.art.collectAsStateWithLifecycle()
                    val pushedRaw by deps.pushStore.items.collectAsStateWithLifecycle()
                    val pushed = remember(pushedRaw) { pushedNotificationItems(pushedRaw) }
                    // Prune expired pushes: while any has an expiry, wake at the nearest one (capped
                    // at 30 s so a far-future expiry still gets re-checked), then prune. A prune that
                    // changes the list re-emits and relaunches this effect; the 30 s cap re-check is
                    // handled by re-reading the store's current value each loop iteration.
                    LaunchedEffect(pushedRaw) {
                        while (true) {
                            val current = deps.pushStore.items.value
                            val expiries = current.mapNotNull { it.expiresAtMs }
                            if (expiries.isEmpty()) break
                            val wait = (expiries.min() - System.currentTimeMillis())
                                .coerceAtMost(30_000L).coerceAtLeast(250L)
                            delay(wait)
                            deps.pushStore.prune(System.currentTimeMillis())
                        }
                    }
                    // Calendar events at Dashboard scope so the home card has data without opening
                    // the panel. Immediate fetch, then every 5 minutes. Calendars are fetched one
                    // per call — HA fails a batched get_events entirely when any one calendar
                    // errors, which would let a single broken calendar freeze the rest. Per
                    // calendar, a failed fetch (null) keeps its last good events while a non-null
                    // response updates them (empty clears). No configured calendars -> no fetch.
                    var calendarEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
                    LaunchedEffect(config.entities.calendars) {
                        val cals = config.entities.calendars
                        if (cals.isEmpty()) {
                            calendarEvents = emptyList()
                            return@LaunchedEffect
                        }
                        val lastGood = HashMap<String, List<CalendarEvent>>()
                        while (true) {
                            var anyFailed = false
                            for (cal in cals) {
                                val result = deps.entityHub.getCalendarEvents(listOf(cal.entity))
                                if (result != null) {
                                    lastGood[cal.entity] =
                                        parseCalendarEvents(result, listOf(cal), ZoneId.systemDefault())
                                } else {
                                    anyFailed = true
                                }
                                // Publish after every calendar, not after the sweep: a slow or
                                // erroring calendar must not delay the ones already fetched.
                                calendarEvents = cals.flatMap { lastGood[it.entity].orEmpty() }
                                    .sortedBy { it.startMs }
                            }
                            // A sweep that failed before ANY calendar ever succeeded is almost
                            // always the cold-start race with the websocket connect — retry
                            // quickly until first data lands, then settle into the 5-min cadence.
                            delay(if (anyFailed && lastGood.isEmpty()) 15_000L else 5 * 60_000L)
                        }
                    }
                    val configUrl = remember { deps.configUrl() }
                    val configPinValue = configPin
                    val view by deps.currentView.collectAsStateWithLifecycle()
                    val uiScope = rememberCoroutineScope()
                    val idleSeconds = config.home.idleReturnSeconds
                    val idleTimer = remember(idleSeconds) {
                        IdleReturnTimer(uiScope, timeoutMs = idleSeconds * 1000L) { deps.currentView.value = DashView.HOME }
                    }
                    DisposableEffect(idleTimer) { onDispose { idleTimer.cancel() } }
                    LaunchedEffect(idleTimer, view) { idleTimer.onViewChanged(view == DashView.HOME) }

                    // Feed the companion media_player entity (config-driven watched set) into the
                    // store. Null when unconfigured or not yet loaded -> local metadata fallback.
                    LaunchedEffect(entities, config.media.companionEntity) {
                        deps.nowPlaying.onEntity(config.media.companionEntity?.let { entities[it] })
                    }

                    // After a configurable paused period, hide the home takeover (photos return)
                    // while keeping the paused session alive underneath — resume brings it back.
                    var pausedTimedOut by remember { mutableStateOf(false) }
                    LaunchedEffect(nowPlayingState.active, nowPlayingState.playing, config.media.pausedDismissSeconds) {
                        if (nowPlayingState.active && !nowPlayingState.playing) {
                            delay(config.media.pausedDismissSeconds * 1000L)
                            pausedTimedOut = true
                        } else {
                            pausedTimedOut = false
                        }
                    }
                    // The user can dismiss the takeover from its home button; the dismissal sticks
                    // for the whole listening session (track changes never resurrect it). Session
                    // end (active -> false) clears it so the NEXT session takes over again.
                    var manualDismissed by remember { mutableStateOf(false) }
                    LaunchedEffect(nowPlayingState.active) {
                        if (!nowPlayingState.active) manualDismissed = false
                    }
                    val takeoverVisible = takeoverVisibleOf(nowPlayingState.active, pausedTimedOut, manualDismissed)

                    // Now-playing card pause-grace state. Declared HERE (App scope), not inside
                    // HomeView, because DashboardShell renders views through a Crossfade that DISPOSES
                    // the outgoing view's subtree -- including on the routine IdleReturnTimer auto-
                    // return to Home. State living inside HomeView would remount on every such
                    // switch, restamping pausedSinceMs to "now" (breaking the 5-minute grace measured
                    // from the real pause instant). Same reasoning as manualDismissed/pausedTimedOut
                    // above, which is why this lives right beside them.
                    var miniPausedSinceMs by remember { mutableStateOf(0L) }
                    LaunchedEffect(nowPlayingState.active, nowPlayingState.playing) {
                        miniPausedSinceMs = if (nowPlayingState.active && !nowPlayingState.playing) {
                            System.currentTimeMillis()
                        } else {
                            0L
                        }
                    }

                    // Hold the screen awake while music is actively playing (not while paused, so a
                    // paused player still lets the backlight sleep). Only wakes the screen; the
                    // idle-return timer is intentionally NOT poked, so a panel still returns Home.
                    LaunchedEffect(nowPlayingState.playing) {
                        if (nowPlayingState.playing) {
                            while (true) {
                                deps.kiosk.onUserInteraction()
                                delay(5_000)
                            }
                        }
                    }

                    val doorbellCoordinator = remember { DoorbellCoordinator() }
                    var doorbellPopup by remember { mutableStateOf<DoorbellPopup?>(null) }
                    LaunchedEffect(entities, config.entities.doorbells, config.panelOptions.doorbellPopupSeconds) {
                        val cmd = doorbellCoordinator.onStates(
                            config.entities.doorbells,
                            entities,
                            config.panelOptions.doorbellPopupSeconds,
                            System.currentTimeMillis(),
                        )
                        if (cmd is PopupCommand.Show) {
                            doorbellPopup = DoorbellPopup(cmd.cameraName, cmd.untilMs)
                        }
                    }

                    // While a popup is visible, keep re-arming the screen/idle timeouts so the
                    // backlight can't blank mid-ring even when the configured timeout is shorter
                    // than the popup duration. Cancels on dismissal, so the prior state resumes.
                    LaunchedEffect(doorbellPopup) {
                        if (doorbellPopup != null) {
                            while (true) {
                                deps.kiosk.onUserInteraction()
                                idleTimer.onInteraction()
                                delay(5000L)
                            }
                        }
                    }

                    // The popup's porch audio plays unmuted over whatever music is up (SendSpin or
                    // radio) -- duck the music under it like an announce, via the same fan-out that
                    // covers both. Keyed on the VISIBILITY boolean, not the popup object, so a
                    // popup-to-popup replacement (second ring extending the first) doesn't flap the
                    // duck; onDispose guarantees the claim releases on tap-dismiss, timeout, and
                    // composition teardown alike.
                    DisposableEffect(doorbellPopup != null) {
                        if (doorbellPopup != null) deps.media.setDucked(DuckSource.DOORBELL, true)
                        onDispose { deps.media.setDucked(DuckSource.DOORBELL, false) }
                    }

                    val nightActive by deps.nightMode.nightActive.collectAsStateWithLifecycle()
                    val nightTicking by deps.nightMode.ticking.collectAsStateWithLifecycle()
                    LaunchedEffect(config.night) {
                        deps.nightMode.onConfig(config.night.enabled, config.night.thresholdLux)
                    }
                    // Re-evaluation ticker: only runs while night is active or an entry is being
                    // held off (touch-hold/override in a dark room), so touch-hold expiry still
                    // fires when the room is silent and dark. No ticker when fully off.
                    LaunchedEffect(nightTicking) {
                        if (nightTicking) {
                            while (true) {
                                deps.nightMode.onTick(SystemClock.elapsedRealtime())
                                delay(5_000)
                            }
                        }
                    }
                    // Brightness mirror: KioskController pins/releases the backlight as night flips
                    // or the configured night brightness changes. Released on dispose so leaving the
                    // Dashboard (logout -> Setup) never strands the screen at night brightness.
                    LaunchedEffect(nightActive, config.night.brightness) {
                        deps.kiosk.setNightDim(nightActive, config.night.brightness)
                    }
                    DisposableEffect(Unit) {
                        onDispose { deps.kiosk.setNightDim(false, 0) }
                    }

                    DashboardShell(
                        current = view,
                        onSelect = { v ->
                            deps.currentView.value = v
                            deps.kiosk.onUserInteraction()
                        },
                        config = config,
                        entities = entities,
                        registry = registry,
                        connState = connState,
                        photo = photo,
                        onPhotoAdvance = { deps.photoStore.advance() },
                        onPhotoBack = { deps.photoStore.back() },
                        nowPlaying = nowPlayingState,
                        art = art,
                        takeoverVisible = takeoverVisible,
                        onToggle = { id -> deps.entityHub.callService("homeassistant", "toggle", entityId = id) },
                        onQuickButton = { qb ->
                            val (domain, service) = quickButtonService(qb.entityId)
                            deps.entityHub.callService(domain, service, entityId = qb.entityId)
                        },
                        onSetTemperature = { id, temp ->
                            deps.entityHub.callService(
                                "climate", "set_temperature",
                                serviceData = buildJsonObject { put("temperature", temp) },
                                entityId = id,
                            )
                        },
                        onSetHvacMode = { id, mode ->
                            deps.entityHub.callService(
                                "climate", "set_hvac_mode",
                                serviceData = buildJsonObject { put("hvac_mode", mode) },
                                entityId = id,
                            )
                        },
                        // When SendSpin owns playback, route transport to the SendSpin server
                        // (Music Assistant); otherwise drive the ExoPlayer/companion media_player.
                        onMediaPlay = {
                            if (nowPlayingState.sendspin) deps.sendspin.transportPlay()
                            else deps.mainScope.launch { deps.media.handleAction("play", null) }
                        },
                        onMediaPause = {
                            if (nowPlayingState.sendspin) deps.sendspin.transportPause()
                            else deps.mainScope.launch { deps.media.handleAction("pause", null) }
                        },
                        onMediaStop = {
                            if (nowPlayingState.sendspin) deps.sendspin.transportStop()
                            else deps.mainScope.launch { deps.media.handleAction("stop", null) }
                        },
                        onMediaNext = {
                            if (nowPlayingState.sendspin) deps.sendspin.transportNext()
                            else config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_next_track", entityId = it)
                            }
                        },
                        onMediaPrev = {
                            if (nowPlayingState.sendspin) deps.sendspin.transportPrev()
                            else config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_previous_track", entityId = it)
                            }
                        },
                        onMediaVolume = { vol ->
                            if (nowPlayingState.sendspin) deps.sendspin.transportVolume(vol)
                            else deps.mainScope.launch {
                                deps.media.handleAction("set-volume", buildJsonObject { put("volume", vol) })
                            }
                        },
                        // Seek is companion-only: the UI never offers it for SendSpin (MA has no seek
                        // command), so that branch is a no-op. seek_position is in seconds.
                        onMediaSeek = { secs ->
                            if (nowPlayingState.sendspin) Unit
                            else config.media.companionEntity?.let {
                                deps.entityHub.callService(
                                    "media_player", "media_seek",
                                    serviceData = buildJsonObject { put("seek_position", secs) },
                                    entityId = it,
                                )
                            }
                        },
                        // SendSpin-only: cycle group repeat / toggle group shuffle. Companion
                        // media_player has no queue concept here, so the else branch is a no-op.
                        onMediaCycleRepeat = {
                            if (nowPlayingState.sendspin)
                                deps.sendspin.transportSetRepeat(nextRepeatMode(nowPlayingState.repeatMode))
                        },
                        onMediaToggleShuffle = {
                            if (nowPlayingState.sendspin)
                                deps.sendspin.transportSetShuffle(!(nowPlayingState.shuffle ?: false))
                        },
                        // Queue-pane chips (MusicBrowser), deliberately NOT gated on
                        // nowPlayingState.sendspin: they compute the next value from the queue's own
                        // MaQueueState, not the local now-playing source, and the SendSpin socket
                        // controls the group queue regardless of which source is locally active.
                        // sendSpin?.setRepeatMode/setShuffle's own null-check is the real gate.
                        onMediaSetRepeat = { mode -> deps.sendspin.transportSetRepeat(mode) },
                        onMediaSetShuffle = { enabled -> deps.sendspin.transportSetShuffle(enabled) },
                        // Favorite/un-favorite the current song. Shared by the takeover heart and
                        // the queue-pane heart (each passes the item its own poll saw). The pure
                        // decision picks add vs remove; the op runs on the app scope (MA-socket I/O).
                        onFavoriteToggle = { favItem ->
                            deps.mainScope.launch {
                                when (val action = favoriteToggleAction(favItem)) {
                                    FavoriteAction.Add -> deps.maLibrary.favoriteCurrentSong()
                                    is FavoriteAction.Remove ->
                                        deps.maLibrary.unfavorite(action.mediaType, action.libraryItemId)
                                }
                            }
                        },
                        library = deps.maLibrary,
                        thumbs = deps.maThumbs,
                        // Takeover's browse button: land on the MEDIA view's library browser
                        // (mirrors onSelect above, incl. the kiosk interaction poke).
                        onBrowse = {
                            deps.currentView.value = DashView.MEDIA
                            deps.kiosk.onUserInteraction()
                        },
                        // Takeover home button hides the takeover for the rest of the session
                        // (music keeps playing); the pinned now-playing row's tap restores it.
                        // Restore also clears the paused-timeout dismissal, so the row is the way
                        // back from that path too (which otherwise has no on-device re-entry).
                        onTakeoverDismiss = { manualDismissed = true },
                        onTakeoverRestore = { manualDismissed = false; pausedTimedOut = false },
                        miniPausedSinceMs = miniPausedSinceMs,
                        fetchForecast = { id -> deps.entityHub.getForecasts(id) },
                        configUrl = configUrl,
                        configPin = configPinValue,
                        onLogout = {
                            deps.ws.stop()
                            deps.settings.clearAuth()
                            screen = Screen.Setup
                        },
                        onInteraction = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                            deps.nightMode.onUserInteraction(SystemClock.elapsedRealtime())
                        },
                        streamResolver = deps.streamResolver,
                        nightActive = nightActive,
                        onNightWake = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                            deps.nightMode.onUserInteraction(SystemClock.elapsedRealtime())
                        },
                        pushed = pushed,
                        onPushDismiss = { id -> deps.pushStore.dismiss(id) },
                        calendarEvents = calendarEvents,
                    )

                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
                    // Kitchen timer takeover: the model maps live chips to render rows and owns
                    // dismiss/re-show + rename. Recompute on every timers emission (and on rev bumps
                    // from dismiss/rename); model.visible is read right after update().
                    val timerTakeover = remember { TimerTakeoverModel() }
                    var timerTakeoverRev by remember { mutableStateOf(0) }
                    val takeoverTimers = remember(timersState, timerTakeoverRev) {
                        timerTakeover.update(timersState.chips)
                    }
                    val timerTakeoverVisible = timerTakeover.visible
                    // Overrides suppress night mode at normal brightness: music takeover, doorbell
                    // popup, voice interaction, or any active/alerting timer.
                    LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState, timersState, timerTakeoverVisible) {
                        deps.nightMode.onOverride(
                            takeoverVisible ||
                                doorbellPopup != null ||
                                voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN ||
                                timersState.chips.any { it.active } ||
                                timersState.alert != null ||
                                timerTakeoverVisible,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                    LaunchedEffect(voiceOverlayState.phase) {
                        if (voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN) {
                            deps.kiosk.onUserInteraction()   // wakes screen + counts as activity
                            idleTimer.onInteraction()
                        }
                    }
                    LaunchedEffect(timerTakeoverVisible) {
                        if (timerTakeoverVisible) {
                            deps.kiosk.onUserInteraction()   // wake the screen when a timer takes over
                            idleTimer.onInteraction()
                        }
                    }
                    // Timer-finished: hold the screen awake + chime for the alert's whole lifetime; stop the
                    // chime when the alert clears (dismiss or auto-silence). One-shot wake is not enough: a
                    // screen_timeout shorter than the 60 s alert would blank mid-alert (same fix as the
                    // doorbell popup's re-arm loop).
                    val alerting = timersState.alert != null
                    LaunchedEffect(alerting) {
                        if (alerting) {
                            deps.timerChime.start(config.voice.timerTone, config.voice.timerVolume)
                            while (true) {
                                deps.kiosk.onUserInteraction()
                                idleTimer.onInteraction()
                                delay(5_000)
                            }
                        } else {
                            deps.timerChime.stop()
                        }
                    }
                    DisposableEffect(Unit) { onDispose { deps.timerChime.stop() } }
                    if (timerTakeoverVisible) {
                        TimersTakeoverView(
                            timers = takeoverTimers,
                            onDismiss = { timerTakeover.dismiss(); timerTakeoverRev++ },
                            onRename = { id, label -> timerTakeover.rename(id, label); timerTakeoverRev++ },
                        )
                    } else {
                        TimerChips(timersState)
                    }
                    WakeGlow(voiceOverlayState.phase == VoiceOverlayPhase.LISTENING)
                    VoiceOverlay(voiceOverlayState, onTap = { deps.satellite.onOverlayTapped() })
                    timersState.alert?.let { alert ->
                        key(alert) {
                            TimerFinishedOverlay(alert, onDismiss = { deps.satellite.dismissTimerAlert() })
                        }
                    }

                    doorbellPopup?.let { popup ->
                        DoorbellPopupView(
                            popup = popup,
                            camera = config.entities.cameras.find { it.name == popup.cameraName },
                            resolver = deps.streamResolver,
                            onDismiss = { doorbellPopup = null },
                        )
                    }
                }
            }
            KioskOverlays(deps.kioskUi, onWakeTouch = { deps.kiosk.onUserInteraction() })

            AnimatedVisibility(
                visible = showSplash,
                enter = EnterTransition.None,
                exit = fadeOut(tween(400)),
            ) {
                SplashScreen()
            }
        }
    }
}
