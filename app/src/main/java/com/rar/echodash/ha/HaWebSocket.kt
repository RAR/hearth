package com.rar.echodash.ha

import com.rar.echodash.data.SettingsStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

/** General Home Assistant WebSocket client: request/reply + id-routed subscriptions. */
interface HaClient {
    val connectionState: StateFlow<ConnState>
    /** Send a command and await its "result" payload. Throws [IOException] if the socket drops first. */
    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement?
    /** Subscribe; [onEvent] receives each event's inner "event" object. Returns the subscription id. */
    suspend fun subscribe(
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        onEvent: (JsonObject) -> Unit,
    ): Int
    /** Cancel a subscription created by [subscribe]. */
    suspend fun unsubscribe(subId: Int)
}

class HaWebSocket(
    private val settings: SettingsStore,
    private val auth: AuthManager,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : HaClient {
    private val _connectionState = MutableStateFlow(ConnState.OFFLINE)
    override val connectionState: StateFlow<ConnState> = _connectionState

    // --- legacy single-temperature path (removed in Task 11) ---
    private val _reading = MutableStateFlow<TempReading?>(null)
    val reading: StateFlow<TempReading?> = _reading
    @Volatile private var entityId: String? = null

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val idCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

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

    override suspend fun request(type: String, fields: JsonObject): JsonElement? {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        val command = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            fields.forEach { (k, v) -> put(k, v) }
        }
        socket?.send(command.toString()) ?: run { pending.remove(id); throw IOException("websocket closed") }
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun subscribe(
        type: String,
        fields: JsonObject,
        onEvent: (JsonObject) -> Unit,
    ): Int {
        connectionState.first { it == ConnState.CONNECTED }
        val id = idCounter.getAndIncrement()
        subscriptions[id] = onEvent
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        val command = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            fields.forEach { (k, v) -> put(k, v) }
        }
        socket?.send(command.toString()) ?: run {
            pending.remove(id); subscriptions.remove(id); throw IOException("websocket closed")
        }
        try {
            deferred.await() // wait for the subscribe result ack; events follow on the same id
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

    /** Fetch temperature sensors (legacy picker path; removed in Task 11). */
    suspend fun fetchTemperatureSensors(): List<SensorEntity> {
        val result = request("get_states") ?: return emptyList()
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
            delay(backoffMs(attempt))
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
                            entityId?.let { id -> subscribeTemp(webSocket, id) }
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

    /** Legacy: subscribe one temp sensor and push [_reading]. Removed in Task 11. */
    private fun subscribeTemp(webSocket: WebSocket, id: String) {
        val subId = idCounter.getAndIncrement()
        subscriptions[subId] = { event -> applyTempEvent(event, id) }
        webSocket.send("""{"id":$subId,"type":"subscribe_entities","entity_ids":["$id"]}""")
    }

    private fun applyTempEvent(event: JsonObject, id: String) {
        val patch = (event["a"] as? JsonObject)?.get(id)?.jsonObject
            ?: (event["c"] as? JsonObject)?.get(id)?.jsonObject?.get("+")?.jsonObject
            ?: return
        val prev = _reading.value
        val state = (patch["s"] as? JsonPrimitive)?.contentOrNull ?: prev?.value ?: return
        val unit = (patch["a"] as? JsonObject)?.get("unit_of_measurement")?.let {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: prev?.unit
        _reading.value = TempReading(value = state, unit = unit, updatedAtMs = clock())
    }
}
