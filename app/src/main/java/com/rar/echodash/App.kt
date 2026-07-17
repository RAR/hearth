package com.rar.echodash

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
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
import com.rar.echodash.photos.photoCacheDirName
import com.rar.echodash.photos.photoTarget
import com.rar.echodash.photos.stalePhotoCacheDirs
import com.rar.echodash.ui.DashView
import com.rar.echodash.ui.DashboardShell
import com.rar.echodash.ui.DoorbellPopupView
import com.rar.echodash.ui.IdleReturnTimer
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
import com.rar.echodash.ui.SplashScreen
import com.rar.echodash.ui.splashDone
import com.rar.echodash.ui.TimerChips
import com.rar.echodash.ui.TimerFinishedOverlay
import com.rar.echodash.ui.VoiceOverlay
import com.rar.echodash.ui.WakeGlow
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.parseCalendarEvents
import com.rar.echodash.ui.model.pushedNotificationItems
import com.rar.echodash.ui.model.quickButtonService
import com.rar.echodash.ui.theme.EchoTheme
import com.rar.echodash.vaca.AndroidKioskDevice
import com.rar.echodash.vaca.AnnouncePlayer
import com.rar.echodash.vaca.AndroidPcmSink
import com.rar.echodash.vaca.DashActionParser
import com.rar.echodash.vaca.DuckSource
import com.rar.echodash.vaca.ExoPlayerEngine
import com.rar.echodash.vaca.KioskController
import com.rar.echodash.vaca.LightSensorReporter
import com.rar.echodash.vaca.MediaBridge
import com.rar.echodash.media.NowPlayingStore
import com.rar.echodash.media.ArtFetcher
import com.rar.echodash.media.MaThumbs
import com.rar.echodash.night.NightModeController
import com.rar.echodash.sendspin.MaLibrary
import com.rar.echodash.sendspin.SendspinEndpoint
import com.rar.echodash.sendspin.UserSettings
import com.rar.echodash.vaca.NsdAdvertiser
import com.rar.echodash.vaca.VacaOutgoing
import com.rar.echodash.vaca.VacaServer
import com.rar.echodash.voice.EarconKind
import com.rar.echodash.voice.EarconPlayer
import com.rar.echodash.voice.MicStreamer
import com.rar.echodash.voice.SatelliteServer
import com.rar.echodash.voice.TfliteWakeGraphs
import com.rar.echodash.voice.TimerChime
import com.rar.echodash.voice.TimersUiState
import com.rar.echodash.voice.WakeDetector
import com.rar.echodash.voice.VoiceOverlayPhase
import com.rar.echodash.voice.VoiceOverlayState
import com.rar.echodash.web.ConfigServer
import com.rar.echodash.web.SessionManager
import com.rar.echodash.web.SetupCoordinator
import com.rar.echodash.web.buildEntityListJson
import com.rar.echodash.web.generatePin
import com.rar.echodash.web.generateNotifyToken
import com.rar.echodash.web.localIpAddress
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

    // getRealSize is deprecated in favor of the API 30+ WindowMetrics path, but it still works
    // fine minSdk 28..targetSdk 34 and a version branch isn't worth it just for this.
    @Suppress("DEPRECATION")
    private val screenTarget = run {
        val point = Point()
        val display = (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        display?.getRealSize(point)
        photoTarget(point.x, point.y)
    }
    private val photoCacheDir = File(appContext.cacheDir, photoCacheDirName(screenTarget)).also { dir ->
        // Delete any stale-size (or legacy unstamped) photo caches: frees the old-size cache;
        // the next sync repopulates the current dir at the right size.
        val names = appContext.cacheDir.list()?.toList() ?: emptyList()
        stalePhotoCacheDirs(names, dir.name).forEach { File(appContext.cacheDir, it).deleteRecursively() }
    }
    private val photoDownloader = AndroidPhotoDownloader(ws, client, { settings.baseUrl }, photoCacheDir, screenTarget)
    val photoStore = PhotoStore(ws, photoDownloader, photoCacheDir, scope, configStore.config)

    val sessions = SessionManager()
    val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val setup = SetupCoordinator(auth, onConfigured = { setupEvents.tryEmit(Unit) })
    private val ensuredPin: String by lazy {
        settings.configPin ?: generatePin().also { settings.configPin = it }
    }
    private val ensuredNotifyToken: String by lazy {
        settings.notifyToken ?: generateNotifyToken().also { settings.notifyToken = it }
    }
    val pushStore = com.rar.echodash.notify.PushNotificationStore()
    val configServer = ConfigServer(
        store = configStore,
        sessions = sessions,
        pin = { configPin() },
        notifyToken = { ensuredNotifyToken },
        deviceName = { deviceName() },
        setDeviceName = { applyDeviceName(it) },
        pushStore = pushStore,
        entitiesJson = { buildEntityListJson(entityHub.registry.value, entityHub.entities.value) },
        setup = setup,
        configured = { settings.refreshToken != null },
        connState = { ws.connectionState.value.name },
        lux = { lastLux },
        sendspinStatus = { sendspin.status.value.name },
        // MA sign-in: exchange credentials for a token on the device, persist token + display
        // name BEFORE returning (the browser re-pulls config right after, and the maToken
        // collector in startSendspin() reacts by connecting the library socket).
        maSignIn = { username, password ->
            maLibrary.signIn(username, password).map { r ->
                configStore.update(configStore.config.value.let { c ->
                    c.copy(sendspin = c.sendspin.copy(maToken = r.accessToken, maUser = r.userName))
                })
                r.userName
            }
        },
        maSignOut = {
            configStore.update(configStore.config.value.let { c ->
                c.copy(sendspin = c.sendspin.copy(maToken = "", maUser = ""))
            })
        },
        previewChime = { tone, volume -> timerChime.playOnce(tone, volume) },
        previewEarcon = { volume -> earconPlayer.play("preview", volume) },
        assetReader = { path ->
            runCatching { appContext.assets.open("config/$path").readBytes() }.getOrNull()
        },
    )
    private var serverStarted = false

    /** The 6-digit config PIN (generated once, persisted). */
    fun configPin(): String = ensuredPin

    /** The config page URL to show the user (best-effort LAN IP). */
    fun configUrl(): String = "http://${localIpAddress() ?: "device-ip"}:8080"

    /** Last 4 chars of ANDROID_ID (lowercase hex as returned); "0000" if unavailable. Read once. */
    private val androidIdSuffix: String by lazy {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        if (id.isNullOrBlank()) "0000" else id.takeLast(4)
    }

    /** Single source of truth for the device identity. Custom name verbatim, else computed default. */
    fun deviceName(): String =
        settings.deviceName ?: "Hearth (${Build.MODEL} $androidIdSuffix)"

    /** Persist a clamped name (null = reset to default) and re-announce every live identity. */
    private fun applyDeviceName(name: String?) {
        settings.deviceName = name
        if (vacaRunning) {
            hearthNsd.unregister(); hearthNsd.register()   // re-announce _hearth._tcp mDNS with the new name
            vaca.stop(); vaca.start()          // drop HA's session so it re-reads info on reconnect
        }
        voiceRestartTick.value += 1            // reactive voice collect tears down + rebuilds (voiceNsd + satellite)
    }

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

    /**
     * The dashboard view, hoisted out of the composable so HA (`set-view`) and the rail stay in
     * lockstep. The UI writes it (rail select / idle-return); a collector in [startVaca] reports
     * `current_view` to HA on change; [statusSnapshot] and `set-view` handling read/write it.
     */
    val currentView = MutableStateFlow(DashView.HOME)
    private val mediaEngine = ExoPlayerEngine(appContext)
    val nowPlaying = NowPlayingStore()
    val artFetcher = ArtFetcher(
        scope = scope,
        http = client,
        baseUrl = { settings.baseUrl },
        token = { runCatching { auth.validAccessToken() }.getOrNull() },
    )
    // Declared BEFORE `media`: MediaBridge's init{} runs applyDucking() -> duckSendspin(1f) during
    // construction, and the duck/URL lambdas below capture `sendspin`, so it must exist first.
    /** SendSpin (Music Assistant) synced-audio playback endpoint. Started reactively by [startSendspin]. */
    val sendspin = SendspinEndpoint(
        context = appContext,
        deviceName = { deviceName() },
        config = configStore.config,
        mediaEngine = mediaEngine,
        nowPlaying = nowPlaying,
        scope = scope,
        mainScope = mainScope,
    )
    /** Thumbnail loader for the MA library browser; shares the app-wide OkHttp client (like ArtFetcher). */
    val maThumbs = MaThumbs(client)
    // Constructed AFTER `sendspin`: getPlayerId() needs UserSettings.initialize, which runs in
    // SendspinEndpoint's init. The MA API socket targets the same server as the audio path —
    // manual config host when set (SendSpin port stripped by MaLibrary), else the endpoint's
    // connected/last-known server.
    /** Music Assistant library browser backend (API socket + search/shelves/queue ops). */
    val maLibrary = MaLibrary(
        scope = scope,
        playerId = UserSettings.getPlayerId(),
        hostProvider = {
            configStore.config.value.sendspin.serverAddress.substringBefore(':').trim().ifBlank { null }
                ?: sendspin.connectedHost()
        },
    )
    // Explicit type: the onUrlEnded lambda below reads `media.ui` (a deferred self-reference,
    // resolved at invoke time), which trips recursive type inference without the annotation.
    val media: MediaBridge = MediaBridge(
        mediaEngine,
        nowPlaying,
        restoredDucking = settings.duckingVolume,
        persistDucking = { settings.duckingVolume = it },
        sendSettings = { s -> scope.launch { vaca.sendSettingsFeedback(s) } },
        sendStatus = { status -> scope.launch { vaca.sendStatus(status) } },
        duckSendspin = { g -> sendspin.setDuckGain(g) },
        onStartUrl = { sendspin.stop() },
        // Local URL session ended -> rearm SendSpin so the device rejoins its Music Assistant group.
        // Delayed recheck, not an immediate start: a stale engine onEnded can arrive just after a
        // NEW play-media (see MediaBridgeTest.playingCallbackReactivatesAfterStaleEnded), and an
        // immediate rejoin would reconnect to MA mid-URL -- if the MA group is actively playing,
        // MA's stream/start would pause the fresh local URL (mutual exclusion firing the wrong
        // way). 750ms is enough for the new session's onPlayingChanged(true) to land, so the
        // recheck sees ui.playing=true and skips; a real end/stop leaves playing=false and the
        // rejoin proceeds. All orderings converge: a URL started during the delay flips
        // playing->true (skip), one started after the rejoin fires onStartUrl -> stop() as
        // normal. The enabled check respects the config toggle (no-op on the tablet, where
        // SendSpin is off); start() is @Synchronized + idempotent, so racing the reactive
        // startSendspin() collector's own start is safe.
        onUrlEnded = {
            scope.launch {
                delay(750)
                if (configStore.config.value.sendspin.enabled && !media.ui.value.playing) {
                    sendspin.start()
                }
            }
        },
    )
    val announce = AnnouncePlayer(
        scope,
        AndroidPcmSink(),
        onPlayed = { scope.launch { vaca.sendPlayed() } },
        setDucking = { ducked -> mainScope.launch { media.setDucked(DuckSource.ANNOUNCE, ducked) } },
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
        infoEvent = { VacaOutgoing.info(BuildConfig.VERSION_NAME, deviceName()) },
        capabilitiesEvent = {
            VacaOutgoing.capabilities(
                VacaOutgoing.buildCapabilities(BuildConfig.VERSION_NAME, lightSensor.hasSensor)
            )
        },
        listener = object : VacaServer.Listener {
            override fun onSessionStarted() {
                announce.onDisconnected()
                mainScope.launch {
                    vaca.sendSettingsFeedback(JsonObject(kiosk.currentSettings() + media.currentSettings()))
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
                    if (!handleDeviceAction(action, payload) &&
                        !media.handleAction(action, payload)
                    ) {
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
    private val hearthNsd =
        NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT, "_hearth._tcp.", name = { deviceName() })

    // --- Voice satellite (Wyoming) ---
    val voiceOverlay = MutableStateFlow(VoiceOverlayState())
    val timersUi = MutableStateFlow(TimersUiState())
    val timerChime = TimerChime()
    val earconPlayer = EarconPlayer()
    private val voiceSink = AndroidPcmSink()
    private val voicePlayer = AnnouncePlayer(
        scope,
        voiceSink,
        onPlayed = { satellite.onPlaybackFinished() },
        setDucking = { ducked -> mainScope.launch { media.setDucked(DuckSource.VOICE, ducked) } },
    )
    private val micStreamer = MicStreamer(
        onChunk = { pcm -> satellite.submitMicChunk(pcm) },
        onError = { satellite.reportMicError() },
    )
    val satellite: SatelliteServer = SatelliteServer(
        scope = scope,
        appVersion = BuildConfig.VERSION_NAME,
        name = { deviceName() },
        out = object : SatelliteServer.Out {
            override fun onStartMic() = micStreamer.start()
            override fun onStopMic() = micStreamer.stop()
            override fun onPlaybackStart(rate: Int, width: Int, channels: Int) =
                voicePlayer.onAudioStart(rate, width, channels)
            override fun onPlaybackChunk(pcm: ByteArray) = voicePlayer.onAudioChunk(pcm)
            override fun onPlaybackStop() = voicePlayer.onAudioStop()
            override fun onOverlay(state: VoiceOverlayState) { voiceOverlay.value = state }
            override fun onTimers(state: TimersUiState) { timersUi.value = state }
            override fun onEarcon(kind: EarconKind) = earconPlayer.play(
                if (kind == EarconKind.WAKE) "wake" else "done",
                configStore.config.value.voice.wakeSoundVolume,
            )
        },
    )
    private val voiceNsd = NsdAdvertiser(appContext, SatelliteServer.PORT, "_wyoming._tcp.", name = { deviceName() })

    // Bumped by applyDeviceName() to force the reactive voice collect to restart the satellite +
    // re-register voiceNsd so HA re-reads the (lambda-sourced) name — even when voice settings are unchanged.
    private val voiceRestartTick = MutableStateFlow(0)

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

    @Volatile private var vacaRunning = false

    fun startVaca() {
        vaca.start()
        hearthNsd.register()
        lightSensor.start()
        // Report the current view to HA whenever it changes (select entity mirror). sendStatus is a
        // no-op until a session connects, so launching before the first session is harmless.
        scope.launch {
            currentView
                .map { it.name.lowercase(Locale.US) }
                .distinctUntilChanged()
                .collect { view ->
                    vaca.sendStatus(buildJsonObject {
                        putJsonObject("sensors") { put("current_view", view) }
                    })
                }
        }
        vacaRunning = true
    }

    /**
     * Reactively (re)run the voice satellite. Restarts on any change to voice.enabled,
     * voice.wakeWord, or voice.wakeThreshold: it rebuilds the TFLite graphs + WakeDetector and
     * starts the satellite in local-wake mode. If the models fail to load, it logs one warning
     * and falls back to localWake=false (HA-side wake, the original always-streaming behavior).
     */
    fun startVoice() {
        scope.launch {
            val voiceSettings = configStore.config
                .map { Triple(it.voice.enabled, it.voice.wakeWord, it.voice.wakeThreshold) }
                .distinctUntilChanged()
            combine(voiceSettings, voiceRestartTick) { s, _ -> s }
                .collect { (enabled, wakeWord, threshold) ->
                    // Tear down any running instance first so a config change fully restarts it.
                    voiceNsd.unregister()
                    satellite.stop()
                    micStreamer.stop()
                    if (enabled) {
                        val graphs = TfliteWakeGraphs.load(appContext.assets, wakeWord)
                        val detector = if (graphs != null) {
                            WakeDetector(graphs.first, graphs.second, graphs.third, threshold) {
                                System.currentTimeMillis()
                            }
                        } else {
                            android.util.Log.w("AppDeps", "wake models failed to load; falling back to HA-side wake")
                            null
                        }
                        satellite.start(localWake = detector != null, detector = detector, wakeWord = wakeWord)
                        voiceNsd.register()
                    } else {
                        timerChime.stop()
                        voiceOverlay.value = VoiceOverlayState()
                        timersUi.value = TimersUiState()
                    }
                }
        }
    }

    /**
     * Reactively (re)run the SendSpin playback endpoint. Restarts on any change to
     * sendspin.enabled or sendspin.serverAddress (mirrors how [startVoice] restarts on
     * config change): tears down any running instance, then starts it if enabled.
     * Sync-delay changes ride a separate live-apply collector and never restart anything.
     */
    fun startSendspin() {
        scope.launch {
            configStore.config
                .map { it.sendspin.enabled to it.sendspin.serverAddress }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    // Guard so a start/stop failure can never crash the collector (and with it
                    // the kiosk). SendspinEndpoint already logs internally; this is the backstop.
                    runCatching {
                        sendspin.stop() // tear down any running instance first
                        if (enabled) sendspin.start()
                    }.onFailure { android.util.Log.e("AppDeps", "SendSpin start/stop failed", it) }
                }
        }
        // Sync-delay tuning is deliberately a SEPARATE collector: setSyncDelay applies live to the
        // running client's time filter, so nudging the slider must never tear down / reconnect the
        // MA session the way (enabled, serverAddress) changes intentionally do.
        scope.launch {
            configStore.config
                .map { it.sendspin.syncDelayMs }
                .distinctUntilChanged()
                .collect { sendspin.setSyncDelay(it) }
        }
        // MA library API socket follows (enabled, maToken): sign-in/out and the config toggle
        // (re)configure it; configure() is idempotent so unrelated config saves are no-ops.
        // serverAddress changes deliberately don't restart it — hostProvider re-reads the
        // address on every (re)connect attempt, and the audio path's own restart drops the
        // shared server anyway.
        scope.launch {
            configStore.config
                .map { it.sendspin.enabled to it.sendspin.maToken }
                .distinctUntilChanged()
                .collect { (enabled, token) -> maLibrary.configure(enabled, token) }
        }
    }

    /**
     * Apply the HA->device actions this app owns (set-view / notify / notify-clear). Returns true
     * when the action was consumed (so [onAction] does not fall through to media/kiosk). Pure
     * parsing lives in [DashActionParser]; this method applies the effects on the main scope.
     */
    private fun handleDeviceAction(action: String, payload: JsonElement?): Boolean {
        // Parser calls are wrapped in runCatching: DashActionParser mirrors ConfigServer, whose
        // .jsonPrimitive access throws on object/array-valued fields — the HTTP path catches that
        // at the top-level parse, but here an arbitrary wire payload must never crash mainScope.
        when (action) {
            "set-view" -> {
                val view = runCatching { DashActionParser.parseSetView(payload) }.getOrNull()
                if (view == null) {
                    android.util.Log.i("AppDeps", "set-view ignored: unknown/invalid view $payload")
                    return true
                }
                val cfg = configStore.config.value
                if (!DashActionParser.isViewAllowed(view, cfg.panels, cfg.entities.cameras.isNotEmpty())) {
                    android.util.Log.i("AppDeps", "set-view ignored: panel disabled for $view")
                    return true
                }
                // A real, enabled view: switch to it (the composable re-arms idle on the change) and
                // wake / exit night mode exactly as a user touch would, even if it equals the current.
                currentView.value = view
                wakeForHaView()
                return true
            }
            "notify" -> {
                val cmd = runCatching { DashActionParser.parseNotify(payload) }.getOrNull()
                if (cmd == null) {
                    android.util.Log.i("AppDeps", "notify ignored: missing/blank title $payload")
                    return true
                }
                pushStore.post(
                    cmd.id, cmd.title, cmd.message, cmd.severity, cmd.timeoutSeconds,
                    System.currentTimeMillis(),
                )
                return true
            }
            "notify-clear" -> {
                when (val cmd = runCatching { DashActionParser.parseNotifyClear(payload) }.getOrNull()) {
                    DashActionParser.NotifyClear.All -> pushStore.clearAll()
                    is DashActionParser.NotifyClear.One -> pushStore.clear(cmd.id)
                    null -> android.util.Log.i("AppDeps", "notify-clear ignored: neither id nor all $payload")
                }
                return true
            }
            else -> return false
        }
    }

    /** Wake the screen / exit night mode for an HA-initiated view change, like a user touch. */
    private fun wakeForHaView() {
        kiosk.onUserInteraction()
        nightMode.onUserInteraction(SystemClock.elapsedRealtime())
    }

    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_view", currentView.value.name.lowercase(Locale.US))
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
                    val configPinValue = remember { deps.configPin() }
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
                        photos = photos,
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
                        library = deps.maLibrary,
                        thumbs = deps.maThumbs,
                        // Takeover's browse button: land on the MEDIA view's library browser
                        // (mirrors onSelect above, incl. the kiosk interaction poke).
                        onBrowse = {
                            deps.currentView.value = DashView.MEDIA
                            deps.kiosk.onUserInteraction()
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
                        pushed = pushed,
                        onPushDismiss = { id -> deps.pushStore.dismiss(id) },
                        calendarEvents = calendarEvents,
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
                                timersState.chips.any { it.active } ||
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
                    WakeGlow(voiceOverlayState.phase == VoiceOverlayPhase.LISTENING)
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
