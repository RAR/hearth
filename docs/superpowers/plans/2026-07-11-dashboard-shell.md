# Dashboard Shell & Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single clock/temperature screen with a multi-view dashboard: a right-side touch rail switching between a photo-backed Home clock view and five HA-fed panels (Lights, Climate, Media, Weather, Solar), all resolved from HA labels over one `subscribe_entities` subscription.

**Architecture:** A new pure-JVM `EntityHub` sits on a reworked generic `HaWebSocket` client (request/reply + id-routed subscriptions) and maintains a `StateFlow<Map<String,EntityState>>` plus a label→entity index. Panel view-models are pure functions/classes derived from those flows and unit-tested; Compose panels stay thin and compile-gated. A `PhotoStore` syncs a HA media folder into the app cache for the Home backdrop. The old single-sensor `TempReading`/picker path is deleted last, after the new UI is wired.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3, OkHttp 4.12.0, androidx.media3-exoplayer 1.4.1, JUnit4 + kotlinx-coroutines-test + okhttp mockwebserver (plain-JVM tests only).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-11-dashboard-shell-design.md`. Prior specs/plans: MVP (`2026-07-10-echo-ha-dashboard-*`), VACA (`2026-07-11-vaca-protocol-support-*`).
- Build/test: `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` first, then run gradle from repo root `/home/rar/android_simpla_ha_dash`. Android SDK at `/home/rar/android-sdk` (platform 34).
- Do NOT bump: `compileSdk = 34`, `targetSdk = 34`, `minSdk = 28`, `applicationId = "com.rar.echodash"`, app label "Echo Dashboard", `jvmTarget = "17"`. media3-exoplayer stays **1.4.1** (1.5.x needs compileSdk 35).
- `testOptions { unitTests.isReturnDefaultValues = true }` is already set in `app/build.gradle.kts` so `android.util.Log` no-ops in JVM tests. Exactly ONE new dependency is approved and added in Task 9: `androidx.compose.material:material-icons-extended` (unversioned — managed by the existing Compose BOM 2024.12.01, which resolves it to 1.7.6). No other new Gradle dependencies.
- Plain-JVM unit tests ONLY: JUnit4, no Robolectric, no instrumentation. Android-API code (window, Bitmap, ExoPlayer, resources) stays behind thin interfaces (existing pattern: `KioskDevice`, `MediaEngine`, `PcmSink`). Compose composables and thin Android adapters are verified by compilation (`assembleDebug`), not unit tests.
- Device is a single Amazon Echo Show 5, LineageOS 18.1 (Android 11 = API 30), 960×480 landscape. Variable fonts and `FontVariation` are supported on API 26+.
- Label matching is by label **id/slug**, lowercased, keeping only ids that start with `echo-`. The entity registry entry's `labels` array already contains label ids (slugs) — no label-registry lookup is needed.
- Timing constants (single source of truth, see `PhotoConfig` in Task 8 and defaults in Tasks 5/7): idle-return **60 s**, setpoint debounce **800 ms**, photo cycle **5 min**, photo sync **6 h**, staleness **15 min**.
- Every code step below is complete and verbatim. Run `export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` once per shell session before any gradle command shown.

## Protocol Reference (pinned — normative for all tasks)

All commands go over the existing authenticated HA WebSocket (`/api/websocket`). After `auth_ok`, every command carries a unique integer `id`. HA replies with exactly one `{"type":"result","id":<id>,"success":bool,"result":...}` per command id; subscription commands then stream `{"type":"event","id":<id>,"event":{...}}` messages reusing that same id until unsubscribed.

**Entity registry list** — request:
```json
{"id": 5, "type": "config/entity_registry/list"}
```
Result `result` is an array of entries. Fields used: `entity_id` (str), `labels` (array of label-id strings, may be empty/absent), `name` (user override, nullable), `original_name` (integration default, nullable). The registry has NO live state — it only maps labels→entities and supplies display names.

**Subscribe to entities** — request (one subscription for the full matched id list):
```json
{"id": 6, "type": "subscribe_entities", "entity_ids": ["light.kitchen", "climate.hall"]}
```
Then a result ack, then compressed events on id 6:
- Initial/added snapshot: `{"event":{"a":{"<entity_id>":{"s":"<state>","a":{<attrs>},"c":"<ctx>","lc":<lastChanged>,"lu":<lastUpdated>}}}}`
- Change delta: `{"event":{"c":{"<entity_id>":{"+":{"s":"<state>","a":{<changed attrs>},"lu":<ts>},"-":{"a":["<removed attr key>"]}}}}}` — `+` adds/replaces (state, attrs, `lu`); `-.a` is a JSON **array** of attribute keys to remove.
- Removals: `{"event":{"r":["<entity_id>"]}}` — `r` is a JSON array of fully-removed entity ids.
- `lu`/`lc` are float epoch **seconds** (e.g. `1720000000.123`); multiply by 1000 for ms.

**Subscribe to registry changes** — request:
```json
{"id": 7, "type": "subscribe_events", "event_type": "entity_registry_updated"}
```
Events: `{"event":{"event_type":"entity_registry_updated","data":{"action":"create|update|remove","entity_id":"<id>"}}}`. On any such event, re-list the registry; if the matched entity-id set changed, unsubscribe and re-subscribe entities.

**Unsubscribe (any subscription type)** — request:
```json
{"id": 20, "type": "unsubscribe_events", "subscription": 6}
```

**Call a service (fire-and-forget)** — request:
```json
{"id": 8, "type": "call_service", "domain": "homeassistant", "service": "toggle", "target": {"entity_id": "light.kitchen"}}
```
`service_data` is an optional object of extra params. Result `result` is `{"context":{...}}` (ignored).

**Weather forecast (service with response)** — request:
```json
{"id": 9, "type": "call_service", "domain": "weather", "service": "get_forecasts",
 "service_data": {"type": "daily"}, "target": {"entity_id": "weather.home"}, "return_response": true}
