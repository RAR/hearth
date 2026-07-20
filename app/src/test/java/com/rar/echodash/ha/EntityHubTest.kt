package com.rar.echodash.ha

import com.rar.echodash.config.DashConfig
import com.rar.echodash.config.Entities
import com.rar.echodash.config.LightGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntityHubTest {

    private class FakeHaClient : HaClient {
        val state = MutableStateFlow(ConnState.OFFLINE)
        override val connectionState: StateFlow<ConnState> = state
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val results = ArrayDeque<JsonElement?>()
        val subscribed = mutableListOf<Pair<String, JsonObject>>()
        val handlers = mutableMapOf<Int, (JsonObject) -> Unit>()
        val unsubscribed = mutableListOf<Int>()
        private var nextId = 100

        // Model the real HaWebSocket: request/subscribe/unsubscribe suspend until CONNECTED
        // rather than throwing while the link is down (HaWebSocket.kt:78/91).
        override suspend fun request(type: String, fields: JsonObject): JsonElement? {
            state.first { it == ConnState.CONNECTED }
            requests += type to fields
            return if (results.isEmpty()) null else results.removeFirst()
        }
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit): Int {
            state.first { it == ConnState.CONNECTED }
            subscribed += type to fields
            val id = nextId++
            handlers[id] = onEvent
            return id
        }
        override suspend fun unsubscribe(subId: Int) {
            state.first { it == ConnState.CONNECTED }
            unsubscribed += subId
        }

        /** entity_ids field of the Nth subscribe_entities call. */
        fun entityIdsOf(index: Int): List<String> =
            (subscribed.filter { it.first == "subscribe_entities" }[index].second["entity_ids"] as JsonArray)
                .map { it.jsonPrimitive.contentOrNull!! }
    }

    private val registryJson =
        """[{"entity_id":"light.kitchen","labels":[],"name":null,"original_name":"Kitchen"},
            {"entity_id":"climate.hall","labels":[],"name":null,"original_name":"Hall"}]"""

    private fun config(vararg ids: String) =
        MutableStateFlow(DashConfig(entities = Entities(lightGroups = listOf(LightGroup("G", ids.toList())))))

    @Test
    fun watchedSetComesFromConfigNotLabels() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals("config/entity_registry/list", fake.requests[0].first)
        assertEquals(listOf("light.kitchen"), fake.entityIdsOf(0))   // from config, not registry labels
    }

    @Test
    fun reSubscribesWhenConfigReferencedSetChanges() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val cfg = config("light.kitchen")
        val hub = EntityHub(fake, backgroundScope, cfg) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })

        cfg.value = DashConfig(entities = Entities(lightGroups = listOf(LightGroup("G", listOf("light.kitchen", "climate.hall")))))
        runCurrent()
        assertTrue(fake.unsubscribed.isNotEmpty())
        assertEquals(2, fake.subscribed.count { it.first == "subscribe_entities" })
        assertEquals(listOf("light.kitchen", "climate.hall"), fake.entityIdsOf(1))
    }

    @Test
    fun registryUpdatedRefreshesNamesWithoutReSubscribingEntities() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })

        // a registry_updated event re-lists (refreshing the picker names) but does NOT touch the sub
        fake.results.add(Json.parseToJsonElement(
            """[{"entity_id":"light.kitchen","labels":[],"name":"Kitchen Light","original_name":"Kitchen"}]"""
        ))
        val regSub = fake.handlers.keys.sorted()[1] // index 0 = entities, index 1 = registry-updated
        fake.handlers.getValue(regSub)(Json.parseToJsonElement(
            """{"event_type":"entity_registry_updated","data":{"action":"update","entity_id":"light.kitchen"}}"""
        ) as JsonObject)
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" }) // unchanged
        assertEquals("Kitchen Light", hub.registry.value.registryNames["light.kitchen"])
    }

    @Test
    fun reconnectReSubscribesAfterOfflineThenConnected() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
        fake.state.value = ConnState.OFFLINE
        runCurrent()
        fake.results.add(Json.parseToJsonElement(registryJson))
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(2, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun configChangeWhileOfflineDefersToReconnectResync() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val cfg = config("light.kitchen")
        val hub = EntityHub(fake, backgroundScope, cfg) { 0L }
        hub.start()

        // Connect: the single subscribe_entities opens.
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })

        // Link drops. The local web UI is still reachable, so the user saves a new config.
        fake.state.value = ConnState.OFFLINE
        runCurrent()
        cfg.value = DashConfig(entities = Entities(lightGroups = listOf(LightGroup("G", listOf("light.kitchen", "climate.hall")))))
        runCurrent()

        // While offline nothing new may be opened — the reopen must defer to the reconnect resync.
        val beforeReconnect = fake.subscribed.count { it.first == "subscribe_entities" }

        // Reconnect: exactly ONE new subscribe_entities may follow, carrying the NEW set.
        fake.results.add(Json.parseToJsonElement(registryJson))
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        advanceUntilIdle()

        val afterReconnect = fake.subscribed.count { it.first == "subscribe_entities" }
        assertEquals(1, afterReconnect - beforeReconnect)
        assertEquals(listOf("light.kitchen", "climate.hall"), fake.entityIdsOf(afterReconnect - 1))
    }

    // The hub's connectionState collector runs in backgroundScope. Under the StandardTestDispatcher,
    // backgroundScope delays are advanced by advanceTimeBy (not by advanceUntilIdle, which only drains
    // the foreground), so the resync backoff loop is driven with runCurrent + advanceTimeBy. 20s clears
    // the full 500+1000+2000+4000+8000ms backoff schedule.
    @Test
    fun resyncRetriesRegistryThenSubscribesOnceHaBecomesReady() = runTest {
        val fake = FakeHaClient()
        // HA not ready for the first two registry lists (device connected before HA fully came up on a
        // co-restart), then it comes up and the third list succeeds.
        fake.results.add(null)
        fake.results.add(null)
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        advanceTimeBy(20_000) // drive virtual time through the backoff delays
        runCurrent()

        assertTrue(fake.requests.count { it.first == "config/entity_registry/list" } >= 3)
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
        assertTrue(hub.registry.value.registryNames.containsKey("light.kitchen"))
    }

    @Test
    fun resyncSubscribesBestEffortWhenRegistryNeverLoads() = runTest {
        val fake = FakeHaClient()
        // Nothing preloaded: every config/entity_registry/list returns null (HA never becomes ready).
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        // Entity data path still comes up despite the registry never loading (best-effort degrade).
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
        // Initial try + RESYNC_MAX_RETRIES (5) retries == 6 total registry-list requests.
        assertEquals(6, fake.requests.count { it.first == "config/entity_registry/list" })
    }

    @Test
    fun resyncStopsRetryingWhenLinkDropsMidRetry() = runTest {
        val fake = FakeHaClient()
        // Nothing preloaded: the registry list keeps failing.
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()                          // first failed attempt runs; loop parks in the backoff delay
        fake.state.value = ConnState.OFFLINE  // link drops before the retry fires
        advanceTimeBy(20_000)                 // delays elapse, loop sees OFFLINE and bails before subscribing
        runCurrent()

        // The loop deferred to the next CONNECTED transition instead of subscribing onto a dead/reconnecting link.
        assertEquals(0, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun secondStartIsNoOp() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope, config("light.kitchen")) { 0L }
        hub.start()
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun callServiceBuildsCommand() = runTest {
        val fake = FakeHaClient()
        fake.state.value = ConnState.CONNECTED
        val hub = EntityHub(fake, this, config()) { 0L }
        hub.callService("homeassistant", "toggle", entityId = "light.kitchen")
        runCurrent()
        val (type, fields) = fake.requests.first { it.first == "call_service" }
        assertEquals("homeassistant", fields["domain"]!!.jsonPrimitive.contentOrNull)
        assertEquals("toggle", fields["service"]!!.jsonPrimitive.contentOrNull)
    }
}
