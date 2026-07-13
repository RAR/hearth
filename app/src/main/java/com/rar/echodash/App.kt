package com.rar.echodash

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.camera.DoorbellCoordinator
import com.rar.echodash.camera.DoorbellPopup
import com.rar.echodash.camera.PopupCommand
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.ConfigStore
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityHub
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.photos.AndroidPhotoDownloader
import com.rar.echodash.photos.PhotoStore
import com.rar.echodash.ui.DashView
import com.rar.echodash.ui.DashboardShell
import com.rar.echodash.ui.DoorbellPopupView
import com.rar.echodash.ui.IdleReturnTimer
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
import com.rar.echodash.ui.TimerChips
import com.rar.echodash.ui.TimerFinishedOverlay
import com.rar.echodash.ui.VoiceOverlay
import com.rar.echodash.ui.theme.EchoTheme
import com.rar.echodash.vaca.AndroidKioskDevice
import com.rar.echodash.vaca.AnnouncePlayer
import com.rar.echodash.vaca.AndroidPcmSink
import com.rar.echodash.vaca.ExoPlayerEngine
import com.rar.echodash.vaca.KioskController
import com.rar.echodash.vaca.LightSensorReporter
import com.rar.echodash.vaca.MediaBridge
import com.rar.echodash.media.NowPlayingStore
import com.rar.echodash.media.ArtFetcher
import com.rar.echodash.night.NightModeController
import com.rar.echodash.vaca.NsdAdvertiser
import com.rar.echodash.vaca.VacaOutgoing
import com.rar.echodash.vaca.VacaServer
import com.rar.echodash.voice.MicStreamer
import com.rar.echodash.voice.SatelliteServer
import com.rar.echodash.voice.TimerChime
import com.rar.echodash.voice.TimersUiState
import com.rar.echodash.voice.VoiceOverlayPhase
import com.rar.echodash.voice.VoiceOverlayState
import com.rar.echodash.web.ConfigServer
import com.rar.echodash.web.SessionManager
import com.rar.echodash.web.SetupCoordinator
import com.rar.echodash.web.buildEntityListJson
import com.rar.echodash.web.generatePin
import com.rar.echodash.web.localIpAddress
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/** Process-wide dependencies; owned by EchoDashApplication, created on the main thread. */
class AppDeps(context: Context) {
    private val appContext = context.applicationContext

    /** Latest ambient-light reading (lux) for the config page's live display; null when no sensor. */
    @Volatile var lastLux: Int? = null

    val settings: SettingsStore = PrefsSettingsStore(appContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)
    val configStore = ConfigStore(appContext.filesDir)
    val entityHub = EntityHub(ws, scope, configStore.config)
    val streamResolver = StreamResolver(
        requestStream = { entityId -> entityHub.cameraStream(entityId) },
        baseUrl = { settings.baseUrl },
    )

    private val photoCacheDir = File(appContext.cacheDir, "photos")
    private val photoDownloader = AndroidPhotoDownloader(ws, client, { settings.baseUrl }, photoCacheDir)
    val photoStore = PhotoStore(ws, photoDownloader, photoCacheDir, scope, configStore.config)

    val sessions = SessionManager()
    val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val setup = SetupCoordinator(auth, onConfigured = { setupEvents.tryEmit(Unit) })
    private val ensuredPin: String by lazy {
        settings.configPin ?: generatePin().also { settings.configPin = it }
    }
    val configServer = ConfigServer(
        store = configStore,
        sessions = sessions,
        pin = { configPin() },
        entitiesJson = { buildEntityListJson(entityHub.registry.value, entityHub.entities.value) },
        setup = setup,
        configured = { settings.refreshToken != null },
        connState = { ws.connectionState.value.name },
        previewChime = { tone, volume -> timerChime.playOnce(tone, volume) },
        assetReader = { path ->
            runCatching { appContext.assets.open("config/$path").readBytes() }.getOrNull()
        },
    )
    private var serverStarted = false

    /** The 6-digit config PIN (generated once, persisted). */
    fun configPin(): String = ensuredPin

    /** The config page URL to show the user (best-effort LAN IP). */
    fun configUrl(): String = "http://${localIpAddress() ?: "device-ip"}:8080"

    /** Start the embedded config web server. Runs for the app's lifetime, independent of HA auth. */
    fun startConfigServer() {
        if (!serverStarted) {
            serverStarted = runCatching { configServer.start() }
                .onFailure { android.util.Log.w("AppDeps", "config server failed to start (port 8080 in use?)", it) }
                .isSuccess
        }
    }

