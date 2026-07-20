package com.rar.hearth.vaca

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Wyoming TCP server for the VACA integration. HA is the client: it makes a
 * short-lived probe connection (describe/capabilities) plus a persistent
 * satellite session that begins with run-satellite. Handshake and ping/pong
 * are handled here; settings/actions/audio are routed to [listener] on IO
 * threads (audio callbacks must NEVER block — HA keepalive pings share this
 * connection and time out in 5 s; AnnouncePlayer enqueues and returns).
 */
class VacaServer(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val infoEvent: () -> WyomingEvent,
    private val capabilitiesEvent: () -> WyomingEvent,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionStarted()
        fun onSettings(settings: JsonObject)
        fun onAction(action: String, payload: JsonElement?)
        fun onAudioStart(rate: Int, width: Int, channels: Int)
        fun onAudioChunk(pcm: ByteArray)
        fun onAudioStop()
        fun onSessionEnded()
    }

    companion object {
        const val DEFAULT_PORT = 10700
        private const val TAG = "VacaServer"
        private const val BIND_RETRY_MS = 5_000L
    }

    private class Connection(val socket: Socket, val out: OutputStream) {
        val writeMutex = Mutex()
    }

    @Volatile var boundPort: Int = -1
        private set

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var active: Connection? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val server = try {
                    ServerSocket(port)
                } catch (e: IOException) {
                    Log.w(TAG, "bind failed, retrying in ${BIND_RETRY_MS}ms", e)
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
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        runCatching { active?.socket?.close() }
        active = null
    }

    suspend fun sendSettingsFeedback(settings: JsonObject) =
        send(VacaOutgoing.settingsFeedback(settings))

    suspend fun sendStatus(status: JsonObject) = send(VacaOutgoing.status(status))

    suspend fun sendPlayed() = send(VacaOutgoing.played())

    private suspend fun send(event: WyomingEvent) {
        val conn = active ?: return
        try {
            withContext(Dispatchers.IO) {
                conn.writeMutex.withLock { WyomingCodec.write(event, conn.out) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    private suspend fun handle(socket: Socket) {
        val conn = try {
            Connection(socket, socket.getOutputStream().buffered())
        } catch (e: IOException) {
            runCatching { socket.close() }
            return
        }
        suspend fun reply(event: WyomingEvent) =
            conn.writeMutex.withLock { WyomingCodec.write(event, conn.out) }

        var isSession = false
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val event = WyomingCodec.read(input) ?: break
                when (val msg = VacaParser.parse(event)) {
                    VacaIncoming.Describe -> reply(infoEvent())
                    VacaIncoming.CapabilitiesRequest -> reply(capabilitiesEvent())
                    is VacaIncoming.Ping -> reply(VacaOutgoing.pong(msg.text))
                    VacaIncoming.RunSatellite -> {
                        active = conn
                        isSession = true
                        listener.onSessionStarted()
                    }
                    is VacaIncoming.SettingsChanged -> listener.onSettings(msg.settings)
                    is VacaIncoming.Action -> listener.onAction(msg.action, msg.payload)
                    is VacaIncoming.AudioStart ->
                        listener.onAudioStart(msg.rate, msg.width, msg.channels)
                    is VacaIncoming.AudioChunk -> listener.onAudioChunk(msg.pcm)
                    VacaIncoming.AudioStop -> listener.onAudioStop()
                    is VacaIncoming.Unknown -> Log.d(TAG, "ignoring event ${msg.type}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
        } finally {
            if (isSession && active === conn) {
                active = null
                listener.onSessionEnded()
            }
            runCatching { socket.close() }
        }
    }
}
