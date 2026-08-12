package com.rar.hearth

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.rar.hearth.camera.StreamResolver
import com.rar.hearth.config.ConfigStore
import com.rar.hearth.data.PrefsSettingsStore
import com.rar.hearth.data.SettingsStore
import com.rar.hearth.diag.DiagLog
import com.rar.hearth.diag.FileLog
import com.rar.hearth.ha.AuthManager
import com.rar.hearth.ha.ConnState
import com.rar.hearth.ha.EntityHub
import com.rar.hearth.ha.HaWebSocket
import com.rar.hearth.photos.AndroidPhotoDownloader
import com.rar.hearth.photos.PhotoLedger
import com.rar.hearth.photos.PhotoStore
import com.rar.hearth.photos.photoCacheDirName
import com.rar.hearth.photos.photoTarget
import com.rar.hearth.photos.stalePhotoCacheDirs
import com.rar.hearth.ui.DashView
import com.rar.hearth.ui.KioskUiState
import com.rar.hearth.device.AndroidKioskDevice
import com.rar.hearth.device.AnnouncePlayer
import com.rar.hearth.device.AndroidPcmSink
import com.rar.hearth.device.DashActionParser
import com.rar.hearth.device.DuckSource
import com.rar.hearth.device.ExoPlayerEngine
import com.rar.hearth.device.KioskController
import com.rar.hearth.device.LightSensorReporter
import com.rar.hearth.device.MediaBridge
import com.rar.hearth.device.NetworkMonitor
import com.rar.hearth.media.NowPlayingStore
import com.rar.hearth.media.ArtFetcher
import com.rar.hearth.media.MaThumbs
import com.rar.hearth.night.NightModeController
import com.rar.hearth.sendspin.MaLibrary
import com.rar.hearth.sendspin.SendspinEndpoint
import com.rar.hearth.sendspin.UserSettings
import com.rar.hearth.device.NsdAdvertiser
import com.rar.hearth.device.HearthOutgoing
import com.rar.hearth.device.HearthServer
import com.rar.hearth.voice.EarconKind
import com.rar.hearth.voice.EarconPlayer
import com.rar.hearth.voice.MicStreamer
import com.rar.hearth.voice.MixerGuard
import com.rar.hearth.voice.SatelliteServer
import com.rar.hearth.voice.TfliteWakeGraphs
import com.rar.hearth.voice.TimerChime
import com.rar.hearth.voice.TimersUiState
import com.rar.hearth.voice.WakeAudioRing
import com.rar.hearth.voice.WakeDetector
import com.rar.hearth.voice.VoiceOverlayState
import com.rar.hearth.web.ConfigServer
import com.rar.hearth.web.SessionManager
import com.rar.hearth.web.SetupCoordinator
import com.rar.hearth.web.buildEntityListJson
import com.rar.hearth.web.generatePin
import com.rar.hearth.web.generateNotifyToken
import com.rar.hearth.web.localIpAddress
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
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

/**
 * Process-wide dependencies; owned by HearthApplication, created on the main thread.
 *
 * This is the app's hand-rolled DI container and the wiring for every long-lived
 * subsystem (HA socket, config server, Hearth wire protocol, voice satellite,
 * SendSpin, photos, media). It holds no Compose state — per-session UI state lives
 * in `App.kt` above the shell Crossfade, where it survives view switches.
 *
 * Construction order matters in places; those spots carry their own comments.
 */
class AppDeps(context: Context) {
    private val appContext = context.applicationContext

    /** Latest ambient-light reading (lux) for the config page's live display; null when no sensor. */
    @Volatile var lastLux: Int? = null

    /**
     * Rolling on-disk log, readable from the config page after a reboot has cleared
     * logcat. Declared first so [startDiagnostics] can capture faults from the rest
     * of construction onward.
     */
    val fileLog = FileLog(appContext.filesDir)