```
Result: `{"result":{"context":{...},"response":{"weather.home":{"forecast":[{"datetime":"2026-07-12T00:00:00+00:00","condition":"sunny","temperature":24.0,"templow":14.0}, ...]}}}}`. Forecast items key on the entity id inside `response`; each item has `datetime` (ISO-8601), `condition`, `temperature` (daily high), `templow` (daily low).

**Browse a media-source folder** — request:
```json
{"id": 10, "type": "media_source/browse_media", "media_content_id": "media-source://media_source/local/echo-frame"}
```
Result is a browse node with `children`: an array of `{"title":"photo1.jpg","media_class":"image","media_content_type":"image/jpeg","media_content_id":"media-source://media_source/local/echo-frame/photo1.jpg","can_play":true,"can_expand":false}`. Keep only children with `media_class == "image"`.

**Resolve a media item to a URL** — request:
```json
{"id": 11, "type": "media_source/resolve_media", "media_content_id": "media-source://media_source/local/echo-frame/photo1.jpg", "expires": 300}
```
Result: `{"result":{"url":"/media/local/echo-frame/photo1.jpg?authSig=<jwt>","mime_type":"image/jpeg"}}`. The `url` is a **relative** path signed with an `authSig` query param and valid for `expires` seconds (default 30 if omitted). Download it by prefixing the HA base URL with **no** Authorization header — the signature authenticates the request. (Verified against HA core `media_source` websocket API; if a future HA version returns an absolute or unsigned URL, prefix only when it starts with `/` and add the auth header otherwise — see Task 8 downloader.)

## File Map

| File | Responsibility | Task |
|---|---|---|
| `ha/EntityModels.kt` | `EntityState`, attr accessors, `RegistryIndex`, `parseEntityRegistry` | 1 |
| `ha/EntityDelta.kt` | `applyEntitiesEvent` compressed-state applier | 1 |
| `ha/WsMessages.kt` (modify) | `WsIncoming.Event`; rename old `EntityState`→`SensorEntity`; drop `EntityUpdate`/`EntityPatch` | 2 |
| `ha/HaWebSocket.kt` (modify) | `HaClient` interface + `request`/`subscribe`/`unsubscribe` + id-routed events | 2 |
| `ha/EntityHub.kt` | Orchestrator: registry list, subscribe, re-subscribe, `callService`, `getForecasts` | 3 |
| `ui/model/LightsModel.kt` | `buildLightGroups` grouping | 4 |
| `ui/model/SolarModel.kt` | `buildSolarFlow` + watt/kWh formatting | 4 |
| `ui/model/ClimateModel.kt` | `thermostatStates` + `SetpointDebouncer` | 5 |
| `ui/model/WeatherModel.kt` | weather pill fallback, condition→icon, forecast parse | 6 |
| `ui/IdleReturnTimer.kt` | 60 s idle-return-to-Home timer | 7 |
| `vaca/MediaBridge.kt` (modify) | `MediaUiState` StateFlow (read-side) | 7 |
| `photos/PhotoStore.kt` | browse parse, list diff, sync scheduler, cache flow | 8 |
| `photos/AndroidPhotoDownloader.kt` | resolve+download+downsample (thin, untested) | 8 |
| `ui/theme/Type.kt` | Nunito variable font family + `EchoTypography` | 9 |
| `res/font/nunito_variable.ttf` | bundled variable font | 9 |
| `app/build.gradle.kts` (modify) | add material-icons-extended (BOM-managed) | 9 |
| `ui/DashboardShell.kt` | rail, view host, Home view, backdrop | 9 |
| `ui/panels/*.kt` | Lights/Climate/Media/Weather/Solar composables | 10 |
| `App.kt`, `MainActivity.kt`, `data/SettingsStore.kt` (modify) | wiring; delete temp path | 11 |
| `ui/DashboardScreen.kt`, `ui/EntityPickerScreen.kt` (delete) | old screens removed | 11 |
| `README.md` (modify) | label scheme, photo setup, panels | 12 |

---

### Task 1: EntityHub core — models, registry parser, delta applier (pure)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/EntityModels.kt`
- Create: `app/src/main/java/com/rar/echodash/ha/EntityDelta.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/EntityModelsTest.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/EntityDeltaTest.kt`

**Interfaces:**
- Consumes: nothing (foundation).
- Produces:
  - `data class EntityState(val entityId: String, val state: String, val attributes: JsonObject, val lastUpdatedMs: Long)` with `fun attr(key: String): String?`, `fun attrDouble(key: String): Double?`, `fun attrStringList(key: String): List<String>`.
  - `data class RegistryIndex(val labelToEntities: Map<String, List<String>>, val registryNames: Map<String, String>)` with `val allEntityIds: List<String>`.
  - `fun RegistryIndex.displayName(entityId: String, state: EntityState?): String`
  - `fun parseEntityRegistry(result: JsonElement): RegistryIndex`
  - `fun applyEntitiesEvent(current: Map<String, EntityState>, event: JsonObject, receivedAtMs: Long): Map<String, EntityState>`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ha/EntityModelsTest.kt`:

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityModelsTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun keepsOnlyEchoLabelsLowercasedAndGroupsEntities() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.kitchen","labels":["Echo-Lights","other"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"light.lamp","labels":["echo-lights-living-room"],"name":"Reading Lamp","original_name":"Lamp"},
              {"entity_id":"sensor.pv","labels":["echo-solar-pv"],"name":null,"original_name":null},
              {"entity_id":"light.no_labels","labels":[],"name":null,"original_name":"Nope"}
            ]"""
        ))
        assertEquals(listOf("light.kitchen"), reg.labelToEntities["echo-lights"])
        assertEquals(listOf("light.lamp"), reg.labelToEntities["echo-lights-living-room"])
        assertEquals(listOf("sensor.pv"), reg.labelToEntities["echo-solar-pv"])
        assertEquals(null, reg.labelToEntities["other"])
        assertEquals(listOf("light.kitchen", "light.lamp", "sensor.pv"), reg.allEntityIds)
    }

    @Test
    fun displayNamePrefersRegistryNameThenFriendlyThenId() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.lamp","labels":["echo-lights"],"name":"Reading Lamp","original_name":"Lamp"},
              {"entity_id":"light.kitchen","labels":["echo-lights"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"light.plain","labels":["echo-lights"],"name":null,"original_name":null}
            ]"""
        ))
        val friendly = EntityState("light.plain", "on",
            Json.parseToJsonElement("""{"friendly_name":"Plain Light"}""").let { it as kotlinx.serialization.json.JsonObject }, 0L)
        assertEquals("Reading Lamp", reg.displayName("light.lamp", null))
        assertEquals("Kitchen", reg.displayName("light.kitchen", null))
        assertEquals("Plain Light", reg.displayName("light.plain", friendly))
        assertEquals("light.plain", reg.displayName("light.plain", null))
    }

    @Test
    fun attributeAccessors() {
        val s = EntityState("climate.hall", "heat",
            Json.parseToJsonElement(
                """{"current_temperature":19.5,"hvac_modes":["off","heat"],"friendly_name":"Hall"}"""
            ) as kotlinx.serialization.json.JsonObject, 0L)
        assertEquals("Hall", s.attr("friendly_name"))
        assertEquals(19.5, s.attrDouble("current_temperature")!!, 0.001)
        assertEquals(listOf("off", "heat"), s.attrStringList("hvac_modes"))
        assertEquals(emptyList<String>(), s.attrStringList("missing"))
    }
}
```

`app/src/test/java/com/rar/echodash/ha/EntityDeltaTest.kt`:

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityDeltaTest {
    private fun event(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun addSnapshotUsesLuWhenPresent() {
        val out = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"light.kitchen":{"s":"on","a":{"friendly_name":"Kitchen"},"lu":1720000000.5}}}"""
        ), receivedAtMs = 999L)
        val e = out.getValue("light.kitchen")
        assertEquals("on", e.state)
        assertEquals("Kitchen", e.attr("friendly_name"))
        assertEquals(1720000000500L, e.lastUpdatedMs)
    }

    @Test
    fun changeMergesAttrsAndRemovesListedKeys() {
        val base = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"climate.hall":{"s":"heat","a":{"current_temperature":19.0,"preset":"eco"},"lu":1.0}}}"""
        ), 0L)
        val out = applyEntitiesEvent(base, event(
            """{"c":{"climate.hall":{"+":{"s":"cool","a":{"current_temperature":21.0}},"-":{"a":["preset"]}}}}"""
        ), receivedAtMs = 500L)
        val e = out.getValue("climate.hall")
        assertEquals("cool", e.state)
        assertEquals(21.0, e.attrDouble("current_temperature")!!, 0.001)
        assertNull(e.attr("preset"))
        assertEquals(500L, e.lastUpdatedMs) // no lu in delta -> receivedAtMs
    }

    @Test
    fun removalDropsEntity() {
        val base = applyEntitiesEvent(emptyMap(), event(
            """{"a":{"light.a":{"s":"on","a":{}},"light.b":{"s":"off","a":{}}}}"""
        ), 0L)
        val out = applyEntitiesEvent(base, event("""{"r":["light.a"]}"""), 0L)
        assertNull(out["light.a"])
        assertEquals("off", out.getValue("light.b").state)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.rar.echodash.ha.EntityModelsTest" --tests "com.rar.echodash.ha.EntityDeltaTest"`
Expected: FAIL — `Unresolved reference: parseEntityRegistry` / `applyEntitiesEvent` / `EntityState`.

- [ ] **Step 3: Create `ha/EntityModels.kt`**

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One entity's live state from subscribe_entities. [attributes] is HA's raw attribute object. */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastUpdatedMs: Long,
) {
    fun attr(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
    fun attrDouble(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull
    fun attrStringList(key: String): List<String> =
        (attributes[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/** Label id (lowercased, echo-* only) -> entity ids, plus registry display names per entity. */
data class RegistryIndex(
    val labelToEntities: Map<String, List<String>>,
    val registryNames: Map<String, String>,
) {
    /** Every entity referenced by any echo-* label, first-seen order, de-duplicated. */
    val allEntityIds: List<String>
        get() = labelToEntities.values.flatten().distinct()
}

/** Display name: registry name/original_name, else live friendly_name, else the entity id. */
fun RegistryIndex.displayName(entityId: String, state: EntityState?): String =
    registryNames[entityId] ?: state?.attr("friendly_name") ?: entityId

/** Build the label index from a config/entity_registry/list result array. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex {
    val labelToEntities = LinkedHashMap<String, MutableList<String>>()
    val names = LinkedHashMap<String, String>()
    for (el in result.jsonArray) {
        val obj = el.jsonObject
        val id = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: continue
        val labels = (obj["labels"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            ?.filter { it.startsWith("echo-") }
            .orEmpty()
        if (labels.isEmpty()) continue
        for (label in labels) labelToEntities.getOrPut(label) { mutableListOf() }.add(id)
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (obj["original_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
    }
    return RegistryIndex(labelToEntities, names)
}
```

- [ ] **Step 4: Create `ha/EntityDelta.kt`**

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Apply one subscribe_entities compressed event (a / c / r) to the current map, returning a new map. */
fun applyEntitiesEvent(
    current: Map<String, EntityState>,
    event: JsonObject,
    receivedAtMs: Long,
): Map<String, EntityState> {
    val next = LinkedHashMap(current)

    (event["a"] as? JsonObject)?.forEach { (id, v) ->
        next[id] = fullState(id, v.jsonObject, receivedAtMs)
    }

    (event["r"] as? JsonArray)?.forEach { el ->
        (el as? JsonPrimitive)?.contentOrNull?.let { next.remove(it) }
    }

    (event["c"] as? JsonObject)?.forEach { (id, v) ->
        val diff = v.jsonObject
        val prev = next[id]
        val plus = diff["+"] as? JsonObject
        val minus = diff["-"] as? JsonObject
        val newState = (plus?.get("s") as? JsonPrimitive)?.contentOrNull ?: prev?.state ?: "unknown"
        val attrs = LinkedHashMap<String, JsonElement>(prev?.attributes ?: JsonObject(emptyMap()))
        (plus?.get("a") as? JsonObject)?.forEach { (k, av) -> attrs[k] = av }
        (minus?.get("a") as? JsonArray)?.forEach { rem ->
            (rem as? JsonPrimitive)?.contentOrNull?.let { attrs.remove(it) }
        }
        val luMs = (plus?.get("lu") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
        next[id] = EntityState(id, newState, JsonObject(attrs), luMs ?: receivedAtMs)
    }

    return next
}

private fun fullState(id: String, o: JsonObject, receivedAtMs: Long): EntityState {
    val luMs = (o["lu"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
    return EntityState(
        entityId = id,
        state = (o["s"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
        attributes = (o["a"] as? JsonObject) ?: JsonObject(emptyMap()),
        lastUpdatedMs = luMs ?: receivedAtMs,
    )
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.rar.echodash.ha.EntityModelsTest" --tests "com.rar.echodash.ha.EntityDeltaTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ha/EntityModels.kt app/src/main/java/com/rar/echodash/ha/EntityDelta.kt app/src/test/java/com/rar/echodash/ha/EntityModelsTest.kt app/src/test/java/com/rar/echodash/ha/EntityDeltaTest.kt
git commit -m "feat: EntityHub core models, registry parser, and entity delta applier"
```

---

### Task 2: Generic HaWebSocket client + WsParser rework (transport)

Reworks the WebSocket into a general HA client with `request`/`subscribe`/`unsubscribe` and id-routed events, **while keeping the existing temperature-reading path alive** (it is deleted in Task 11 once the new UI replaces it). This is why the old `EntityState` is renamed rather than removed here.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ha/WsMessages.kt`
- Modify: `app/src/main/java/com/rar/echodash/ha/HaWebSocket.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt` (rename `EntityState`→`SensorEntity`)
- Modify: `app/src/test/java/com/rar/echodash/ha/WsParserTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/ha/HaWebSocketTest.kt`

**Interfaces:**
- Consumes: `EntityState`/`applyEntitiesEvent` from Task 1 (not required here; the temp path stays on raw parsing).
- Produces:
  - `interface HaClient { val connectionState: StateFlow<ConnState>; suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement?; suspend fun subscribe(type: String, fields: JsonObject = JsonObject(emptyMap()), onEvent: (JsonObject) -> Unit): Int; suspend fun unsubscribe(subId: Int) }`
  - `HaWebSocket : HaClient` — `request` sends `{"id":<n>,"type":type,...fields}` and awaits the result payload (throws `IOException` when the socket drops); `subscribe` registers `onEvent` for events on the command id and returns that id; `unsubscribe` sends `unsubscribe_events`.
  - `data class WsIncoming.Event(val id: Int, val event: JsonObject) : WsIncoming`
  - `data class SensorEntity(val entityId: String, val state: String, val unit: String?, val friendlyName: String?)` (renamed from old `EntityState`); `WsParser.temperatureSensors(...)` now returns `List<SensorEntity>`.

- [ ] **Step 1: Rewrite `ha/WsMessages.kt`**

Replace the whole file with:

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface WsIncoming {
    data object AuthRequired : WsIncoming
    data object AuthOk : WsIncoming
    data class AuthInvalid(val message: String) : WsIncoming
    /** A subscription event; [id] matches the subscribe command id, [event] is its inner "event" object. */
    data class Event(val id: Int, val event: JsonObject) : WsIncoming
    data class Result(val id: Int, val success: Boolean, val result: JsonElement?) : WsIncoming
    data class Unknown(val type: String) : WsIncoming
}

/** Full entity state from get_states (temperature picker path only; removed with that path). */
data class SensorEntity(
    val entityId: String,
    val state: String,
    val unit: String?,
    val friendlyName: String?,
)

object WsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): WsIncoming {
        val obj = json.parseToJsonElement(text).jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> WsIncoming.AuthRequired
            "auth_ok" -> WsIncoming.AuthOk
            "auth_invalid" -> WsIncoming.AuthInvalid(
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "auth failed"
            )
            "event" -> WsIncoming.Event(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                event = obj["event"]?.jsonObject ?: JsonObject(emptyMap()),
            )
            "result" -> WsIncoming.Result(
                id = obj["id"]?.jsonPrimitive?.int ?: -1,
                success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                result = obj["result"],
            )
            else -> WsIncoming.Unknown(type ?: "?")
        }
    }

    fun temperatureSensors(result: JsonElement): List<SensorEntity> =
        result.jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.startsWith("sensor.")) return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            if (attrs["device_class"]?.jsonPrimitive?.contentOrNull != "temperature") return@mapNotNull null
            SensorEntity(
                entityId = id,
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "?",
                unit = attrs["unit_of_measurement"]?.jsonPrimitive?.contentOrNull,
                friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull,
            )
        }
}
```

- [ ] **Step 2: Rewrite `ha/HaWebSocket.kt`**

Replace the whole file with:

```kotlin
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
```

- [ ] **Step 3: Update `ui/EntityPickerScreen.kt` for the rename**

In `app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt`, change the import and the two `EntityState` references:

Replace `import com.rar.echodash.ha.EntityState` with `import com.rar.echodash.ha.SensorEntity`.
Replace `var sensors by remember { mutableStateOf<List<EntityState>?>(null) }` with `var sensors by remember { mutableStateOf<List<SensorEntity>?>(null) }`.
Replace `compareByDescending<EntityState> { it.entityId == DEFAULT_TEMPERATURE_ENTITY }` with `compareByDescending<SensorEntity> { it.entityId == DEFAULT_TEMPERATURE_ENTITY }`.

- [ ] **Step 4: Update `ha/WsParserTest.kt`**

Replace the whole file with (drops the removed EntityUpdate tests, adds an Event parse test, keeps the sensors test under the new name):

```kotlin
package com.rar.echodash.ha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsParserTest {

    @Test
    fun parsesAuthHandshakeMessages() {
        assertEquals(WsIncoming.AuthRequired, WsParser.parse("""{"type":"auth_required","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthOk, WsParser.parse("""{"type":"auth_ok","ha_version":"2025.1.0"}"""))
        assertEquals(WsIncoming.AuthInvalid("Invalid access token"),
            WsParser.parse("""{"type":"auth_invalid","message":"Invalid access token"}"""))
    }

    @Test
    fun parsesEventCarriesIdAndInnerEvent() {
        val msg = WsParser.parse(
            """{"id":6,"type":"event","event":{"a":{"light.kitchen":{"s":"on","a":{}}}}}"""
        ) as WsIncoming.Event
        assertEquals(6, msg.id)
        assertTrue(msg.event.containsKey("a"))
    }

    @Test
    fun parsesResultMessage() {
        val msg = WsParser.parse("""{"id":7,"type":"result","success":true,"result":[1,2]}""") as WsIncoming.Result
        assertEquals(7, msg.id)
        assertTrue(msg.success)
    }

    @Test
    fun filtersTemperatureSensorsFromGetStates() {
        val states = Json.parseToJsonElement(
            """[
              {"entity_id":"sensor.outside_temperature","state":"15.6","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Outside Temperature"}},
              {"entity_id":"sensor.outside_temperature_battery","state":"12","attributes":{"device_class":"battery"}},
              {"entity_id":"light.kitchen","state":"on","attributes":{}},
              {"entity_id":"sensor.no_attrs","state":"x","attributes":{}}
            ]"""
        )
        val sensors = WsParser.temperatureSensors(states)
        assertEquals(1, sensors.size)
        assertEquals(
            SensorEntity("sensor.outside_temperature", "15.6", "°C", "Outside Temperature"),
            sensors[0]
        )
    }
}
```

- [ ] **Step 5: Add request/subscribe tests to `ha/HaWebSocketTest.kt`**

Keep the existing file and append these two tests inside the `HaWebSocketTest` class (before the final closing brace). They reuse the existing `haServerListener()` fake server, which already acks `subscribe_entities` (id N) then pushes an event on that id, and answers `get_states`:

```kotlin
    @Test
    fun requestReturnsResultPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"; accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start(null)
                val result = withTimeout(10_000) { ws.request("get_states") }!!
                assertEquals("sensor.outside_temperature",
                    result.jsonArray[0].jsonObject["entity_id"]!!.jsonPrimitive.contentOrNull)
            } finally { ws.stop(); scope.cancel() }
        }
    }

    @Test
    fun subscribeRoutesEventsByCommandId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(haServerListener()))
            server.start()
            val settings = InMemorySettingsStore().apply {
                baseUrl = server.url("/").toString().trimEnd('/')
                accessToken = "AT"; accessTokenExpiresAt = Long.MAX_VALUE
            }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ws = HaWebSocket(settings, AuthManager(settings, client) { 0L }, client, scope)
            try {
                ws.start(null)
                val received = CompletableDeferred<JsonObject>()
                withTimeout(10_000) {
                    ws.subscribe("subscribe_entities",
                        buildJsonObject { putJsonArray("entity_ids") { add("sensor.outside_temperature") } }
                    ) { event -> if (!received.isCompleted) received.complete(event) }
                }
                val event = withTimeout(10_000) { received.await() }
                assertTrue(event.containsKey("a"))
            } finally { ws.stop(); scope.cancel() }
        }
    }
```

Add these imports to the top of `HaWebSocketTest.kt` (alongside the existing imports):

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonArray
```

The existing `connectsAuthenticatesSubscribesAndReceivesReading` and `fetchesTemperatureSensorsViaGetStates` tests still pass unchanged (temp path and `fetchTemperatureSensors` preserved). In `fetchesTemperatureSensorsViaGetStates`, the `sensors[0].entityId` assertion is unaffected by the `SensorEntity` rename.

- [ ] **Step 6: Run the ha test suite**

Run: `./gradlew test --tests "com.rar.echodash.ha.*"`
Expected: PASS (WsParserTest, HaWebSocketTest incl. the 2 new tests, EntityModelsTest, EntityDeltaTest, AuthManagerTest).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ha/WsMessages.kt app/src/main/java/com/rar/echodash/ha/HaWebSocket.kt app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt app/src/test/java/com/rar/echodash/ha/WsParserTest.kt app/src/test/java/com/rar/echodash/ha/HaWebSocketTest.kt
git commit -m "feat: generic HaClient request/subscribe API on HaWebSocket"
```

---

### Task 3: EntityHub orchestrator (registry list, subscribe, re-subscribe, services)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ha/EntityHub.kt`
- Test: `app/src/test/java/com/rar/echodash/ha/EntityHubTest.kt`

**Interfaces:**
- Consumes: `HaClient` (Task 2), `ConnState` (Task 2), `parseEntityRegistry`/`RegistryIndex`/`EntityState`/`applyEntitiesEvent` (Task 1).
- Produces:
  - `class EntityHub(client: HaClient, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis)`
  - `val entities: StateFlow<Map<String, EntityState>>`
  - `val registry: StateFlow<RegistryIndex>`
  - `fun start()` — begins observing the connection; (re)syncs on each CONNECTED transition.
  - `fun callService(domain: String, service: String, serviceData: JsonObject = JsonObject(emptyMap()), entityId: String? = null)` — fire-and-forget, errors logged.
  - `suspend fun getForecasts(entityId: String): JsonElement?` — raw `weather.get_forecasts` result (parsed in Task 6).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/ha/EntityHubTest.kt`:

```kotlin
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

        override suspend fun request(type: String, fields: JsonObject): JsonElement? {
            requests += type to fields
            return if (results.isEmpty()) null else results.removeFirst()
        }
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit): Int {
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
        val hub = EntityHub(fake, this) { 1_000L }
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
        val hub = EntityHub(fake, this) { 0L }
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.rar.echodash.ha.EntityHubTest"`
Expected: FAIL — `Unresolved reference: EntityHub`.

- [ ] **Step 3: Create `ha/EntityHub.kt`**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.rar.echodash.ha.EntityHubTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ha/EntityHub.kt app/src/test/java/com/rar/echodash/ha/EntityHubTest.kt
git commit -m "feat: EntityHub orchestrator with label resolution and re-subscribe"
```

**Deviation (2026-07-11, review finding):** `resync()`/`onRegistryUpdated()` guarded against a
mid-cycle `IOException` (socket drop during `subscribe`) so the reconnect collector survives —
previously an uncaught `IOException` would kill the `connectionState.collect` coroutine
permanently. Code no longer matches Step 3 verbatim.

---

### Task 4: Lights + Solar panel models (pure)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/LightsModel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/LightsModelTest.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`

**Interfaces:**
- Consumes: `RegistryIndex`, `EntityState`, `RegistryIndex.displayName` (Task 1).
- Produces:
  - `data class LightTile(val entityId: String, val name: String, val domain: String, val on: Boolean, val available: Boolean)`
  - `data class LightGroup(val title: String?, val tiles: List<LightTile>)` (title `null` = ungrouped, listed first)
  - `fun buildLightGroups(registry: RegistryIndex, entities: Map<String, EntityState>): List<LightGroup>`
  - `data class SolarNode(val label: String, val watts: String)`
  - `data class SolarFlow(val pv: SolarNode?, val home: SolarNode?, val grid: SolarNode?, val gridImporting: Boolean?, val todayLine: String?)`
  - `fun buildSolarFlow(registry: RegistryIndex, entities: Map<String, EntityState>): SolarFlow`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ui/model/LightsModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightsModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, a: String = "{}") =
        EntityState(id, state, attrs(a), 0L)

    @Test
    fun ungroupedFirstThenAlphabeticalGroupsWithTitleCasing() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[
              {"entity_id":"light.hall","labels":["echo-lights"],"original_name":"Hall"},
              {"entity_id":"light.sofa","labels":["echo-lights-living-room"],"original_name":"Sofa"},
              {"entity_id":"switch.fan","labels":["echo-lights-bedroom"],"original_name":"Fan"}
            ]"""
        ))
        val entities = mapOf(
            "light.hall" to st("light.hall", "on"),
            "light.sofa" to st("light.sofa", "off"),
            "switch.fan" to st("switch.fan", "unavailable"),
        )
        val groups = buildLightGroups(reg, entities)
        assertEquals(listOf(null, "Bedroom", "Living Room"), groups.map { it.title })
        assertEquals("Hall", groups[0].tiles[0].name)
        assertEquals(true, groups[0].tiles[0].on)
        assertEquals(false, groups[2].tiles[0].on)          // Living Room / Sofa off
        assertEquals(false, groups[1].tiles[0].available)   // Bedroom / Fan unavailable
        assertEquals("switch", groups[1].tiles[0].domain)
    }

    @Test
    fun entityInMultipleLabelsAppearsInEachGroup() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[{"entity_id":"light.lamp","labels":["echo-lights-a","echo-lights-b"],"original_name":"Lamp"}]"""
        ))
        val groups = buildLightGroups(reg, mapOf("light.lamp" to st("light.lamp", "on")))
        assertEquals(listOf("A", "B"), groups.map { it.title })
        assertEquals("Lamp", groups[0].tiles[0].name)
        assertEquals("Lamp", groups[1].tiles[0].name)
        assertNull(groups.firstOrNull { it.title == null })
    }
}
```

`app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String) =
        EntityState(id, state, attrs("""{"unit_of_measurement":"$unit"}"""), 0L)

    private val regAll = parseEntityRegistry(Json.parseToJsonElement(
        """[
          {"entity_id":"sensor.pv","labels":["echo-solar-pv"]},
          {"entity_id":"sensor.load","labels":["echo-solar-load"]},
          {"entity_id":"sensor.grid","labels":["echo-solar-grid"]},
          {"entity_id":"sensor.pvday","labels":["echo-solar-pv-today"]},
          {"entity_id":"sensor.loadday","labels":["echo-solar-load-today"]}
        ]"""
    ))

    @Test
    fun formatsWattsAndKwAndGridSignAndToday() {
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3500", "W"),
            "sensor.load" to st("sensor.load", "800", "W"),
            "sensor.grid" to st("sensor.grid", "-1200", "W"),
            "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
        )
        val flow = buildSolarFlow(regAll, entities)
        assertEquals("3.5 kW", flow.pv!!.watts)
        assertEquals("800 W", flow.home!!.watts)
        assertEquals("1.2 kW", flow.grid!!.watts)     // magnitude only; sign is separate
        assertEquals(false, flow.gridImporting)       // -1200 => exporting
        assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", flow.todayLine)
    }

    @Test
    fun noGridSensorGivesTwoNodeFlowAndPartialToday() {
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "1000", "W"),
            "sensor.load" to st("sensor.load", "1500", "W"),
            "sensor.pvday" to st("sensor.pvday", "5.0", "kWh"),
        )
        val flow = buildSolarFlow(regAll, entities)
        assertNull(flow.grid)
        assertNull(flow.gridImporting)
        assertEquals("1.0 kW", flow.pv!!.watts)
        assertEquals("Today: 5.0 kWh produced", flow.todayLine)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.LightsModelTest" --tests "com.rar.echodash.ui.model.SolarModelTest"`
Expected: FAIL — `Unresolved reference: buildLightGroups` / `buildSolarFlow`.

- [ ] **Step 3: Create `ui/model/LightsModel.kt`**

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName
import java.util.Locale

data class LightTile(
    val entityId: String,
    val name: String,
    val domain: String,
    val on: Boolean,
    val available: Boolean,
)

/** A section of light tiles. [title] null = the ungrouped bare `echo-lights` section (listed first). */
data class LightGroup(val title: String?, val tiles: List<LightTile>)

private const val LIGHTS_LABEL = "echo-lights"
private const val LIGHTS_PREFIX = "echo-lights-"

fun buildLightGroups(registry: RegistryIndex, entities: Map<String, EntityState>): List<LightGroup> {
    fun tilesFor(label: String): List<LightTile> =
        registry.labelToEntities[label].orEmpty().map { id ->
            val state = entities[id]
            val s = state?.state
            LightTile(
                entityId = id,
                name = registry.displayName(id, state),
                domain = id.substringBefore('.'),
                on = s == "on",
                available = s != null && s != "unavailable" && s != "unknown",
            )
        }

    val out = mutableListOf<LightGroup>()
    registry.labelToEntities[LIGHTS_LABEL]?.let {
        out += LightGroup(title = null, tiles = tilesFor(LIGHTS_LABEL))
    }
    registry.labelToEntities.keys
        .filter { it.startsWith(LIGHTS_PREFIX) && it.length > LIGHTS_PREFIX.length }
        .map { it to titleCase(it.removePrefix(LIGHTS_PREFIX)) }
        .sortedBy { it.second.lowercase(Locale.getDefault()) }
        .forEach { (label, title) -> out += LightGroup(title = title, tiles = tilesFor(label)) }
    return out
}

private fun titleCase(slug: String): String =
    slug.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
```

- [ ] **Step 4: Create `ui/model/SolarModel.kt`**

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import java.util.Locale
import kotlin.math.abs

data class SolarNode(val label: String, val watts: String)

/** [gridImporting] null = no grid sensor (two-node flow). [todayLine] null = no `-today` sensors. */
data class SolarFlow(
    val pv: SolarNode?,
    val home: SolarNode?,
    val grid: SolarNode?,
    val gridImporting: Boolean?,
    val todayLine: String?,
)

fun buildSolarFlow(registry: RegistryIndex, entities: Map<String, EntityState>): SolarFlow {
    fun first(label: String): EntityState? =
        registry.labelToEntities[label]?.firstOrNull()?.let { entities[it] }

    val pv = first("echo-solar-pv")
    val load = first("echo-solar-load")
    val grid = first("echo-solar-grid")
    val pvToday = first("echo-solar-pv-today")
    val loadToday = first("echo-solar-load-today")

    val todayLine = buildString {
        pvToday?.let { append("${trimNum(it.state)} ${it.attr("unit_of_measurement") ?: "kWh"} produced") }
        loadToday?.let {
            if (isNotEmpty()) append(" · ")
            append("${trimNum(it.state)} ${it.attr("unit_of_measurement") ?: "kWh"} used")
        }
    }.takeIf { it.isNotEmpty() }?.let { "Today: $it" }

    val gridValue = grid?.state?.toDoubleOrNull()

    return SolarFlow(
        pv = pv?.let { SolarNode("Solar", formatWatts(it)) },
        home = load?.let { SolarNode("Home", formatWatts(it)) },
        grid = grid?.let { SolarNode("Grid", formatWatts(it)) },
        gridImporting = gridValue?.let { it >= 0 },
        todayLine = todayLine,
    )
}

/** Format a live power sensor by its own unit; W magnitudes >= 1000 roll up to kW (magnitude only). */
private fun formatWatts(state: EntityState): String {
    val unit = state.attr("unit_of_measurement") ?: "W"
    val v = state.state.toDoubleOrNull() ?: return "${state.state} $unit"
    val mag = abs(v)
    return when {
        unit.equals("kW", ignoreCase = true) -> String.format(Locale.US, "%.2f kW", mag)
        mag >= 1000 -> String.format(Locale.US, "%.1f kW", mag / 1000.0)
        else -> "${mag.toInt()} W"
    }
}

private fun trimNum(s: String): String =
    s.toDoubleOrNull()?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString() else s
    } ?: s
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.LightsModelTest" --tests "com.rar.echodash.ui.model.SolarModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/LightsModel.kt app/src/main/java/com/rar/echodash/ui/model/SolarModel.kt app/src/test/java/com/rar/echodash/ui/model/LightsModelTest.kt app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt
git commit -m "feat: lights grouping and solar power-flow view models"
```

---

### Task 5: Climate model + setpoint debouncer (pure)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/ClimateModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/ClimateModelTest.kt`

**Interfaces:**
- Consumes: `RegistryIndex`, `EntityState`, `RegistryIndex.displayName` (Task 1).
- Produces:
  - `data class ThermostatState(val entityId: String, val name: String, val current: Double?, val target: Double?, val minTemp: Double, val maxTemp: Double, val step: Double, val hvacAction: String?, val hvacModes: List<String>, val mode: String, val available: Boolean)`
  - `fun thermostatStates(registry: RegistryIndex, entities: Map<String, EntityState>): List<ThermostatState>`
  - `class SetpointDebouncer(scope: CoroutineScope, debounceMs: Long = 800, onCommit: (Double) -> Unit)` with `fun reset(current: Double, min: Double, max: Double, step: Double = 0.5)`, `fun nudge(direction: Int)`, `fun displayTarget(): Double`, `fun cancel()`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/ui/model/ClimateModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClimateModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun derivesThermostatFromAttributes() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[{"entity_id":"climate.hall","labels":["echo-climate"],"original_name":"Hall"}]"""
        ))
        val entities = mapOf("climate.hall" to EntityState("climate.hall", "heat",
            attrs("""{"current_temperature":19.5,"temperature":21.0,"min_temp":7.0,"max_temp":30.0,
                      "hvac_action":"heating","hvac_modes":["off","heat","cool"]}"""), 0L))
        val t = thermostatStates(reg, entities).single()
        assertEquals("Hall", t.name)
        assertEquals(19.5, t.current!!, 0.001)
        assertEquals(21.0, t.target!!, 0.001)
        assertEquals(7.0, t.minTemp, 0.001)
        assertEquals(30.0, t.maxTemp, 0.001)
        assertEquals("heating", t.hvacAction)
        assertEquals(listOf("off", "heat", "cool"), t.hvacModes)
        assertEquals("heat", t.mode)
        assertEquals(true, t.available)
    }

    @Test
    fun debouncerAccumulatesTapsIntoOneClampedCommit() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 20.0, min = 7.0, max = 22.0)
        repeat(5) { d.nudge(+1) }        // 20.0 + 5*0.5 = 22.5 -> clamped to 22.0
        assertEquals(22.0, d.displayTarget(), 0.001)
        assertEquals(0, commits.size)    // nothing sent yet
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(22.0), commits)
        d.cancel()
    }

    @Test
    fun debouncerStepsHalfDegreeDownAndClampsToMin() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 8.0, min = 7.0, max = 30.0)
        d.nudge(-1); d.nudge(-1); d.nudge(-1)   // 8.0 -> 7.5 -> 7.0 -> 7.0 (clamp)
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(7.0), commits)
        d.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.ClimateModelTest"`
