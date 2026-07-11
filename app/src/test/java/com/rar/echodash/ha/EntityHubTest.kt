package com.rar.echodash.ha

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntityHubTest {

    /** Records commands and lets the test drive subscription events + queued results. */
    private class FakeHaClient : HaClient {
        val state = MutableStateFlow(ConnState.OFFLINE)
        override val connectionState: StateFlow<ConnState> = state
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val results = ArrayDeque<JsonElement?>()
        val subscribed = mutableListOf<Pair<String, JsonObject>>()
        val handlers = mutableMapOf<Int, (JsonObject) -> Unit>()
        val unsubscribed = mutableListOf<Int>()
        private var nextId = 100
        /** Number of subsequent subscribe() calls that should throw IOException instead of succeeding. */
        var subscribeFailures = 0

        override suspend fun request(type: String, fields: JsonObject): JsonElement? {
            requests += type to fields
            return if (results.isEmpty()) null else results.removeFirst()
        }
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit): Int {
            if (subscribeFailures > 0) {
                subscribeFailures--
                throw java.io.IOException("socket dropped mid-subscribe")
            }
            subscribed += type to fields
            val id = nextId++
            handlers[id] = onEvent
            return id
        }
        override suspend fun unsubscribe(subId: Int) { unsubscribed += subId }

        fun push(subIndex: Int, eventJson: String) {
            val id = handlers.keys.sorted()[subIndex]
            handlers.getValue(id)(Json.parseToJsonElement(eventJson) as JsonObject)
        }
    }

    private val registryJson =
        """[{"entity_id":"light.kitchen","labels":["echo-lights"],"name":null,"original_name":"Kitchen"}]"""

    @Test
    fun listsRegistryThenSubscribesEntitiesAndAppliesEvents() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson)) // config/entity_registry/list
        // hub.start() launches a connectionState collector that runs for the app's lifetime;
        // backgroundScope is TestScope's mechanism for such never-completing observers (they're
        // cancelled automatically at test teardown instead of failing the test as a leaked job).
        val hub = EntityHub(fake, backgroundScope) { 1_000L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()

        assertEquals("config/entity_registry/list", fake.requests[0].first)
        assertEquals("subscribe_entities", fake.subscribed[0].first)
        assertEquals(listOf("light.kitchen"), hub.registry.value.allEntityIds)
        // entities subscription is index 0, registry-updated subscription is index 1
        fake.push(0, """{"a":{"light.kitchen":{"s":"on","a":{"friendly_name":"Kitchen"}}}}""")
        assertEquals("on", hub.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun reSubscribesWhenRegistryLabelSetChanges() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        // a registry_updated event arrives; hub re-lists with a bigger set
        fake.results.add(Json.parseToJsonElement(
            """[{"entity_id":"light.kitchen","labels":["echo-lights"]},{"entity_id":"light.lamp","labels":["echo-lights"]}]"""
        ))
        fake.push(1, """{"event_type":"entity_registry_updated","data":{"action":"update","entity_id":"light.lamp"}}""")
        runCurrent()
        assertTrue(fake.unsubscribed.isNotEmpty())
        // a second subscribe_entities was opened (the first is index 0; subscribe_events is index 1)
        assertEquals(2, fake.subscribed.count { it.first == "subscribe_entities" })
        assertEquals(listOf("light.kitchen", "light.lamp"), hub.registry.value.allEntityIds)
    }

    @Test
    fun reconnectReSubscribesAfterOfflineThenConnected() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()
        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })

        // socket drops, then reconnects
        fake.state.value = ConnState.OFFLINE
        runCurrent()
        fake.results.add(Json.parseToJsonElement(registryJson))
        fake.state.value = ConnState.CONNECTED
        runCurrent()

        // a second subscribe_entities subscription was opened on the reconnect
        assertEquals(2, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun collectorSurvivesIOExceptionDuringResyncAndRetriesOnNextConnected() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        // simulate the socket dropping mid-resync: the subscribe_entities call throws IOException
        fake.subscribeFailures = 1
        val hub = EntityHub(fake, backgroundScope) { 0L }
        hub.start()
        fake.state.value = ConnState.CONNECTED
        runCurrent()

        // the failed resync must not kill the connectionState collector, and must not have
        // recorded a subscription
        assertEquals(0, fake.subscribed.count { it.first == "subscribe_entities" })

        // next CONNECTED transition retries and this time succeeds, proving the collector
        // coroutine survived the uncaught IOException from the prior resync attempt
        fake.state.value = ConnState.OFFLINE
        runCurrent()
        fake.results.add(Json.parseToJsonElement(registryJson))
        fake.state.value = ConnState.CONNECTED
        runCurrent()

        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun secondStartIsNoOp() = runTest {
        val fake = FakeHaClient()
        fake.results.add(Json.parseToJsonElement(registryJson))
        val hub = EntityHub(fake, backgroundScope) { 0L }
        hub.start()
        hub.start() // re-entering the dashboard screen must not stack a second collector
        fake.state.value = ConnState.CONNECTED
        runCurrent()

        assertEquals(1, fake.subscribed.count { it.first == "subscribe_entities" })
    }

    @Test
    fun callServiceBuildsCommand() = runTest {
        val fake = FakeHaClient()
        val hub = EntityHub(fake, this) { 0L }
        hub.callService("homeassistant", "toggle", entityId = "light.kitchen")
        runCurrent()
        val (type, fields) = fake.requests.first { it.first == "call_service" }
        assertEquals("homeassistant", fields["domain"]!!.jsonPrimitive.contentOrNull)
        assertEquals("toggle", fields["service"]!!.jsonPrimitive.contentOrNull)
        assertEquals("light.kitchen",
            fields["target"]!!.jsonObject["entity_id"]!!.jsonPrimitive.contentOrNull)
    }
}
