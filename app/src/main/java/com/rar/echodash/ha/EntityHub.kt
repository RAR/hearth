package com.rar.echodash.ha

import com.rar.echodash.config.DashConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    private val _registry = MutableStateFlow(RegistryIndex(emptyMap(), emptyMap()))
    val registry: StateFlow<RegistryIndex> = _registry

    private var entitiesSubId: Int? = null
    private var watched: List<String> = emptyList()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            client.connectionState.collect { st ->
                if (st == ConnState.CONNECTED) resync()
            }
        }
        scope.launch {
            config
                .map { it.referencedEntityIds() }
                .distinctUntilChanged()
                .collect { onConfigChanged(it) }
        }
    }

    private suspend fun resync() {
        val reg = listRegistry() ?: return
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

    private suspend fun onConfigChanged(newWatched: List<String>) {
        if (newWatched.toSet() == watched.toSet()) return
        if (entitiesSubId == null) { watched = newWatched; return } // not subscribed yet; resync will use it
        try {
            entitiesSubId?.let { client.unsubscribe(it) }
            watched = newWatched
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
}