Expected: FAIL — `Unresolved reference: thermostatStates` / `SetpointDebouncer`.

- [ ] **Step 3: Create `ui/model/ClimateModel.kt`**

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ThermostatState(
    val entityId: String,
    val name: String,
    val current: Double?,
    val target: Double?,
    val minTemp: Double,
    val maxTemp: Double,
    val step: Double,
    val hvacAction: String?,
    val hvacModes: List<String>,
    val mode: String,
    val available: Boolean,
)

const val CLIMATE_LABEL = "echo-climate"
const val SETPOINT_STEP = 0.5

fun thermostatStates(registry: RegistryIndex, entities: Map<String, EntityState>): List<ThermostatState> =
    registry.labelToEntities[CLIMATE_LABEL].orEmpty()
        .filter { it.startsWith("climate.") }
        .map { id ->
            val s = entities[id]
            ThermostatState(
                entityId = id,
                name = registry.displayName(id, s),
                current = s?.attrDouble("current_temperature"),
                target = s?.attrDouble("temperature"),
                minTemp = s?.attrDouble("min_temp") ?: 7.0,
                maxTemp = s?.attrDouble("max_temp") ?: 35.0,
                step = SETPOINT_STEP,
                hvacAction = s?.attr("hvac_action"),
                hvacModes = s?.attrStringList("hvac_modes") ?: emptyList(),
                mode = s?.state ?: "unknown",
                available = s != null && s.state != "unavailable" && s.state != "unknown",
            )
        }

