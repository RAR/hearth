package com.rar.hearth.ha

import com.rar.hearth.data.SettingsStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }

fun wsUrl(baseUrl: String): String = baseUrl.replaceFirst("http", "ws") + "/api/websocket"

/** Exponential reconnect-backoff CEILING: 2s, 4s, 8s, 16s, 32s, capped at 60s. */
fun backoffMs(attempt: Int): Long =
    (2_000L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(60_000L)

/**
 * Equal-jitter reconnect delay in `[ceil/2, ceil]` where `ceil` = [backoffMs] for this attempt. Half
 * the delay is fixed (so attempt 0 still paces ~1s+) and half is random, spreading the several kiosks
 * that share one HA server so they don't reconnect in lockstep after a co-restart. RNG injected so
 * the bound is unit-testable.
 */
fun nextBackoffMs(attempt: Int, random: Random = Random): Long {
    val ceil = backoffMs(attempt)
    return ceil / 2 + random.nextLong(ceil / 2 + 1) // [ceil/2, ceil]
}

/** General Home Assistant WebSocket client: request/reply + id-routed subscriptions. */
interface HaClient {
    val connectionState: StateFlow<ConnState>
    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement?
    suspend fun subscribe(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        onEvent: (JsonObject) -> Unit,
    ): Int
    suspend fun unsubscribe(subId: Int)
}

class HaWebSocket(
    private val settings: SettingsStore,
    private val auth: AuthManager,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : HaClient {
    private val _connectionState = MutableStateFlow(ConnState.OFFLINE)
    override val connectionState: StateFlow<ConnState> = _connectionState

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val idCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

    fun start() {
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

    override suspend fun request(type: String, fields: JsonObject): JsonElement? {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        socket?.send(command(id, type, fields)) ?: run { pending.remove(id); throw IOException("websocket closed") }
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit): Int {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        subscriptions[id] = onEvent
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        socket?.send(command(id, type, fields)) ?: run {
            pending.remove(id); subscriptions.remove(id); throw IOException("websocket closed")
        }
        try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
        return id
    }

    override suspend fun unsubscribe(subId: Int) {
        subscriptions.remove(subId)
        runCatching {
            request("unsubscribe_events", buildJsonObject { put("subscription", JsonPrimitive(subId)) })
        }
    }

    private fun command(id: Int, type: String, fields: JsonObject): String =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            fields.forEach { (k, v) -> put(k, v) }
        }.toString()

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnState.CONNECTING
            val session = Session()
            try {
                val token = auth.validAccessToken()
                socket = openSocket(token, session)
                session.closed.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRevokedException) {
                _connectionState.value = ConnState.AUTH_FAILED
                return
            } catch (e: Exception) {
                // network error before/at connect — fall through to backoff
            } finally {
                failPending()
            }
            _connectionState.value = ConnState.OFFLINE
            attempt = if (session.sawAuthOk) 0 else attempt + 1
            delay(nextBackoffMs(attempt))
        }
    }

    private fun failPending() {
        subscriptions.clear()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            it.remove()
            entry.value.completeExceptionally(IOException("websocket closed"))
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
                        }
                        is WsIncoming.AuthInvalid -> {
                            auth.invalidateAccessToken()
                            webSocket.close(1000, "auth invalid")
                        }
                        is WsIncoming.Event -> subscriptions[msg.id]?.invoke(msg.event)
                        is WsIncoming.Result -> pending.remove(msg.id)?.complete(msg.result)
                        is WsIncoming.Unknown -> {}
                    }
                } catch (e: Exception) {
                    android.util.Log.w("HaWebSocket", "dropped frame", e)
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
