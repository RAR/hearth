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

/**
 * Wyoming TCP server for the voice satellite (port 10600). HA connects inbound.
 * Newest connection wins. Reader runs off the lock; the active [SatelliteSession]
 * and all socket writes are serialized on [lock], so pongs are never starved by
 * blocking playback (playback is offloaded to an AnnouncePlayer via [out]). Note:
 * outbound mic-chunk writes share the same socket and can still stall pongs under
 * TCP back-pressure (e.g. a stalled HA); this is bounded and self-heals via disconnect.
 */
class SatelliteServer(
    private val scope: CoroutineScope,
    private val port: Int = PORT,
    private val appVersion: String,
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
    }

    companion object {
        const val PORT = 10600
        private const val TAG = "SatelliteServer"
        private const val BIND_RETRY_MS = 5_000L
        private const val TICK_MS = 500L
    }

    private class Connection(val socket: Socket, val out: OutputStream)

    @Volatile var boundPort: Int = -1
        private set

    // One session for the server's lifetime so device-local timers persist across connections.
    private val session = SatelliteSession(appVersion)
    private val lock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    private var active: Connection? = null
    private var acceptJob: Job? = null
    private var tickJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return
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
        runCatching { serverSocket?.close() }
        synchronized(lock) {
            active?.let { runCatching { it.socket.close() } }
            active = null
        }
    }

    /** Feed a mic chunk; resulting audio-chunk is written to the active socket (dropped if none). */
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
        synchronized(lock) {
            active?.let { runCatching { it.socket.close() } }  // newest wins
            active = conn
            dispatch(conn, session.onConnected())
        }
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val event = WyomingCodec.read(input) ?: break
                synchronized(lock) {
                    if (active !== conn) return           // superseded
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
     * actions never produce Sends, so nothing is lost.
     */
    private fun dispatch(conn: Connection?, actions: List<SatelliteAction>) {
        for (a in actions) when (a) {
            is SatelliteAction.Send ->
                if (conn != null) {
                    try { WyomingCodec.write(a.event, conn.out) } catch (e: Exception) { Log.w(TAG, "write failed", e) }
                }
            SatelliteAction.StartMic -> out.onStartMic()
            SatelliteAction.StopMic -> out.onStopMic()
            is SatelliteAction.PlaybackStart -> out.onPlaybackStart(a.rate, a.width, a.channels)
            is SatelliteAction.PlaybackChunk -> out.onPlaybackChunk(a.pcm)
            SatelliteAction.PlaybackStop -> out.onPlaybackStop()
            is SatelliteAction.Overlay -> out.onOverlay(a.state)
            is SatelliteAction.Timers -> out.onTimers(a.state)
        }
    }
}