/**
 * Accumulates rapid +/- setpoint taps and commits the final clamped target [debounceMs] after the
 * last tap, so five quick taps make one service call. Confined to [scope]'s dispatcher.
 */
class SetpointDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 800,
    private val onCommit: (Double) -> Unit,
) {
    private var target = 0.0
    private var min = 7.0
    private var max = 35.0
    private var step = SETPOINT_STEP
    private var job: Job? = null

    fun reset(current: Double, min: Double, max: Double, step: Double = SETPOINT_STEP) {
        this.target = current
        this.min = min
        this.max = max
        this.step = step
        job?.cancel(); job = null
    }

    fun nudge(direction: Int) {
        target = (target + direction * step).coerceIn(min, max)
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            onCommit(target)
        }
    }

    fun displayTarget(): Double = target

    fun cancel() { job?.cancel(); job = null }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.ClimateModelTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/ClimateModel.kt app/src/test/java/com/rar/echodash/ui/model/ClimateModelTest.kt
git commit -m "feat: thermostat view model and 800ms setpoint debouncer"
```

---

### Task 6: Weather pill + forecast model (pure)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/WeatherModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/WeatherModelTest.kt`

**Interfaces:**
- Consumes: `RegistryIndex`, `EntityState` (Task 1). Parses the raw result from `EntityHub.getForecasts` (Task 3).
- Produces:
  - `enum class WeatherIcon { SUNNY, CLEAR_NIGHT, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, STORM, FOG, WIND, UNKNOWN }`
  - `fun conditionIcon(condition: String?): WeatherIcon`
  - `data class WeatherPill(val icon: WeatherIcon, val conditionText: String?, val temperature: String?, val stale: Boolean)`
  - `fun weatherPill(registry: RegistryIndex, entities: Map<String, EntityState>, nowMs: Long): WeatherPill?`
  - `data class DailyForecast(val dayOfWeek: String, val icon: WeatherIcon, val high: Double?, val low: Double?)`
  - `fun parseForecasts(result: JsonElement?, entityId: String): List<DailyForecast>`
  - `const val STALE_AFTER_MS = 15 * 60_000L`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/ui/model/WeatherModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val reg = parseEntityRegistry(Json.parseToJsonElement(
        """[
          {"entity_id":"sensor.temp","labels":["echo-temp"]},
          {"entity_id":"weather.home","labels":["echo-weather"]}
        ]"""
    ))
    private val regWeatherOnly = parseEntityRegistry(Json.parseToJsonElement(
        """[{"entity_id":"weather.home","labels":["echo-weather"]}]"""
    ))

    @Test
    fun pillPrefersEchoTempSensor() {
        val entities = mapOf(
            "sensor.temp" to EntityState("sensor.temp", "14.1",
                attrs("""{"unit_of_measurement":"°C"}"""), 1_000L),
            "weather.home" to EntityState("weather.home", "rainy",
                attrs("""{"temperature":9.0}"""), 1_000L),
        )
        val pill = weatherPill(reg, entities, nowMs = 1_500L)!!
        assertEquals("14.1 °C", pill.temperature)
        assertEquals(WeatherIcon.RAIN, pill.icon)
        assertEquals("rainy", pill.conditionText)
        assertEquals(false, pill.stale)
    }

    @Test
    fun pillFallsBackToWeatherAttributeThenHides() {
        val onlyWeather = mapOf("weather.home" to EntityState("weather.home", "sunny",
            attrs("""{"temperature":24.0,"temperature_unit":"°C"}"""), 0L))
        val pill = weatherPill(regWeatherOnly, onlyWeather, nowMs = 0L)!!
        assertEquals("24.0 °C", pill.temperature)
        assertEquals(WeatherIcon.SUNNY, pill.icon)
        // neither temp sensor nor weather entity -> hidden
        val empty = parseEntityRegistry(Json.parseToJsonElement("[]"))
        assertNull(weatherPill(empty, emptyMap(), nowMs = 0L))
    }

    @Test
    fun pillDimsWhenTempSensorStale() {
        val entities = mapOf("sensor.temp" to EntityState("sensor.temp", "10.0",
            attrs("""{"unit_of_measurement":"°C"}"""), updatedAtMs = 0L))
        val pill = weatherPill(reg, entities, nowMs = STALE_AFTER_MS + 1)!!
        assertEquals(true, pill.stale)
    }

    @Test
    fun parsesFiveDayForecast() {
        val result = Json.parseToJsonElement(
            """{"response":{"weather.home":{"forecast":[
              {"datetime":"2026-07-13T00:00:00+00:00","condition":"sunny","temperature":25.0,"templow":15.0},
              {"datetime":"2026-07-14T00:00:00+00:00","condition":"cloudy","temperature":22.0,"templow":14.0}
            ]}}}"""
        )
        val days = parseForecasts(result, "weather.home")
        assertEquals(2, days.size)
        assertEquals(WeatherIcon.SUNNY, days[0].icon)
        assertEquals(25.0, days[0].high!!, 0.001)
        assertEquals(15.0, days[0].low!!, 0.001)
        assertEquals("Mon", days[0].dayOfWeek)   // 2026-07-13 is a Monday
        assertEquals("Tue", days[1].dayOfWeek)
    }

    @Test
    fun forecastParseIsNullSafe() {
        assertEquals(emptyList<DailyForecast>(), parseForecasts(null, "weather.home"))
        assertEquals(emptyList<DailyForecast>(),
            parseForecasts(Json.parseToJsonElement("""{"response":{}}"""), "weather.home"))
    }

    @Test
    fun conditionMapping() {
        assertEquals(WeatherIcon.CLEAR_NIGHT, conditionIcon("clear-night"))
        assertEquals(WeatherIcon.PARTLY_CLOUDY, conditionIcon("partlycloudy"))
        assertEquals(WeatherIcon.RAIN, conditionIcon("pouring"))
        assertEquals(WeatherIcon.SNOW, conditionIcon("snowy-rainy"))
        assertEquals(WeatherIcon.STORM, conditionIcon("lightning-rainy"))
        assertEquals(WeatherIcon.WIND, conditionIcon("windy-variant"))
        assertEquals(WeatherIcon.UNKNOWN, conditionIcon(null))
        assertEquals(WeatherIcon.UNKNOWN, conditionIcon("exceptional"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.WeatherModelTest"`
Expected: FAIL — `Unresolved reference: weatherPill` / `parseForecasts` / `conditionIcon`.

- [ ] **Step 3: Create `ui/model/WeatherModel.kt`**

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

const val STALE_AFTER_MS = 15 * 60_000L

enum class WeatherIcon { SUNNY, CLEAR_NIGHT, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, STORM, FOG, WIND, UNKNOWN }

/** Maps a HA weather condition string to the app's icon set. */
fun conditionIcon(condition: String?): WeatherIcon = when (condition) {
    "sunny" -> WeatherIcon.SUNNY
    "clear-night" -> WeatherIcon.CLEAR_NIGHT
    "partlycloudy" -> WeatherIcon.PARTLY_CLOUDY
    "cloudy" -> WeatherIcon.CLOUDY
    "rainy", "pouring", "hail" -> WeatherIcon.RAIN
    "snowy", "snowy-rainy" -> WeatherIcon.SNOW
    "lightning", "lightning-rainy" -> WeatherIcon.STORM
    "fog" -> WeatherIcon.FOG
    "windy", "windy-variant" -> WeatherIcon.WIND
    else -> WeatherIcon.UNKNOWN
}

data class WeatherPill(
    val icon: WeatherIcon,
    val conditionText: String?,
    val temperature: String?,
    val stale: Boolean,
)

/** Pill temperature: first echo-temp sensor, else weather entity's temperature attr, else hidden. */
fun weatherPill(registry: RegistryIndex, entities: Map<String, EntityState>, nowMs: Long): WeatherPill? {
    val tempSensor = registry.labelToEntities["echo-temp"]?.firstOrNull()?.let { entities[it] }
    val weather = registry.labelToEntities["echo-weather"]?.firstOrNull()?.let { entities[it] }

    val temperature: String?
    val stale: Boolean
    when {
        tempSensor != null && tempSensor.state.toDoubleOrNull() != null -> {
            val unit = tempSensor.attr("unit_of_measurement")
            temperature = if (unit != null) "${tempSensor.state} $unit" else tempSensor.state
            stale = nowMs - tempSensor.lastUpdatedMs > STALE_AFTER_MS
        }
        weather?.attrDouble("temperature") != null -> {
            val unit = weather.attr("temperature_unit")
            val t = weather.attrDouble("temperature")
            temperature = if (unit != null) "$t $unit" else t.toString()
            stale = false
        }
        else -> { temperature = null; stale = false }
    }

    if (temperature == null && weather == null) return null

    return WeatherPill(
        icon = conditionIcon(weather?.state),
        conditionText = weather?.state,
        temperature = temperature,
        stale = stale,
    )
}

data class DailyForecast(val dayOfWeek: String, val icon: WeatherIcon, val high: Double?, val low: Double?)

/** Parse a weather.get_forecasts result into up to 5 daily columns for [entityId]. */
fun parseForecasts(result: JsonElement?, entityId: String): List<DailyForecast> {
    val forecast = ((result as? JsonObject)
        ?.get("response") as? JsonObject)
        ?.get(entityId)?.let { it as? JsonObject }
        ?.get("forecast") as? JsonArray
        ?: return emptyList()
    return forecast.take(5).mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        DailyForecast(
            dayOfWeek = dayOfWeek((o["datetime"] as? JsonPrimitive)?.contentOrNull),
            icon = conditionIcon((o["condition"] as? JsonPrimitive)?.contentOrNull),
            high = (o["temperature"] as? JsonPrimitive)?.doubleOrNull,
            low = (o["templow"] as? JsonPrimitive)?.doubleOrNull,
        )
    }
}

private fun dayOfWeek(datetime: String?): String =
    runCatching { OffsetDateTime.parse(datetime).dayOfWeek }
        .getOrDefault(DayOfWeek.MONDAY)
        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
```

Note the test asserts English short day names (`Mon`, `Tue`); `Locale.ENGLISH` is used so the test is locale-independent. The composable will pass these through as-is.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.rar.echodash.ui.model.WeatherModelTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/WeatherModel.kt app/src/test/java/com/rar/echodash/ui/model/WeatherModelTest.kt
git commit -m "feat: weather pill fallback chain and forecast parsing"
```

---

### Task 7: Idle-return timer + MediaBridge UI state

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/IdleReturnTimer.kt`
- Modify: `app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/IdleReturnTimerTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt`

**Interfaces:**
- Consumes: nothing new (coroutines only).
- Produces:
  - `class IdleReturnTimer(scope: CoroutineScope, timeoutMs: Long = 60_000, onReturnHome: () -> Unit)` with `fun onViewChanged(isHome: Boolean)`, `fun onInteraction()`, `fun cancel()`.
  - `data class MediaUiState(val playing: Boolean = false, val nowPlaying: String = "Nothing playing", val volume: Int = 90)` (in `vaca` package).
  - `MediaBridge.ui: StateFlow<MediaUiState>` (read-side; VACA behavior unchanged).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/ui/IdleReturnTimerTest.kt`:

```kotlin
package com.rar.echodash.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleReturnTimerTest {

    @Test
    fun firesAfterTimeoutOnNonHomeView() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(1, returns)
        timer.cancel()
    }

    @Test
    fun interactionResetsTheCountdown() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(59_000); runCurrent()
        timer.onInteraction()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, returns)          // reset kept it alive
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, returns)
        timer.cancel()
    }

    @Test
    fun homeViewIsExemptAndCancelsPending() = runTest {
        var returns = 0
        val timer = IdleReturnTimer(this, timeoutMs = 60_000) { returns++ }
        timer.onViewChanged(isHome = false)
        advanceTimeBy(30_000); runCurrent()
        timer.onViewChanged(isHome = true)   // back to Home cancels
        advanceTimeBy(60_000); runCurrent()
        timer.onInteraction()                // interaction on Home does nothing
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, returns)
        timer.cancel()
    }
}
```

Append to `app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt` (inside the class, before the final brace):

```kotlin
    @Test
    fun uiStateTracksPlayNowPlayingVolumeAndStop() {
        val engine = FakeEngine()
        val bridge = MediaBridge(engine) {}
        bridge.handleAction("play-media", json("""{"url":"http://radio/stream.mp3","volume":80}"""))
        assertEquals("http://radio/stream.mp3", bridge.ui.value.nowPlaying)
        assertEquals(80, bridge.ui.value.volume)
        engine.onPlayingChanged!!.invoke(true)
        assertTrue(bridge.ui.value.playing)
        bridge.handleAction("stop", null)
        assertFalse(bridge.ui.value.playing)
        assertEquals("Nothing playing", bridge.ui.value.nowPlaying)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.rar.echodash.ui.IdleReturnTimerTest" --tests "com.rar.echodash.vaca.MediaBridgeTest"`
Expected: FAIL — `Unresolved reference: IdleReturnTimer`; `bridge.ui` unresolved.

- [ ] **Step 3: Create `ui/IdleReturnTimer.kt`**

```kotlin
package com.rar.echodash.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After [timeoutMs] with no interaction on a non-Home view, invokes [onReturnHome]. Home is exempt.
 * Confined to [scope]'s dispatcher; callers hop into that scope. Plain, testable — not in composables.
 */
class IdleReturnTimer(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 60_000,
    private val onReturnHome: () -> Unit,
) {
    private var onHome = true
    private var job: Job? = null

    fun onViewChanged(isHome: Boolean) {
        onHome = isHome
        if (isHome) cancel() else arm()
    }

    fun onInteraction() {
        if (!onHome) arm()
    }

    private fun arm() {
        job?.cancel()
        job = scope.launch {
            delay(timeoutMs)
            onReturnHome()
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
```

- [ ] **Step 4: Add `MediaUiState` to `vaca/MediaBridge.kt`**

Add these imports to the top of the file (with the existing imports):

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
```

Add the data class above the `MediaBridge` class declaration:

```kotlin
/** Read-side snapshot of the on-device player for the Media panel. */
data class MediaUiState(
    val playing: Boolean = false,
    val nowPlaying: String = "Nothing playing",
    val volume: Int = 90,
)
```

Inside `MediaBridge`, add the flow after the `ducked` field:

```kotlin
    private val _ui = MutableStateFlow(MediaUiState(volume = volumePercent))
    val ui: StateFlow<MediaUiState> = _ui
```

In the `init { }` block, update the playing flag alongside the existing status send:

```kotlin
    init {
        engine.onPlayingChanged = { playing ->
            _ui.update { it.copy(playing = playing) }
            sendStatus(buildJsonObject {
                putJsonObject("media_player") { put("playing", playing) }
            })
        }
    }
```

In `handleAction`, update `_ui` for the media actions. Replace the `play-media`, `stop`, and `set-volume` branches with:

```kotlin
        "play-media" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            val url = payloadUrl(payload)
            if (url != null) {
                engine.play(url)
                _ui.update { it.copy(nowPlaying = url, volume = volumePercent) }
            } else {
                _ui.update { it.copy(volume = volumePercent) }
            }
            true
        }
```

```kotlin
        "stop" -> {
            engine.stop()
            _ui.update { it.copy(playing = false, nowPlaying = "Nothing playing") }
            true
        }
```

```kotlin
        "set-volume" -> {
            payloadVolume(payload)?.let { volumePercent = it; applyVolume() }
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
```

And in the `play` branch add a `_ui` volume update after `applyVolume()`:

```kotlin
        "play" -> {
            payloadVolume(payload)?.let { volumePercent = it }
            applyVolume()
            engine.resume()
            _ui.update { it.copy(volume = volumePercent) }
            true
        }
```

Also mirror volume into `_ui` at the end of `applySettings` (so `music_volume` settings show in the panel). Replace `if (changed) applyVolume()` with:

```kotlin
        if (changed) { applyVolume(); _ui.update { it.copy(volume = volumePercent) } }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.rar.echodash.ui.IdleReturnTimerTest" --tests "com.rar.echodash.vaca.MediaBridgeTest"`
Expected: PASS (IdleReturnTimerTest 3 tests; MediaBridgeTest existing + new all pass).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/IdleReturnTimer.kt app/src/main/java/com/rar/echodash/vaca/MediaBridge.kt app/src/test/java/com/rar/echodash/ui/IdleReturnTimerTest.kt app/src/test/java/com/rar/echodash/vaca/MediaBridgeTest.kt
git commit -m "feat: idle-return timer and MediaBridge UI state flow"
```

---

### Task 8: PhotoStore — browse/diff + sync scheduler (pure) + Android downloader (thin)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/photos/PhotoStore.kt`
- Create: `app/src/main/java/com/rar/echodash/photos/AndroidPhotoDownloader.kt`
- Test: `app/src/test/java/com/rar/echodash/photos/PhotoStoreTest.kt`

**Interfaces:**
- Consumes: `HaClient` (Task 2), `ConnState` (Task 2).
- Produces:
  - `object PhotoConfig { const val FOLDER = "echo-frame"; const val MEDIA_CONTENT_ID = "media-source://media_source/local/echo-frame"; const val CYCLE_MS = 5 * 60_000L; const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L; const val MAX_W = 960; const val MAX_H = 480 }`
  - `data class RemotePhoto(val contentId: String, val title: String)`
  - `fun parseBrowseChildren(result: JsonElement?): List<RemotePhoto>`
  - `data class PhotoDiff(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)`
  - `fun diffPhotos(cachedKeys: Set<String>, remote: List<RemotePhoto>): PhotoDiff`
  - `fun cacheKey(contentId: String): String` (filesystem-safe key)
  - `interface PhotoDownloader { suspend fun download(contentId: String, cacheKey: String): File? }`
  - `class PhotoStore(client: HaClient, downloader: PhotoDownloader, cacheDir: File, scope: CoroutineScope, syncIntervalMs: Long = PhotoConfig.SYNC_INTERVAL_MS)` with `val photos: StateFlow<List<File>>`, `fun start(connectionState: StateFlow<ConnState>)`, `suspend fun sync()`.
  - `class AndroidPhotoDownloader(client: HaClient, http: OkHttpClient, baseUrl: () -> String?, cacheDir: File) : PhotoDownloader` (thin; untested).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/photos/PhotoStoreTest.kt`:

```kotlin
package com.rar.echodash.photos

import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStoreTest {

    private class FakeHaClient(val browse: JsonElement?) : HaClient {
        override val connectionState = MutableStateFlow(ConnState.OFFLINE)
        override suspend fun request(type: String, fields: JsonObject): JsonElement? =
            if (type == "media_source/browse_media") browse else null
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit) = 0
        override suspend fun unsubscribe(subId: Int) {}
    }

    private val browseJson = Json.parseToJsonElement(
        """{"children":[
            {"title":"a.jpg","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/a.jpg"},
            {"title":"b.png","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/b.png"},
            {"title":"notes.txt","media_class":"document","media_content_id":"x/notes.txt"}
        ]}"""
    )

    @Test
    fun parsesOnlyImageChildren() {
        val photos = parseBrowseChildren(browseJson)
        assertEquals(listOf("a.jpg", "b.png"), photos.map { it.title })
    }

    @Test
    fun diffFindsNewAndRemoved() {
        val remote = parseBrowseChildren(browseJson)
        val cached = setOf(cacheKey("media-source://media_source/local/echo-frame/a.jpg"), "stale-key")
        val diff = diffPhotos(cached, remote)
        assertEquals(listOf("b.png"), diff.toDownload.map { it.title })
        assertEquals(listOf("stale-key"), diff.toDeleteKeys)
    }

    @Test
    fun syncDownloadsNewDeletesStaleAndPublishesFiles() = runTest {
        val cacheDir = File.createTempFile("photocache", "").let { it.delete(); it.mkdirs(); it }
        // pre-seed a stale cached file that is no longer remote
        File(cacheDir, "stale-key").writeText("old")
        val downloaded = mutableListOf<String>()
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? {
                downloaded += contentId
                return File(cacheDir, cacheKey).apply { writeText("img") }
            }
        }
        val store = PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, this)
        store.sync()
        assertEquals(2, downloaded.size)                       // a.jpg + b.png
        assertTrue(!File(cacheDir, "stale-key").exists())      // stale deleted
        assertEquals(2, store.photos.value.size)               // published
        cacheDir.deleteRecursively()
    }

    @Test
    fun schedulerSyncsOnConnectAndEverySixHours() = runTest {
        val cacheDir = File.createTempFile("photocache2", "").let { it.delete(); it.mkdirs(); it }
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        // subclass to count syncs deterministically
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, this, syncIntervalMs = 6 * 60 * 60_000L) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)                                  // start/reconnect trigger
        advanceTimeBy(6 * 60 * 60_000L + 1); runCurrent()
        assertEquals(2, syncs)                                  // 6h periodic
        cacheDir.deleteRecursively()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.rar.echodash.photos.PhotoStoreTest"`