    val settings: SettingsStore = PrefsSettingsStore(appContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    /** Backstop for uncaught exceptions in [scope]/[mainScope] children: a SupervisorJob keeps
     *  siblings alive, but an unhandled throw in a bare launch{} would otherwise reach the default
     *  handler and crash the kiosk process. Log-and-swallow instead. */
    private val coroutineErr = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("AppDeps", "uncaught coroutine exception", e)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineErr)
    val ws = HaWebSocket(settings, auth, client, scope)
    val configStore = ConfigStore(appContext.filesDir)
    val entityHub = EntityHub(ws, scope, configStore.config)
    val streamResolver = StreamResolver(
        requestStream = { entityId -> entityHub.cameraStream(entityId) },
        baseUrl = { settings.baseUrl },
    )

    // getRealSize is deprecated in favor of the API 30+ WindowMetrics path, but it still works
    // fine minSdk 27..targetSdk 34 and a version branch isn't worth it just for this.
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
    // The seen-ledger lives in filesDir, not cacheDir: the cache dir is subject to Android's
    // storage-pressure eviction and to the stale-resolution wipe above, and its listing is the
    // prefetch buffer inventory -- a stray file there would be read as a photo.
    private val photoLedger = PhotoLedger(File(appContext.filesDir, "photo-seen.txt"))
    val photoStore = PhotoStore(ws, photoDownloader, photoCacheDir, scope, configStore.config, photoLedger)