    /** Stop the config server (on logout). */
    fun stopConfigServer() {
        if (serverStarted) { configServer.stop(); serverStarted = false }
    }

    // --- VACA ---
    val kioskUi = KioskUiState()
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val kioskDevice = AndroidKioskDevice(kioskUi) { ws.stop(); ws.start() }
    val kiosk = KioskController(
        mainScope,
        kioskDevice,
        persist = { settings.vacaSettingsJson = it },
        restoredJson = settings.vacaSettingsJson,
    )
    val nightMode = NightModeController()
    private val mediaEngine = ExoPlayerEngine(appContext)
    val nowPlaying = NowPlayingStore()
    val artFetcher = ArtFetcher(
        scope = scope,
        http = client,
        baseUrl = { settings.baseUrl },
        token = { runCatching { auth.validAccessToken() }.getOrNull() },
    )
    val media = MediaBridge(mediaEngine, nowPlaying) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
    val announce = AnnouncePlayer(
        scope,
        AndroidPcmSink(),
        onPlayed = { scope.launch { vaca.sendPlayed() } },
        setDucking = { ducked -> mainScope.launch { media.setDucked(ducked) } },
    )
    val lightSensor = LightSensorReporter(appContext) { lux ->
        lastLux = lux.toInt()
        mainScope.launch {
            kiosk.onLightLevel(lux)
            nightMode.onLux(lux, SystemClock.elapsedRealtime())
        }
        scope.launch {
            vaca.sendStatus(buildJsonObject {
                putJsonObject("sensors") { put("light", lux.toInt()) }
            })
        }
    }
    val vaca: VacaServer = VacaServer(
        scope = scope,
        infoEvent = { VacaOutgoing.info(BuildConfig.VERSION_NAME) },
        capabilitiesEvent = {
            VacaOutgoing.capabilities(
                VacaOutgoing.buildCapabilities(BuildConfig.VERSION_NAME, lightSensor.hasSensor)
            )
        },
        listener = object : VacaServer.Listener {
            override fun onSessionStarted() {
                announce.onDisconnected()
                mainScope.launch {
                    vaca.sendSettingsFeedback(kiosk.currentSettings())
                    vaca.sendStatus(statusSnapshot())
                }
            }
            override fun onSettings(settings: JsonObject) {
                mainScope.launch {
                    kiosk.applySettings(settings)
                    media.applySettings(settings)
                }
            }
            override fun onAction(action: String, payload: JsonElement?) {
                mainScope.launch {
                    if (!media.handleAction(action, payload)) {
                        kiosk.handleAction(action, payload)
                    }
                }
            }
            override fun onAudioStart(rate: Int, width: Int, channels: Int) =
                announce.onAudioStart(rate, width, channels)
            override fun onAudioChunk(pcm: ByteArray) = announce.onAudioChunk(pcm)
            override fun onAudioStop() = announce.onAudioStop()
            override fun onSessionEnded() = announce.onDisconnected()
        },
    )
    private val nsd = NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT)

    // --- Voice satellite (Wyoming) ---
    val voiceOverlay = MutableStateFlow(VoiceOverlayState())
    val timersUi = MutableStateFlow(TimersUiState())
    val timerChime = TimerChime()
    private val voiceSink = AndroidPcmSink()
    private val voicePlayer = AnnouncePlayer(
        scope,
        voiceSink,
        onPlayed = { satellite.onPlaybackFinished() },
        setDucking = { ducked -> mainScope.launch { media.setDucked(ducked) } },
    )
    private val micStreamer = MicStreamer(
        onChunk = { pcm -> satellite.submitMicChunk(pcm) },
        onError = { satellite.reportMicError() },
    )
    val satellite: SatelliteServer = SatelliteServer(
        scope = scope,
        appVersion = BuildConfig.VERSION_NAME,
        out = object : SatelliteServer.Out {
            override fun onStartMic() = micStreamer.start()
            override fun onStopMic() = micStreamer.stop()
            override fun onPlaybackStart(rate: Int, width: Int, channels: Int) =
                voicePlayer.onAudioStart(rate, width, channels)
            override fun onPlaybackChunk(pcm: ByteArray) = voicePlayer.onAudioChunk(pcm)
            override fun onPlaybackStop() = voicePlayer.onAudioStop()
            override fun onOverlay(state: VoiceOverlayState) { voiceOverlay.value = state }
            override fun onTimers(state: TimersUiState) { timersUi.value = state }
        },
    )
    private val voiceNsd = NsdAdvertiser(appContext, SatelliteServer.PORT, "_wyoming._tcp.")

    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
        artFetcher.start(nowPlaying.state)
    }

    /** Start the HA connection, entity hub, and photo sync. */
    fun startDashboard() {
        entityHub.start()
        photoStore.start(ws.connectionState)
        ws.start()
    }

    fun startVaca() {
        vaca.start()
        nsd.register()
        lightSensor.start()
    }

    /** Reactively run the voice satellite while config.voice.enabled; no app restart needed. */
    fun startVoice() {
        scope.launch {
            configStore.config
                .map { it.voice.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        satellite.start()
                        voiceNsd.register()
                    } else {
                        voiceNsd.unregister()
                        satellite.stop()
                        micStreamer.stop()
                        timerChime.stop()
                        voiceOverlay.value = VoiceOverlayState()
                        timersUi.value = TimersUiState()
                    }
                }
        }
    }

    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_path", "dashboard")
        }
    }
}