Expected: FAIL — `Unresolved reference: parseBrowseChildren` / `PhotoStore`.

- [ ] **Step 3: Create `photos/PhotoStore.kt`**

```kotlin
package com.rar.echodash.photos

import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

object PhotoConfig {
    const val FOLDER = "echo-frame"
    const val MEDIA_CONTENT_ID = "media-source://media_source/local/echo-frame"
    const val CYCLE_MS = 5 * 60_000L
    const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L
    const val MAX_W = 960
    const val MAX_H = 480
}

data class RemotePhoto(val contentId: String, val title: String)

/** Keep only image children of a media_source/browse_media result. */
fun parseBrowseChildren(result: JsonElement?): List<RemotePhoto> {
    val children = (result as? JsonObject)?.get("children") as? JsonArray ?: return emptyList()
    return children.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        if ((o["media_class"] as? JsonPrimitive)?.contentOrNull != "image") return@mapNotNull null
        val id = (o["media_content_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: id.substringAfterLast('/')
        RemotePhoto(id, title)
    }
}

data class PhotoDiff(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)

/** Compare cached keys against remote; new photos to fetch, cached keys no longer remote to delete. */
fun diffPhotos(cachedKeys: Set<String>, remote: List<RemotePhoto>): PhotoDiff {
    val remoteKeys = remote.associateBy { cacheKey(it.contentId) }
    val toDownload = remote.filter { cacheKey(it.contentId) !in cachedKeys }
    val toDelete = cachedKeys.filter { it !in remoteKeys.keys }
    return PhotoDiff(toDownload, toDelete)
}

/** Filesystem-safe cache filename derived from a media content id. */
fun cacheKey(contentId: String): String =
    contentId.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(120)

interface PhotoDownloader {
    /** Resolve + download + downsample [contentId] to a cached file named [cacheKey]. Null on failure. */
    suspend fun download(contentId: String, cacheKey: String): File?
}

/**
 * Syncs HA's echo-frame media folder into [cacheDir] and publishes the cached files. Open for a test
 * subclass that overrides [sync]. Sync triggers: each CONNECTED transition + every [syncIntervalMs].
 */
open class PhotoStore(
    private val client: HaClient,
    private val downloader: PhotoDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val syncIntervalMs: Long = PhotoConfig.SYNC_INTERVAL_MS,
) {
    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    fun start(connectionState: StateFlow<ConnState>) {
        scope.launch {
            connectionState.collect { if (it == ConnState.CONNECTED) sync() }
        }
        scope.launch {
            while (isActive) {
                delay(syncIntervalMs)
                sync()
            }
        }
    }

    open suspend fun sync() {
        val browse = runCatching {
            client.request("media_source/browse_media", buildJsonObject {
                put("media_content_id", JsonPrimitive(PhotoConfig.MEDIA_CONTENT_ID))
            })
        }.getOrNull() ?: return
        val remote = parseBrowseChildren(browse)
        val cachedKeys = cacheDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val diff = diffPhotos(cachedKeys, remote)
        diff.toDeleteKeys.forEach { File(cacheDir, it).delete() }
        diff.toDownload.forEach { photo ->
            runCatching { downloader.download(photo.contentId, cacheKey(photo.contentId)) }
        }
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }
}
```

- [ ] **Step 4: Create `photos/AndroidPhotoDownloader.kt` (thin, untested)**

```kotlin
package com.rar.echodash.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rar.echodash.ha.HaClient
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a media content id to a signed URL, downloads it (no auth header — the authSig query
 * param authenticates), and decodes it downsampled to <=960x480 before caching as JPEG.
 */
class AndroidPhotoDownloader(
    private val client: HaClient,
    private val http: OkHttpClient,
    private val baseUrl: () -> String?,
    private val cacheDir: File,
) : PhotoDownloader {

    override suspend fun download(contentId: String, cacheKey: String): File? = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext null
        val resolved = client.request("media_source/resolve_media", buildJsonObject {
            put("media_content_id", JsonPrimitive(contentId))
            put("expires", JsonPrimitive(300))
        }) as? JsonObject ?: return@withContext null
        val rel = (resolved["url"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
        // Signed relative URLs authenticate via the authSig query param — no Authorization header.
        val url = if (rel.startsWith("/")) base.trimEnd('/') + rel else rel
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.bytes() ?: return@withContext null
        }
        val bmp = decodeDownsampled(bytes) ?: return@withContext null
        val out = File(cacheDir, cacheKey)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        out
    }

    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > PhotoConfig.MAX_W * 2 ||
            bounds.outHeight / sample > PhotoConfig.MAX_H * 2
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
```

