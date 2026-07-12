package com.rar.echodash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.rar.echodash.ui.IdleReturnTimer
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
import com.rar.echodash.ui.theme.EchoTheme
import com.rar.echodash.vaca.AndroidKioskDevice
import com.rar.echodash.vaca.AnnouncePlayer
import com.rar.echodash.vaca.AndroidPcmSink
import com.rar.echodash.vaca.ExoPlayerEngine
import com.rar.echodash.vaca.KioskController
import com.rar.echodash.vaca.LightSensorReporter
import com.rar.echodash.vaca.MediaBridge
import com.rar.echodash.vaca.NsdAdvertiser
import com.rar.echodash.vaca.VacaOutgoing
import com.rar.echodash.vaca.VacaServer
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
import kotlinx.coroutines.flow.MutableSharedFlow
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

    val settings: SettingsStore = PrefsSettingsStore(appContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)
    val configStore = ConfigStore(appContext.filesDir)
    val entityHub = EntityHub(ws, scope, configStore.config)

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
    private val mediaEngine = ExoPlayerEngine(appContext)
    val media = MediaBridge(mediaEngine) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
    val announce = AnnouncePlayer(
        scope,
        AndroidPcmSink(),
        onPlayed = { scope.launch { vaca.sendPlayed() } },
        setDucking = { ducked -> mainScope.launch { media.setDucked(ducked) } },
    )
    val lightSensor = LightSensorReporter(appContext) { lux ->
        mainScope.launch { kiosk.onLightLevel(lux) }
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

    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
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
                    val mediaUi by deps.media.ui.collectAsStateWithLifecycle()
                    val config by deps.configStore.config.collectAsStateWithLifecycle()
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
                        mediaUi = mediaUi,
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
                        },
                    )
                }
            }
            KioskOverlays(deps.kioskUi, onWakeTouch = { deps.kiosk.onUserInteraction() })
        }
    }
}