sealed interface Screen {
    data object Setup : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen =
    if (settings.refreshToken == null) Screen.Setup else Screen.Dashboard

@Composable
fun EchoDashApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    LaunchedEffect(Unit) {
        deps.setupEvents.collect { screen = Screen.Dashboard }
    }

    EchoTheme {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Setup -> SetupScreen(
                    configUrl = remember { deps.configUrl() },
                    configPin = remember { deps.configPin() },
                )
                Screen.Dashboard -> {
                    LaunchedEffect(Unit) { deps.startDashboard() }
                    val entities by deps.entityHub.entities.collectAsStateWithLifecycle()
                    val registry by deps.entityHub.registry.collectAsStateWithLifecycle()
                    val photos by deps.photoStore.photos.collectAsStateWithLifecycle()
                    val config by deps.configStore.config.collectAsStateWithLifecycle()
                    val nowPlayingState by deps.nowPlaying.state.collectAsStateWithLifecycle()
                    val art by deps.artFetcher.art.collectAsStateWithLifecycle()
                    val configUrl = remember { deps.configUrl() }
                    val configPinValue = remember { deps.configPin() }
                    var view by remember { mutableStateOf(DashView.HOME) }
                    val uiScope = rememberCoroutineScope()
                    val idleSeconds = config.home.idleReturnSeconds
                    val idleTimer = remember(idleSeconds) {
                        IdleReturnTimer(uiScope, timeoutMs = idleSeconds * 1000L) { view = DashView.HOME }
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
                    val takeoverVisible = nowPlayingState.active && !pausedTimedOut

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
                    // or the configured night brightness changes.
                    LaunchedEffect(nightActive, config.night.brightness) {
                        deps.kiosk.setNightDim(nightActive, config.night.brightness)
                    }

                    DashboardShell(
                        current = view,
                        onSelect = { v ->
                            view = v
                            deps.kiosk.onUserInteraction()
                        },
                        config = config,
                        entities = entities,
                        registry = registry,
                        connState = connState,
                        photos = photos,
                        nowPlaying = nowPlayingState,
                        art = art,
                        takeoverVisible = takeoverVisible,
                        onToggle = { id -> deps.entityHub.callService("homeassistant", "toggle", entityId = id) },
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
                        onMediaPlay = { deps.mainScope.launch { deps.media.handleAction("play", null) } },
                        onMediaPause = { deps.mainScope.launch { deps.media.handleAction("pause", null) } },
                        onMediaStop = { deps.mainScope.launch { deps.media.handleAction("stop", null) } },
                        onMediaNext = {
                            config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_next_track", entityId = it)
                            }
                        },
                        onMediaPrev = {
                            config.media.companionEntity?.let {
                                deps.entityHub.callService("media_player", "media_previous_track", entityId = it)
                            }
                        },
                        onMediaVolume = { vol ->
                            deps.mainScope.launch {
                                deps.media.handleAction("set-volume", buildJsonObject { put("volume", vol) })
                            }
                        },
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
                    )

                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
                    // Overrides suppress night mode at normal brightness: music takeover, doorbell
                    // popup, voice interaction, or any active/alerting timer.
                    LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState, timersState) {
                        deps.nightMode.onOverride(
                            takeoverVisible ||
                                doorbellPopup != null ||
                                voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN ||
                                timersState.chips.isNotEmpty() ||
                                timersState.alert != null,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                    LaunchedEffect(voiceOverlayState.phase) {
                        if (voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN) {
                            deps.kiosk.onUserInteraction()   // wakes screen + counts as activity
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
                    TimerChips(timersState)
                    VoiceOverlay(voiceOverlayState)
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
        }
    }
}
