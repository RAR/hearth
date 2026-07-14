package com.rar.echodash.voice

import android.util.Log
import com.rar.echodash.vaca.WyomingCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * Wyoming TCP server for the voice satellite (port 10600). HA connects inbound.
 * Newest *real* connection wins: a connection only displaces the active one on its first
 * event other than describe/ping, so zeroconf identify-probes (bare describe) pass through
 * harmlessly. Reader runs off the lock; the active [SatelliteSession]
 * and all socket writes are serialized on [lock], so pongs are never starved by
 * blocking playback (playback is offloaded to an AnnouncePlayer via [out]). Note:
 * outbound mic-chunk writes share the same socket and can still stall pongs under
 * TCP back-pressure (e.g. a stalled HA); this is bounded and self-heals via disconnect.
 *
 * When started with localWake, an on-device [WakeDetector] runs on a dedicated daemon
 * thread fed by a bounded, drop-oldest queue (FeedDetector/ResetDetector actions from the
 * session). A detector hit re-enters the session under [lock] via onWakeDetected.
 */
class SatelliteServer(
    private val scope: CoroutineScope,
    private val port: Int = PORT,
    private val appVersion: String,
    private val name: () -> String,
    private val out: Out,
) {
    interface Out {
        fun onStartMic()
        fun onStopMic()
        fun onPlaybackStart(rate: Int, width: Int, channels: Int)
        fun onPlaybackChunk(pcm: ByteArray)
        fun onPlaybackStop()
        fun onOverlay(state: VoiceOverlayState)
        fun onTimers(state: TimersUiState)
        fun onEarcon(kind: EarconKind)
    }

    companion object {
        const val PORT = 10600
        private const val TAG = "SatelliteServer"
        private const val BIND_RETRY_MS = 5_000L
        private const val TICK_MS = 500L
        private const val DETECTOR_QUEUE_MAX = 8
        private val RESET_MARKER = Any()
    }

    private class Connection(val socket: Socket, val out: OutputStream)

    @Volatile var boundPort: Int = -1
        private set

    // Recreated on each start() with the current localWake flag; device-local timers persist
    // across HA reconnects (same session instance) but are dropped on a start()/stop() cycle
    // (voice enable/disable or a wake-word/threshold change), which is rare and acceptable.
    @Volatile private var session = SatelliteSession(appVersion, name)
    private val lock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    private var active: Connection? = null
    private var acceptJob: Job? = null
    private var tickJob: Job? = null

    // On-device wake detection (localWake only).
    @Volatile private var detector: WakeDetector? = null
    @Volatile private var wakeWord: String = "okay_nabu"
    private val detectorQueue = LinkedBlockingDeque<Any>()
    @Volatile private var detectorThread: Thread? = null

    fun start(localWake: Boolean = false, detector: WakeDetector? = null, wakeWord: String = "okay_nabu") {
        if (acceptJob?.isActive == true) return
        session = SatelliteSession(appVersion, name, localWake)
        this.detector = if (localWake) detector else null
        this.wakeWord = wakeWord
        startDetectorThread()
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val server = try {
                    ServerSocket(port)
                } catch (e: IOException) {
                    Log.w(TAG, "bind failed, retrying", e)
                    delay(BIND_RETRY_MS)
                    continue
                }
                serverSocket = server
                boundPort = server.localPort
                try {
                    while (isActive) {
                        val socket = server.accept()
                        launch { handle(socket) }
                    }
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "accept loop ended", e)
                } finally {
                    runCatching { server.close() }
                    serverSocket = null
                    boundPort = -1
                }
            }
        }
        // Runs regardless of connection state so timers keep counting down while HA is away.
        tickJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TICK_MS)
                synchronized(lock) { dispatch(active, session.onTick(System.currentTimeMillis())) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel(); acceptJob = null
        tickJob?.cancel(); tickJob = null
        detectorThread?.interrupt(); detectorThread = null
        detectorQueue.clear()
        detector = null
        runCatching { serverSocket?.close() }
        synchronized(lock) {
            active?.let { runCatching { it.socket.close() } }
            active = null
        }
    }

    /** Feed a mic chunk; resulting audio-chunk/FeedDetector actions run against the active session. */
    fun submitMicChunk(pcm: ByteArray) {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onMicChunk(pcm))
        }
    }

    fun reportMicError() {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onMicError())
        }
    }

    fun onPlaybackFinished() {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onPlaybackFinished(System.currentTimeMillis()))
        }
    }

    /** Tap on the "Timer done" overlay: clear the alert (may run with no active connection). */
    fun dismissTimerAlert() {
        synchronized(lock) { dispatch(active, session.onTimerAlertDismissed(System.currentTimeMillis())) }
    }

    private fun handle(socket: Socket) {
        val conn = try {
            Connection(socket, socket.getOutputStream().buffered())
        } catch (e: IOException) {
            runCatching { socket.close() }
            return
        }
        // Promotion to the active connection is deferred until the first event other than
        // describe/ping. HA's wyoming config_flow probes the advertised _wyoming._tcp service
        // with a bare describe on every mDNS refresh; promoting those probes killed the live
        // satellite connection (HA saw a reset, silently restarted its loop, and the device
        // went deaf for the ~3s reconnect — wake words chirped but never reached HA).
        // describe/ping are stateless and answered on the probing connection itself.
        var promoted = false
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val event = WyomingCodec.read(input) ?: break
                if (event.type != "ping") Log.d(TAG, "recv ${event.type} ${event.data}")
                synchronized(lock) {
                    if (!promoted && event.type != "describe" && event.type != "ping") {
                        active?.let { runCatching { it.socket.close() } }  // newest real client wins
                        active = conn
                        promoted = true
                        dispatch(conn, session.onConnected())
                    }
                    if (promoted && active !== conn) return           // superseded
                    dispatch(conn, session.onEvent(event, System.currentTimeMillis()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
        } finally {
            synchronized(lock) {
                if (active === conn) {
                    dispatch(conn, session.onDisconnected())
                    active = null
                }
            }
            runCatching { socket.close() }
        }
    }

    /**
     * Must be called while holding [lock]. Writes are small Wyoming frames. [conn] may be null
     * (e.g. a timer tick while HA is disconnected): Send actions are then dropped — timer/overlay
     * actions never produce Sends, so nothing is lost. FeedDetector/ResetDetector are handled
     * internally against the detector thread (never surfaced on [out]).
     */
    private fun dispatch(conn: Connection?, actions: List<SatelliteAction>) {
        for (a in actions) when (a) {
            is SatelliteAction.Send ->
                if (conn != null) {
                    if (a.event.type != "audio-chunk" && a.event.type != "pong") {
                        Log.d(TAG, "send ${a.event.type} ${a.event.data}")
                    }
                    try { WyomingCodec.write(a.event, conn.out) } catch (e: Exception) { Log.w(TAG, "write failed", e) }
                }
            SatelliteAction.StartMic -> out.onStartMic()
            SatelliteAction.StopMic -> out.onStopMic()
            is SatelliteAction.PlaybackStart -> out.onPlaybackStart(a.rate, a.width, a.channels)
            is SatelliteAction.PlaybackChunk -> out.onPlaybackChunk(a.pcm)
            SatelliteAction.PlaybackStop -> out.onPlaybackStop()
            is SatelliteAction.Overlay -> out.onOverlay(a.state)
            is SatelliteAction.Timers -> out.onTimers(a.state)
            is SatelliteAction.Earcon -> out.onEarcon(a.kind)
            is SatelliteAction.FeedDetector -> enqueueDetector(a.pcm)
            SatelliteAction.ResetDetector -> {
                detectorQueue.clear()
                detectorQueue.offer(RESET_MARKER)
            }
        }
    }

    private fun enqueueDetector(pcm: ByteArray) {
        // Drop oldest so the mic path never blocks on slow inference.
        while (detectorQueue.size >= DETECTOR_QUEUE_MAX) detectorQueue.pollFirst()
        detectorQueue.offer(pcm)
    }

    private fun startDetectorThread() {
        val det = detector ?: return
        detectorQueue.clear()
        detectorThread = thread(name = "WakeDetector", isDaemon = true) {
            var windowMax = 0f
            var windowStart = System.currentTimeMillis()
            try {
                while (true) {
                    val item = detectorQueue.take()
                    if (item === RESET_MARKER) {
                        det.reset()
                        continue
                    }
                    if (item !is ByteArray) continue
                    val fired = det.process(item)
                    val score = det.lastScore
                    if (score > windowMax) windowMax = score
                    val nowW = System.currentTimeMillis()
                    if (nowW - windowStart >= 5_000L) {
                        Log.d(TAG, "wake max score=%.2f (5s)".format(windowMax))
                        windowMax = 0f
                        windowStart = nowW
                    }
                    if (fired) {
                        synchronized(lock) {
                            val conn = active
                            if (conn != null) {
                                Log.i(TAG, "wake '$wakeWord' score=%.2f".format(score))
                                dispatch(conn, session.onWakeDetected(wakeWord, System.currentTimeMillis()))
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // stop() interrupted us; exit cleanly.
            } catch (e: Exception) {
                Log.w(TAG, "wake detector thread died", e)
            }
        }
    }
}
