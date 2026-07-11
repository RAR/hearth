package com.rar.echodash.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Resolves echo-* labels to entities and maintains their live states over one subscribe_entities
 * subscription. Re-lists and re-subscribes when the entity registry changes. Pure orchestration:
 * all parsing/diffing lives in [parseEntityRegistry]/[applyEntitiesEvent].
 */
class EntityHub(
    private val client: HaClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entities = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entities: StateFlow<Map<String, EntityState>> = _entities

    private val _registry = MutableStateFlow(RegistryIndex(emptyMap(), emptyMap()))
    val registry: StateFlow<RegistryIndex> = _registry

    private var entitiesSubId: Int? = null
    private var matched: List<String> = emptyList()

    fun start() {
        scope.launch {
            client.connectionState.collect { st ->
                if (st == ConnState.CONNECTED) resync()
            }
        }
    }

    private suspend fun resync() {
        val reg = listRegistry() ?: return
        _registry.value = reg
        matched = reg.allEntityIds
        _entities.value = emptyMap()
        openEntitiesSubscription()
        client.subscribe("subscribe_events", buildJsonObject { put("event_type", "entity_registry_updated") }) {
            scope.launch { onRegistryUpdated() }
        }
    }

    private suspend fun onRegistryUpdated() {
        val reg = listRegistry() ?: return
        _registry.value = reg
        val newMatched = reg.allEntityIds
        if (newMatched.toSet() != matched.toSet()) {
            entitiesSubId?.let { client.unsubscribe(it) }
            matched = newMatched
            _entities.value = emptyMap()
            openEntitiesSubscription()
        }
    }

    private suspend fun listRegistry(): RegistryIndex? =
        runCatching { client.request("config/entity_registry/list") }
            .getOrNull()
            ?.let { parseEntityRegistry(it) }

    private suspend fun openEntitiesSubscription() {
        entitiesSubId = client.subscribe(
            "subscribe_entities",
            buildJsonObject { putJsonArray("entity_ids") { matched.forEach { add(it) } } },
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
