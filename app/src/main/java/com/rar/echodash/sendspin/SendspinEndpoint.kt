package com.rar.echodash.sendspin

import android.content.Context
import android.util.Log
import com.rar.echodash.config.DashConfig
import com.rar.echodash.media.NowPlayingStore
import com.rar.echodash.sendspin.coordinator.TransportState
import com.rar.echodash.sendspin.discovery.NsdDiscoveryManager
import com.rar.echodash.sendspin.sendspin.PlaybackState
import com.rar.echodash.sendspin.sendspin.SendSpin
import com.rar.echodash.sendspin.sendspin.SyncAudioPlayer
import com.rar.echodash.sendspin.sendspin.SyncAudioPlayerCallback
import com.rar.echodash.sendspin.sendspin.decoder.AudioDecoder
import com.rar.echodash.sendspin.sendspin.decoder.AudioDecoderFactory
import com.rar.echodash.vaca.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** User-facing playback status for the SendSpin endpoint (config page / diagnostics). */
enum class SendspinStatus { Disconnected, Connecting, Connected, Playing }

/**
 * Pure derivation of [SendspinStatus] from the transport [state] plus whether a
 * server-announced audio stream is currently active. Kept top-level so it is
 * unit-testable off-device (see `SendspinStatusTest`).
 */
fun sendspinStatus(state: TransportState, streaming: Boolean): SendspinStatus = when (state) {
    is TransportState.Ready -> if (streaming) SendspinStatus.Playing else SendspinStatus.Connected
    is TransportState.Connecting -> SendspinStatus.Connecting
    else -> SendspinStatus.Disconnected // Idle, Failed
}

/**
 * Long-lived glue that makes Hearth act as a SendSpin (Music Assistant) multi-room
 * synced-audio playback endpoint. NOT an Android Service -- a plain process-lifetime
 * object owned by `AppDeps`.
 *
 * Responsibilities:
 *  - Build/tear down the vendored [SendSpin] client (connect via manual address or mDNS).
 *  - Own the decode -> queue audio path: [SendSpin.Callback.onAudioChunk] delivers
 *    codec-encoded bytes, which a single-owner decode worker turns into PCM and feeds
 *    to a [SyncAudioPlayer]. (The vendored facade has no `setSyncAudioPlayer`.)
 *  - Publish a coarse [status] flow.
 *
 * Ducking routing and now-playing metadata mapping land in Task 6; this task only wires
 * the plumbing (see `setDuckGain` note and the `// Task 6:` markers).
 */
