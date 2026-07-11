package com.rar.echodash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.ui.DashboardScreen
import com.rar.echodash.ui.EntityPickerScreen
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // --- VACA ---
    // Lambdas below reference `vaca`, declared last; they only run after
    // construction completes (playback/session events), never during init.
    val kioskUi = KioskUiState()
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val kioskDevice = AndroidKioskDevice(kioskUi) {
        settings.temperatureEntityId?.let { entity ->
            ws.stop()
            ws.start(entity)
        }
    }
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
                // A new session can be adopted while the old connection is still
                // half-open, so the previous onSessionEnded may never fire. Reset
                // announce state first — a brand-new session cannot have an
                // in-flight announce stream, so this only clears stale ducking/track.
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
    data object Picker : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen = when {
    settings.refreshToken == null -> Screen.Setup
    settings.temperatureEntityId == null -> Screen.Picker
    else -> Screen.Dashboard
}

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

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Setup -> SetupScreen(deps.settings, deps.auth) {
                    screen = Screen.Picker
                }
                Screen.Picker -> EntityPickerScreen(deps.settings, deps.ws) {
                    screen = Screen.Dashboard
                }
                Screen.Dashboard -> {
                    LaunchedEffect(Unit) { deps.ws.start(deps.settings.temperatureEntityId) }
                    val reading by deps.ws.reading.collectAsStateWithLifecycle()
                    DashboardScreen(
                        reading = reading,
                        connState = connState,
                        onChangeSensor = { screen = Screen.Picker },
                        onLogout = {
                            deps.ws.stop()
                            deps.settings.clearAuth()
                            screen = Screen.Setup
                        },
                    )
                }
            }
            KioskOverlays(deps.kioskUi, onWakeTouch = { deps.kiosk.onUserInteraction() })
        }
    }
}