    val sessions = SessionManager()
    val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val setup = SetupCoordinator(auth, onConfigured = { setupEvents.tryEmit(Unit) })
    private val ensuredPin: String by lazy {
        settings.configPin ?: generatePin().also { settings.configPin = it }
    }
    private val ensuredNotifyToken: String by lazy {
        settings.notifyToken ?: generateNotifyToken().also { settings.notifyToken = it }
    }
    /**
     * Live config PIN for the on-device display (Setup screen + Home menu). Seeded from the
     * persisted/first-boot PIN and re-published by [applyConfigPin] / [resetConfigPin] so a PIN
     * change made in the web config shows on the device screen without an app restart.
     */
    val pinState = MutableStateFlow(configPin())
    val pushStore = com.rar.hearth.notify.PushNotificationStore()
    val apkUpdater = com.rar.hearth.update.ApkUpdater(
        context = appContext,
        http = client,
        scope = scope,
        currentVersionCode = BuildConfig.VERSION_CODE,
    )
    val configServer = ConfigServer(
        store = configStore,
        sessions = sessions,
        pin = { configPin() },
        notifyToken = { ensuredNotifyToken },
        deviceName = { deviceName() },
        setDeviceName = { applyDeviceName(it) },
        setPin = { applyConfigPin(it) },
        resetPin = { resetConfigPin() },
        pushStore = pushStore,
        entitiesJson = { buildEntityListJson(entityHub.registry.value, entityHub.entities.value) },
        setup = setup,
        configured = { settings.refreshToken != null },
        connState = { ws.connectionState.value.name },
        haUrl = { settings.baseUrl },
        // clearAuth() on the server (worker) thread so the web's next /api/status poll
        // already reports configured:false; ws.stop() happens in the main-thread collector.
        disconnect = { settings.clearAuth(); logoutEvents.tryEmit(Unit) },
        lux = { lastLux },
        sendspinStatus = { sendspin.status.value.name },
        appVersion = { BuildConfig.VERSION_NAME },
        appVersionCode = { BuildConfig.VERSION_CODE },
        startUpdate = { url -> apkUpdater.start(url) },
        updateStatus = { apkUpdater.status.value },
        logText = { limit -> fileLog.tail(limit) },
        logSizeBytes = { fileLog.sizeBytes() },
        clearLog = { fileLog.clear() },
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

    /** The config PIN: a custom/persisted value if set, else the generate-once default. */
    fun configPin(): String = settings.configPin ?: ensuredPin

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
            hearth.stop(); hearth.start()          // drop HA's session so it re-reads info on reconnect
        }
        voiceRestartTick.value += 1            // reactive voice collect tears down + rebuilds (voiceNsd + satellite)
    }

    /** Persist a user-chosen PIN and publish it to the live on-device display. */
    private fun applyConfigPin(pin: String) {
        settings.configPin = pin
        pinState.value = pin
    }

    /** Generate a fresh random PIN, persist + publish it, and return it. */
    private fun resetConfigPin(): String {
        val pin = generatePin()
        settings.configPin = pin
        pinState.value = pin
        return pin
    }

    /**
     * Begin persisting diagnostics: crash handler first (so a fault during the rest
     * of startup is recorded), then the session banner, then the logcat pump. Call
     * this before anything else in HearthApplication.
     */
    fun startDiagnostics() {
        DiagLog.installCrashHandler(fileLog)
        fileLog.banner("Hearth ${BuildConfig.VERSION_NAME} started on ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        DiagLog.startLogcatPump(fileLog)
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
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + coroutineErr)
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
     * lockstep. The UI writes it (rail select / idle-return); a collector in [startHearth] reports
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
    /** Reconnects the HA WebSocket (if not already up) and re-arms SendSpin the instant a network
     *  becomes available, so a wifi/router blip recovers immediately instead of waiting out reconnect
     *  backoff. Started in [startDashboard]; callback hops to mainScope off the binder thread. */
    val networkMonitor = NetworkMonitor(appContext) {
        mainScope.launch {
            // Restart only from a clean OFFLINE (never interrupt an in-flight CONNECTING handshake
            // or override AUTH_FAILED) and only while still authed (no refreshToken == logged out ->
            // don't resurrect the socket on the Setup screen).
            if (settings.refreshToken != null && ws.connectionState.value == ConnState.OFFLINE) {
                ws.start()
            }
            sendspin.onNetworkAvailable()
        }
    }
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
        sendSettings = { s -> scope.launch { hearth.sendSettingsFeedback(s) } },
        sendStatus = { status -> scope.launch { hearth.sendStatus(status) } },
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
        onPlayed = { scope.launch { hearth.sendPlayed() } },
        setDucking = { ducked -> mainScope.launch { media.setDucked(DuckSource.ANNOUNCE, ducked) } },
    )
    val lightSensor = LightSensorReporter(appContext) { lux ->
        lastLux = lux.toInt()
        mainScope.launch {
            kiosk.onLightLevel(lux)
            nightMode.onLux(lux, SystemClock.elapsedRealtime())
        }
        scope.launch {
            hearth.sendStatus(buildJsonObject {
                putJsonObject("sensors") { put("light", lux.toInt()) }
            })
        }
    }
    val hearth: HearthServer = HearthServer(
        scope = scope,
        infoEvent = { HearthOutgoing.info(BuildConfig.VERSION_NAME, deviceName()) },
        capabilitiesEvent = {
            HearthOutgoing.capabilities(
                HearthOutgoing.buildCapabilities(BuildConfig.VERSION_NAME, lightSensor.hasSensor)
            )
        },
        listener = object : HearthServer.Listener {
            override fun onSessionStarted() {
                announce.onDisconnected()
                mainScope.launch {
                    hearth.sendSettingsFeedback(JsonObject(kiosk.currentSettings() + media.currentSettings()))
                    hearth.sendStatus(statusSnapshot())
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
        NsdAdvertiser(appContext, HearthServer.DEFAULT_PORT, "_hearth._tcp.", name = { deviceName() })

    // --- Voice satellite (Wyoming) ---
    val voiceOverlay = MutableStateFlow(VoiceOverlayState())
    val timersUi = MutableStateFlow(TimersUiState())
    val timerChime = TimerChime(assetFd = { runCatching { appContext.assets.openFd(it) }.getOrNull() })
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
        // Read LIVE on each playback finish — deliberately NOT in startVoice()'s restart
        // trigger set: restarting the satellite would drop device-local timers.
        followUp = { configStore.config.value.voice.followUpEnabled },
        out = object : SatelliteServer.Out {
            override fun onStartMic() = micStreamer.start()
            override fun onStopMic() = micStreamer.stop()
            override fun onPlaybackStart(rate: Int, width: Int, channels: Int) =
                voicePlayer.onAudioStart(rate, width, channels)
            override fun onPlaybackChunk(pcm: ByteArray) = voicePlayer.onAudioChunk(pcm)
            override fun onPlaybackStop() = voicePlayer.onAudioStop()
            override fun onPlaybackAbort() = voicePlayer.abort()
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
        kiosk.sendFeedback = { s -> scope.launch { hearth.sendSettingsFeedback(s) } }
        artFetcher.start(nowPlaying.state)
    }

    /** Start the HA connection, entity hub, and photo sync. */
    fun startDashboard() {
        entityHub.start()
        photoStore.start(ws.connectionState)
        ws.start()
        networkMonitor.start()
    }

    @Volatile private var vacaRunning = false

    fun startHearth() {
        hearth.start()
        hearthNsd.register()
        lightSensor.start()
        // Report the current view to HA whenever it changes (select entity mirror). sendStatus is a
        // no-op until a session connects, so launching before the first session is harmless.
        scope.launch {
            currentView
                .map { it.name.lowercase(Locale.US) }
                .distinctUntilChanged()
                .collect { view ->
                    hearth.sendStatus(buildJsonObject {
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
                .map {
                    VoiceKey(
                        it.voice.enabled, it.voice.wakeWord, it.voice.wakeThreshold,
                        it.voice.captureOnWake, it.voice.captureThreshold,
                    )
                }
                .distinctUntilChanged()
            combine(voiceSettings, voiceRestartTick) { s, _ -> s }
                .collect { (enabled, wakeWord, threshold, captureOnWake, captureThreshold) ->
                    // Tear down any running instance first so a config change fully restarts it.
                    voiceNsd.unregister()
                    satellite.stop()
                    micStreamer.stop()
                    if (enabled) {
                        MixerGuard.apply()
                        val graphs = TfliteWakeGraphs.load(appContext.assets, wakeWord)
                        val detector = if (graphs != null) {
                            // Primary head = the configured wake word at the user's threshold.
                            val heads = mutableListOf(WakeDetector.Head(wakeWord, graphs.third, threshold))
                            // Optional second head: an always-on "stop" that silences a ringing timer
                            // alarm with no wake word. Loaded separately so a missing/corrupt stop
                            // asset can never break the primary wake word (feature just stays absent).
                            TfliteWakeGraphs.loadHead(appContext.assets, SatelliteServer.STOP_HEAD)?.let { stop ->
                                heads += WakeDetector.Head(SatelliteServer.STOP_HEAD, stop, SatelliteServer.STOP_THRESHOLD_PCT)
                            }
                            WakeDetector(graphs.first, graphs.second, heads) {
                                System.currentTimeMillis()
                            }
                        } else {
                            android.util.Log.w("AppDeps", "wake models failed to load; falling back to HA-side wake")
                            null
                        }
                        val audioRing = if (captureOnWake) {
                            WakeAudioRing(java.io.File(appContext.filesDir, "wake-captures"))
                        } else {
                            null
                        }
                        satellite.start(
                            localWake = detector != null,
                            detector = detector,
                            wakeWord = wakeWord,
                            audioRing = audioRing,
                            captureThresholdPct = captureThreshold,
                        )
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

/** Restart key for the voice collector: any change here rebuilds the satellite. */
private data class VoiceKey(
    val enabled: Boolean,
    val wakeWord: String,
    val threshold: Int,
    val captureOnWake: Boolean,
    val captureThreshold: Int,
)