class SendspinEndpoint(
    private val context: Context,
    private val deviceName: () -> String,
    private val config: StateFlow<DashConfig>,
    private val mediaEngine: MediaEngine,
    private val nowPlaying: NowPlayingStore, // SendSpin now-playing mapping (Part B)
    private val scope: CoroutineScope, // Dispatchers.Default work
    private val mainScope: CoroutineScope, // Dispatchers.Main.immediate for SyncAudioPlayer + NSD
) {
    private companion object {
        const val TAG = "SendspinEndpoint"
    }

    init {
        // Persist the SendSpin player id across app restarts. First call wins.
        UserSettings.initialize(context)
    }

    // ---- Public status ----

    private val _status = MutableStateFlow(SendspinStatus.Disconnected)
    val status: StateFlow<SendspinStatus> = _status.asStateFlow()

    // ---- Lifecycle state (touched on the caller thread; start/stop are not re-entrant) ----

    private var started = false
    // Read from the NSD executor thread + WS-IO callbacks, written from the caller thread
    // (start/stop) and the discovery listener -- @Volatile for cross-thread visibility.
    @Volatile private var sendSpin: SendSpin? = null
    @Volatile private var nsd: NsdDiscoveryManager? = null
    private var stateCollectorJob: Job? = null

    /** deferred: latency tuning -- read but intentionally NOT wired into the time filter yet. */
    @Volatile private var syncDelayMs: Int = 0

    // ---- Ducking (routing only in Task 5; SyncAudioPlayer.setVolume is a documented no-op) ----

    @Volatile private var duckGain: Float = 1f

    fun setDuckGain(fraction: Float) {
        duckGain = fraction.coerceIn(0f, 1f)
        syncAudioPlayer?.setVolume(duckGain)
    }

    // Forward the now-playing takeover's controls to Music Assistant. Play/Pause/Stop set the local
    // [playWhenReady] intent optimistically and publish, so the takeover's icon flips immediately;
    // the server's playback_state broadcast then confirms it (and the audio-state callback corrects
    // it if the server and the actual audio ever disagree). No-op while disconnected.
    fun transportPlay() { playWhenReady = true; sendSpin?.play(); publishNowPlaying() }
    fun transportPause() { playWhenReady = false; sendSpin?.pause(); publishNowPlaying() }
    // Stop maps to PAUSE, exactly as the reference SendSpinPlayer.stop() does: sending a real "stop"
    // command makes Music Assistant requeue and RESTART the track -- the reported "stop then restarts"
    // bug. Pausing stops the audio and keeps the (paused) takeover, which the paused-dismiss timer
    // then hides gracefully.
    fun transportStop() { playWhenReady = false; sendSpin?.pause(); publishNowPlaying() }
    fun transportNext() { sendSpin?.next() }
    fun transportPrev() { sendSpin?.previous() }
    /** Set the SendSpin group volume (0..100). */
    fun transportVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        Log.i(TAG, "transportVolume $v (connected=${sendSpin != null})")
        sendSpin?.setGroupVolume(v)
    }

    // ---- Audio pipeline ----

    // The active player. Written on mainScope, read from the WS callback + decode
    // worker threads, so @Volatile for cross-thread visibility.
    @Volatile private var syncAudioPlayer: SyncAudioPlayer? = null

    // True once a stream has been announced (onStreamStart) until it clears/ends or
    // we disconnect. Drives the Playing vs Connected status distinction.
    @Volatile private var streaming: Boolean = false

    // ---- Now-playing metadata (Part B) ----
    // The onMetadataUpdate / onArtwork / onVolumeChanged callbacks arrive SEPARATELY on the WS-IO
    // thread, so we hold the latest of each and publish the merge into NowPlayingStore (via
    // publishNowPlaying) on mainScope -- keeping UI-facing writes off WS-IO.
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null
    @Volatile private var npVolume: Int = 90

    // Play/pause INTENT, mirroring the reference SendSpinPlayer.playWhenReady. Set optimistically by
    // the transport buttons and reconciled from the server's playback_state broadcasts (WITHOUT
    // echoing a command back). This -- not the raw stream/start-end flag -- is the takeover's
    // play/pause icon, so the icon can't get stuck showing "paused" while audio is actually flowing.
    @Volatile private var playWhenReady: Boolean = false

    // True once we have a track worth showing (first stream/start or first real metadata) and until
    // the endpoint stops or the transport dies. Drives the takeover's visibility ([active]); it must
    // PERSIST across pause and across the stream/end that MA fires at every track boundary, so a
    // pause shows a paused track rather than making the whole takeover vanish.
    @Volatile private var hasTrack: Boolean = false

    /** Publish the current merged now-playing snapshot to [NowPlayingStore]. */
    private fun publishNowPlaying() {
        val active = hasTrack
        val playing = playWhenReady
        Log.i(TAG, "publish active=$active playing=$playing title='$npTitle' art=${npArtwork?.size ?: 0} vol=$npVolume")
        mainScope.launch {
            nowPlaying.onSendspin(
                active = active, playing = playing,
                title = npTitle, artist = npArtist, album = npAlbum,
                artworkData = npArtwork, volume = npVolume,
            )
        }
    }

    /**
     * Drive the local [SyncAudioPlayer] from a server-broadcast playback_state and reconcile the
     * play/pause [playWhenReady] intent WITHOUT echoing a command back (which would create a feedback
     * loop). This is the half of the control loop the endpoint was missing: the server saying
     * "playing/paused/stopped" must actually resume/pause/clear the local audio, not just relabel it.
     * Mirrors SendSpinDroid PlaybackService.onStateChanged / onGroupUpdate. Runs on mainScope because
     * the SyncAudioPlayer is owned by the main looper.
     *
     * Music Assistant uses "stopped" as its pause signal (it does not emit "paused"), so STOPPED is
     * handled the reference way -- clear the buffer and pause -- UNLESS we are DRAINING (mid-reconnect
     * with a live buffer), in which case we ask the server to resume rather than kill the audio.
     */
    private fun applyServerPlaybackState(rawState: String) {
        mainScope.launch {
            val player = syncAudioPlayer
            when {
                rawState.equals("playing", ignoreCase = true) -> {
                    playWhenReady = true
                    player?.resume()
                }
                rawState.equals("paused", ignoreCase = true) -> {
                    playWhenReady = false
                    player?.pause() // keep the buffer for a seamless resume
                }
                rawState.equals("stopped", ignoreCase = true) ||
                    rawState.equals("idle", ignoreCase = true) -> {
                    if (player?.getPlaybackState() == PlaybackState.DRAINING) {
                        // Reconnecting with a live buffer -- resume rather than discard it.
                        sendSpin?.play()
                    } else {
                        playWhenReady = false
                        player?.clearBuffer()
                        player?.pause()
                    }
                }
                // Unknown state -- leave the intent untouched.
            }
            publishNowPlaying()
        }
    }

    /**
     * Reconciles the UI from the ACTUAL audio-player state. Fires only on real state transitions.
     * When audio physically reaches PLAYING/DRAINING but our intent still says paused, the server
     * told us "stopped" before audio actually resumed -- correct the intent so the icon is truthful
     * (this is the reference SendSpinPlayer.updateStateFromPlayer correction). A user pause does not
     * change the player's state enum, so this never spuriously overrides a genuine pause.
     */
    private val audioStateCallback = object : SyncAudioPlayerCallback {
        override fun onPlaybackStateChanged(state: PlaybackState) {
            val flowing = state == PlaybackState.PLAYING || state == PlaybackState.DRAINING
            if (flowing && !playWhenReady) {
                playWhenReady = true
            }
            publishNowPlaying()
        }
    }

    // Fast-path gate read on the WS-IO thread: once onStreamStart is observed we set
    // this true so chunks are enqueued for the (soon-to-exist) decoder; a total
    // decoder-create failure flips it back false so we stop piling up chunks.
    @Volatile private var decoderReady: Boolean = false

    // Single-owner decode worker: everything below is touched ONLY on decodeDispatcher.
    private val decodeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SendspinDecode").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val decodeChannel = Channel<DecodeTask>(capacity = Channel.UNLIMITED)
    private var decodeJob: Job? = null
    private var audioDecoder: AudioDecoder? = null
    private var currentCodec: String = "pcm"

    /**
     * Tasks sent through [decodeChannel] to the single-owner decode worker. Decoder
     * lifecycle transitions travel on the same channel as [Chunk] so they preserve FIFO
     * ordering with pending decodes (a Chunk enqueued before StartStream decodes with the
     * new decoder once the worker drains the StartStream ahead of it).
     */
    private sealed class DecodeTask {
        class Chunk(val serverTimeMicros: Long, val audioData: ByteArray) : DecodeTask()
        class StartStream(
            val codec: String,
            val sampleRate: Int,
            val channels: Int,
            val bitDepth: Int,
            val codecHeader: ByteArray?,
        ) : DecodeTask()
        object Flush : DecodeTask()
        object Release : DecodeTask()
    }

    /** Launch the decode worker once; it lives for the endpoint's lifetime. */
    private fun ensureWorker() {
        if (decodeJob != null) return
        decodeJob = scope.launch(decodeDispatcher) {
            for (task in decodeChannel) {
                try {
                    when (task) {
                        is DecodeTask.Chunk -> handleChunk(task)
                        is DecodeTask.StartStream -> handleStartStream(task)
                        DecodeTask.Flush -> audioDecoder?.flush()
                        DecodeTask.Release -> {
                            audioDecoder?.release()
                            audioDecoder = null
                            decoderReady = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decode worker error on ${task::class.simpleName}", e)
                }
            }
        }
    }

    private fun handleChunk(t: DecodeTask.Chunk) {
        val decoder = audioDecoder
        val pcm: ByteArray = try {
            when {
                decoder != null -> decoder.decode(t.audioData)
                currentCodec == "pcm" -> t.audioData // PCM pass-through when no decoder installed
                else -> return // compressed codec with no decoder -- drop
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error, dropping chunk", e)
            return
        }
        syncAudioPlayer?.queueChunk(t.serverTimeMicros, pcm)
    }

    private fun handleStartStream(t: DecodeTask.StartStream) {
        audioDecoder?.release()
        audioDecoder = null
        currentCodec = t.codec
        try {
            val decoder = AudioDecoderFactory.create(t.codec)
            decoder.configure(t.sampleRate, t.channels, t.bitDepth, t.codecHeader)
            audioDecoder = decoder
            decoderReady = true
            Log.i(TAG, "Audio decoder created: ${t.codec}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create decoder for ${t.codec}, falling back to PCM", e)
            try {
                val fallback = AudioDecoderFactory.create("pcm")
                fallback.configure(t.sampleRate, t.channels, t.bitDepth)
                audioDecoder = fallback
                currentCodec = "pcm"
                decoderReady = true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "PCM fallback decoder also failed", fallbackEx)
                audioDecoder = null
                decoderReady = false
            }
        }
    }

    // ---- Public lifecycle ----

    /**
     * Idempotent: build the client (lazily) and begin connecting via address or discovery.
     *
     * @Synchronized with [stop] -- called from both the reactive config collector
     * (Dispatchers.Default, via AppDeps.startSendspin) and the main thread (via
     * MediaBridge.onStartUrl -> sendspin.stop() on a URL play). Both methods only
     * launch/cancel coroutines and build/destroy objects (no inline network I/O), so
     * synchronizing them is cheap and prevents one thread's start() from racing the
     * other's stop() and leaving `started=true` pointing at a torn-down client.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true

        val cfg = config.value.sendspin
        syncDelayMs = cfg.syncDelayMs // deferred: latency tuning
        ensureWorker()

        val ss = SendSpin(context, deviceName(), EndpointCallback())
        sendSpin = ss

        // Publish status derived from the transport state + streaming flag.
        stateCollectorJob = scope.launch {
            ss.connectionState.collect { state ->
                // Clear a stale streaming flag on any non-Ready transition (e.g. an abrupt
                // drop with no onStreamEnd/onStreamClear) so a SendSpin auto-reconnect back
                // to Ready starts from Connected, not a falsely-resumed Playing.
                if (state !is TransportState.Ready) {
                    streaming = false
                }
                _status.value = sendspinStatus(state, streaming)
                // A dead-end transport (exhausted reconnect, or never-started) must not leave
                // a phantom now-playing takeover on the home screen forever -- onStreamEnd/stop()
                // only fire on a clean end/explicit stop, not an abrupt drop that never recovers.
                // Connecting is deliberately excluded: a transient reconnect must keep the
                // takeover up while the buffer drains.
                if (state is TransportState.Failed || state is TransportState.Idle) {
                    hasTrack = false
                    playWhenReady = false
                    mainScope.launch {
                        nowPlaying.onSendspin(false, false, null, null, null, null, npVolume)
                    }
                }
            }
        }

        val address = cfg.serverAddress.trim()
        if (address.isNotBlank()) {
            Log.i(TAG, "Connecting to manual SendSpin address: $address")
            ss.connectLocal(address)
        } else {
            val discovery = NsdDiscoveryManager(context, DiscoveryListenerImpl())
            nsd = discovery
            // NsdManager needs a Looper -- start discovery on the main thread. Guarded so a
            // discovery failure (missing multicast permission, Wi-Fi down, NSD unavailable)
            // degrades to Disconnected instead of crashing the kiosk on the main dispatcher.
            mainScope.launch {
                try {
                    discovery.startDiscovery()
                } catch (e: Exception) {
                    Log.e(TAG, "SendSpin discovery failed to start", e)
                    _status.value = SendspinStatus.Disconnected
                }
            }
        }
    }

    /**
     * Idempotent: disconnect, tear down discovery, and release the player + decoder.
     *
     * @Synchronized with [start] -- see the note there.
     */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        streaming = false
        decoderReady = false

        stateCollectorJob?.cancel()
        stateCollectorJob = null

        val discovery = nsd
        nsd = null
        if (discovery != null) {
            mainScope.launch {
                discovery.stopDiscovery()
                discovery.cleanup()
            }
        }

        // destroy() disconnects internally, then null it so a later start() rebuilds cleanly.
        sendSpin?.destroy()
        sendSpin = null

        // Release the decoder on its owning worker thread. Sent synchronously (not via a
        // fresh launch) so it preserves FIFO order relative to any task enqueued just
        // before stop() from a callback thread; trySend always succeeds on an UNLIMITED
        // channel while open.
        if (decodeChannel.trySend(DecodeTask.Release).isFailure) {
            Log.w(TAG, "decodeChannel closed, dropped Release task")
        }

        val player = syncAudioPlayer
        syncAudioPlayer = null
        if (player != null) {
            mainScope.launch { player.release() }
        }

        _status.value = SendspinStatus.Disconnected
        hasTrack = false
        playWhenReady = false
        mainScope.launch { nowPlaying.onSendspin(false, false, null, null, null, null, npVolume) }
    }

    /** Recompute the published status from the current transport state + streaming flag. */
    private fun updateStatus() {
        val state = sendSpin?.connectionState?.value ?: TransportState.Idle
        _status.value = sendspinStatus(state, streaming)
    }

    // ---- Discovery ----

    private inner class DiscoveryListenerImpl : NsdDiscoveryManager.DiscoveryListener {
        override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
            val ss = sendSpin ?: return
            if (ss.isConnected) return
            Log.i(TAG, "Discovered SendSpin server $friendlyName at $address path=$path")
            ss.connectLocal(address, path)
            mainScope.launch { nsd?.stopDiscovery() }
        }
        override fun onServerLost(name: String) {}
        override fun onDiscoveryStarted() {}
        override fun onDiscoveryStopped() {}
        override fun onDiscoveryError(error: String) {
            Log.w(TAG, "Discovery error: $error")
        }
    }

    // ---- SendSpin callback: cheap on the WS-IO thread; hand off to worker / mainScope ----

    private inner class EndpointCallback : SendSpin.Callback {

        override fun onStreamStart(
            codec: String,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?,
        ) {
            // Optimistic gate so chunks arriving during reconfiguration are still enqueued;
            // they decode once the worker drains the StartStream ahead of them.
            decoderReady = true
            streaming = true
            hasTrack = true
            // A stream starting is the server's strongest "playing" signal; set the intent so the
            // takeover shows playing immediately. The playback_state broadcast + the audio-state
            // callback then confirm it.
            playWhenReady = true
            Log.i(TAG, "onStreamStart codec=$codec ${sampleRate}Hz ${channels}ch ${bitDepth}bit")
            // Do NOT clear metadata here. Music Assistant sends the next track's metadata + artwork
            // BEFORE it starts the audio stream, so by the time onStreamStart fires npTitle/npArtwork
            // already hold the NEW track -- clearing them would wipe the art the user just saw (and
            // there is no stale-flash window to guard against, since new metadata always precedes the
            // stream). A fresh connection simply has null fields until the first metadata arrives.
            publishNowPlaying() // active -> true; metadata already present or fills in shortly
            // Sent synchronously on the WS-IO callback thread (not via scope.launch) so
            // enqueue order matches callback order -- a launch here could race with the
            // Chunk/Flush launches below and reorder relative to them on the channel.
            if (decodeChannel.trySend(
                    DecodeTask.StartStream(codec, sampleRate, channels, bitDepth, codecHeader),
                ).isFailure
            ) {
                Log.w(TAG, "decodeChannel closed, dropped StartStream task")
            }
            // Player lifecycle lives on the main looper.
            mainScope.launch {
                val ss = sendSpin ?: return@launch
                val existing = syncAudioPlayer
                if (existing != null && existing.matchesFormat(sampleRate, channels, bitDepth)) {
                    // Reuse -- DAC stays warm.
                    existing.clearBuffer()
                } else {
                    existing?.release()
                    val player = SyncAudioPlayer(
                        timeFilter = ss.getTimeFilter(),
                        sampleRate = sampleRate,
                        channels = channels,
                        bitDepth = bitDepth,
                    )
                    // Learn the real audio-player state so the UI reconciles from what is actually
                    // playing, not from the stream/start-end flag (which flaps on every track change).
                    player.setStateCallback(audioStateCallback)
                    player.initialize()
                    player.start()
                    // Honor an in-progress duck (real per-track gain; see setDuckGain).
                    player.setVolume(duckGain)
                    syncAudioPlayer = player
                    Log.i(TAG, "SyncAudioPlayer created: ${sampleRate}Hz ${channels}ch ${bitDepth}bit")
                }
                // Mutual exclusion with local media playback.
                mediaEngine.pause()
                updateStatus() // -> Playing
            }
        }

        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
            if (!decoderReady) return
            // Sent synchronously (see onStreamStart) to preserve FIFO order with the
            // StartStream/Flush/Release tasks around it.
            if (decodeChannel.trySend(DecodeTask.Chunk(serverTimeMicros, audioData)).isFailure) {
                Log.w(TAG, "decodeChannel closed, dropped Chunk task")
            }
        }

        override fun onStreamClear() {
            Log.i(TAG, "onStreamClear -> flush + clearBuffer")
            streaming = false
            // Sent synchronously (see onStreamStart) to preserve FIFO order.
            if (decodeChannel.trySend(DecodeTask.Flush).isFailure) {
                Log.w(TAG, "decodeChannel closed, dropped Flush task")
            }
            mainScope.launch { syncAudioPlayer?.clearBuffer() }
            updateStatus()
        }

        override fun onStreamEnd() {
            Log.i(TAG, "onStreamEnd -> enterIdle (keep DAC warm)")
            streaming = false
            // MA fires stream/end on pause/stop AND at every track boundary (the ~10s transcode gap
            // on "next"). Enter idle -- keep the AudioTrack alive writing silence so the DAC stays
            // warm for the next stream -- but do NOT clear the takeover here: the server's
            // playback_state is what actually pauses/stops the audio (applyServerPlaybackState), and
            // clearing now would blank the now-playing mid-track-change. hasTrack/playWhenReady
            // persist; only a disconnect or an explicit endpoint stop() tears the takeover down.
            mainScope.launch { syncAudioPlayer?.enterIdle() }
            updateStatus() // -> Connected (while Ready)
        }

        override fun onSyncMuteChanged(muted: Boolean) {
            syncAudioPlayer?.setSyncMuted(muted)
        }

        // ---- Log-only / no-op for now ----

        override fun onServerDiscovered(name: String, address: String) {}

        override fun onStateChanged(state: String) {
            Log.i(TAG, "server/state playback_state=$state")
            applyServerPlaybackState(state)
        }

        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            Log.i(TAG, "group/update playback_state=$playbackState group='$groupName'")
            applyServerPlaybackState(playbackState)
        }

        override fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long,
            playbackSpeed: Int,
        ) {
            // MA interleaves progress-only server/state updates (no title/artist/album -- the
            // vendored callback flattens the absent fields to blanks) between full metadata
            // snapshots. Ignore the progress-only ones so they don't wipe the real track info;
            // a genuine track change always carries a non-blank title.
            // MA sends binary artwork via onArtwork -- ignore artworkUrl (do not fetch it).
            if (title.isBlank()) return
            npTitle = title
            npArtist = artist
            npAlbum = album
            hasTrack = true
            Log.i(TAG, "meta title='$title' artist='$artist' album='$album'")
            publishNowPlaying()
        }

        override fun onArtwork(imageData: ByteArray) {
            Log.i(TAG, "onArtwork ${imageData.size} bytes")
            npArtwork = imageData
            publishNowPlaying()
        }

        override fun onArtworkCleared() {
            Log.i(TAG, "onArtworkCleared")
            npArtwork = null
            publishNowPlaying()
        }

        override fun onVolumeChanged(volume: Int) {
            Log.i(TAG, "onVolumeChanged $volume")
            npVolume = volume
            publishNowPlaying()
        }

        override fun onMutedChanged(muted: Boolean) {}

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}

        override fun onNetworkChanged() {}
    }
}
