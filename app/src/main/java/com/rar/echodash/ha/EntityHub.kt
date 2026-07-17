package com.rar.echodash.ha

import com.rar.echodash.config.DashConfig
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Maintains live states for the entities referenced by [config] over one subscribe_entities
 * subscription. The watched set is DashConfig.referencedEntityIds() — labels no longer decide it.
 * Re-lists the registry (names + full entity list for the web picker) on connect and on
 * entity_registry_updated; re-subscribes entities whenever the config's referenced set changes.
 */
class EntityHub(
    private val client: HaClient,
    private val scope: CoroutineScope,
    private val config: StateFlow<DashConfig>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entities = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entities: StateFlow<Map<String, EntityState>> = _entities

    private val _registry = MutableStateFlow(RegistryIndex(emptyMap()))
    val registry: StateFlow<RegistryIndex> = _registry

    // Guards all hub mutations (entitiesSubId/watched/_entities + subscribe/unsubscribe) so a config
    // change and a reconnect resync can never interleave their subscribe calls in the CONNECTED state.
    private val mutex = Mutex()
    @Volatile private var entitiesSubId: Int? = null
    private var watched: List<String> = emptyList()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            client.connectionState.collect { st ->
                when (st) {
                    ConnState.CONNECTED -> resync()
                    // Link dropped: HaWebSocket already cleared server-side routing for our subscription,
                    // so there is nothing to unsubscribe. Forget the stale id so onConfigChanged does not
                    // treat us as "still subscribed" and reopen while offline.
                    ConnState.OFFLINE -> entitiesSubId = null
                    else -> {}
                }
            }
        }
        scope.launch {
            config
                .map { it.referencedEntityIds() }
                .distinctUntilChanged()
                .collect { onConfigChanged(it) }
        }
    }

    private suspend fun resync() = mutex.withLock {
        val reg = listRegistry() ?: return@withLock
        _registry.value = reg
        watched = config.value.referencedEntityIds()
        _entities.value = emptyMap()
        try {
            openEntitiesSubscription()
            client.subscribe("subscribe_events", buildJsonObject { put("event_type", "entity_registry_updated") }) {
                scope.launch { onRegistryUpdated() }
            }
        } catch (e: IOException) {
            // socket dropped mid-resync; the next CONNECTED transition retries from scratch
            android.util.Log.w("EntityHub", "resync failed", e)
        }
    }

    /** Registry changed in HA: refresh names + picker list. The watched set is config-driven, so this
     * never re-subscribes entities. */
    private suspend fun onRegistryUpdated() {
        listRegistry()?.let { _registry.value = it }
    }

    private suspend fun onConfigChanged(newWatched: List<String>) = mutex.withLock {
        if (newWatched.toSet() == watched.toSet()) return@withLock
        watched = newWatched
        // Only reopen live when we are connected AND already hold a subscription. If the link is down
        // (or we have not subscribed yet), the real client's subscribe()/unsubscribe() would park until
        // CONNECTED and then race the reconnect resync into a second subscribe_entities. Defer instead:
        // the CONNECTED resync re-derives the set from config.value and opens the single subscription.
        if (client.connectionState.value != ConnState.CONNECTED || entitiesSubId == null) return@withLock
        try {
            entitiesSubId?.let { client.unsubscribe(it) }
            _entities.value = emptyMap()
            openEntitiesSubscription()
        } catch (e: IOException) {
            // socket dropped mid-update; the next CONNECTED transition resyncs from scratch
            android.util.Log.w("EntityHub", "onConfigChanged failed", e)
        }
    }

    private suspend fun listRegistry(): RegistryIndex? =
        runCatching { client.request("config/entity_registry/list") }
            .getOrNull()
            ?.let { parseEntityRegistry(it) }

    private suspend fun openEntitiesSubscription() {
        entitiesSubId = client.subscribe(
            "subscribe_entities",
            buildJsonObject { putJsonArray("entity_ids") { watched.forEach { add(it) } } },
        ) { event ->
            _entities.value = applyEntitiesEvent(_entities.value, event, clock())
        }
    }

    fun callService(
        domain: String,
        service: String,
        serviceData: JsonObject = JsonObject(emptyMap()),
        entityId: String? = null,
    ) {
        scope.launch {
            runCatching {
                client.request("call_service", buildJsonObject {
                    put("domain", domain)
                    put("service", service)
                    if (serviceData.isNotEmpty()) put("service_data", serviceData)
                    if (entityId != null) putJsonObject("target") { put("entity_id", entityId) }
                })
            }.onFailure { android.util.Log.w("EntityHub", "call_service $domain.$service failed", it) }
        }
    }

    suspend fun getForecasts(entityId: String): JsonElement? =
        runCatching {
            client.request("call_service", buildJsonObject {
                put("domain", "weather")
                put("service", "get_forecasts")
                putJsonObject("service_data") { put("type", "daily") }
                putJsonObject("target") { put("entity_id", entityId) }
                put("return_response", JsonPrimitive(true))
            })
        }.getOrNull()

    /**
     * One calendar.get_events call for all configured calendars. Returns the raw
     * {"response":{"<entity>":{"events":[...]}}} element, or null on any failure (caller keeps last
     * good list). Window is now .. now+5 days, RFC3339 with the device's local offset
     * (e.g. 2026-07-14T11:30:00-04:00). Events come from this service call, NOT state subscriptions.
     */
    suspend fun getCalendarEvents(entityIds: List<String>): JsonElement? =
        runCatching {
            val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            // 5-day window: the agenda can now show up to 5 day columns (AdaptiveGeometry.agendaDayCount
            // caps at 5) and the home next-event card gets the longer horizon for free.
            val end = now.plusDays(5)
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            client.request("call_service", buildJsonObject {
                put("domain", "calendar")
                put("service", "get_events")
                putJsonObject("service_data") {
                    put("start_date_time", now.format(fmt))
                    put("end_date_time", end.format(fmt))
                }
                putJsonObject("target") {
                    putJsonArray("entity_id") { entityIds.forEach { add(it) } }
                }
                put("return_response", JsonPrimitive(true))
            })
        }.getOrNull()

    /** Ask HA to prepare an HLS stream. Returns e.g. {"url":"/api/hls/<token>/master_playlist.m3u8"}
     * or null on any failure — the StreamResolver maps null/missing url to Unavailable. */
    suspend fun cameraStream(entityId: String): JsonElement? =
        runCatching {
            client.request("camera/stream", buildJsonObject { put("entity_id", entityId) })
        }.getOrNull()
}
