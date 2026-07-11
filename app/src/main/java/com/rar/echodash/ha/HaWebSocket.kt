package com.rar.echodash.ha

import com.rar.echodash.data.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }

data class TempReading(val value: String, val unit: String?, val updatedAtMs: Long)

fun wsUrl(baseUrl: String): String = baseUrl.replaceFirst("http", "ws") + "/api/websocket"

fun backoffMs(attempt: Int): Long =
    (2_000L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(60_000L)

class HaWebSocket(
    private val settings: SettingsStore,
    private val auth: AuthManager,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _connectionState = MutableStateFlow(ConnState.OFFLINE)
    val connectionState: StateFlow<ConnState> = _connectionState

    private val _reading = MutableStateFlow<TempReading?>(null)
    val reading: StateFlow<TempReading?> = _reading

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val idCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    @Volatile private var entityId: String? = null

    fun start(entityId: String?) {
        this.entityId = entityId
        job?.cancel()
        socket?.cancel()
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
        _connectionState.value = ConnState.OFFLINE
    }

    suspend fun fetchTemperatureSensors(): List<EntityState> {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        socket?.send("""{"id":$id,"type":"get_states"}""")
            ?: run { pending.remove(id); return emptyList() }
        val result = deferred.await() ?: return emptyList()
        return WsParser.temperatureSensors(result)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnState.CONNECTING
            val session = Session()
            try {
                val token = auth.validAccessToken()
                socket = openSocket(token, session)
                session.closed.await()
            } catch (e: AuthRevokedException) {
                _connectionState.value = ConnState.AUTH_FAILED
                return
            } catch (e: Exception) {
                // network error before/at connect — fall through to backoff
            }
            _connectionState.value = ConnState.OFFLINE
            attempt = if (session.sawAuthOk) 0 else attempt + 1
            delay(backoffMs(attempt))
        }
    }

    private class Session {
        val closed = CompletableDeferred<Unit>()
        @Volatile var sawAuthOk = false
    }

    private fun openSocket(token: String, session: Session): WebSocket {
        val base = settings.baseUrl ?: error("no base url configured")
        val request = Request.Builder().url(wsUrl(base)).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when (val msg = WsParser.parse(text)) {
                        is WsIncoming.AuthRequired ->
                            webSocket.send("""{"type":"auth","access_token":"$token"}""")
                        is WsIncoming.AuthOk -> {
                            session.sawAuthOk = true
                            _connectionState.value = ConnState.CONNECTED
                            entityId?.let { id ->
                                webSocket.send(
                                    """{"id":${idCounter.getAndIncrement()},"type":"subscribe_entities","entity_ids":["$id"]}"""
                                )
                            }
                        }
                        // Possibly an expired token raced the connect; close and let the
                        // reconnect loop refresh via validAccessToken().
                        is WsIncoming.AuthInvalid -> webSocket.close(1000, "auth invalid")
                        is WsIncoming.EntityUpdate -> {
                            val patch = entityId?.let { msg.states[it] } ?: return
                            val prev = _reading.value
                            val value = patch.state ?: prev?.value ?: return
                            _reading.value = TempReading(
                                value = value,
                                unit = patch.unit ?: prev?.unit,
                                updatedAtMs = clock(),
                            )
                        }
                        is WsIncoming.Result -> pending.remove(msg.id)?.complete(msg.result)
                        is WsIncoming.Unknown -> {}
                    }
                } catch (e: Exception) {
                    // malformed or unexpected frame — ignore
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.closed.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                session.closed.complete(Unit)
            }
        })
    }
}