Note: media-source resolve returns a signed **relative** path in every current HA version, so the download uses no auth header (per the Protocol Reference). The `if (rel.startsWith("/"))` prefixes the base URL for that normal relative case; a non-relative value (not expected on this device) is passed through unchanged.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.rar.echodash.photos.PhotoStoreTest"`
Expected: PASS (4 tests). (`AndroidPhotoDownloader` references Android `Bitmap`/`BitmapFactory` — it is not touched by the JVM test and compiles under `assembleDebug` in Task 11.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/photos/PhotoStore.kt app/src/main/java/com/rar/echodash/photos/AndroidPhotoDownloader.kt app/src/test/java/com/rar/echodash/photos/PhotoStoreTest.kt
git commit -m "feat: PhotoStore browse/diff/sync scheduler and Android downloader"
```

---

### Task 9: Nunito fonts, EchoTheme, Home view + icon rail (compile-gated)

This task adds the Material icons dependency, font resources, and Compose composables. They compile standalone (verified by `assembleDebug`) but are not wired into the running app until Task 11. UI has no unit tests; all logic it renders is already tested (Tasks 4–6). Rail, tile, and weather icons come from `androidx.compose.material:material-icons-extended` (approved new dependency; version managed by the existing Compose BOM). Every icon name used below was verified to exist in the material-icons 1.7.6 artifacts.

**Files:**
- Modify: `app/build.gradle.kts` (add material-icons-extended)
- Create: `app/src/main/res/font/nunito_variable.ttf` (downloaded)
- Create: `app/src/main/java/com/rar/echodash/ui/theme/Type.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/DashViews.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/HomeView.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/IconRail.kt`

**Interfaces:**
- Consumes: `WeatherPill`/`WeatherIcon` (Task 6), `ConnState` (Task 2).
- Produces:
  - `val NunitoFamily: FontFamily`, `val EchoTypography: Typography` (theme package).
  - `@Composable fun EchoTheme(content: @Composable () -> Unit)`
  - `enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, WEATHER, SOLAR }`
  - `fun railIcon(view: DashView): ImageVector`
  - `fun weatherIcon(icon: WeatherIcon): ImageVector`
  - `@Composable fun HomeView(photos: List<File>, pill: WeatherPill?, connState: ConnState, onLogout: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun IconRail(current: DashView, onSelect: (DashView) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Add the extended Material icons dependency**

In `app/build.gradle.kts`, inside the `dependencies { }` block, add this line directly after `implementation("androidx.compose.material3:material3")`. It is intentionally unversioned — the existing `composeBom` platform manages it (resolving to 1.7.6), matching how the other Compose artifacts are declared:

```kotlin
    implementation("androidx.compose.material:material-icons-extended")
```

(The artifact is large at compile time; that trade-off is approved for this project. No version suffix — adding one would fight the BOM.)

- [ ] **Step 2: Download the Nunito variable font**

Run (from repo root):

```bash
mkdir -p app/src/main/res/font
curl -sL "https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/Nunito%5Bwght%5D.ttf" \
  -o app/src/main/res/font/nunito_variable.ttf
ls -l app/src/main/res/font/nunito_variable.ttf
```
Expected: a file of roughly 270–290 KB (about 276932 bytes). Verify it is a TrueType file:
```bash
head -c 4 app/src/main/res/font/nunito_variable.ttf | xxd
```
Expected output: `00000000: 0001 0000` — the first four bytes are `00 01 00 00`, the TrueType (sfnt) magic. If the file is 0 bytes or begins with `<` (HTML), the download failed — retry; do not proceed with an empty file.

- [ ] **Step 3: Create `ui/theme/Type.kt`**

```kotlin
package com.rar.echodash.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.rar.echodash.R

/** Nunito as a single variable font; three weights via the wght axis (API 26+; device is API 30). */
val NunitoFamily = FontFamily(
    Font(R.font.nunito_variable, FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.nunito_variable, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.nunito_variable, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val base = Typography()

/** Material3 typography with every style switched to Nunito. */
val EchoTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = NunitoFamily),
    displayMedium = base.displayMedium.copy(fontFamily = NunitoFamily),
    displaySmall = base.displaySmall.copy(fontFamily = NunitoFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = NunitoFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = NunitoFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = NunitoFamily),
    titleLarge = base.titleLarge.copy(fontFamily = NunitoFamily),
    titleMedium = base.titleMedium.copy(fontFamily = NunitoFamily),
    titleSmall = base.titleSmall.copy(fontFamily = NunitoFamily),
    bodyLarge = base.bodyLarge.copy(fontFamily = NunitoFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = NunitoFamily),
    bodySmall = base.bodySmall.copy(fontFamily = NunitoFamily),
    labelLarge = base.labelLarge.copy(fontFamily = NunitoFamily),
    labelMedium = base.labelMedium.copy(fontFamily = NunitoFamily),
    labelSmall = base.labelSmall.copy(fontFamily = NunitoFamily),
)
```

- [ ] **Step 4: Create `ui/theme/Theme.kt`**

```kotlin
package com.rar.echodash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** App-wide dark theme with Nunito typography. */
@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography = EchoTypography,
        content = content,
    )
}
```

- [ ] **Step 5: Create `ui/DashViews.kt`**

```kotlin
package com.rar.echodash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.SolarPower
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.rar.echodash.ui.model.WeatherIcon

/** The six rail destinations, top-to-bottom. */
enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, WEATHER, SOLAR }

/** Material icon for a weather condition (used by the Home pill and the Weather panel). */
fun weatherIcon(icon: WeatherIcon): ImageVector = when (icon) {
    WeatherIcon.SUNNY -> Icons.Outlined.WbSunny
    WeatherIcon.CLEAR_NIGHT -> Icons.Outlined.NightsStay
    WeatherIcon.PARTLY_CLOUDY -> Icons.Outlined.WbCloudy
    WeatherIcon.CLOUDY -> Icons.Outlined.Cloud
    WeatherIcon.RAIN -> Icons.Outlined.WaterDrop
    WeatherIcon.SNOW -> Icons.Outlined.AcUnit
    WeatherIcon.STORM -> Icons.Outlined.Thunderstorm
    WeatherIcon.FOG -> Icons.Outlined.Dehaze
    WeatherIcon.WIND -> Icons.Outlined.Air
    WeatherIcon.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** Material icon for each rail destination. */
fun railIcon(view: DashView): ImageVector = when (view) {
    DashView.HOME -> Icons.Outlined.Home
    DashView.LIGHTS -> Icons.Outlined.Lightbulb
    DashView.CLIMATE -> Icons.Outlined.Thermostat
    DashView.MEDIA -> Icons.Outlined.MusicNote
    DashView.WEATHER -> Icons.Outlined.WbCloudy
    DashView.SOLAR -> Icons.Outlined.SolarPower
}
```

- [ ] **Step 6: Create `ui/IconRail.kt`**

```kotlin
package com.rar.echodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Right-side translucent rail: six Material icon buttons; the active one sits on an accent square. */
@Composable
fun IconRail(current: DashView, onSelect: (DashView) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DashView.entries.forEach { view ->
            val active = view == current
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) Color(0xFF3A6EA5) else Color.Transparent)
                    .clickable { onSelect(view) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = railIcon(view),
                    contentDescription = view.name,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 7: Create `ui/HomeView.kt`**

```kotlin
package com.rar.echodash.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ui.model.WeatherPill
import com.rar.echodash.photos.PhotoConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
private fun rememberMinuteTicker(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000 - now.longValue % 60_000)
        }
    }
    return now
}

@Composable
private fun DuskBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFF0B1026),
                0.55f to Color(0xFF2B2E4A),
                0.8f to Color(0xFF7A4A6B),
                1.0f to Color(0xFFC98A5E),
            )
        )
        val rng = Random(42)
        repeat(80) {
            drawCircle(
                color = Color.White.copy(alpha = 0.2f + rng.nextFloat() * 0.5f),
                radius = 0.4f + rng.nextFloat() * 1.8f,
                center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height * 0.55f),
            )
        }
    }
}

/** Photo slideshow backdrop; cycles shuffled cached photos every 5 min with a crossfade, else dusk. */
@Composable
private fun PhotoBackdrop(photos: List<File>) {
    if (photos.isEmpty()) { DuskBackground(); return }
    val order = remember(photos) { photos.shuffled() }
    var index by remember(order) { mutableIntStateOf(0) }
    LaunchedEffect(order) {
        while (true) {
            delay(PhotoConfig.CYCLE_MS)
            index = (index + 1) % order.size
        }
    }
    Crossfade(targetState = order[index % order.size], animationSpec = tween(1000), label = "photo") { file ->
        val bitmap = remember(file) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            DuskBackground()
        }
    }
}

/** Home: photo backdrop + 35% scrim + clock/date/weather pill; offline dot + long-press menu. */
@Composable
fun HomeView(
    photos: List<File>,
    pill: WeatherPill?,
    connState: ConnState,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberMinuteTicker()

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
    ) {
        PhotoBackdrop(photos)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            Text(
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
                color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light,
            )
            Text(
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)),
                color = Color.White.copy(alpha = 0.9f), fontSize = 24.sp,
            )
            if (pill != null) {
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val dim = if (pill.stale) 0.4f else 0.95f
                    Icon(
                        imageVector = weatherIcon(pill.icon),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = dim),
                        modifier = Modifier.size(22.dp),
                    )
                    val text = listOfNotNull(pill.conditionText, pill.temperature).joinToString(" · ")
                    Text(text, color = Color.White.copy(alpha = dim), fontSize = 18.sp)
                }
            }
        }

        if (connState != ConnState.CONNECTED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(8.dp)
                    .background(Color(0xFFE0A030), CircleShape)
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Android settings") },
                onClick = {
                    menuOpen = false
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                },
            )
            DropdownMenuItem(
                text = { Text("Log out") },
                onClick = { menuOpen = false; onLogout() },
            )
        }
    }
}
```

- [ ] **Step 8: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. (New composables are not yet referenced; unused-symbol warnings are fine. If the font resource is malformed, `aapt` fails here — re-check Step 2. If `Icons.Outlined.Lightbulb` etc. are unresolved, the icons dependency from Step 1 is missing.)

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/font/nunito_variable.ttf app/src/main/java/com/rar/echodash/ui/theme/Type.kt app/src/main/java/com/rar/echodash/ui/theme/Theme.kt app/src/main/java/com/rar/echodash/ui/DashViews.kt app/src/main/java/com/rar/echodash/ui/IconRail.kt app/src/main/java/com/rar/echodash/ui/HomeView.kt
git commit -m "feat: Nunito theme, Material icon rail, and photo-backed Home view"
```

---

### Task 10: Panel composables + DashboardShell (compile-gated)

Renders the five panels from the already-tested view models and composes them with the rail and Home view. View state is hoisted so the idle-return timer (wired in Task 11) can force a return to Home. Compile-gated; no unit tests (logic lives in Tasks 4–7).

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/panels/LightsPanel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/panels/ClimatePanel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/panels/WeatherPanel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/panels/SolarPanel.kt`
- Create: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`

**Interfaces:**
- Consumes: view models (Tasks 4–6), `MediaUiState` (Task 7), `EntityState`/`RegistryIndex` (Task 1), `DashView`/`weatherIcon`/`IconRail`/`HomeView` (Task 9).
- Produces:
  - `@Composable fun LightsPanel(groups: List<LightGroup>, connected: Boolean, onToggle: (String) -> Unit)`
  - `@Composable fun ClimatePanel(thermostats: List<ThermostatState>, connected: Boolean, onSetTemperature: (String, Double) -> Unit, onSetHvacMode: (String, String) -> Unit)`
  - `@Composable fun MediaPanel(mediaUi: MediaUiState, onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, onVolume: (Int) -> Unit)`
  - `@Composable fun WeatherPanel(weather: EntityState?, weatherEntityId: String?, fetchForecast: suspend (String) -> JsonElement?)`
  - `@Composable fun SolarPanel(flow: SolarFlow)`
  - `@Composable fun DashboardShell(current: DashView, onSelect: (DashView) -> Unit, entities: Map<String, EntityState>, registry: RegistryIndex, connState: ConnState, photos: List<File>, mediaUi: MediaUiState, onToggle: (String) -> Unit, onSetTemperature: (String, Double) -> Unit, onSetHvacMode: (String, String) -> Unit, onMediaPlay: () -> Unit, onMediaPause: () -> Unit, onMediaStop: () -> Unit, onMediaVolume: (Int) -> Unit, fetchForecast: suspend (String) -> JsonElement?, onLogout: () -> Unit, onInteraction: () -> Unit)`

- [ ] **Step 1: Create `ui/panels/PanelScaffold.kt` shared helpers**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen dark panel background; content is inset from the right so the rail never overlaps it. */
@Composable
fun PanelSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF12141C))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 96.dp),
    ) { content() }
}

/** Centered hint shown when a panel's labels match nothing. */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
```

- [ ] **Step 2: Create `ui/panels/LightsPanel.kt`**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.LightGroup
import com.rar.echodash.ui.model.LightTile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightsPanel(groups: List<LightGroup>, connected: Boolean, onToggle: (String) -> Unit) {
    PanelSurface {
        if (groups.all { it.tiles.isEmpty() }) {
            EmptyHint("Label entities with `echo-lights` in Home Assistant")
            return@PanelSurface
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEach { group ->
                group.title?.let { Text(it, color = Color.White, fontSize = 20.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.tiles.forEach { tile -> LightTileView(tile, connected, onToggle) }
                }
            }
        }
    }
}

@Composable
private fun LightTileView(tile: LightTile, connected: Boolean, onToggle: (String) -> Unit) {
    val enabled = connected && tile.available
    val bg = if (tile.on) Color(0xFF3A6EA5) else Color(0xFF232733)
    val icon = when (tile.domain) {
        "switch" -> Icons.Outlined.Power
        "fan" -> Icons.Outlined.Cyclone
        else -> Icons.Outlined.Lightbulb
    }
    Column(
        Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled) { onToggle(tile.entityId) }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(tile.name, color = Color.White, fontSize = 16.sp)
        Text(if (tile.on) "On" else "Off", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
```

- [ ] **Step 3: Create `ui/panels/ClimatePanel.kt`**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.rar.echodash.ui.model.SetpointDebouncer
import com.rar.echodash.ui.model.ThermostatState

@Composable
fun ClimatePanel(
    thermostats: List<ThermostatState>,
    connected: Boolean,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
) {
    PanelSurface {
        if (thermostats.isEmpty()) {
            EmptyHint("Label a thermostat with `echo-climate` in Home Assistant")
            return@PanelSurface
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            thermostats.forEach { t -> Thermostat(t, connected, onSetTemperature, onSetHvacMode) }
        }
    }
}

@Composable
private fun Thermostat(
    t: ThermostatState,
    connected: Boolean,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var shown by remember(t.entityId) { mutableDoubleStateOf(t.target ?: t.minTemp) }
    val debouncer = remember(t.entityId) {
        SetpointDebouncer(scope) { onSetTemperature(t.entityId, it) }
    }
    // keep local display in sync with incoming target until the user starts nudging
    remember(t.target) { t.target?.let { debouncer.reset(it, t.minTemp, t.maxTemp, t.step); shown = it }; 0 }
    val enabled = connected && t.available

    Column(
        Modifier
            .width(280.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1B1F2A))
            .alpha(if (enabled) 1f else 0.5f)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.name, color = Color.White, fontSize = 20.sp)
        Text(t.current?.let { "${it}°" } ?: "--", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StepButton("−", enabled) { debouncer.nudge(-1); shown = debouncer.displayTarget() }
            Text("${shown}°", color = Color(0xFF7FB2FF), fontSize = 28.sp)
            StepButton("+", enabled) { debouncer.nudge(+1); shown = debouncer.displayTarget() }
        }
        Text(t.hvacAction ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            t.hvacModes.forEach { mode ->
                val active = mode == t.mode
                Text(
                    mode,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color(0xFF3A6EA5) else Color(0xFF232733))
                        .clickable(enabled = enabled) { onSetHvacMode(t.entityId, mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 28.sp,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onClick() }
            .padding(top = 8.dp),
    )
}
```

- [ ] **Step 4: Create `ui/panels/MediaPanel.kt`**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.vaca.MediaUiState

@Composable
fun MediaPanel(
    mediaUi: MediaUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("On this device", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Text(mediaUi.nowPlaying, color = Color.White, fontSize = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TransportButton(if (mediaUi.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                    if (mediaUi.playing) onPause() else onPlay()
                }
                TransportButton(Icons.Outlined.Stop) { onStop() }
            }
            var slider by remember(mediaUi.volume) { mutableFloatStateOf(mediaUi.volume.toFloat()) }
            Text("Volume ${slider.toInt()}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onVolume(slider.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
```

- [ ] **Step 5: Create `ui/panels/WeatherPanel.kt`**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ui.model.DailyForecast
import com.rar.echodash.ui.model.conditionIcon
import com.rar.echodash.ui.model.parseForecasts
import com.rar.echodash.ui.weatherIcon
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

@Composable
fun WeatherPanel(
    weather: EntityState?,
    weatherEntityId: String?,
    fetchForecast: suspend (String) -> JsonElement?,
) {
    PanelSurface {
        if (weather == null || weatherEntityId == null) {
            EmptyHint("Label a weather entity with `echo-weather` in Home Assistant")
            return@PanelSurface
        }
        var forecast by remember(weatherEntityId) { mutableStateOf<List<DailyForecast>>(emptyList()) }
        // refresh on open and every 30 min; keep last on failure
        LaunchedEffect(weatherEntityId) {
            while (true) {
                val parsed = parseForecasts(fetchForecast(weatherEntityId), weatherEntityId)
                if (parsed.isNotEmpty()) forecast = parsed
                delay(30 * 60_000L)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = weatherIcon(conditionIcon(weather.state)),
                    contentDescription = weather.state,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp),
                )
                Text(weather.state, color = Color.White, fontSize = 22.sp)
                weather.attrDouble("temperature")?.let {
                    Text("${it}°", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
                }
                weather.attrDouble("humidity")?.let {
                    Text("Humidity ${it.toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                forecast.forEach { day ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B1F2A))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(day.dayOfWeek, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Icon(
                            imageVector = weatherIcon(day.icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            "${day.high?.toInt() ?: "-"}° / ${day.low?.toInt() ?: "-"}°",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Create `ui/panels/SolarPanel.kt`**

```kotlin
package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.SolarFlow
import com.rar.echodash.ui.model.SolarNode

@Composable
fun SolarPanel(flow: SolarFlow) {
    PanelSurface {
        if (flow.pv == null && flow.home == null) {
            EmptyHint("Label solar sensors with `echo-solar-pv` / `echo-solar-load` in Home Assistant")
            return@PanelSurface
        }
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                flow.pv?.let { Node(it, Color(0xFFE0A030)) }
                Arrow("→")
                flow.home?.let { Node(it, Color(0xFF3A6EA5)) }
                if (flow.grid != null) {
                    Arrow(if (flow.gridImporting == true) "←" else "→")
                    Node(flow.grid, Color(0xFF6B7280))
                }
            }
            flow.todayLine?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp) }
        }
    }
}

@Composable
private fun Node(node: SolarNode, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(96.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) { Text(node.label, color = Color.White, fontSize = 16.sp) }
        Text(node.watts, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun Arrow(glyph: String) {
    Text(glyph, color = Color.White.copy(alpha = 0.8f), fontSize = 32.sp)
}
```

- [ ] **Step 7: Create `ui/DashboardShell.kt`**

```kotlin
package com.rar.echodash.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.buildLightGroups
import com.rar.echodash.ui.model.buildSolarFlow
import com.rar.echodash.ui.model.thermostatStates
import com.rar.echodash.ui.model.weatherPill
import com.rar.echodash.ui.panels.ClimatePanel
import com.rar.echodash.ui.panels.LightsPanel
import com.rar.echodash.ui.panels.MediaPanel
import com.rar.echodash.ui.panels.SolarPanel
import com.rar.echodash.ui.panels.WeatherPanel
import com.rar.echodash.vaca.MediaUiState
import java.io.File
import kotlinx.serialization.json.JsonElement

@Composable
fun DashboardShell(
    current: DashView,
    onSelect: (DashView) -> Unit,
    entities: Map<String, EntityState>,
    registry: RegistryIndex,
    connState: ConnState,
    photos: List<File>,
    mediaUi: MediaUiState,
    onToggle: (String) -> Unit,
    onSetTemperature: (String, Double) -> Unit,
    onSetHvacMode: (String, String) -> Unit,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaStop: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    fetchForecast: suspend (String) -> JsonElement?,
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = registry.labelToEntities["echo-weather"]?.firstOrNull()

    Box(
        Modifier
            .fillMaxSize()
            // Report every touch without consuming it, so panels still receive their gestures.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onInteraction()
                    }
                }
            }
    ) {
        Crossfade(targetState = current, animationSpec = tween(300), label = "view") { view ->
            when (view) {
                DashView.HOME -> {
                    val pill = remember(entities, registry) { weatherPill(registry, entities, System.currentTimeMillis()) }
                    HomeView(photos = photos, pill = pill, connState = connState, onLogout = onLogout)
                }
                DashView.LIGHTS -> {
                    val groups = remember(entities, registry) { buildLightGroups(registry, entities) }
                    LightsPanel(groups, connected, onToggle)
                }
                DashView.CLIMATE -> {
                    val thermostats = remember(entities, registry) { thermostatStates(registry, entities) }
                    ClimatePanel(thermostats, connected, onSetTemperature, onSetHvacMode)
                }
                DashView.MEDIA -> MediaPanel(mediaUi, onMediaPlay, onMediaPause, onMediaStop, onMediaVolume)
                DashView.WEATHER -> WeatherPanel(
                    weather = weatherEntityId?.let { entities[it] },
                    weatherEntityId = weatherEntityId,
                    fetchForecast = fetchForecast,
                )
                DashView.SOLAR -> {
                    val flow = remember(entities, registry) { buildSolarFlow(registry, entities) }
                    SolarPanel(flow)
                }
            }
        }

        IconRail(
            current = current,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
    }
}
```

- [ ] **Step 8: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`FlowRow`/`ExperimentalLayoutApi` are stable-with-opt-in in Compose foundation 1.7.x from the BOM; the `@OptIn` is present. Composables are still unreferenced by the app until Task 11.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/panels/ app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
git commit -m "feat: dashboard panels and shell composition"
```

---

### Task 11: Wire the shell into the app; delete the temperature path (integration)

Wires `EntityHub`, `PhotoStore`, the theme, and `DashboardShell` into `AppDeps`/`EchoDashApp`, then removes the retired single-sensor path (`TempReading`, `reading`, `fetchTemperatureSensors`, `temperatureEntityId`, `EntityPickerScreen`, `DashboardScreen`, the Picker screen state, and the "Change sensor" menu). Ends with the full test suite green and a debug build.

**Files:**
- Rewrite: `app/src/main/java/com/rar/echodash/App.kt`
- Rewrite: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt`
- Rewrite: `app/src/main/java/com/rar/echodash/ha/HaWebSocket.kt` (drop legacy path)
- Modify: `app/src/main/java/com/rar/echodash/ha/WsMessages.kt` (drop `temperatureSensors`/`SensorEntity`)
- Delete: `app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt`
- Delete: `app/src/main/java/com/rar/echodash/ui/DashboardScreen.kt`
- Delete: `app/src/test/java/com/rar/echodash/ui/DashboardLogicTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/ha/HaWebSocketTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/ha/WsParserTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–10.
- Produces: `AppDeps.entityHub`, `AppDeps.photoStore`, `AppDeps.startDashboard()`; `HaWebSocket.start()` (no-arg). No new public types.

- [ ] **Step 1: Delete the retired screens and their test**

```bash
git rm app/src/main/java/com/rar/echodash/ui/EntityPickerScreen.kt \
       app/src/main/java/com/rar/echodash/ui/DashboardScreen.kt \
       app/src/test/java/com/rar/echodash/ui/DashboardLogicTest.kt
```

- [ ] **Step 2: Rewrite `data/SettingsStore.kt` (remove `temperatureEntityId`)**

Replace the whole file with:

```kotlin
package com.rar.echodash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SettingsStore {
    var baseUrl: String?
    var accessToken: String?
    var accessTokenExpiresAt: Long
    var refreshToken: String?
    var vacaSettingsJson: String?
    fun clearAuth()
}

class InMemorySettingsStore : SettingsStore {
    override var baseUrl: String? = null
    override var accessToken: String? = null
    override var accessTokenExpiresAt: Long = 0L
    override var refreshToken: String? = null
    override var vacaSettingsJson: String? = null

    override fun clearAuth() {
        accessToken = null
        accessTokenExpiresAt = 0L
        refreshToken = null
    }
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "echodash_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun string(key: String) = prefs.getString(key, null)
    private fun put(key: String, value: String?) =
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

    override var baseUrl: String?
        get() = string("base_url"); set(v) = put("base_url", v)
    override var accessToken: String?
        get() = string("access_token"); set(v) = put("access_token", v)
    override var accessTokenExpiresAt: Long
        get() = prefs.getLong("access_token_expires_at", 0L)
        set(v) = prefs.edit().putLong("access_token_expires_at", v).apply()
    override var refreshToken: String?
        get() = string("refresh_token"); set(v) = put("refresh_token", v)
    override var vacaSettingsJson: String?
        get() = string("vaca_settings"); set(v) = put("vaca_settings", v)

    override fun clearAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("access_token_expires_at")
            .remove("refresh_token")
            .apply()
    }
}
```

- [ ] **Step 3: Rewrite `ha/HaWebSocket.kt` (drop legacy temperature path)**

Replace the whole file with:

```kotlin
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }

fun wsUrl(baseUrl: String): String = baseUrl.replaceFirst("http", "ws") + "/api/websocket"

fun backoffMs(attempt: Int): Long =
    (2_000L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(60_000L)

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
```

Note: the `import ...put` for the `buildJsonObject` DSL is `kotlinx.serialization.json.put`; add it to the import list (the rewrite above omits it — include `import kotlinx.serialization.json.put`).

- [ ] **Step 4: Trim `ha/WsMessages.kt`**

Delete the `SensorEntity` data class and the `WsParser.temperatureSensors(...)` function (and their now-unused imports `jsonArray`). The remaining `WsParser` keeps only `parse(...)`. The file's imports reduce to:

```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

(`JsonElement` is still used by `WsIncoming.Result`.)

- [ ] **Step 5: Rewrite `App.kt`**

Replace the whole file with:

```kotlin
package com.rar.echodash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityHub
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.photos.AndroidPhotoDownloader
import com.rar.echodash.photos.PhotoStore
import com.rar.echodash.ui.DashView
import com.rar.echodash.ui.DashboardShell
import com.rar.echodash.ui.IdleReturnTimer
import com.rar.echodash.ui.KioskOverlays
import com.rar.echodash.ui.KioskUiState
import com.rar.echodash.ui.SetupScreen
import com.rar.echodash.ui.theme.EchoTheme
import com.rar.echodash.vaca.AndroidKioskDevice
import com.rar.echodash.vaca.AnnouncePlayer
import com.rar.echodash.vaca.AndroidPcmSink
import com.rar.echodash.vaca.ExoPlayerEngine
import com.rar.echodash.vaca.KioskController
import com.rar.echodash.vaca.LightSensorReporter
import com.rar.echodash.vaca.MediaBridge
import com.rar.echodash.vaca.NsdAdvertiser
import com.rar.echodash.vaca.VacaOutgoing
import com.rar.echodash.vaca.VacaServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/** Process-wide dependencies; owned by EchoDashApplication, created on the main thread. */
class AppDeps(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsStore = PrefsSettingsStore(appContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)
    val entityHub = EntityHub(ws, scope)

    private val photoCacheDir = File(appContext.cacheDir, "photos")
    private val photoDownloader = AndroidPhotoDownloader(ws, client, { settings.baseUrl }, photoCacheDir)
    val photoStore = PhotoStore(ws, photoDownloader, photoCacheDir, scope)

    // --- VACA ---
    val kioskUi = KioskUiState()
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val kioskDevice = AndroidKioskDevice(kioskUi) { ws.stop(); ws.start() }
    val kiosk = KioskController(
        mainScope,
        kioskDevice,
        persist = { settings.vacaSettingsJson = it },
        restoredJson = settings.vacaSettingsJson,
    )
    private val mediaEngine = ExoPlayerEngine(appContext)
    val media = MediaBridge(mediaEngine) { status ->
        scope.launch { vaca.sendStatus(status) }
    }
    val announce = AnnouncePlayer(
        scope,
        AndroidPcmSink(),
        onPlayed = { scope.launch { vaca.sendPlayed() } },
        setDucking = { ducked -> mainScope.launch { media.setDucked(ducked) } },
    )
    val lightSensor = LightSensorReporter(appContext) { lux ->
        mainScope.launch { kiosk.onLightLevel(lux) }
        scope.launch {
            vaca.sendStatus(buildJsonObject {
                putJsonObject("sensors") { put("light", lux.toInt()) }
            })
        }
    }
    val vaca: VacaServer = VacaServer(
        scope = scope,
        infoEvent = { VacaOutgoing.info(BuildConfig.VERSION_NAME) },
        capabilitiesEvent = {
            VacaOutgoing.capabilities(
                VacaOutgoing.buildCapabilities(BuildConfig.VERSION_NAME, lightSensor.hasSensor)
            )
        },
        listener = object : VacaServer.Listener {
            override fun onSessionStarted() {
                announce.onDisconnected()
                mainScope.launch {
                    vaca.sendSettingsFeedback(kiosk.currentSettings())
                    vaca.sendStatus(statusSnapshot())
                }
            }
            override fun onSettings(settings: JsonObject) {
                mainScope.launch {
                    kiosk.applySettings(settings)
                    media.applySettings(settings)
                }
            }
            override fun onAction(action: String, payload: JsonElement?) {
                mainScope.launch {
                    if (!media.handleAction(action, payload)) {
                        kiosk.handleAction(action, payload)
                    }
                }
            }
            override fun onAudioStart(rate: Int, width: Int, channels: Int) =
                announce.onAudioStart(rate, width, channels)
            override fun onAudioChunk(pcm: ByteArray) = announce.onAudioChunk(pcm)
            override fun onAudioStop() = announce.onAudioStop()
            override fun onSessionEnded() = announce.onDisconnected()
        },
    )
    private val nsd = NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT)

    init {
        kiosk.sendFeedback = { s -> scope.launch { vaca.sendSettingsFeedback(s) } }
    }

    /** Start the HA connection, entity hub, and photo sync for the dashboard. */
    fun startDashboard() {
        entityHub.start()
        photoStore.start(ws.connectionState)
        ws.start()
    }

    fun startVaca() {
        vaca.start()
        nsd.register()
        lightSensor.start()
    }

    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_path", "dashboard")
        }
    }
}

sealed interface Screen {
    data object Setup : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen =
    if (settings.refreshToken == null) Screen.Setup else Screen.Dashboard

@Composable
fun EchoDashApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    EchoTheme {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Setup -> SetupScreen(deps.settings, deps.auth) {
                    screen = Screen.Dashboard
                }
                Screen.Dashboard -> {
                    LaunchedEffect(Unit) { deps.startDashboard() }
                    val entities by deps.entityHub.entities.collectAsStateWithLifecycle()
                    val registry by deps.entityHub.registry.collectAsStateWithLifecycle()
                    val photos by deps.photoStore.photos.collectAsStateWithLifecycle()
                    val mediaUi by deps.media.ui.collectAsStateWithLifecycle()
                    var view by remember { mutableStateOf(DashView.HOME) }
                    val uiScope = rememberCoroutineScope()
                    val idleTimer = remember { IdleReturnTimer(uiScope) { view = DashView.HOME } }

                    DashboardShell(
                        current = view,
                        onSelect = { v ->
                            view = v
                            idleTimer.onViewChanged(v == DashView.HOME)
                            deps.kiosk.onUserInteraction()
                        },
                        entities = entities,
                        registry = registry,
                        connState = connState,
                        photos = photos,
                        mediaUi = mediaUi,
                        onToggle = { id -> deps.entityHub.callService("homeassistant", "toggle", entityId = id) },
                        onSetTemperature = { id, temp ->
                            deps.entityHub.callService(
                                "climate", "set_temperature",
                                serviceData = buildJsonObject { put("temperature", temp) },
                                entityId = id,
                            )
                        },
                        onSetHvacMode = { id, mode ->
                            deps.entityHub.callService(
                                "climate", "set_hvac_mode",
                                serviceData = buildJsonObject { put("hvac_mode", mode) },
                                entityId = id,
                            )
                        },
                        onMediaPlay = { deps.mainScope.launch { deps.media.handleAction("play", null) } },
                        onMediaPause = { deps.mainScope.launch { deps.media.handleAction("pause", null) } },
                        onMediaStop = { deps.mainScope.launch { deps.media.handleAction("stop", null) } },
                        onMediaVolume = { vol ->
                            deps.mainScope.launch {
                                deps.media.handleAction("set-volume", buildJsonObject { put("volume", vol) })
                            }
                        },
                        fetchForecast = { id -> deps.entityHub.getForecasts(id) },
                        onLogout = {
                            deps.ws.stop()
                            deps.settings.clearAuth()
                            screen = Screen.Setup
                        },
                        onInteraction = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                        },
                    )
                }
            }
            KioskOverlays(deps.kioskUi, onWakeTouch = { deps.kiosk.onUserInteraction() })
        }
    }
}
```

- [ ] **Step 6: Update `data/SettingsStoreTest.kt`**

Remove every `temperatureEntityId` line. Replace the two affected tests with:

```kotlin
    @Test
    fun roundTripsAllFields() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        assertEquals("http://ha.local:8123", s.baseUrl)
        assertEquals("at", s.accessToken)
        assertEquals(123L, s.accessTokenExpiresAt)
        assertEquals("rt", s.refreshToken)
    }

    @Test
    fun clearAuthKeepsUrl() {
        val s: SettingsStore = InMemorySettingsStore()
        s.baseUrl = "http://ha.local:8123"
        s.accessToken = "at"
        s.accessTokenExpiresAt = 123L
        s.refreshToken = "rt"
        s.clearAuth()
        assertNull(s.accessToken)
        assertEquals(0L, s.accessTokenExpiresAt)
        assertNull(s.refreshToken)
        assertEquals("http://ha.local:8123", s.baseUrl)
    }
```

- [ ] **Step 7: Update `ha/WsParserTest.kt` and `ha/HaWebSocketTest.kt`**

In `WsParserTest.kt`, delete the `filtersTemperatureSensorsFromGetStates` test and the now-unused `import kotlinx.serialization.json.Json` if it is no longer referenced (it is used nowhere else in that file — remove it).

In `HaWebSocketTest.kt`:
- Delete `connectsAuthenticatesSubscribesAndReceivesReading` (it asserts `ws.reading`) and `fetchesTemperatureSensorsViaGetStates` (it calls the removed `fetchTemperatureSensors`).
- Change every `ws.start("sensor.outside_temperature")` and `ws.start(null)` call to `ws.start()`.
- Rewrite `failsPendingRequestWhenSocketDropsBeforeResult` to drive `request` instead of `fetchTemperatureSensors`. Replace its body's inner try block with:

```kotlin
                ws.start()
                try {
                    withTimeout(10_000) { ws.request("get_states") }
                    fail("expected request to fail when socket drops")
                } catch (e: IOException) {
                    // expected: pending request failed on disconnect
                }
```

The `requestReturnsResultPayload` and `subscribeRoutesEventsByCommandId` tests from Task 2 stay (update their `ws.start(null)` to `ws.start()`). The `wsUrlConversion` and `backoffDoublesAndCaps` tests are unchanged.

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`; all tests pass (VACA suite, ha suite incl. EntityHub/EntityModels/EntityDelta, ui.model suites, IdleReturnTimer, PhotoStore, MediaBridge, SettingsStore, SetupLogic).

- [ ] **Step 9: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: wire dashboard shell into app; remove single-sensor temperature path"
```

---

### Task 12: README update

**Files:**
- Modify: `README.md`

**Interfaces:** none (documentation).

- [ ] **Step 1: Update the intro paragraph**

In `README.md`, replace the first paragraph (the "shows a fullscreen dashboard: dusk-gradient background, minute clock, and a live temperature" sentence) with:

```markdown
A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page) and shows a multi-view dashboard: a right-side icon rail switches between a photo-backed Home clock view and five panels — Lights, Climate, Media, Weather, and Solar — all driven by HA labels over one authenticated `subscribe_entities` subscription. Bundled Nunito font; auto-returns to Home after 60 s idle. Speaks the [VACA](https://github.com/msp1974/ViewAssist_Companion_App) device protocol, so the VACA HACS integration gives HA full control of the device — screen, brightness, screensaver, toasts, TTS announcements, and a media player — with native rendering instead of VACA's WebView.
```

- [ ] **Step 2: Replace the "First-run flow" section**

Replace steps 3–4 of the "First-run flow" section with:

```markdown
3. **Label your entities in HA** — the dashboard is configured entirely by labels (Settings → Areas & Labels). See *Label scheme* below. No on-device pickers.
4. **Dashboard** — tap the right-side rail to switch views; long-press the Home view for the menu (Android settings / Log out).
```

- [ ] **Step 3: Add a "Label scheme" section** after the "HA-side controls (VACA)" section

```markdown
## Label scheme

Tag entities with these labels in Home Assistant (Settings → Areas & Labels → Labels, then assign on each entity). Matching is by label id, case-insensitive.

| Label | Role |
|---|---|
| `echo-temp` | Home-pill temperature sensor (first match wins) |
| `echo-weather` | Weather entity for the pill and the Weather panel |
| `echo-lights` | Lights panel, ungrouped section (listed first) |
| `echo-lights-<group>` | Lights panel group; suffix becomes the title (`echo-lights-living-room` → "Living Room") |
| `echo-climate` | Thermostat(s) for the Climate panel (`climate.*`) |
| `echo-solar-pv` | Solar production power (W) |
| `echo-solar-load` | Home load power (W) |
| `echo-solar-grid` | Grid power (W, optional; positive = import) |
| `echo-solar-pv-today` | Today's production energy (kWh) |
| `echo-solar-load-today` | Today's consumption energy (kWh) |

Re-labeling in HA updates the device live (no restart). A panel with no matching labels shows a hint.
```

- [ ] **Step 4: Add a "Photo slideshow" section** after "Label scheme"

```markdown
## Photo slideshow (Home backdrop)

Drop images into Home Assistant's media folder at `media/echo-frame/` (via the HA Media browser or Samba). The device syncs that folder on connect and every 6 h, caches downsampled copies, and cycles them on the Home view every 5 minutes with a crossfade. With no photos, the Home view falls back to the dusk-gradient background.
```

- [ ] **Step 5: Update the panels overview** — append to the "HA-side controls (VACA)" section or add a short "Panels" note

```markdown
## Panels

- **Home** — clock, date, and a weather pill (condition + temperature); photo or gradient backdrop.
- **Lights** — grouped toggle tiles; tap to `homeassistant.toggle` (no optimistic UI).
- **Climate** — current temperature, +/- setpoint (±0.5°, debounced 800 ms → `climate.set_temperature`), HVAC mode row (`climate.set_hvac_mode`).
- **Media** — the Echo's own VACA player only: play/pause/stop and a 0–100 volume slider.
- **Weather** — current conditions + a 5-day forecast (`weather.get_forecasts`, refreshed every 30 min).
- **Solar** — Solar → Home ↔ Grid power-flow with live watts and today's kWh.
```

- [ ] **Step 6: Fix the test count line** — update the "N plain-JVM unit tests" number to the current count

Run `./gradlew test` and read the count, then update the "74 plain-JVM unit tests" sentence in the intro to the new total (the dashboard tasks add roughly 25 tests; adjust to the actual number reported).

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: dashboard shell — label scheme, photo folder, panels"
```

---

## Self-Review

**1. Spec coverage** — every spec section maps to a task:

| Spec area | Task(s) |
|---|---|
| Right rail, 6 views, crossfade, active highlight | 9 (IconRail), 10 (DashboardShell) |
| Touch → `KioskController.onUserInteraction()` | 10/11 (`onInteraction` wiring) |
| 60 s idle-return, testable class | 7 (IdleReturnTimer), 11 (wiring) |
| Offline dot + long-press menu on Home; "Change sensor" removed | 9 (HomeView), 11 (deletion) |
| Home: photo backdrop, scrim, clock, date, weather pill + fallback + staleness | 6 (weatherPill), 9 (HomeView) |
| Label scheme + EntityHub (registry list, subscribe_entities, re-subscribe, callService, getForecasts) | 1–3 |
| Lights grouping/toggle | 4, 10 |
| Climate control + ±0.5° + 800 ms debounce | 5, 10 |
| Media (device-only) + `MediaUiState` | 7, 10 |
| Weather current + 5-day forecast, 30-min refresh | 6, 10 |
| Solar flow, grid sign, W/kW, today kWh, 2-node fallback | 4, 10 |
| Empty states | 10 (EmptyHint) |
| Photo slideshow: browse/resolve/download/downsample, diff, 6 h/reconnect sync, 5-min cycle | 8, 9 |
| Nunito font app-wide, clock Light | 9 |
| Error handling (disconnect disables controls, unavailable dims, forecast keeps last, photo failures skip) | 4/5/6/8/10 |
| Deletions: EntityPickerScreen, TempReading path, stored sensor id, Change sensor | 2 (rename bridge), 11 (removal) |
| README | 12 |

**2. Placeholder scan** — no `TBD`/`TODO`/"handle edge cases"/"similar to Task N"; every code step is verbatim; every command has expected output. All Material icon names used (rail, light tiles, media transport, weather conditions) were verified to exist in the `material-icons-extended-android` / `material-icons-core-android` 1.7.6 artifacts (the versions the Compose BOM 2024.12.01 resolves).

**3. Type consistency** — signatures verified across tasks:
- `HaClient.request/subscribe/unsubscribe` (Task 2) match `EntityHub` (Task 3), `PhotoStore` (Task 8), `AndroidPhotoDownloader` (Task 8), and `HaWebSocket` rewrite (Task 11).
- `EntityState`/`RegistryIndex`/`displayName`/`applyEntitiesEvent` (Task 1) used identically in Tasks 3–6, 10.
- `DashView` / `DashboardShell` parameter list (Task 10) matches the `EchoDashApp` call site (Task 11) field-for-field.
- `MediaUiState` (Task 7) fields (`playing`, `nowPlaying`, `volume`) match `MediaPanel` (Task 10).
- `SetpointDebouncer(scope, debounceMs, onCommit)` with `reset/nudge/displayTarget/cancel` (Task 5) match `ClimatePanel` usage (Task 10).
- `weatherPill`/`WeatherPill(stale)`/`parseForecasts`/`conditionIcon`/`WeatherIcon` (Task 6) match `HomeView` (Task 9) and `WeatherPanel` (Task 10); `weatherIcon(WeatherIcon): ImageVector` and `railIcon(DashView): ImageVector` (Task 9) match their call sites in `IconRail`/`HomeView` (Task 9) and `WeatherPanel` (Task 10).
- `PhotoConfig.CYCLE_MS` (Task 8) used by `HomeView` (Task 9); `PhotoStore.photos`/`start` match `AppDeps` (Task 11).
- `SettingsStore` no longer declares `temperatureEntityId` (Task 11) — all consumers removed in the same task.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-11-dashboard-shell.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration (REQUIRED SUB-SKILL: superpowers:subagent-driven-development).
2. **Inline Execution** — execute tasks in this session with checkpoints (REQUIRED SUB-SKILL: superpowers:executing-plans).

Which approach?
