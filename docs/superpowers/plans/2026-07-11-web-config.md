# On-Device Web Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace HA-label-driven entity configuration with an on-device web config UI: the app runs an embedded NanoHTTPD server on the LAN, a browser configures entities/panels/home-settings/photo source, and a versioned `config.json` (not labels) becomes the single live source of truth.

**Architecture:** A pure-JVM `config/` layer (`DashConfig` document, `ConfigStore` with atomic persistence + StateFlow, label→config seeding) drives everything. A `web/` layer (`SessionManager` auth + `ConfigServer` NanoHTTPD subclass) serves the config page and JSON API. `EntityHub`'s watched set and `PhotoStore`'s folder/cap are derived from `DashConfig` instead of labels; the shell/panels read panel order, groups, and per-panel knobs from config. Labels are consulted only once, to seed the first config. VACA, kiosk, and OAuth code are untouched.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3, OkHttp 4.12.0, NanoHTTPD 2.3.1 (new), JUnit4 + kotlinx-coroutines-test + okhttp mockwebserver (plain-JVM tests only).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-11-web-config-design.md`. This plan supersedes the label scheme from the dashboard-shell plan (`2026-07-11-dashboard-shell.md`).
- Kotlin **2.1.0**; `compileSdk = 34`; `targetSdk = 34`; `minSdk = 28`; `jvmTarget = "17"`; `applicationId = "com.rar.echodash"`. Do NOT bump any of these. media3-exoplayer stays **1.4.1**.
- The ONLY new Gradle dependency approved is `org.nanohttpd:nanohttpd:2.3.1` (added in Task 8). No other new dependencies.
- All tests are plain-JVM JUnit4: no Robolectric, no androidTest/instrumentation. NanoHTTPD is pure Java and is exercised in JVM tests on an ephemeral port (construct with port **0**, read the bound port via `getListeningPort()`) driven by OkHttp (already a dependency).
- Pure logic (config model, seeding, rotating-subset selection, auth/session, server routing, entity-list building) is Android-free so it runs in plain JVM tests. `android.util.Log` may appear only where the established pattern already tolerates it: `app/build.gradle.kts` sets `testOptions { unitTests.isReturnDefaultValues = true }`, which no-ops `android.util.Log` in JVM tests — this is exactly why `EntityHub` and `HaWebSocket` call `android.util.Log.w` and still unit-test on the JVM. Mirror that pattern: keep `Log` usage to incidental warn-logging only; never let a test assert on it.
- VACA protocol code (`vaca/`), `KioskController`, `MainActivity` window/kiosk handling, and the OAuth/`SetupScreen` login flow are untouched. The `AppDeps` VACA wiring block (kiosk/media/announce/lightSensor/vaca/nsd) must not change.
- Build/test commands (run each in the repo root `/home/rar/android_simpla_ha_dash`):
  - Focused test: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests '<pattern>'`
  - Full unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest`
  - Full build: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:assembleDebug`
- TDD per task; frequent commits with conventional-commit messages. **Order guarantees the branch compiles and all tests pass at every commit** — no intentionally-broken intermediate states.
- Every code step below is complete and verbatim. Config numeric clamps (single source of truth): idle-return **15–3600 s**, photo cache cap **5–500**, thermostat step **0.1–5.0**, forecast days **1–5**. Config path: `filesDir/config.json`; corrupt file → `config.json.bad`. Server port **8080**. PIN: **6 digits**.

## Reference (pinned — normative for all tasks)

### HaClient interface (existing, `ha/HaWebSocket.kt`) — unchanged by this plan

```kotlin
interface HaClient {
    val connectionState: StateFlow<ConnState>            // enum ConnState { CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }
    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement?
    suspend fun subscribe(type: String, fields: JsonObject = JsonObject(emptyMap()), onEvent: (JsonObject) -> Unit): Int
    suspend fun unsubscribe(subId: Int)
}
```

### Entity model shapes (existing, `ha/EntityModels.kt`) — `RegistryIndex` gains `allEntities` in Task 6

```kotlin
data class EntityState(val entityId: String, val state: String, val attributes: JsonObject, val lastUpdatedMs: Long) {
    fun attr(key: String): String?
    fun attrDouble(key: String): Double?
    fun attrStringList(key: String): List<String>
}
data class RegistryEntity(val id: String, val name: String?, val domain: String)   // added Task 6
data class RegistryIndex(
    val labelToEntities: Map<String, List<String>>,   // echo-* label id -> entity ids (seeding only)
    val registryNames: Map<String, String>,           // entity id -> display name (all entities, Task 6)
    val allEntities: List<RegistryEntity> = emptyList(), // every registry entity (picker feed, Task 6)
) { val allEntityIds: List<String> }
fun RegistryIndex.displayName(entityId: String, state: EntityState?): String
fun parseEntityRegistry(result: JsonElement): RegistryIndex
```

### DashConfig — complete example document (`version: 1`)

```json
{
  "version": 1,
  "panels": {
    "lights":  {"enabled": true,  "order": 1},
    "climate": {"enabled": true,  "order": 2},
    "media":   {"enabled": true,  "order": 3},
    "weather": {"enabled": true,  "order": 4},
    "solar":   {"enabled": false, "order": 5}
  },
  "entities": {
    "tempSensor": "sensor.living_room_temperature",
    "weather": "weather.home",
    "climate": ["climate.hallway"],
    "solar": {
      "pv": "sensor.solar_power",
      "load": "sensor.house_load",
      "grid": "sensor.grid_power",
      "pvToday": "sensor.solar_today",
      "loadToday": "sensor.load_today"
    },
    "lightGroups": [
      {"name": "Lights", "entities": ["light.kitchen", "switch.lamp"]},
      {"name": "Living Room", "entities": ["light.tv_backlight"]}
    ]
  },
  "home": {
    "idleReturnSeconds": 60,
    "clockFormat": "AUTO",
    "slideshowEnabled": true,
    "photoFolder": "echo-frame",
    "photoCacheCap": 50
  },
  "panelOptions": {
    "thermostatStep": 0.5,
    "forecastDays": 5
  }
}
```

`clockFormat` ∈ `{"AUTO","H12","H24"}`. All fields have defaults; missing fields deserialize to defaults, unknown fields are ignored.

### HTTP API route table

| Method | Path | Auth | Request body | Success | Failure |
|---|---|---|---|---|---|
| GET | `/` , `/app.js`, `/style.css` | none | — | 200 asset bytes | 404 if asset missing |
| POST | `/api/login` | none | `{"pin":"123456"}` | 200 `{"ok":true}` + `Set-Cookie: session=<token>; Path=/; HttpOnly` | 401 `{"error":"invalid pin"}`; after 5 consecutive wrong: 429 `{"error":"locked out","retryAfter":60}` |
| GET | `/api/config` | cookie | — | 200 `DashConfig` JSON | 401 `{"error":"unauthorized"}` |
| PUT | `/api/config` | cookie | `DashConfig` JSON | 200 stored (clamped) `DashConfig` JSON | 400 `{"error":"<reason>"}` (config untouched); 401 if no cookie |
| GET | `/api/entities` | cookie | — | 200 `[{"id","name","domain","state"}, ...]` (all registry entities) | 401 |

### NanoHTTPD serve() override pattern (2.3.1)

```kotlin
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse

// Constructor: NanoHTTPD(port). port 0 => ephemeral; getListeningPort() returns the bound port.
// start(): start(SOCKET_READ_TIMEOUT, false); throws IOException if the port is already bound.
// stop(): shuts the listener down.

override fun serve(session: NanoHTTPD.IHTTPSession): Response {
    val uri = session.uri              // e.g. "/api/config"
    val method = session.method        // NanoHTTPD.Method.GET / .POST / .PUT
    // Request body: parseBody() stores non-form POST bodies under key "postData".
    // QUIRK: for PUT, NanoHTTPD saves the body to a temp file and stores its PATH under key "content".
    val files = HashMap<String, String>()
    runCatching { session.parseBody(files) }   // no-op for GET
    val body = files["postData"]
        ?: files["content"]?.let { runCatching { java.io.File(it).readText() }.getOrNull() }
        ?: ""
    // Cookie header (NanoHTTPD lowercases header names):
    val cookieHeader = session.headers["cookie"]   // "session=abc; other=1" or null
    // ... route, then build a Response:
    return newFixedLengthResponse(Status.OK, "application/json", """{"ok":true}""").apply {
        addHeader("Set-Cookie", "session=$token; Path=/; HttpOnly")
    }
}

// 429 is not in NanoHTTPD 2.3.1's Status enum — use a custom IStatus:
private val STATUS_429 = object : Response.IStatus {
    override fun getRequestStatus() = 429
    override fun getDescription() = "429 Too Many Requests"
}
// Asset bytes: newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
```

## File Map

| File | Responsibility | Task |
|---|---|---|
| `config/DashConfig.kt` | `DashConfig` + nested data classes, `ClockFormat`, `ConfigJson`, `referencedEntityIds()`, `clamped()`, `decodeConfig` | 1 |
| `config/Seeding.kt` | `seedConfig(RegistryIndex): DashConfig` (label→config, lights grouping) | 2 |
| `config/ConfigStore.kt` | load/save `config.json` (atomic), `StateFlow<DashConfig>`, corrupt→`.bad`, `needsSeed`/`seedFrom`/`update` | 3 |
| `photos/RotatingSubset.kt` | pure `rotatingSubset(listing, cachedKeys, cap, random)` selection | 4 |
| `photos/PhotoStore.kt` (modify) | config-driven folder/cap/enabled; folder/cap change resync; rotating subset | 5 |
| `ha/EntityModels.kt` (modify) | `RegistryEntity`, `RegistryIndex.allEntities`, names for all entities | 6 |
| `ha/EntityHub.kt` (modify) | config-driven watched set; re-subscribe on config change; registry for names/picker | 6 |
| `web/EntityList.kt` | pure `buildEntityListJson(registry, entities): String` | 6 |
| `web/SessionManager.kt` | PIN check, session tokens, 5-fail 60 s lockout | 7 |
| `web/Pin.kt` | `generatePin(random): String` | 7 |
| `web/ConfigServer.kt` | NanoHTTPD subclass; routes; asset serving; cookie auth | 8 |
| `web/NetworkInfo.kt` | `localIpAddress(): String?` (NetworkInterface enumeration) | 8 |
| `app/build.gradle.kts` (modify) | add `org.nanohttpd:nanohttpd:2.3.1` | 8 |
| `assets/config/index.html`, `app.js`, `style.css` | self-contained config page (vanilla JS) | 9 |
| `ui/model/LightsModel.kt`, `ClimateModel.kt`, `WeatherModel.kt`, `SolarModel.kt` (modify) | config-driven builders | 10 |
| `ui/DashViews.kt` (modify) | `railViews(Panels)`, `clockPattern(ClockFormat, Boolean)` | 10 |
| `ui/IconRail.kt`, `DashboardShell.kt`, `HomeView.kt`, `panels/*.kt` (modify) | read config: rail, groups, step, forecastDays, clock, slideshow | 10 |
| `data/SettingsStore.kt` (modify) | `var configPin: String?` | 11 |
| `App.kt` (modify) | wire `ConfigStore`/`ConfigServer`/`SessionManager`/PIN; seed on first registry; server lifecycle; Configure menu plumbing | 11 |
| `ui/HomeView.kt` (modify) | "Configure" menu entry (URL + PIN) | 11 |
| `README.md` (modify) | config page replaces label scheme; seeding migration; LAN security | 12 |

---

### Task 1: DashConfig model + clamping + serialization (pure)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces:**
- Consumes: nothing (foundation).
- Produces:
  - `enum class ClockFormat { AUTO, H12, H24 }`
  - `data class PanelConfig(val enabled: Boolean, val order: Int)`
  - `data class Panels(lights, climate, media, weather, solar: PanelConfig)` with defaults order 1..5, all enabled.
  - `data class SolarConfig(pv, load, grid, pvToday, loadToday: String? = null)` + `fun ids(): List<String>`
  - `data class LightGroup(val name: String, val entities: List<String> = emptyList())`
  - `data class Entities(tempSensor: String?, weather: String?, climate: List<String>, solar: SolarConfig, lightGroups: List<LightGroup>)`
  - `data class HomeSettings(idleReturnSeconds: Int, clockFormat: ClockFormat, slideshowEnabled: Boolean, photoFolder: String, photoCacheCap: Int)`
  - `data class PanelOptions(thermostatStep: Double, forecastDays: Int)`
  - `data class DashConfig(version, panels, entities, home, panelOptions)` + `fun referencedEntityIds(): List<String>` + `fun clamped(): DashConfig`
  - `object ConfigJson { val json: Json }` and `fun decodeConfig(text: String): DashConfig`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`:

```kotlin
package com.rar.echodash.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashConfigTest {

    @Test
    fun roundTripsThroughJson() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall"),
                solar = SolarConfig(pv = "sensor.pv"),
                lightGroups = listOf(LightGroup("Lights", listOf("light.k"))),
            ),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
    }

    @Test
    fun defaultsFillMissingFieldsAndUnknownKeysIgnored() {
        val cfg = decodeConfig("""{"version":1,"whatIsThis":true,"home":{"photoFolder":"nas"}}""")
        assertEquals(1, cfg.version)
        assertEquals("nas", cfg.home.photoFolder)
        assertEquals(60, cfg.home.idleReturnSeconds)       // default
        assertEquals(ClockFormat.AUTO, cfg.home.clockFormat) // default
        assertEquals(0.5, cfg.panelOptions.thermostatStep, 0.0)
        assertTrue(cfg.panels.lights.enabled)
    }

    @Test
    fun clampsOutOfRangeNumbers() {
        val cfg = DashConfig(
            home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 999),
            panelOptions = PanelOptions(thermostatStep = 12.0, forecastDays = 9),
        ).clamped()
        assertEquals(15, cfg.home.idleReturnSeconds)   // floor 15
        assertEquals(500, cfg.home.photoCacheCap)      // ceil 500
        assertEquals(5.0, cfg.panelOptions.thermostatStep, 0.0) // ceil 5.0
        assertEquals(5, cfg.panelOptions.forecastDays)  // ceil 5

        val low = DashConfig(
            home = HomeSettings(idleReturnSeconds = 9000, photoCacheCap = 1),
            panelOptions = PanelOptions(thermostatStep = 0.0, forecastDays = 0),
        ).clamped()
        assertEquals(3600, low.home.idleReturnSeconds)
        assertEquals(5, low.home.photoCacheCap)
        assertEquals(0.1, low.panelOptions.thermostatStep, 0.0001)
        assertEquals(1, low.panelOptions.forecastDays)
    }

    @Test
    fun referencedEntityIdsCollectsEverySlotDistinct() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall", "climate.hall"),
                solar = SolarConfig(pv = "sensor.pv", grid = "sensor.grid"),
                lightGroups = listOf(
                    LightGroup("A", listOf("light.k", "sensor.t")), // sensor.t dup with tempSensor
                    LightGroup("B", listOf("light.l")),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.t", "weather.home", "climate.hall", "sensor.pv", "sensor.grid", "light.k", "light.l"),
            cfg.referencedEntityIds(),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.DashConfigTest'`
Expected: FAIL — `Unresolved reference: DashConfig`.

- [ ] **Step 3: Create `config/DashConfig.kt`**

```kotlin
package com.rar.echodash.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ClockFormat { AUTO, H12, H24 }

@Serializable
data class PanelConfig(val enabled: Boolean = true, val order: Int = 0)

@Serializable
data class Panels(
    val lights: PanelConfig = PanelConfig(true, 1),
    val climate: PanelConfig = PanelConfig(true, 2),
    val media: PanelConfig = PanelConfig(true, 3),
    val weather: PanelConfig = PanelConfig(true, 4),
    val solar: PanelConfig = PanelConfig(true, 5),
)

@Serializable
data class SolarConfig(
    val pv: String? = null,
    val load: String? = null,
    val grid: String? = null,
    val pvToday: String? = null,
    val loadToday: String? = null,
) {
    fun ids(): List<String> = listOfNotNull(pv, load, grid, pvToday, loadToday)
}

@Serializable
data class LightGroup(val name: String, val entities: List<String> = emptyList())

@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
)

@Serializable
data class HomeSettings(
    val idleReturnSeconds: Int = 60,
    val clockFormat: ClockFormat = ClockFormat.AUTO,
    val slideshowEnabled: Boolean = true,
    val photoFolder: String = "echo-frame",
    val photoCacheCap: Int = 50,
)

@Serializable
data class PanelOptions(
    val thermostatStep: Double = 0.5,
    val forecastDays: Int = 5,
)

/** The whole device configuration; one versioned document persisted at filesDir/config.json. */
@Serializable
data class DashConfig(
    val version: Int = 1,
    val panels: Panels = Panels(),
    val entities: Entities = Entities(),
    val home: HomeSettings = HomeSettings(),
    val panelOptions: PanelOptions = PanelOptions(),
) {
    /** Every entity id referenced anywhere, first-seen order, de-duplicated (EntityHub watched set). */
    fun referencedEntityIds(): List<String> = buildList {
        entities.tempSensor?.let { add(it) }
        entities.weather?.let { add(it) }
        addAll(entities.climate)
        addAll(entities.solar.ids())
        entities.lightGroups.forEach { addAll(it.entities) }
    }.distinct()

    /** Coerce out-of-range numbers into their sane bounds (validation on save). */
    fun clamped(): DashConfig = copy(
        version = 1,
        home = home.copy(
            idleReturnSeconds = home.idleReturnSeconds.coerceIn(15, 3600),
            photoCacheCap = home.photoCacheCap.coerceIn(5, 500),
        ),
        panelOptions = panelOptions.copy(
            thermostatStep = panelOptions.thermostatStep.coerceIn(0.1, 5.0),
            forecastDays = panelOptions.forecastDays.coerceIn(1, 5),
        ),
    )
}

/** Shared JSON: tolerate unknown keys, always emit defaults so the stored document is complete. */
object ConfigJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/** Decode a config document; throws [kotlinx.serialization.SerializationException] on malformed input. */
fun decodeConfig(text: String): DashConfig = ConfigJson.json.decodeFromString(DashConfig.serializer(), text)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.DashConfigTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "feat: DashConfig versioned config model with clamping and serialization"
```

---

### Task 2: Label→config seeding (pure)

Builds the first `DashConfig` from current `echo-*` labels so existing installs migrate seamlessly. Replicates the dashboard-shell lights-grouping semantics (bare `echo-lights` → group "Lights" first; `echo-lights-<suffix>` → title-cased, alphabetical) without depending on the UI layer.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/config/Seeding.kt`
- Test: `app/src/test/java/com/rar/echodash/config/SeedingTest.kt`

**Interfaces:**
- Consumes: `RegistryIndex`, `RegistryIndex.labelToEntities` (existing `ha/EntityModels.kt`); `DashConfig`/`Entities`/`SolarConfig`/`LightGroup` (Task 1).
- Produces: `fun seedConfig(registry: RegistryIndex): DashConfig`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/config/SeedingTest.kt`:

```kotlin
package com.rar.echodash.config

import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedingTest {
    private fun reg(s: String) = parseEntityRegistry(Json.parseToJsonElement(s))

    @Test
    fun seedsEverySlotFromLabels() {
        val cfg = seedConfig(reg(
            """[
              {"entity_id":"sensor.temp","labels":["echo-temp"]},
              {"entity_id":"weather.home","labels":["echo-weather"]},
              {"entity_id":"climate.hall","labels":["echo-climate"]},
              {"entity_id":"sensor.notclimate","labels":["echo-climate"]},
              {"entity_id":"sensor.pv","labels":["echo-solar-pv"]},
              {"entity_id":"sensor.load","labels":["echo-solar-load"]},
              {"entity_id":"sensor.grid","labels":["echo-solar-grid"]},
              {"entity_id":"sensor.pvtoday","labels":["echo-solar-pv-today"]},
              {"entity_id":"sensor.loadtoday","labels":["echo-solar-load-today"]}
            ]"""
        ))
        assertEquals("sensor.temp", cfg.entities.tempSensor)
        assertEquals("weather.home", cfg.entities.weather)
        assertEquals(listOf("climate.hall"), cfg.entities.climate) // only climate.* kept
        assertEquals("sensor.pv", cfg.entities.solar.pv)
        assertEquals("sensor.load", cfg.entities.solar.load)
        assertEquals("sensor.grid", cfg.entities.solar.grid)
        assertEquals("sensor.pvtoday", cfg.entities.solar.pvToday)
        assertEquals("sensor.loadtoday", cfg.entities.solar.loadToday)
    }

    @Test
    fun seedsLightGroupsBareFirstThenTitleCasedAlphabetical() {
        val cfg = seedConfig(reg(
            """[
              {"entity_id":"light.k","labels":["echo-lights"]},
              {"entity_id":"switch.lamp","labels":["echo-lights"]},
              {"entity_id":"light.tv","labels":["echo-lights-living-room"]},
              {"entity_id":"light.bed","labels":["echo-lights-bedroom"]}
            ]"""
        ))
        assertEquals(
            listOf("Lights", "Bedroom", "Living Room"),
            cfg.entities.lightGroups.map { it.name },
        )
        assertEquals(listOf("light.k", "switch.lamp"), cfg.entities.lightGroups[0].entities)
        assertEquals(listOf("light.bed"), cfg.entities.lightGroups[1].entities)
        assertEquals(listOf("light.tv"), cfg.entities.lightGroups[2].entities)
    }

    @Test
    fun emptyRegistryYieldsDefaults() {
        val cfg = seedConfig(reg("""[]"""))
        assertEquals(DashConfig(), cfg)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.SeedingTest'`
Expected: FAIL — `Unresolved reference: seedConfig`.

- [ ] **Step 3: Create `config/Seeding.kt`**

```kotlin
package com.rar.echodash.config

import com.rar.echodash.ha.RegistryIndex
import java.util.Locale

private const val LIGHTS_LABEL = "echo-lights"
private const val LIGHTS_PREFIX = "echo-lights-"

/**
 * Build the first DashConfig from current echo-* labels. Called once, when no config.json exists.
 * Mirrors the dashboard-shell grouping: bare `echo-lights` becomes a group named "Lights" (listed
 * first); each `echo-lights-<suffix>` becomes a title-cased group, ordered alphabetically by title.
 */
fun seedConfig(registry: RegistryIndex): DashConfig {
    val l = registry.labelToEntities
    fun first(label: String): String? = l[label]?.firstOrNull()

    val groups = buildList {
        l[LIGHTS_LABEL]?.let { add(LightGroup(name = "Lights", entities = it)) }
        l.keys
            .filter { it.startsWith(LIGHTS_PREFIX) && it.length > LIGHTS_PREFIX.length }
            .map { it to titleCase(it.removePrefix(LIGHTS_PREFIX)) }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
            .forEach { (label, title) -> add(LightGroup(name = title, entities = l[label].orEmpty())) }
    }

    return DashConfig(
        entities = Entities(
            tempSensor = first("echo-temp"),
            weather = first("echo-weather"),
            climate = l["echo-climate"].orEmpty().filter { it.startsWith("climate.") },
            solar = SolarConfig(
                pv = first("echo-solar-pv"),
                load = first("echo-solar-load"),
                grid = first("echo-solar-grid"),
                pvToday = first("echo-solar-pv-today"),
                loadToday = first("echo-solar-load-today"),
            ),
            lightGroups = groups,
        ),
    )
}

private fun titleCase(slug: String): String =
    slug.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.SeedingTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/Seeding.kt app/src/test/java/com/rar/echodash/config/SeedingTest.kt
git commit -m "feat: seed DashConfig from echo-* labels for label->config migration"
```

---

### Task 3: ConfigStore — atomic persistence, StateFlow, seeding, corrupt recovery

Loads/saves `config.json` under an injected directory, exposes `StateFlow<DashConfig>`, seeds from the registry on first run, and recovers from corruption by renaming to `config.json.bad`. Also wires an (initially unused) `ConfigStore` into `AppDeps` so later tasks can consume it while the branch keeps compiling.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/config/ConfigStore.kt`
- Test: `app/src/test/java/com/rar/echodash/config/ConfigStoreTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (add `val configStore`)

**Interfaces:**
- Consumes: `DashConfig`/`ConfigJson`/`decodeConfig`/`clamped` (Task 1); `seedConfig` (Task 2); `RegistryIndex` (existing).
- Produces:
  - `class ConfigStore(dir: File, seeder: (RegistryIndex) -> DashConfig = ::seedConfig)`
  - `val config: StateFlow<DashConfig>`
  - `fun needsSeed(): Boolean` — true when no valid `config.json` has been persisted yet.
  - `fun seedFrom(registry: RegistryIndex)` — build via `seeder`, clamp, persist, emit; clears `needsSeed`.
  - `fun update(new: DashConfig): DashConfig` — clamp, persist atomically, emit, return the stored value.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/config/ConfigStoreTest.kt`:

```kotlin
package com.rar.echodash.config

import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigStoreTest {
    private fun tempDir(): File =
        File.createTempFile("cfgstore", "").let { it.delete(); it.mkdirs(); it }

    private fun reg(s: String) = parseEntityRegistry(Json.parseToJsonElement(s))

    @Test
    fun freshDirNeedsSeedAndHoldsDefaults() {
        val store = ConfigStore(tempDir())
        assertTrue(store.needsSeed())
        assertEquals(DashConfig(), store.config.value)
    }

    @Test
    fun seedFromPersistsAndClearsNeedsSeed() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        store.seedFrom(reg("""[{"entity_id":"weather.home","labels":["echo-weather"]}]"""))
        assertFalse(store.needsSeed())
        assertEquals("weather.home", store.config.value.entities.weather)
        // a new store over the same dir loads the persisted config and does NOT need seeding
        val reopened = ConfigStore(dir)
        assertFalse(reopened.needsSeed())
        assertEquals("weather.home", reopened.config.value.entities.weather)
    }

    @Test
    fun updateClampsPersistsAndEmits() {
        val dir = tempDir()
        val store = ConfigStore(dir)
        val stored = store.update(
            DashConfig(home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 9000))
        )
        assertEquals(15, stored.home.idleReturnSeconds)   // clamped
        assertEquals(500, stored.home.photoCacheCap)      // clamped
        assertEquals(stored, store.config.value)
        assertFalse(store.needsSeed())
        assertEquals(stored, ConfigStore(dir).config.value) // survives reload
    }

    @Test
    fun corruptFileIsRenamedToBadAndReseedable() {
        val dir = tempDir()
        File(dir, "config.json").writeText("{ this is not json")
        val store = ConfigStore(dir)
        assertTrue(store.needsSeed())                       // corrupt => treat as fresh
        assertTrue(File(dir, "config.json.bad").exists())   // corrupt file preserved
        assertEquals(DashConfig(), store.config.value)      // defaults until seeded
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.ConfigStoreTest'`
Expected: FAIL — `Unresolved reference: ConfigStore`.

- [ ] **Step 3: Create `config/ConfigStore.kt`**

```kotlin
package com.rar.echodash.config

import com.rar.echodash.ha.RegistryIndex
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the DashConfig document at [dir]/config.json and exposes it as a StateFlow. Writes are
 * atomic (temp file + rename). A corrupt file is renamed to config.json.bad and the store falls back
 * to defaults, flagged as [needsSeed] so the caller can seed from labels once the registry arrives.
 * Android-free (java.io.File injected) so it runs in plain JVM tests.
 */
class ConfigStore(
    private val dir: File,
    private val seeder: (RegistryIndex) -> DashConfig = ::seedConfig,
) {
    private val file = File(dir, "config.json")
    private val _config = MutableStateFlow(DashConfig())
    val config: StateFlow<DashConfig> = _config
    private var persisted = false

    init {
        if (!dir.exists()) dir.mkdirs()
        if (file.exists()) {
            val loaded = runCatching { decodeConfig(file.readText()) }.getOrNull()
            if (loaded != null) {
                _config.value = loaded.clamped()
                persisted = true
            } else {
                runCatching { file.renameTo(File(dir, "config.json.bad")) }
                android.util.Log.w("ConfigStore", "config.json corrupt; renamed to config.json.bad")
            }
        }
    }

    /** True until a valid config has been persisted (fresh install or recovered corruption). */
    fun needsSeed(): Boolean = !persisted

    /** Seed from the registry, persist, and emit. No-op semantics: safe even with an empty registry. */
    fun seedFrom(registry: RegistryIndex) {
        write(seeder(registry).clamped())
    }

    /** Clamp, persist atomically, emit, and return the stored config. */
    fun update(new: DashConfig): DashConfig {
        val clamped = new.clamped()
        write(clamped)
        return clamped
    }

    private fun write(cfg: DashConfig) {
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(ConfigJson.json.encodeToString(DashConfig.serializer(), cfg))
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse rename onto an existing file; fall back to delete + rename.
            file.delete()
            tmp.renameTo(file)
        }
        _config.value = cfg
        persisted = true
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.ConfigStoreTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Wire `ConfigStore` into `AppDeps` (unused for now)**

In `app/src/main/java/com/rar/echodash/App.kt`, add the import and a field. After the existing import block add:

```kotlin
import com.rar.echodash.config.ConfigStore
```

Immediately after the `val entityHub = EntityHub(ws, scope)` line, add:

```kotlin
    val configStore = ConfigStore(appContext.filesDir)
```

(It is referenced by later tasks; leaving it unused here compiles cleanly.)

- [ ] **Step 6: Verify the module still builds and tests pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.config.*'`
Expected: PASS (DashConfigTest, SeedingTest, ConfigStoreTest).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/config/ConfigStore.kt app/src/test/java/com/rar/echodash/config/ConfigStoreTest.kt app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat: ConfigStore with atomic persistence, seeding, and corrupt recovery"
```

---

### Task 4: Rotating-subset selection (pure)

The bounded-cache selection algorithm for large photo folders. Under-cap → sync-all; over-cap → maintain a random subset of `cap` items, evicting folder-removed files first, then ~20% of the surviving cache, refilling to `cap` with never-cached items. Randomness injected for determinism.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/photos/RotatingSubset.kt`
- Test: `app/src/test/java/com/rar/echodash/photos/RotatingSubsetTest.kt`

**Interfaces:**
- Consumes: `RemotePhoto`, `cacheKey(String)` (existing `photos/PhotoStore.kt`).
- Produces:
  - `data class PhotoPlan(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)`
  - `fun rotatingSubset(listing: List<RemotePhoto>, cachedKeys: Set<String>, cap: Int, random: Random): PhotoPlan`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rar/echodash/photos/RotatingSubsetTest.kt`:

```kotlin
package com.rar.echodash.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RotatingSubsetTest {
    private fun photo(name: String) =
        RemotePhoto("media-source://media_source/local/f/$name", name)

    private fun keyOf(name: String) = cacheKey("media-source://media_source/local/f/$name")

    @Test
    fun underCapDownloadsNewAndDeletesRemovedLikeSyncAll() {
        val listing = listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"))
        val cached = setOf(keyOf("a.jpg"), "gone-key")
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(0))
        assertEquals(setOf(keyOf("b.jpg"), keyOf("c.jpg")), plan.toDownload.map { cacheKey(it.contentId) }.toSet())
        assertEquals(listOf("gone-key"), plan.toDeleteKeys)
    }

    @Test
    fun overCapEvictsRemovedFilesFirst() {
        val listing = (1..10).map { photo("p$it.jpg") }         // 10 remote, cap 4
        // cache holds two remote survivors + one file no longer in the folder
        val cached = setOf(keyOf("p1.jpg"), keyOf("p2.jpg"), "removed-from-folder")
        val plan = rotatingSubset(listing, cached, cap = 4, random = Random(1))
        assertTrue("removed-from-folder must always be evicted", "removed-from-folder" in plan.toDeleteKeys)
    }

    @Test
    fun overCapRefillsToCapWithNeverCachedItems() {
        val listing = (1..20).map { photo("p$it.jpg") }         // 20 remote, cap 8
        val cached = (1..4).map { keyOf("p$it.jpg") }.toSet()   // 4 currently cached, all still remote
        val plan = rotatingSubset(listing, cached, cap = 8, random = Random(2))
        // ~20% of 4 surviving cached = ceil(0.8) = 1 evicted; final cache size lands on cap (8):
        // survivorsKept = 4 - evicted; downloads = 8 - survivorsKept
        val evicted = plan.toDeleteKeys.count { it in cached }
        val survivorsKept = 4 - evicted
        assertEquals(8, survivorsKept + plan.toDownload.size)
        // every download is a never-cached remote item
        assertTrue(plan.toDownload.none { cacheKey(it.contentId) in cached })
        // downloads are distinct
        assertEquals(plan.toDownload.size, plan.toDownload.map { it.contentId }.toSet().size)
    }

    @Test
    fun overCapEvictsAboutTwentyPercentOfSurvivingCache() {
        val listing = (1..100).map { photo("p$it.jpg") }
        val cached = (1..50).map { keyOf("p$it.jpg") }.toSet()  // 50 cached, all still remote, cap 50
        val plan = rotatingSubset(listing, cached, cap = 50, random = Random(3))
        val evicted = plan.toDeleteKeys.count { it in cached }
        assertEquals(10, evicted)                                // ceil(50 * 0.20) = 10
        assertEquals(10, plan.toDownload.size)                   // refill back to 50
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.photos.RotatingSubsetTest'`
Expected: FAIL — `Unresolved reference: rotatingSubset`.

- [ ] **Step 3: Create `photos/RotatingSubset.kt`**

```kotlin
package com.rar.echodash.photos

import kotlin.math.ceil
import kotlin.random.Random

/** A sync plan: which remote items to fetch and which cached keys to delete. */
data class PhotoPlan(val toDownload: List<RemotePhoto>, val toDeleteKeys: List<String>)

/**
 * Choose the next cached subset for a photo folder.
 *
 * - listing.size <= cap: sync-all — download every not-yet-cached remote item, delete every cached
 *   key that is no longer in the folder.
 * - listing.size > cap: keep a bounded random subset. Always evict files that left the folder, then
 *   evict ceil(20%) of the surviving cache, then refill to [cap] with random never-cached remote
 *   items. Over successive syncs the whole archive rotates through; storage stays bounded.
 *
 * [random] is injected so the selection is deterministic under test.
 */
fun rotatingSubset(
    listing: List<RemotePhoto>,
    cachedKeys: Set<String>,
    cap: Int,
    random: Random,
): PhotoPlan {
    val remoteByKey = listing.associateBy { cacheKey(it.contentId) }
    val removed = cachedKeys.filter { it !in remoteByKey }        // no longer in the folder
    val survivors = cachedKeys.filter { it in remoteByKey }        // cached AND still remote

    if (listing.size <= cap) {
        val toDownload = listing.filter { cacheKey(it.contentId) !in cachedKeys }
        return PhotoPlan(toDownload = toDownload, toDeleteKeys = removed)
    }

    val evictCount = ceil(survivors.size * 0.20).toInt()
    val evicted = survivors.shuffled(random).take(evictCount)
    val keptKeys = survivors.toSet() - evicted.toSet()

    val neverCached = listing.filter { cacheKey(it.contentId) !in cachedKeys }
    val refillCount = (cap - keptKeys.size).coerceAtLeast(0)
    val toDownload = neverCached.shuffled(random).take(refillCount)

    return PhotoPlan(toDownload = toDownload, toDeleteKeys = removed + evicted)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.photos.RotatingSubsetTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/photos/RotatingSubset.kt app/src/test/java/com/rar/echodash/photos/RotatingSubsetTest.kt
git commit -m "feat: rotating-subset selection for bounded photo cache"
```

---

### Task 5: PhotoStore — config-driven folder/cap/enabled + rotating subset

Replaces the compile-time `echo-frame`/`SYNC` constants with values read from `DashConfig`: the media folder, the cache cap, and the slideshow-enabled flag all come from `ConfigStore`. A folder or cap change triggers a resync; `sync()` uses `rotatingSubset` so a huge NAS-mounted folder rotates through a bounded cache. When the slideshow is disabled, `sync()` no-ops (the Home view falls back to the dusk gradient).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/photos/PhotoStore.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (pass `configStore.config`)
- Modify: `app/src/test/java/com/rar/echodash/photos/PhotoStoreTest.kt`

**Interfaces:**
- Consumes: `PhotoDownloader`, `RemotePhoto`, `parseBrowseChildren`, `cacheKey` (existing); `rotatingSubset`/`PhotoPlan` (Task 4); `ConfigStore.config: StateFlow<DashConfig>` (Task 3).
- Produces (changed signatures):
  - `open class PhotoStore(client, downloader, cacheDir, scope, config: StateFlow<DashConfig>, syncIntervalMs)`
  - `fun start(connectionState: StateFlow<ConnState>)` — now also collects `config` and resyncs on folder/cap change.
  - `open suspend fun sync()` — folder/cap/enabled from `config.value`.

- [ ] **Step 1: Update `PhotoStoreTest.kt` for the new constructor + behaviors**

Replace the whole file with:

```kotlin
package com.rar.echodash.photos

import com.rar.echodash.config.DashConfig
import com.rar.echodash.config.HomeSettings
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStoreTest {

    /** Answers browse for whatever folder id is requested; records the requested content ids. */
    private class FakeHaClient(val browse: JsonElement?) : HaClient {
        override val connectionState = MutableStateFlow(ConnState.OFFLINE)
        val browseContentIds = mutableListOf<String>()
        override suspend fun request(type: String, fields: JsonObject): JsonElement? {
            if (type == "media_source/browse_media") {
                (fields["media_content_id"] as? JsonPrimitive)?.contentOrNull?.let { browseContentIds += it }
                return browse
            }
            return null
        }
        override suspend fun subscribe(type: String, fields: JsonObject, onEvent: (JsonObject) -> Unit) = 0
        override suspend fun unsubscribe(subId: Int) {}
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private val browseJson = Json.parseToJsonElement(
        """{"children":[
            {"title":"a.jpg","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/a.jpg"},
            {"title":"b.png","media_class":"image","media_content_id":"media-source://media_source/local/echo-frame/b.png"}
        ]}"""
    )

    private fun cfg(folder: String = "echo-frame", cap: Int = 50, slideshow: Boolean = true) =
        DashConfig(home = HomeSettings(photoFolder = folder, photoCacheCap = cap, slideshowEnabled = slideshow))

    @Test
    fun syncDownloadsNewDeletesStaleAndPublishesFiles() = runTest {
        val cacheDir = tempDir("photocache")
        File(cacheDir, "stale-key").writeText("old")
        val downloaded = mutableListOf<String>()
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? {
                downloaded += contentId
                return File(cacheDir, cacheKey).apply { writeText("img") }
            }
        }
        val store = PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, this, MutableStateFlow(cfg()))
        store.sync()
        assertEquals(2, downloaded.size)
        assertTrue(!File(cacheDir, "stale-key").exists())
        assertEquals(2, store.photos.value.size)
        cacheDir.deleteRecursively()
    }

    @Test
    fun syncUsesConfiguredFolderForBrowse() = runTest {
        val cacheDir = tempDir("photocache_folder")
        val client = FakeHaClient(browseJson)
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? =
                File(cacheDir, cacheKey).apply { writeText("img") }
        }
        val store = PhotoStore(client, downloader, cacheDir, this, MutableStateFlow(cfg(folder = "nas-photos")))
        store.sync()
        assertEquals(
            listOf("media-source://media_source/local/nas-photos"),
            client.browseContentIds,
        )
        cacheDir.deleteRecursively()
    }

    @Test
    fun disabledSlideshowSkipsSync() = runTest {
        val cacheDir = tempDir("photocache_disabled")
        val client = FakeHaClient(browseJson)
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val store = PhotoStore(client, downloader, cacheDir, this, MutableStateFlow(cfg(slideshow = false)))
        store.sync()
        assertTrue(client.browseContentIds.isEmpty()) // never browsed
        cacheDir.deleteRecursively()
    }

    @Test
    fun schedulerSyncsOnConnectAndEverySixHours() = runTest {
        val cacheDir = tempDir("photocache2")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, MutableStateFlow(cfg()), syncIntervalMs = 6 * 60 * 60_000L) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)
        advanceTimeBy(6 * 60 * 60_000L + 1); runCurrent()
        assertEquals(2, syncs)
        cacheDir.deleteRecursively()
    }

    @Test
    fun resyncsWhenFolderOrCapChanges() = runTest {
        val cacheDir = tempDir("photocache_cfgchange")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val config = MutableStateFlow(cfg(folder = "echo-frame", cap = 50))
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, config) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)                              // connect trigger
        config.value = cfg(folder = "new-folder", cap = 50); runCurrent()
        assertEquals(2, syncs)                              // folder change resyncs
        config.value = cfg(folder = "new-folder", cap = 25); runCurrent()
        assertEquals(3, syncs)                              // cap change resyncs
        // an unrelated change (slideshow flag flip only) still resyncs at most on folder/cap keys:
        config.value = cfg(folder = "new-folder", cap = 25); runCurrent()
        assertEquals(3, syncs)                              // identical folder+cap => no extra sync
        cacheDir.deleteRecursively()
    }

    @Test
    fun secondStartIsNoOp() = runTest {
        val cacheDir = tempDir("photocache4")
        var syncs = 0
        val downloader = object : PhotoDownloader {
            override suspend fun download(contentId: String, cacheKey: String): File? = null
        }
        val conn = MutableStateFlow(ConnState.OFFLINE)
        val store = object : PhotoStore(FakeHaClient(browseJson), downloader, cacheDir, backgroundScope, MutableStateFlow(cfg())) {
            override suspend fun sync() { syncs++ }
        }
        store.start(conn)
        store.start(conn)
        conn.value = ConnState.CONNECTED; runCurrent()
        assertEquals(1, syncs)
        cacheDir.deleteRecursively()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.photos.PhotoStoreTest'`
Expected: FAIL — constructor arity mismatch / `photoFolder` unresolved.

- [ ] **Step 3: Rewrite `photos/PhotoStore.kt`**

Replace the whole file with:

```kotlin
package com.rar.echodash.photos

import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaClient
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

object PhotoConfig {
    const val CYCLE_MS = 5 * 60_000L
    const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L
    const val MAX_W = 960
    const val MAX_H = 480
    /** media-source content id for a folder relative to HA's media/ root. */
    fun contentId(folder: String): String = "media-source://media_source/local/$folder"
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

/** Filesystem-safe cache filename derived from a media content id. */
fun cacheKey(contentId: String): String =
    contentId.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(120)

interface PhotoDownloader {
    /** Resolve + download + downsample [contentId] to a cached file named [cacheKey]. Null on failure. */
    suspend fun download(contentId: String, cacheKey: String): File?
}

/**
 * Syncs a HA media folder into [cacheDir] and publishes the cached files. The folder, the cache cap,
 * and the slideshow-enabled flag come from [config]. Sync triggers: each CONNECTED transition, every
 * [syncIntervalMs], and every change to the (folder, cap) pair. Large folders are kept as a bounded
 * rotating subset via [rotatingSubset]; folders within the cap sync fully. [sync] is serialized with
 * a mutex so a reconnect mid-sync can't race a config-change or periodic trigger over the same cache.
 * Open for a test subclass that overrides [sync].
 */
open class PhotoStore(
    private val client: HaClient,
    private val downloader: PhotoDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val config: StateFlow<DashConfig>,
    private val syncIntervalMs: Long = PhotoConfig.SYNC_INTERVAL_MS,
    private val random: Random = Random.Default,
) {
    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos
    private val syncMutex = Mutex()
    private var started = false

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    fun start(connectionState: StateFlow<ConnState>) {
        if (started) return
        started = true
        scope.launch {
            connectionState.collect { if (it == ConnState.CONNECTED) sync() }
        }
        scope.launch {
            // Resync when the folder or cap changes (ignore other config edits).
            config
                .map { it.home.photoFolder to it.home.photoCacheCap }
                .distinctUntilChanged()
                .collect { sync() }
        }
        scope.launch {
            while (isActive) {
                delay(syncIntervalMs)
                sync()
            }
        }
    }

    open suspend fun sync() = syncMutex.withLock {
        val home = config.value.home
        if (!home.slideshowEnabled) return@withLock
        val browse = runCatching {
            client.request("media_source/browse_media", buildJsonObject {
                put("media_content_id", JsonPrimitive(PhotoConfig.contentId(home.photoFolder)))
            })
        }.getOrNull() ?: return@withLock
        val remote = parseBrowseChildren(browse)
        val cachedKeys = cacheDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val plan = rotatingSubset(remote, cachedKeys, home.photoCacheCap, random)
        plan.toDeleteKeys.forEach { File(cacheDir, it).delete() }
        plan.toDownload.forEach { photo ->
            runCatching { downloader.download(photo.contentId, cacheKey(photo.contentId)) }
        }
        _photos.value = cacheDir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }
}
```

Note: the `config` collector fires once with the current value on `start`, and again on each folder/cap change — the first firing plus the CONNECTED collector both call `sync()`, which is safe (mutex-serialized and idempotent). `PhotoConfig.FOLDER`/`MEDIA_CONTENT_ID` are removed (replaced by `contentId(folder)`); `diffPhotos`/`PhotoDiff` are removed (superseded by `rotatingSubset`).

- [ ] **Step 4: Update `App.kt` to pass the config flow**

In `app/src/main/java/com/rar/echodash/App.kt`, change the `photoStore` construction line:

```kotlin
    val photoStore = PhotoStore(ws, photoDownloader, photoCacheDir, scope, configStore.config)
```

- [ ] **Step 5: Run the photo tests + full module compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.photos.*'`
Expected: PASS (RotatingSubsetTest + PhotoStoreTest). This also compiles `App.kt` (the `photoStore` change) and `AndroidPhotoDownloader.kt` (unchanged — still uses `PhotoConfig.MAX_W/MAX_H`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/photos/PhotoStore.kt app/src/main/java/com/rar/echodash/App.kt app/src/test/java/com/rar/echodash/photos/PhotoStoreTest.kt
git commit -m "feat: config-driven photo folder/cap with rotating subset sync"
```

---

### Task 6: EntityHub config-driven watching + full registry list + entity-list API

Three coupled changes: (a) `parseEntityRegistry`/`RegistryIndex` now capture **every** registry entity (id, name, domain) and names for all entities, feeding the web picker; (b) `EntityHub`'s watched set is derived from `DashConfig.referencedEntityIds()` (collected from an injected config flow) instead of `echo-*` labels — it re-subscribes on config change and keeps re-listing the registry for names; (c) a pure `buildEntityListJson` renders the `/api/entities` payload.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ha/EntityModels.kt`
- Modify: `app/src/main/java/com/rar/echodash/ha/EntityHub.kt`
- Create: `app/src/main/java/com/rar/echodash/web/EntityList.kt`
- Modify: `app/src/test/java/com/rar/echodash/ha/EntityModelsTest.kt`
- Modify: `app/src/test/java/com/rar/echodash/ha/EntityHubTest.kt`
- Create: `app/src/test/java/com/rar/echodash/web/EntityListTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (pass `configStore.config` to `EntityHub`)

**Interfaces:**
- Consumes: `HaClient`/`ConnState` (existing); `DashConfig.referencedEntityIds()` (Task 1); `parseEntityRegistry`/`applyEntitiesEvent`/`EntityState` (existing).
- Produces:
  - `data class RegistryEntity(val id: String, val name: String?, val domain: String)`
  - `RegistryIndex(labelToEntities, registryNames, allEntities = emptyList())` (new field; `registryNames` now covers all entities).
  - `class EntityHub(client, scope, config: StateFlow<DashConfig>, clock)` — watched set = `config.value.referencedEntityIds()`.
  - `fun buildEntityListJson(registry: RegistryIndex, entities: Map<String, EntityState>): String`

- [ ] **Step 1: Update `EntityModelsTest.kt` — assert all entities are captured**

Add this test method inside `EntityModelsTest` (before the closing brace):

```kotlin
    @Test
    fun capturesEveryRegistryEntityForThePicker() {
        val reg = parseEntityRegistry(json(
            """[
              {"entity_id":"light.kitchen","labels":["echo-lights"],"name":null,"original_name":"Kitchen"},
              {"entity_id":"switch.fan","labels":[],"name":"Desk Fan","original_name":"Fan"},
              {"entity_id":"climate.hall","labels":[],"name":null,"original_name":null}
            ]"""
        ))
        // allEntities includes entities with NO echo labels (the picker needs the full list)
        assertEquals(listOf("light.kitchen", "switch.fan", "climate.hall"), reg.allEntities.map { it.id })
        assertEquals(listOf("light", "switch", "climate"), reg.allEntities.map { it.domain })
        assertEquals("Desk Fan", reg.allEntities[1].name)
        // registryNames now covers unlabeled entities too
        assertEquals("Desk Fan", reg.registryNames["switch.fan"])
        // labelToEntities stays echo-only (seeding contract unchanged)
        assertEquals(listOf("light.kitchen"), reg.labelToEntities["echo-lights"])
        assertEquals(null, reg.labelToEntities["other"])
    }
```

- [ ] **Step 2: Write the `EntityHubTest` config-driven tests**

Replace the whole `EntityHubTest.kt` file with:

```kotlin
package com.rar.echodash.ha

import com.rar.echodash.config.DashConfig
import com.rar.echodash.config.Entities
import com.rar.echodash.config.LightGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val hub = EntityHub(fake, this, config()) { 0L }
        hub.callService("homeassistant", "toggle", entityId = "light.kitchen")
        runCurrent()
        val (type, fields) = fake.requests.first { it.first == "call_service" }
        assertEquals("homeassistant", fields["domain"]!!.jsonPrimitive.contentOrNull)
        assertEquals("toggle", fields["service"]!!.jsonPrimitive.contentOrNull)
    }
}
```

- [ ] **Step 3: Write the `EntityListTest.kt`**

`app/src/test/java/com/rar/echodash/web/EntityListTest.kt`:

```kotlin
package com.rar.echodash.web

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityListTest {
    @Test
    fun rendersIdNameDomainAndStateForEveryEntity() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[
              {"entity_id":"light.kitchen","labels":[],"name":"Kitchen","original_name":"Kitchen"},
              {"entity_id":"climate.hall","labels":[],"name":null,"original_name":null}
            ]"""
        ))
        val states = mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on",
                Json.parseToJsonElement("{}") as JsonObject, 0L),
        )
        val arr = Json.parseToJsonElement(buildEntityListJson(reg, states)) as JsonArray
        assertEquals(2, arr.size)
        val kitchen = arr[0].jsonObject
        assertEquals("light.kitchen", kitchen["id"]!!.jsonPrimitive.content)
        assertEquals("Kitchen", kitchen["name"]!!.jsonPrimitive.content)
        assertEquals("light", kitchen["domain"]!!.jsonPrimitive.content)
        assertEquals("on", kitchen["state"]!!.jsonPrimitive.content)
        // climate.hall has no live state and no registry name -> falls back to id + "unavailable"
        val hall = arr[1].jsonObject
        assertEquals("climate.hall", hall["name"]!!.jsonPrimitive.content)
        assertEquals("unavailable", hall["state"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ha.EntityModelsTest' --tests 'com.rar.echodash.ha.EntityHubTest' --tests 'com.rar.echodash.web.EntityListTest'`
Expected: FAIL — `allEntities`/`RegistryEntity`/`buildEntityListJson` unresolved; `EntityHub` constructor arity mismatch.

- [ ] **Step 5: Update `ha/EntityModels.kt`**

Replace the whole file with:

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

/** One registry entity for the web picker: id, display name (nullable), and domain. */
data class RegistryEntity(val id: String, val name: String?, val domain: String)

/**
 * Registry index. [labelToEntities] keeps echo-* labels for one-time config seeding; [registryNames]
 * and [allEntities] cover EVERY registry entity so the web picker can list and name them.
 */
data class RegistryIndex(
    val labelToEntities: Map<String, List<String>>,
    val registryNames: Map<String, String>,
    val allEntities: List<RegistryEntity> = emptyList(),
) {
    /** Every entity referenced by any echo-* label, first-seen order, de-duplicated (seeding only). */
    val allEntityIds: List<String>
        get() = labelToEntities.values.flatten().distinct()
}

/** Display name: registry name/original_name, else live friendly_name, else the entity id. */
fun RegistryIndex.displayName(entityId: String, state: EntityState?): String =
    registryNames[entityId] ?: state?.attr("friendly_name") ?: entityId

/** Build the index from a config/entity_registry/list result array. */
fun parseEntityRegistry(result: JsonElement): RegistryIndex {
    val labelToEntities = LinkedHashMap<String, MutableList<String>>()
    val names = LinkedHashMap<String, String>()
    val all = ArrayList<RegistryEntity>()
    for (el in result.jsonArray) {
        val obj = el.jsonObject
        val id = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: continue
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (obj["original_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (name != null) names[id] = name
        all += RegistryEntity(id = id, name = name, domain = id.substringBefore('.'))

        val labels = (obj["labels"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            ?.filter { it.startsWith("echo-") }
            .orEmpty()
        for (label in labels) labelToEntities.getOrPut(label) { mutableListOf() }.add(id)
    }
    return RegistryIndex(labelToEntities, names, all)
}
```

- [ ] **Step 6: Rewrite `ha/EntityHub.kt`**

Replace the whole file with:

```kotlin
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
```

- [ ] **Step 7: Create `web/EntityList.kt`**

```kotlin
package com.rar.echodash.web

import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Render the /api/entities payload: every registry entity as {id, name, domain, state}. State is the
 * last-known live state (or "unavailable" for entities not in the watched set). Pure — no Android.
 */
fun buildEntityListJson(registry: RegistryIndex, entities: Map<String, EntityState>): String =
    buildJsonArray {
        registry.allEntities.forEach { e ->
            add(buildJsonObject {
                put("id", e.id)
                put("name", registry.displayName(e.id, entities[e.id]))
                put("domain", e.domain)
                put("state", entities[e.id]?.state ?: "unavailable")
            })
        }
    }.toString()
```

- [ ] **Step 8: Update `App.kt` to pass the config flow to `EntityHub`**

In `app/src/main/java/com/rar/echodash/App.kt`, change the `entityHub` line to construct after `configStore` and pass the flow:

```kotlin
    val configStore = ConfigStore(appContext.filesDir)
    val entityHub = EntityHub(ws, scope, configStore.config)
```

(Delete the old `val entityHub = EntityHub(ws, scope)` line and the separate `configStore` line added in Task 3; the two lines above replace both, keeping `configStore` declared before `entityHub`.)

- [ ] **Step 9: Run the affected suites**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ha.*' --tests 'com.rar.echodash.web.EntityListTest'`
Expected: PASS (EntityModelsTest, EntityHubTest, EntityDeltaTest, WsParserTest, HaWebSocketTest, AuthManagerTest, EntityListTest).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ha/EntityModels.kt app/src/main/java/com/rar/echodash/ha/EntityHub.kt app/src/main/java/com/rar/echodash/web/EntityList.kt app/src/main/java/com/rar/echodash/App.kt app/src/test/java/com/rar/echodash/ha/EntityModelsTest.kt app/src/test/java/com/rar/echodash/ha/EntityHubTest.kt app/src/test/java/com/rar/echodash/web/EntityListTest.kt
git commit -m "feat: config-driven EntityHub watched set + full registry picker feed"
```

---

### Task 7: Auth — SessionManager + PIN generation (pure)

The authentication core, injectable-clock/random so it unit-tests deterministically. `SessionManager` checks the PIN, issues per-session tokens (valid until app restart), and locks out for 60 s after 5 consecutive wrong PINs. `generatePin` produces the 6-digit code.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/web/SessionManager.kt`
- Create: `app/src/main/java/com/rar/echodash/web/Pin.kt`
- Test: `app/src/test/java/com/rar/echodash/web/SessionManagerTest.kt`
- Test: `app/src/test/java/com/rar/echodash/web/PinTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed interface LoginResult { data class Ok(val token: String); data object Invalid; data class LockedOut(val retryAfterSeconds: Long) }`
  - `class SessionManager(clock: () -> Long, random: Random)` with `fun login(pin: String, correctPin: String): LoginResult` and `fun isValidSession(token: String?): Boolean`
  - `fun generatePin(random: Random): String` (6 digits, may have leading zeros)

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/rar/echodash/web/SessionManagerTest.kt`:

```kotlin
package com.rar.echodash.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionManagerTest {
    private var now = 1_000L
    private fun mgr() = SessionManager(clock = { now }, random = Random(7))

    @Test
    fun correctPinIssuesTokenThatValidates() {
        val m = mgr()
        val r = m.login("123456", "123456")
        assertTrue(r is LoginResult.Ok)
        val token = (r as LoginResult.Ok).token
        assertTrue(m.isValidSession(token))
        assertFalse(m.isValidSession("nope"))
        assertFalse(m.isValidSession(null))
    }

    @Test
    fun wrongPinReturnsInvalidWithoutLockoutUntilFifth() {
        val m = mgr()
        repeat(4) { assertEquals(LoginResult.Invalid, m.login("000000", "123456")) }
        // 5th consecutive failure triggers the lockout
        val fifth = m.login("000000", "123456")
        assertTrue(fifth is LoginResult.LockedOut)
        assertEquals(60L, (fifth as LoginResult.LockedOut).retryAfterSeconds)
    }

    @Test
    fun lockoutRejectsEvenCorrectPinUntilItExpires() {
        val m = mgr()
        repeat(5) { m.login("000000", "123456") } // now locked out at t=1000
        val duringLockout = m.login("123456", "123456")
        assertTrue(duringLockout is LoginResult.LockedOut)

        now += 60_000L + 1 // lockout window elapses
        val afterLockout = m.login("123456", "123456")
        assertTrue(afterLockout is LoginResult.Ok)
    }

    @Test
    fun successResetsTheFailureCounter() {
        val m = mgr()
        repeat(4) { m.login("000000", "123456") }
        assertTrue(m.login("123456", "123456") is LoginResult.Ok) // resets counter
        repeat(4) { assertEquals(LoginResult.Invalid, m.login("000000", "123456")) } // no lockout yet
    }
}
```

`app/src/test/java/com/rar/echodash/web/PinTest.kt`:

```kotlin
package com.rar.echodash.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PinTest {
    @Test
    fun sixDigitsWithLeadingZerosAllowed() {
        repeat(200) {
            val pin = generatePin(Random(it.toLong()))
            assertEquals(6, pin.length)
            assertTrue(pin.all { c -> c.isDigit() })
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.web.SessionManagerTest' --tests 'com.rar.echodash.web.PinTest'`
Expected: FAIL — `Unresolved reference: SessionManager` / `generatePin`.

- [ ] **Step 3: Create `web/SessionManager.kt`**

```kotlin
package com.rar.echodash.web

import kotlin.random.Random

/** Outcome of a login attempt. */
sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object Invalid : LoginResult
    data class LockedOut(val retryAfterSeconds: Long) : LoginResult
}

/**
 * PIN check + browser sessions for the config server. Tokens are valid until app restart (held in
 * memory). Five consecutive wrong PINs lock the login route for 60 s. Clock and RNG are injected so
 * the logic unit-tests deterministically; no Android APIs.
 */
class SessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val tokens = HashSet<String>()
    private var consecutiveFailures = 0
    private var lockoutUntilMs = 0L

    fun login(pin: String, correctPin: String): LoginResult {
        val now = clock()
        if (now < lockoutUntilMs) {
            return LoginResult.LockedOut(((lockoutUntilMs - now + 999) / 1000))
        }
        return if (pin == correctPin) {
            consecutiveFailures = 0
            val token = newToken()
            tokens += token
            LoginResult.Ok(token)
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= 5) {
                consecutiveFailures = 0
                lockoutUntilMs = now + 60_000L
                LoginResult.LockedOut(60L)
            } else {
                LoginResult.Invalid
            }
        }
    }

    fun isValidSession(token: String?): Boolean = token != null && token in tokens

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Create `web/Pin.kt`**

```kotlin
package com.rar.echodash.web

import kotlin.random.Random

/** A 6-digit PIN, zero-padded (so "000123" is valid). Generated once and persisted in app prefs. */
fun generatePin(random: Random = Random.Default): String =
    "%06d".format(random.nextInt(0, 1_000_000))
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.web.SessionManagerTest' --tests 'com.rar.echodash.web.PinTest'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/web/SessionManager.kt app/src/main/java/com/rar/echodash/web/Pin.kt app/src/test/java/com/rar/echodash/web/SessionManagerTest.kt app/src/test/java/com/rar/echodash/web/PinTest.kt
git commit -m "feat: SessionManager PIN auth with lockout, and PIN generation"
```

---

### Task 8: ConfigServer (NanoHTTPD) + network info + dependency

The embedded HTTP server. Adds the sole new dependency, implements all routes with cookie auth, serves assets through an injected reader (so JVM tests fake them), and is exercised end-to-end over OkHttp on an ephemeral port. `localIpAddress()` finds the LAN IP for the Configure menu.

**Files:**
- Modify: `app/build.gradle.kts` (add NanoHTTPD)
- Create: `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`
- Create: `app/src/main/java/com/rar/echodash/web/NetworkInfo.kt`
- Test: `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`

**Interfaces:**
- Consumes: `ConfigStore`/`DashConfig`/`ConfigJson`/`decodeConfig` (Tasks 1, 3); `SessionManager`/`LoginResult` (Task 7); `buildEntityListJson` (Task 6).
- Produces:
  - `class ConfigServer(port: Int = 8080, store: ConfigStore, sessions: SessionManager, pin: () -> String, entitiesJson: () -> String, assetReader: (String) -> ByteArray?) : NanoHTTPD(port)`
  - `fun localIpAddress(): String?`

- [ ] **Step 1: Add the NanoHTTPD dependency**

In `app/build.gradle.kts`, add this line in the `dependencies` block right after the media3 line:

```kotlin
    implementation("org.nanohttpd:nanohttpd:2.3.1")
```

- [ ] **Step 2: Write the failing end-to-end test**

`app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`:

```kotlin
package com.rar.echodash.web

import com.rar.echodash.config.ConfigStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ConfigServerTest {
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()
    private lateinit var server: ConfigServer
    private lateinit var store: ConfigStore
    private lateinit var base: String

    private fun tempDir(): File =
        File.createTempFile("cfgserver", "").let { it.delete(); it.mkdirs(); it }

    @Before
    fun setUp() {
        store = ConfigStore(tempDir())
        server = ConfigServer(
            port = 0,
            store = store,
            sessions = SessionManager(random = Random(1)),
            pin = { "123456" },
            entitiesJson = { """[{"id":"light.k","name":"K","domain":"light","state":"on"}]""" },
            assetReader = { path -> if (path == "index.html") "<html>ok</html>".toByteArray() else null },
        )
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() { server.stop() }

    private fun login(pin: String): okhttp3.Response =
        http.newCall(Request.Builder().url("$base/api/login")
            .post("""{"pin":"$pin"}""".toRequestBody(json)).build()).execute()

    private fun cookieFrom(resp: okhttp3.Response): String =
        resp.header("Set-Cookie")!!.substringBefore(";") // "session=<token>"

    @Test
    fun apiRequiresSessionCookie() {
        http.newCall(Request.Builder().url("$base/api/config").build()).execute().use { r ->
            assertEquals(401, r.code)
        }
    }

    @Test
    fun wrongPinReturns401() {
        login("000000").use { r -> assertEquals(401, r.code) }
    }

    @Test
    fun loginGetPutEntitiesRoundTrip() {
        val cookie = login("123456").use { r ->
            assertEquals(200, r.code)
            cookieFrom(r)
        }

        // GET config
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"version\":1"))
            }

        // PUT config (valid) -> 200 stored, persisted
        val putBody = """{"version":1,"home":{"photoFolder":"nas","photoCacheCap":9000}}"""
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie)
            .put(putBody.toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                val text = r.body!!.string()
                assertTrue(text.contains("\"photoFolder\":\"nas\""))
                assertTrue(text.contains("\"photoCacheCap\":500")) // clamped
            }
        assertEquals("nas", store.config.value.home.photoFolder)

        // GET entities
        http.newCall(Request.Builder().url("$base/api/entities").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"light.k\""))
            }
    }

    @Test
    fun putInvalidBodyReturns400AndLeavesConfigUntouched() {
        val cookie = cookieFrom(login("123456"))
        val before = store.config.value
        http.newCall(Request.Builder().url("$base/api/config").header("Cookie", cookie)
            .put("{ not valid json".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
                assertTrue(r.body!!.string().contains("\"error\""))
            }
        assertEquals(before, store.config.value) // untouched
    }

    @Test
    fun fiveWrongPinsLockOutWith429() {
        repeat(4) { login("000000").use { assertEquals(401, it.code) } }
        login("000000").use { assertEquals(429, it.code) }
        // even the correct pin is refused during lockout
        login("123456").use { assertEquals(429, it.code) }
    }

    @Test
    fun rootServesIndexAsset() {
        http.newCall(Request.Builder().url("$base/").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertEquals("<html>ok</html>", r.body!!.string())
        }
    }

    @Test
    fun missingAssetReturns404() {
        http.newCall(Request.Builder().url("$base/nope.js").build()).execute().use { r ->
            assertEquals(404, r.code)
        }
    }

    @Test
    fun localIpAddressIsNullOrIpv4() {
        val ip = localIpAddress()
        assertTrue(ip == null || ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
        assertNotNull(server) // touch server so the test is meaningful even if no LAN IP is present
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.web.ConfigServerTest'`
Expected: FAIL — `Unresolved reference: ConfigServer` (and NanoHTTPD import once the class is added).

- [ ] **Step 4: Create `web/ConfigServer.kt`**

```kotlin
package com.rar.echodash.web

import com.rar.echodash.config.ConfigJson
import com.rar.echodash.config.ConfigStore
import com.rar.echodash.config.DashConfig
import com.rar.echodash.config.decodeConfig
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Embedded LAN config server (NanoHTTPD). Serves the config page from [assetReader], a JSON API for
 * the DashConfig + entity picker, and PIN login. All /api/* routes except login require the session
 * cookie. Pure Java under the hood, so this runs in plain-JVM tests on an ephemeral port (port 0).
 */
class ConfigServer(
    port: Int = 8080,
    private val store: ConfigStore,
    private val sessions: SessionManager,
    private val pin: () -> String,
    private val entitiesJson: () -> String,
    private val assetReader: (String) -> ByteArray?,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response =
        try {
            route(session)
        } catch (e: Exception) {
            android.util.Log.w("ConfigServer", "serve failed", e)
            error(Response.Status.INTERNAL_ERROR, "server error")
        }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri == "/api/login" && method == Method.POST) return handleLogin(session)

        if (uri.startsWith("/api/")) {
            if (!authed(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized")
            return when {
                uri == "/api/config" && method == Method.GET ->
                    ok(ConfigJson.json.encodeToString(DashConfig.serializer(), store.config.value))
                uri == "/api/config" && method == Method.PUT -> handlePutConfig(session)
                uri == "/api/entities" && method == Method.GET -> ok(entitiesJson())
                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        }

        if (method == Method.GET) {
            val path = if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')
            return asset(path)
        }
        return error(Response.Status.NOT_FOUND, "not found")
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val pinInput = runCatching {
            (ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject)["pin"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: ""
        return when (val r = sessions.login(pinInput, pin())) {
            is LoginResult.Ok -> ok("""{"ok":true}""").apply {
                addHeader("Set-Cookie", "session=${r.token}; Path=/; HttpOnly")
            }
            LoginResult.Invalid -> error(Response.Status.UNAUTHORIZED, "invalid pin")
            is LoginResult.LockedOut -> json(STATUS_429, buildJsonObject {
                put("error", "locked out"); put("retryAfter", r.retryAfterSeconds)
            }.toString())
        }
    }

    private fun handlePutConfig(session: IHTTPSession): Response {
        val body = readBody(session)
        val parsed = runCatching { decodeConfig(body) }.getOrElse {
            return error(Response.Status.BAD_REQUEST, "invalid config: ${it.message ?: "malformed"}")
        }
        val stored = store.update(parsed)
        return ok(ConfigJson.json.encodeToString(DashConfig.serializer(), stored))
    }

    private fun authed(session: IHTTPSession): Boolean = sessions.isValidSession(sessionToken(session))

    private fun sessionToken(session: IHTTPSession): String? =
        session.headers["cookie"]?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("session=") }?.substringAfter("session=")

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        files["postData"]?.let { return it }
        // NanoHTTPD quirk: PUT bodies are saved to a temp file whose PATH is stored under "content"
        return files["content"]?.let { f -> runCatching { java.io.File(f).readText() }.getOrNull() } ?: ""
    }

    private fun asset(path: String): Response {
        val bytes = assetReader(path) ?: return error(Response.Status.NOT_FOUND, "not found")
        return newFixedLengthResponse(Response.Status.OK, mimeOf(path), ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun ok(body: String): Response = json(Response.Status.OK, body)

    private fun error(status: Response.IStatus, reason: String): Response =
        json(status, buildJsonObject { put("error", reason) }.toString())

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        else -> "application/octet-stream"
    }

    companion object {
        private val STATUS_429 = object : Response.IStatus {
            override fun getRequestStatus(): Int = 429
            override fun getDescription(): String = "429 Too Many Requests"
        }
    }
}
```

- [ ] **Step 5: Create `web/NetworkInfo.kt`**

```kotlin
package com.rar.echodash.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** First site-local IPv4 address of an up, non-loopback interface (for the Configure URL). */
fun localIpAddress(): String? =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.web.ConfigServerTest'`
Expected: PASS (8 tests).

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/rar/echodash/web/ConfigServer.kt app/src/main/java/com/rar/echodash/web/NetworkInfo.kt app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt
git commit -m "feat: ConfigServer HTTP API over NanoHTTPD with cookie auth"
```

---

### Task 9: Config web page assets (vanilla JS, untested)

The self-contained config page under `app/src/main/assets/config/`. No framework, no build step, no external resources — `index.html` references sibling `style.css` and `app.js` served by the same server (same-origin). The JS is intentionally thin: it renders controls, mutates an in-memory config object, and PUTs it. **All validation is server-side** (ConfigStore clamping). This task ships no unit tests (JS is verified manually in a browser); it is proven only by `assembleDebug` packaging the assets.

**Files:**
- Create: `app/src/main/assets/config/index.html`
- Create: `app/src/main/assets/config/style.css`
- Create: `app/src/main/assets/config/app.js`

**Interfaces:** none (static assets; served by `ConfigServer.assetReader` wired in Task 11).

- [ ] **Step 1: Create `app/src/main/assets/config/index.html`**

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Echo Dashboard Config</title>
  <link rel="stylesheet" href="style.css">
  <script defer src="app.js"></script>
</head>
<body>
  <div id="login" class="overlay">
    <form id="login-form" class="card">
      <h1>Echo Dashboard</h1>
      <p>Enter the PIN shown on the device.</p>
      <input id="pin" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="123456">
      <button type="submit">Unlock</button>
      <div id="login-error" class="error"></div>
    </form>
  </div>

  <main id="app" hidden>
    <header>
      <h1>Echo Dashboard Config</h1>
      <div class="actions">
        <span id="status" class="status"></span>
        <button id="save">Save</button>
      </div>
    </header>

    <section id="panels-section">
      <h2>Panels</h2>
      <div id="panels"></div>
    </section>

    <section id="entities-section">
      <h2>Entities</h2>
      <div id="entities"></div>
    </section>

    <section id="home-section">
      <h2>Home screen</h2>
      <div id="home"></div>
    </section>

    <section id="options-section">
      <h2>Panel options</h2>
      <div id="options"></div>
    </section>
  </main>
</body>
</html>
```

- [ ] **Step 2: Create `app/src/main/assets/config/style.css`**

```css
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
  background: #0f1420;
  color: #e7ecf3;
}
h1 { font-size: 1.4rem; margin: 0; }
h2 { font-size: 1.1rem; margin: 0 0 .6rem; color: #9fb4d6; }
button {
  background: #3a6ea5; color: #fff; border: 0; border-radius: 8px;
  padding: .5rem .9rem; font-size: .95rem; cursor: pointer;
}
button.ghost { background: #232b3a; }
button.small { padding: .25rem .5rem; font-size: .8rem; }
input, select {
  background: #171d2b; color: #e7ecf3; border: 1px solid #2a3346;
  border-radius: 6px; padding: .4rem .5rem; font-size: .9rem; min-width: 8rem;
}
.overlay {
  position: fixed; inset: 0; display: flex; align-items: center; justify-content: center;
  background: #0b0f18;
}
.card {
  background: #151b28; padding: 1.5rem; border-radius: 12px; width: 20rem;
  display: flex; flex-direction: column; gap: .8rem; text-align: center;
}
.card input { text-align: center; letter-spacing: .3em; font-size: 1.4rem; }
main { max-width: 46rem; margin: 0 auto; padding: 1rem; }
header {
  display: flex; align-items: center; justify-content: space-between;
  position: sticky; top: 0; background: #0f1420; padding: .8rem 0; z-index: 5;
}
.actions { display: flex; align-items: center; gap: .8rem; }
section { background: #131826; border-radius: 12px; padding: 1rem; margin-bottom: 1rem; }
.row { display: flex; align-items: center; gap: .6rem; flex-wrap: wrap; margin: .35rem 0; }
.row label { min-width: 9rem; color: #b9c6dd; }
.group { border: 1px solid #232b3a; border-radius: 8px; padding: .7rem; margin: .6rem 0; }
.group-head { display: flex; align-items: center; gap: .5rem; margin-bottom: .5rem; }
.picker { display: inline-flex; }
.status { font-size: .9rem; }
.status.ok { color: #6fcf97; }
.status.err { color: #ff8a80; }
.error { color: #ff8a80; min-height: 1.2rem; font-size: .85rem; }
.muted { color: #7f8da6; font-size: .8rem; }
```

- [ ] **Step 3: Create `app/src/main/assets/config/app.js`**

```javascript
"use strict";

let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
let dlSeq = 0;

const PANEL_KEYS = ["lights", "climate", "media", "weather", "solar"];
const PANEL_LABELS = {
  lights: "Lights", climate: "Climate", media: "Media", weather: "Weather", solar: "Solar",
};

// ---------- tiny DOM helpers ----------
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}
function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
function setStatus(msg, ok) {
  const s = document.getElementById("status");
  s.textContent = msg;
  s.className = "status " + (ok ? "ok" : "err");
}

// ---------- auth + data ----------
async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  return fetch(path, opts);
}

async function tryLoad() {
  const r = await api("GET", "/api/config");
  if (r.status === 401) { showLogin(); return; }
  config = await r.json();
  const er = await api("GET", "/api/entities");
  entities = er.ok ? await er.json() : [];
  showApp();
  render();
}

function showLogin() {
  document.getElementById("login").hidden = false;
  document.getElementById("app").hidden = true;
}
function showApp() {
  document.getElementById("login").hidden = true;
  document.getElementById("app").hidden = false;
}

async function doLogin(ev) {
  ev.preventDefault();
  const pin = document.getElementById("pin").value.trim();
  const errBox = document.getElementById("login-error");
  errBox.textContent = "";
  const r = await api("POST", "/api/login", { pin });
  if (r.ok) { await tryLoad(); return; }
  if (r.status === 429) {
    const b = await r.json().catch(() => ({}));
    errBox.textContent = "Too many attempts. Try again in " + (b.retryAfter || 60) + "s.";
  } else {
    errBox.textContent = "Wrong PIN.";
  }
}

async function save() {
  setStatus("Saving…", true);
  const r = await api("PUT", "/api/config", config);
  if (r.ok) {
    config = await r.json();  // adopt the server's clamped copy
    render();
    setStatus("Saved.", true);
  } else if (r.status === 401) {
    showLogin();
  } else {
    const b = await r.json().catch(() => ({}));
    setStatus("Error: " + (b.error || r.status), false);
  }
}

// ---------- reusable controls ----------
function entityPicker(domains, value, onChange) {
  const wrap = el("span", "picker");
  const input = el("input");
  const listId = "dl-" + (++dlSeq);
  input.setAttribute("list", listId);
  input.value = value || "";
  const dl = el("datalist");
  dl.id = listId;
  entities.filter(e => domains.includes(e.domain))
    .sort((a, b) => a.name.localeCompare(b.name))
    .forEach(e => {
      const o = el("option");
      o.value = e.id;
      o.textContent = e.name + " (" + e.id + ")";
      dl.appendChild(o);
    });
  input.addEventListener("change", () => onChange(input.value.trim() || null));
  wrap.appendChild(input);
  wrap.appendChild(dl);
  return wrap;
}

function labeledRow(labelText, control) {
  const row = el("div", "row");
  row.appendChild(el("label", null, labelText));
  row.appendChild(control);
  return row;
}

// ---------- render ----------
function render() {
  renderPanels();
  renderEntities();
  renderHome();
  renderOptions();
}

function renderPanels() {
  const host = document.getElementById("panels");
  clear(host);
  const ordered = PANEL_KEYS.slice().sort((a, b) => config.panels[a].order - config.panels[b].order);
  ordered.forEach((key, idx) => {
    const p = config.panels[key];
    const row = el("div", "row");
    const cb = el("input"); cb.type = "checkbox"; cb.checked = p.enabled;
    cb.addEventListener("change", () => { p.enabled = cb.checked; });
    row.appendChild(cb);
    row.appendChild(el("label", null, PANEL_LABELS[key]));

    const up = el("button", "ghost small", "↑");
    up.disabled = idx === 0;
    up.addEventListener("click", () => { swapOrder(ordered, idx, idx - 1); renderPanels(); });
    const down = el("button", "ghost small", "↓");
    down.disabled = idx === ordered.length - 1;
    down.addEventListener("click", () => { swapOrder(ordered, idx, idx + 1); renderPanels(); });
    row.appendChild(up); row.appendChild(down);
    host.appendChild(row);
  });
  host.appendChild(el("div", "muted", "Home is always first and cannot be moved or hidden."));
}

function swapOrder(ordered, i, j) {
  const a = config.panels[ordered[i]], b = config.panels[ordered[j]];
  const t = a.order; a.order = b.order; b.order = t;
}

function renderEntities() {
  const host = document.getElementById("entities");
  clear(host);
  const e = config.entities;

  host.appendChild(labeledRow("Temperature sensor",
    entityPicker(["sensor"], e.tempSensor, v => e.tempSensor = v)));
  host.appendChild(labeledRow("Weather",
    entityPicker(["weather"], e.weather, v => e.weather = v)));

  // climate list
  host.appendChild(el("h3", null, "Thermostats"));
  e.climate.forEach((id, i) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["climate"], id, v => { if (v) e.climate[i] = v; else e.climate.splice(i, 1); renderEntities(); }));
    const del = el("button", "ghost small", "Remove");
    del.addEventListener("click", () => { e.climate.splice(i, 1); renderEntities(); });
    row.appendChild(del);
    host.appendChild(row);
  });
  const addClimate = el("button", "ghost small", "Add thermostat");
  addClimate.addEventListener("click", () => { e.climate.push(""); renderEntities(); });
  host.appendChild(addClimate);

  // solar slots
  host.appendChild(el("h3", null, "Solar"));
  const solarSlots = [["pv", "PV power"], ["load", "Home load"], ["grid", "Grid power"],
    ["pvToday", "PV today"], ["loadToday", "Load today"]];
  solarSlots.forEach(([k, lbl]) => {
    host.appendChild(labeledRow(lbl, entityPicker(["sensor"], e.solar[k], v => e.solar[k] = v)));
  });

  // light groups
  host.appendChild(el("h3", null, "Light groups"));
  e.lightGroups.forEach((g, gi) => host.appendChild(renderLightGroup(g, gi)));
  const addGroup = el("button", "ghost small", "Add group");
  addGroup.addEventListener("click", () => { e.lightGroups.push({ name: "New group", entities: [] }); renderEntities(); });
  host.appendChild(addGroup);
}

function renderLightGroup(g, gi) {
  const groups = config.entities.lightGroups;
  const box = el("div", "group");
  const head = el("div", "group-head");
  const name = el("input"); name.value = g.name;
  name.addEventListener("change", () => g.name = name.value.trim() || "Group");
  head.appendChild(name);
  const up = el("button", "ghost small", "↑");
  up.disabled = gi === 0;
  up.addEventListener("click", () => { const t = groups[gi]; groups[gi] = groups[gi - 1]; groups[gi - 1] = t; renderEntities(); });
  const down = el("button", "ghost small", "↓");
  down.disabled = gi === groups.length - 1;
  down.addEventListener("click", () => { const t = groups[gi]; groups[gi] = groups[gi + 1]; groups[gi + 1] = t; renderEntities(); });
  const del = el("button", "ghost small", "Delete");
  del.addEventListener("click", () => { groups.splice(gi, 1); renderEntities(); });
  head.appendChild(up); head.appendChild(down); head.appendChild(del);
  box.appendChild(head);

  g.entities.forEach((id, ei) => {
    const row = el("div", "row");
    row.appendChild(entityPicker(["light", "switch", "fan"], id, v => { if (v) g.entities[ei] = v; else g.entities.splice(ei, 1); renderEntities(); }));
    const eup = el("button", "ghost small", "↑");
    eup.disabled = ei === 0;
    eup.addEventListener("click", () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei - 1]; g.entities[ei - 1] = t; renderEntities(); });
    const edown = el("button", "ghost small", "↓");
    edown.disabled = ei === g.entities.length - 1;
    edown.addEventListener("click", () => { const t = g.entities[ei]; g.entities[ei] = g.entities[ei + 1]; g.entities[ei + 1] = t; renderEntities(); });
    const erm = el("button", "ghost small", "Remove");
    erm.addEventListener("click", () => { g.entities.splice(ei, 1); renderEntities(); });
    row.appendChild(eup); row.appendChild(edown); row.appendChild(erm);
    box.appendChild(row);
  });
  const addEnt = el("button", "ghost small", "Add entity");
  addEnt.addEventListener("click", () => { g.entities.push(""); renderEntities(); });
  box.appendChild(addEnt);
  return box;
}

function numberInput(value, onChange) {
  const n = el("input"); n.type = "number"; n.value = value;
  n.addEventListener("change", () => onChange(parseFloat(n.value)));
  return n;
}

function renderHome() {
  const host = document.getElementById("home");
  clear(host);
  const h = config.home;
  host.appendChild(labeledRow("Idle return (s)", numberInput(h.idleReturnSeconds, v => h.idleReturnSeconds = Math.round(v))));
  const clock = el("select");
  ["AUTO", "H12", "H24"].forEach(opt => { const o = el("option", null, opt); o.value = opt; if (h.clockFormat === opt) o.selected = true; clock.appendChild(o); });
  clock.addEventListener("change", () => h.clockFormat = clock.value);
  host.appendChild(labeledRow("Clock format", clock));
  const slide = el("input"); slide.type = "checkbox"; slide.checked = h.slideshowEnabled;
  slide.addEventListener("change", () => h.slideshowEnabled = slide.checked);
  host.appendChild(labeledRow("Photo slideshow", slide));
  const folder = el("input"); folder.value = h.photoFolder;
  folder.addEventListener("change", () => h.photoFolder = folder.value.trim());
  host.appendChild(labeledRow("Photo folder", folder));
  host.appendChild(labeledRow("Photo cache cap", numberInput(h.photoCacheCap, v => h.photoCacheCap = Math.round(v))));
  host.appendChild(el("div", "muted", "Idle 15–3600 s, cap 5–500 (clamped on save)."));
}

function renderOptions() {
  const host = document.getElementById("options");
  clear(host);
  const o = config.panelOptions;
  host.appendChild(labeledRow("Thermostat step", numberInput(o.thermostatStep, v => o.thermostatStep = v)));
  host.appendChild(labeledRow("Forecast days", numberInput(o.forecastDays, v => o.forecastDays = Math.round(v))));
  host.appendChild(el("div", "muted", "Step 0.1–5.0, forecast 1–5 (clamped on save)."));
}

// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("save").addEventListener("click", save);
tryLoad();
```

- [ ] **Step 4: Verify the assets package into the APK**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (The page is verified manually in a browser during the on-device check; there is no unit test for JS.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/style.css app/src/main/assets/config/app.js
git commit -m "feat: self-contained web config page (vanilla JS)"
```

---

### Task 10: Shell + panels driven by config (models + Compose)

Cuts every panel over from `echo-*` labels to `DashConfig`: light sections from explicit groups, thermostats from the climate id list (with `thermostatStep`), the weather pill from the configured sensor/weather ids, the solar flow from the solar id slots, the rail order/enable from `panels`, the clock from `clockFormat`, forecast length from `forecastDays`, and the idle-return timeout from `idleReturnSeconds`. Model functions are pure and unit-tested; composables are compile-gated. This is one commit so signatures and their callers change together and the branch stays green.

**Files:**
- Modify: `ui/model/LightsModel.kt`, `ui/model/ClimateModel.kt`, `ui/model/WeatherModel.kt`, `ui/model/SolarModel.kt`
- Modify: `ui/DashViews.kt`, `ui/IconRail.kt`, `ui/DashboardShell.kt`, `ui/HomeView.kt`
- Modify: `ui/panels/WeatherPanel.kt`, `ui/panels/LightsPanel.kt`, `ui/panels/ClimatePanel.kt`
- Modify: `App.kt`
- Modify tests: `LightsModelTest.kt`, `ClimateModelTest.kt`, `WeatherModelTest.kt`, `SolarModelTest.kt`
- Create test: `ui/DashViewsTest.kt`

**Interfaces:**
- Consumes: `DashConfig`/`Panels`/`SolarConfig`/`ClockFormat`/`LightGroup` (Task 1); `RegistryIndex`/`EntityState`/`displayName` (existing).
- Produces (new/changed signatures):
  - `fun lightSections(groups: List<com.rar.echodash.config.LightGroup>, registry: RegistryIndex, entities: Map<String, EntityState>): List<LightGroup>`
  - `fun thermostats(ids: List<String>, registry: RegistryIndex, entities: Map<String, EntityState>, step: Double = SETPOINT_STEP): List<ThermostatState>`
  - `fun weatherPill(tempSensorId: String?, weatherId: String?, entities: Map<String, EntityState>, nowMs: Long): WeatherPill?`
  - `fun solarFlow(cfg: com.rar.echodash.config.SolarConfig, entities: Map<String, EntityState>): SolarFlow`
  - `fun railViews(panels: Panels): List<DashView>` — `HOME` first, then enabled panels by `order`.
  - `fun clockPattern(format: ClockFormat, systemIs24: Boolean): String`
  - `IconRail(current, views: List<DashView>, onSelect, modifier)`; `DashboardShell(... config: DashConfig ...)`; `HomeView(... clockFormat: ClockFormat ...)`; `WeatherPanel(weather, weatherEntityId, forecastDays, fetchForecast)`.

- [ ] **Step 1: Rewrite the model tests (they define the new signatures)**

Replace `app/src/test/java/com/rar/echodash/ui/model/LightsModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.LightGroup
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.parseEntityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LightsModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, a: String = "{}") = EntityState(id, state, attrs(a), 0L)

    private val reg = parseEntityRegistry(Json.parseToJsonElement(
        """[
          {"entity_id":"light.hall","labels":[],"original_name":"Hall"},
          {"entity_id":"light.sofa","labels":[],"original_name":"Sofa"},
          {"entity_id":"switch.fan","labels":[],"original_name":"Fan"}
        ]"""
    ))

    @Test
    fun sectionsPreserveConfiguredOrderNamesAndTileState() {
        val groups = listOf(
            LightGroup("Lights", listOf("light.hall")),
            LightGroup("Living Room", listOf("light.sofa", "switch.fan")),
        )
        val entities = mapOf(
            "light.hall" to st("light.hall", "on"),
            "light.sofa" to st("light.sofa", "off"),
            "switch.fan" to st("switch.fan", "unavailable"),
        )
        val sections = lightSections(groups, reg, entities)
        assertEquals(listOf("Lights", "Living Room"), sections.map { it.title })
        assertEquals("Hall", sections[0].tiles[0].name)
        assertEquals(true, sections[0].tiles[0].on)
        assertEquals(false, sections[1].tiles[0].on)          // sofa off
        assertEquals(false, sections[1].tiles[1].available)   // fan unavailable
        assertEquals("switch", sections[1].tiles[1].domain)
    }

    @Test
    fun missingEntityStillProducesTileByIdUnavailable() {
        val sections = lightSections(listOf(LightGroup("G", listOf("light.ghost"))), reg, emptyMap())
        assertEquals("light.ghost", sections[0].tiles[0].name)
        assertEquals(false, sections[0].tiles[0].available)
    }
}
```

Replace `app/src/test/java/com/rar/echodash/ui/model/ClimateModelTest.kt`:

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
    fun derivesThermostatFromAttributesWithConfiguredStep() {
        val reg = parseEntityRegistry(Json.parseToJsonElement(
            """[{"entity_id":"climate.hall","labels":[],"original_name":"Hall"}]"""
        ))
        val entities = mapOf("climate.hall" to EntityState("climate.hall", "heat",
            attrs("""{"current_temperature":19.5,"temperature":21.0,"min_temp":7.0,"max_temp":30.0,
                      "hvac_action":"heating","hvac_modes":["off","heat","cool"]}"""), 0L))
        // a non-climate id in the list is ignored
        val t = thermostats(listOf("climate.hall", "sensor.notclimate"), reg, entities, step = 1.0).single()
        assertEquals("Hall", t.name)
        assertEquals(19.5, t.current!!, 0.001)
        assertEquals(21.0, t.target!!, 0.001)
        assertEquals(1.0, t.step, 0.001)          // from config
        assertEquals(listOf("off", "heat", "cool"), t.hvacModes)
        assertEquals(true, t.available)
    }

    @Test
    fun debouncerAccumulatesTapsIntoOneClampedCommit() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 20.0, min = 7.0, max = 22.0)
        repeat(5) { d.nudge(+1) }
        assertEquals(22.0, d.displayTarget(), 0.001)
        assertEquals(0, commits.size)
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(22.0), commits)
        d.cancel()
    }

    @Test
    fun debouncerHonorsConfiguredStep() = runTest {
        val commits = mutableListOf<Double>()
        val d = SetpointDebouncer(this, debounceMs = 800) { commits += it }
        d.reset(current = 20.0, min = 7.0, max = 30.0, step = 1.0)
        d.nudge(+1); d.nudge(+1)                  // 20 -> 21 -> 22 with step 1.0
        advanceTimeBy(801); runCurrent()
        assertEquals(listOf(22.0), commits)
        d.cancel()
    }
}
```

Replace `app/src/test/java/com/rar/echodash/ui/model/WeatherModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun pillPrefersTempSensor() {
        val entities = mapOf(
            "sensor.temp" to EntityState("sensor.temp", "14.1", attrs("""{"unit_of_measurement":"°C"}"""), 1_000L),
            "weather.home" to EntityState("weather.home", "rainy", attrs("""{"temperature":9.0}"""), 1_000L),
        )
        val pill = weatherPill("sensor.temp", "weather.home", entities, nowMs = 1_500L)!!
        assertEquals("14.1 °C", pill.temperature)
        assertEquals(WeatherIcon.RAIN, pill.icon)
        assertEquals("rainy", pill.conditionText)
        assertEquals(false, pill.stale)
    }

    @Test
    fun pillFallsBackToWeatherAttributeThenHides() {
        val onlyWeather = mapOf("weather.home" to EntityState("weather.home", "sunny",
            attrs("""{"temperature":24.0,"temperature_unit":"°C"}"""), 0L))
        val pill = weatherPill(null, "weather.home", onlyWeather, nowMs = 0L)!!
        assertEquals("24.0 °C", pill.temperature)
        assertEquals(WeatherIcon.SUNNY, pill.icon)
        assertNull(weatherPill(null, null, emptyMap(), nowMs = 0L))
    }

    @Test
    fun pillDimsWhenTempSensorStale() {
        val entities = mapOf("sensor.temp" to EntityState("sensor.temp", "10.0",
            attrs("""{"unit_of_measurement":"°C"}"""), lastUpdatedMs = 0L))
        val pill = weatherPill("sensor.temp", null, entities, nowMs = STALE_AFTER_MS + 1)!!
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
        assertEquals("Mon", days[0].dayOfWeek)
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

Replace `app/src/test/java/com/rar/echodash/ui/model/SolarModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.SolarConfig
import com.rar.echodash.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarModelTest {
    private fun attrs(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun st(id: String, state: String, unit: String) =
        EntityState(id, state, attrs("""{"unit_of_measurement":"$unit"}"""), 0L)

    @Test
    fun formatsWattsAndKwAndGridSignAndToday() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", grid = "sensor.grid",
            pvToday = "sensor.pvday", loadToday = "sensor.loadday")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "3500", "W"),
            "sensor.load" to st("sensor.load", "800", "W"),
            "sensor.grid" to st("sensor.grid", "-1200", "W"),
            "sensor.pvday" to st("sensor.pvday", "12.4", "kWh"),
            "sensor.loadday" to st("sensor.loadday", "9.1", "kWh"),
        )
        val flow = solarFlow(cfg, entities)
        assertEquals("3.5 kW", flow.pv!!.watts)
        assertEquals("800 W", flow.home!!.watts)
        assertEquals("1.2 kW", flow.grid!!.watts)
        assertEquals(false, flow.gridImporting)
        assertEquals("Today: 12.4 kWh produced · 9.1 kWh used", flow.todayLine)
    }

    @Test
    fun noGridSensorGivesTwoNodeFlowAndPartialToday() {
        val cfg = SolarConfig(pv = "sensor.pv", load = "sensor.load", pvToday = "sensor.pvday")
        val entities = mapOf(
            "sensor.pv" to st("sensor.pv", "1000", "W"),
            "sensor.load" to st("sensor.load", "1500", "W"),
            "sensor.pvday" to st("sensor.pvday", "5.0", "kWh"),
        )
        val flow = solarFlow(cfg, entities)
        assertNull(flow.grid)
        assertNull(flow.gridImporting)
        assertEquals("1.0 kW", flow.pv!!.watts)
        assertEquals("Today: 5.0 kWh produced", flow.todayLine)
    }
}
```

Create `app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt`:

```kotlin
package com.rar.echodash.ui

import com.rar.echodash.config.ClockFormat
import com.rar.echodash.config.PanelConfig
import com.rar.echodash.config.Panels
import org.junit.Assert.assertEquals
import org.junit.Test

class DashViewsTest {
    @Test
    fun railViewsPutHomeFirstThenEnabledPanelsByOrder() {
        val panels = Panels(
            lights = PanelConfig(true, 2),
            climate = PanelConfig(false, 1),   // disabled -> excluded
            media = PanelConfig(true, 3),
            weather = PanelConfig(true, 5),
            solar = PanelConfig(true, 4),
        )
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.MEDIA, DashView.SOLAR, DashView.WEATHER),
            railViews(panels),
        )
    }

    @Test
    fun clockPatternHonorsFormatThenSystem() {
        assertEquals("HH:mm", clockPattern(ClockFormat.H24, systemIs24 = false))
        assertEquals("h:mm a", clockPattern(ClockFormat.H12, systemIs24 = true))
        assertEquals("HH:mm", clockPattern(ClockFormat.AUTO, systemIs24 = true))
        assertEquals("h:mm a", clockPattern(ClockFormat.AUTO, systemIs24 = false))
    }
}
```

- [ ] **Step 2: Run the model/dashviews tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.*' --tests 'com.rar.echodash.ui.DashViewsTest'`
Expected: FAIL — `lightSections`/`thermostats`/`solarFlow`/`railViews`/`clockPattern` unresolved; `weatherPill` arity mismatch.

- [ ] **Step 3: Rewrite `ui/model/LightsModel.kt`**

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.LightGroup as ConfigLightGroup
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ha.displayName

data class LightTile(
    val entityId: String,
    val name: String,
    val domain: String,
    val on: Boolean,
    val available: Boolean,
)

/** A section of light tiles; [title] is the configured group name. */
data class LightGroup(val title: String?, val tiles: List<LightTile>)

/** Build display sections from explicit configured groups, in configured order. */
fun lightSections(
    groups: List<ConfigLightGroup>,
    registry: RegistryIndex,
    entities: Map<String, EntityState>,
): List<LightGroup> =
    groups.map { g ->
        LightGroup(
            title = g.name,
            tiles = g.entities.map { id ->
                val state = entities[id]
                val s = state?.state
                LightTile(
                    entityId = id,
                    name = registry.displayName(id, state),
                    domain = id.substringBefore('.'),
                    on = s == "on",
                    available = s != null && s != "unavailable" && s != "unknown",
                )
            },
        )
    }
```

- [ ] **Step 4: Rewrite `ui/model/ClimateModel.kt`**

Replace the `CLIMATE_LABEL` constant and `thermostatStates` function (keep `ThermostatState`, `SETPOINT_STEP`, `SetpointDebouncer` exactly). The top of the file through the function becomes:

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

const val SETPOINT_STEP = 0.5

/** Build thermostats from the configured climate id list; non-`climate.*` ids are ignored. */
fun thermostats(
    ids: List<String>,
    registry: RegistryIndex,
    entities: Map<String, EntityState>,
    step: Double = SETPOINT_STEP,
): List<ThermostatState> =
    ids.filter { it.startsWith("climate.") }.map { id ->
        val s = entities[id]
        ThermostatState(
            entityId = id,
            name = registry.displayName(id, s),
            current = s?.attrDouble("current_temperature"),
            target = s?.attrDouble("temperature"),
            minTemp = s?.attrDouble("min_temp") ?: 7.0,
            maxTemp = s?.attrDouble("max_temp") ?: 35.0,
            step = step,
            hvacAction = s?.attr("hvac_action"),
            hvacModes = s?.attrStringList("hvac_modes") ?: emptyList(),
            mode = s?.state ?: "unknown",
            available = s != null && s.state != "unavailable" && s.state != "unknown",
        )
    }
```

Leave the `SetpointDebouncer` class below unchanged.

- [ ] **Step 5: Rewrite the `weatherPill` function in `ui/model/WeatherModel.kt`**

Remove the `import com.rar.echodash.ha.RegistryIndex` line and replace the `weatherPill` function (keep everything else — `conditionIcon`, `WeatherPill`, `DailyForecast`, `parseForecasts`, `dayOfWeek`, `STALE_AFTER_MS`) with:

```kotlin
/** Pill temperature: configured temp sensor first, else weather entity's temperature attr, else hidden. */
fun weatherPill(
    tempSensorId: String?,
    weatherId: String?,
    entities: Map<String, EntityState>,
    nowMs: Long,
): WeatherPill? {
    val tempSensor = tempSensorId?.let { entities[it] }
    val weather = weatherId?.let { entities[it] }

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
```

- [ ] **Step 6: Rewrite the `buildSolarFlow` function in `ui/model/SolarModel.kt`**

Remove the `import com.rar.echodash.ha.RegistryIndex` line, add `import com.rar.echodash.config.SolarConfig`, and replace `buildSolarFlow` (keep `SolarNode`, `SolarFlow`, `formatWatts`) with:

```kotlin
fun solarFlow(cfg: SolarConfig, entities: Map<String, EntityState>): SolarFlow {
    fun get(id: String?): EntityState? = id?.let { entities[it] }

    val pv = get(cfg.pv)
    val load = get(cfg.load)
    val grid = get(cfg.grid)
    val pvToday = get(cfg.pvToday)
    val loadToday = get(cfg.loadToday)

    val todayLine = buildString {
        pvToday?.let { append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} produced") }
        loadToday?.let {
            if (isNotEmpty()) append(" · ")
            append("${it.state} ${it.attr("unit_of_measurement") ?: "kWh"} used")
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
```

- [ ] **Step 7: Add `railViews` + `clockPattern` to `ui/DashViews.kt`**

Add these imports near the top of `ui/DashViews.kt`:

```kotlin
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.config.Panels
```

Append these two functions at the end of the file:

```kotlin
/** The rail destinations: HOME first, then enabled panels ordered by their configured `order`. */
fun railViews(panels: Panels): List<DashView> {
    val configured = listOf(
        DashView.LIGHTS to panels.lights,
        DashView.CLIMATE to panels.climate,
        DashView.MEDIA to panels.media,
        DashView.WEATHER to panels.weather,
        DashView.SOLAR to panels.solar,
    ).filter { it.second.enabled }.sortedBy { it.second.order }.map { it.first }
    return listOf(DashView.HOME) + configured
}

/** SimpleDateFormat time pattern for the configured clock format (AUTO follows the system setting). */
fun clockPattern(format: ClockFormat, systemIs24: Boolean): String {
    val is24 = when (format) {
        ClockFormat.AUTO -> systemIs24
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    return if (is24) "HH:mm" else "h:mm a"
}
```

- [ ] **Step 8: Rewrite `ui/IconRail.kt` to take a view list**

Change the function signature and the iteration. Replace the `@Composable fun IconRail(...)` declaration line and the `DashView.entries.forEach` line:

```kotlin
@Composable
fun IconRail(current: DashView, views: List<DashView>, onSelect: (DashView) -> Unit, modifier: Modifier = Modifier) {
```

and

```kotlin
        views.forEach { view ->
```

(everything else in the file is unchanged.)

- [ ] **Step 9: Rewrite `ui/DashboardShell.kt`**

Replace the whole file with:

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
import com.rar.echodash.config.DashConfig
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.RegistryIndex
import com.rar.echodash.ui.model.lightSections
import com.rar.echodash.ui.model.solarFlow
import com.rar.echodash.ui.model.thermostats
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
    config: DashConfig,
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
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
) {
    val connected = connState == ConnState.CONNECTED
    val weatherEntityId = config.entities.weather
    val views = remember(config.panels) { railViews(config.panels) }

    Box(
        Modifier
            .fillMaxSize()
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
                    val pill = remember(entities, config.entities) {
                        weatherPill(config.entities.tempSensor, config.entities.weather, entities, System.currentTimeMillis())
                    }
                    HomeView(
                        photos = if (config.home.slideshowEnabled) photos else emptyList(),
                        pill = pill,
                        clockFormat = config.home.clockFormat,
                        connState = connState,
                        configUrl = configUrl,
                        configPin = configPin,
                        onLogout = onLogout,
                    )
                }
                DashView.LIGHTS -> {
                    val sections = remember(entities, registry, config.entities.lightGroups) {
                        lightSections(config.entities.lightGroups, registry, entities)
                    }
                    LightsPanel(sections, connected, onToggle)
                }
                DashView.CLIMATE -> {
                    val list = remember(entities, registry, config.entities.climate, config.panelOptions.thermostatStep) {
                        thermostats(config.entities.climate, registry, entities, config.panelOptions.thermostatStep)
                    }
                    ClimatePanel(list, connected, onSetTemperature, onSetHvacMode)
                }
                DashView.MEDIA -> MediaPanel(mediaUi, onMediaPlay, onMediaPause, onMediaStop, onMediaVolume)
                DashView.WEATHER -> WeatherPanel(
                    weather = weatherEntityId?.let { entities[it] },
                    weatherEntityId = weatherEntityId,
                    forecastDays = config.panelOptions.forecastDays,
                    fetchForecast = fetchForecast,
                )
                DashView.SOLAR -> {
                    val flow = remember(entities, config.entities.solar) { solarFlow(config.entities.solar, entities) }
                    SolarPanel(flow)
                }
            }
        }

        IconRail(
            current = current,
            views = views,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
    }
}
```

- [ ] **Step 10: Update `ui/panels/WeatherPanel.kt` to cap forecast days**

Change the `WeatherPanel` signature to add `forecastDays: Int`, and cap the rendered strip. Replace the function signature:

```kotlin
@Composable
fun WeatherPanel(
    weather: EntityState?,
    weatherEntityId: String?,
    forecastDays: Int,
    fetchForecast: suspend (String) -> JsonElement?,
) {
```

and change the empty hint plus the forecast `Row` to slice by `forecastDays`. Replace the `EmptyHint(...)` line:

```kotlin
            EmptyHint("Set a weather entity in the web config")
```

and replace the `forecast.forEach { day ->` line with:

```kotlin
                    forecast.take(forecastDays).forEach { day ->
```

- [ ] **Step 11: Update panel empty-hint text (config, not labels)**

In `ui/panels/LightsPanel.kt`, replace the `EmptyHint(...)` line with:

```kotlin
            EmptyHint("Add a light group in the web config")
```

In `ui/panels/ClimatePanel.kt`, replace the `EmptyHint(...)` line with:

```kotlin
            EmptyHint("Add a thermostat in the web config")
```

- [ ] **Step 12: Update `ui/HomeView.kt` — clock format param (Configure menu comes in Task 11)**

Add imports:

```kotlin
import com.rar.echodash.config.ClockFormat
```

Change the `HomeView` signature to accept `clockFormat` (and placeholders for the Configure entry added in Task 11 — add them now so Task 11 only fills the menu body):

```kotlin
@Composable
fun HomeView(
    photos: List<File>,
    pill: WeatherPill?,
    clockFormat: ClockFormat,
    connState: ConnState,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace the clock pattern line:

```kotlin
            val pattern = clockPattern(clockFormat, DateFormat.is24HourFormat(context))
```

Add a "Configure" item to the `DropdownMenu` (above the "Android settings" item):

```kotlin
            DropdownMenuItem(
                text = { Text("Configure: $configUrl  ·  PIN $configPin") },
                onClick = { menuOpen = false },
            )
```

- [ ] **Step 13: Update `App.kt` — collect config, pass it, idle from config**

In `EchoDashApp`'s `Screen.Dashboard` branch, add a config collection after the other `collectAsStateWithLifecycle` lines:

```kotlin
                    val config by deps.configStore.config.collectAsStateWithLifecycle()
```

Change the `IdleReturnTimer` creation to key on the configured timeout:

```kotlin
                    val idleSeconds = config.home.idleReturnSeconds
                    val idleTimer = remember(idleSeconds) {
                        IdleReturnTimer(uiScope, timeoutMs = idleSeconds * 1000L) { view = DashView.HOME }
                    }
```

In the `DashboardShell(...)` call, add `config = config,` as the first argument after `onSelect`, and add `configUrl = deps.configUrl(), configPin = deps.configPin(),` just before `onLogout = {`. (`deps.configUrl()` and `deps.configPin()` are added in Task 11; for this task, temporarily inline `configUrl = "", configPin = ""` so the module compiles — Task 11 replaces them with the real accessors.)

Add the `ConfigStore` import if not already present (it was added in Task 3):

```kotlin
import com.rar.echodash.config.ConfigStore
```

- [ ] **Step 14: Run the model/dashviews tests + full build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.*' --tests 'com.rar.echodash.ui.DashViewsTest'`
Expected: PASS.

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (all composables compile against the new signatures).

- [ ] **Step 15: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui app/src/main/java/com/rar/echodash/App.kt app/src/test/java/com/rar/echodash/ui
git commit -m "feat: drive shell, panels, rail, clock, and idle timer from DashConfig"
```

---

### Task 11: Wire the config server into the app + Configure menu + seeding

Adds the persisted PIN to settings, constructs `SessionManager`/`ConfigServer` in `AppDeps`, generates the PIN once, seeds the config from the registry on first run, starts the server with the dashboard (surviving a port-in-use), stops it on logout, and fills the Home "Configure" menu with the real URL + PIN.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt`
- Modify: `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes: `ConfigStore` (Task 3), `SessionManager` (Task 7), `ConfigServer`/`localIpAddress` (Task 8), `generatePin` (Task 7), `buildEntityListJson` (Task 6).
- Produces: `SettingsStore.configPin`; `AppDeps.configServer`, `AppDeps.configUrl()`, `AppDeps.configPin()`, `AppDeps.stopConfigServer()`.

- [ ] **Step 1: Add `configPin` to `SettingsStore` (test first)**

In `app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt`, add this test method inside the class:

```kotlin
    @Test
    fun configPinPersistsAcrossClearAuth() {
        val s: SettingsStore = InMemorySettingsStore()
        s.configPin = "042100"
        assertEquals("042100", s.configPin)
        s.accessToken = "at"; s.refreshToken = "rt"
        s.clearAuth()
        assertEquals("042100", s.configPin) // the PIN is not auth; it survives logout
    }
```

(Ensure `import org.junit.Assert.assertEquals` is present — it already is.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.data.SettingsStoreTest'`
Expected: FAIL — `configPin` unresolved.

- [ ] **Step 3: Add `configPin` to `SettingsStore.kt`**

In the `SettingsStore` interface, add:

```kotlin
    var configPin: String?
```

In `InMemorySettingsStore`, add the property (before `clearAuth`):

```kotlin
    override var configPin: String? = null
```

In `PrefsSettingsStore`, add the property (alongside the other `string`/`put`-backed properties):

```kotlin
    override var configPin: String?
        get() = string("config_pin"); set(v) = put("config_pin", v)
```

`clearAuth()` is unchanged (it must NOT remove `config_pin`).

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.data.SettingsStoreTest'`
Expected: PASS.

- [ ] **Step 5: Wire the server into `AppDeps`**

In `app/src/main/java/com/rar/echodash/App.kt`, add imports:

```kotlin
import com.rar.echodash.web.ConfigServer
import com.rar.echodash.web.SessionManager
import com.rar.echodash.web.buildEntityListJson
import com.rar.echodash.web.generatePin
import com.rar.echodash.web.localIpAddress
```

Add these members to `AppDeps` (place them right after the `photoStore` declaration, before the `// --- VACA ---` block — the VACA block stays untouched):

```kotlin
    val sessions = SessionManager()
    private val ensuredPin: String by lazy {
        settings.configPin ?: generatePin().also { settings.configPin = it }
    }
    val configServer = ConfigServer(
        store = configStore,
        sessions = sessions,
        pin = { configPin() },
        entitiesJson = { buildEntityListJson(entityHub.registry.value, entityHub.entities.value) },
        assetReader = { path ->
            runCatching { appContext.assets.open("config/$path").readBytes() }.getOrNull()
        },
    )
    private var seedStarted = false
    private var serverStarted = false

    /** The 6-digit config PIN (generated once, persisted). */
    fun configPin(): String = ensuredPin

    /** The config page URL to show the user (best-effort LAN IP). */
    fun configUrl(): String = "http://${localIpAddress() ?: "device-ip"}:8080"

    /** Stop the config server (on logout). */
    fun stopConfigServer() {
        if (serverStarted) { configServer.stop(); serverStarted = false }
    }
```

- [ ] **Step 6: Start seeding + server in `startDashboard()`**

Replace the existing `startDashboard()` body with:

```kotlin
    /** Start the HA connection, entity hub, photo sync, config seeding, and config server. */
    fun startDashboard() {
        entityHub.start()
        photoStore.start(ws.connectionState)
        if (!seedStarted) {
            seedStarted = true
            scope.launch {
                entityHub.registry.collect { reg ->
                    if (configStore.needsSeed() && reg.allEntities.isNotEmpty()) configStore.seedFrom(reg)
                }
            }
        }
        if (!serverStarted) {
            serverStarted = runCatching { configServer.start() }
                .onFailure { android.util.Log.w("AppDeps", "config server failed to start (port 8080 in use?)", it) }
                .isSuccess
        }
        ws.start()
    }
```

- [ ] **Step 7: Pass real Configure URL/PIN and stop the server on logout**

In `EchoDashApp`'s `Screen.Dashboard` branch, add (near the other `remember`/collect lines):

```kotlin
                    val configUrl = remember { deps.configUrl() }
                    val configPinValue = remember { deps.configPin() }
```

In the `DashboardShell(...)` call, replace the temporary `configUrl = "", configPin = ""` (added in Task 10) with:

```kotlin
                        configUrl = configUrl,
                        configPin = configPinValue,
```

In the `onLogout` lambda, add the server stop as the first line:

```kotlin
                        onLogout = {
                            deps.stopConfigServer()
                            deps.ws.stop()
                            deps.settings.clearAuth()
                            screen = Screen.Setup
                        },
```

- [ ] **Step 8: Run the full unit suite + build**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all tests pass (config, web, ha, photos, ui.model, ui.DashViews, data, vaca suites).

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rar/echodash/data/SettingsStore.kt app/src/test/java/com/rar/echodash/data/SettingsStoreTest.kt app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat: wire config server, PIN, seeding, and Configure menu into the app"
```

---

### Task 12: README — web config replaces label scheme

Documents the config page (the label scheme is gone from user docs), the one-time label→config seeding migration, and the LAN-only security model.

**Files:**
- Modify: `README.md`

**Interfaces:** none (documentation).

- [ ] **Step 1: Update the intro paragraph**

In `README.md`, replace the first paragraph (line 3, the "…all driven by HA labels over one authenticated `subscribe_entities` subscription…" sentence) with:

```markdown
A native Android kiosk dashboard for an Amazon Echo Show 5 running LineageOS. Logs into Home Assistant via OAuth2 (HA's own login page) and shows a multi-view dashboard: a right-side icon rail switches between a photo-backed Home clock view and five panels — Lights, Climate, Media, Weather, and Solar. Everything (entity assignment, panel order/visibility, home-screen settings, per-panel options, and the photo source) is configured from an on-device **web config page** served on the LAN — no HA labels, no on-device pickers. Config is one versioned `config.json`, applied live. Bundled Nunito font; auto-returns to Home after the configured idle timeout. Speaks the [VACA](https://github.com/msp1974/ViewAssist_Companion_App) device protocol, so the VACA HACS integration gives HA full control of the device — screen, brightness, screensaver, toasts, TTS announcements, and a media player — with native rendering instead of VACA's WebView.
```

- [ ] **Step 2: Replace the "First-run flow" entity step**

Replace step 3 of the "First-run flow" section with:

```markdown
3. **Configure the dashboard** — long-press the Home view, choose **Configure**, and open the shown URL (`http://<device-ip>:8080`) in any browser on the same network. Enter the PIN shown on the device, then assign entities, order/hide panels, and set home-screen and photo options. Existing installs are migrated automatically: on first launch the app seeds the config from any current `echo-*` labels, after which labels are ignored.
```

- [ ] **Step 3: Replace the "Label scheme" section with "Web configuration"**

Replace the entire `## Label scheme` section (heading + table + trailing paragraph) with:

```markdown
## Web configuration

The dashboard is configured from a small web page the device serves on your LAN (NanoHTTPD, port 8080). Long-press the Home view → **Configure** shows the URL (`http://<device-ip>:8080`) and a 6-digit PIN. Open the URL in a browser, enter the PIN (once per browser session), and configure:

- **Panels** — enable/disable and reorder Lights, Climate, Media, Weather, Solar (Home is always first).
- **Entities** — pick the temperature sensor, weather entity, thermostats, and solar sensors from searchable, domain-filtered lists; build named light groups with ordered members.
- **Home screen** — idle-return seconds (15–3600), clock format (auto/12h/24h), photo slideshow on/off, photo folder, and photo cache cap (5–500).
- **Panel options** — thermostat step (0.1–5.0) and forecast days (1–5).

Press **Save** to apply; the device updates within a couple of seconds. Out-of-range numbers are clamped on save. Config is stored at the app's `config.json` and survives reboots.

### Migration from labels

Earlier versions used HA labels (`echo-temp`, `echo-weather`, `echo-lights[-group]`, `echo-climate`, `echo-solar-*`). On first launch with no `config.json`, the app seeds the configuration from those labels once (bare `echo-lights` becomes a group named "Lights", suffixes become title-cased groups). After seeding, labels are never consulted again — all further changes happen on the web page.

### Security

Plain HTTP, LAN-only trust — the same grade as a default Home Assistant install. Access is gated by the on-device PIN (session cookie per browser; 5 wrong PINs lock the login for 60 s). There is no TLS, no multi-user accounts, and no remote access; keep the device on a trusted network.
```

- [ ] **Step 4: Note the configurable photo folder**

In the "Photo slideshow (Home backdrop)" section, replace the first sentence with:

```markdown
Drop images into a Home Assistant media folder (default `media/echo-frame/`, changeable on the config page). The device syncs that folder on connect and every 6 h, caches downsampled copies (bounded by the photo cache cap — large folders rotate through a random subset), and cycles them on the Home view every 5 minutes with a crossfade. With the slideshow off or no photos, the Home view falls back to the dusk-gradient background.
```

- [ ] **Step 5: Update the panels overview line for Climate/Weather**

In the "Panels" section, replace the Climate and Weather bullets with:

```markdown
- **Climate** — current temperature, +/- setpoint (configurable step, debounced 800 ms → `climate.set_temperature`), HVAC mode row (`climate.set_hvac_mode`).
- **Weather** — current conditions + a configurable-length forecast (up to 5 days, `weather.get_forecasts`, refreshed every 30 min).
```

- [ ] **Step 6: Fix the test-count line**

Run `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest` and read the reported total, then update the "N plain-JVM unit tests" sentence (line 5) to the new count.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: web configuration replaces the label scheme; migration + LAN security"
```

---

## Self-Review

**1. Spec coverage** — every spec section maps to a task:

| Spec area | Task(s) |
|---|---|
| `DashConfig` versioned document, clamping, unknown-key tolerance | 1 |
| Label→config seeding (each slot, lights suffix grouping, empty default) | 2 |
| `ConfigStore` StateFlow, atomic write, corrupt→`.bad`+reseed | 3 |
| Rotating-subset selection (under-cap passthrough, over-cap evict/refill, removed-first, injected RNG) | 4 |
| PhotoStore folder/cap/enabled from config; folder/cap change resync | 5 |
| EntityHub watched set from config; re-subscribe on config change; registry for names/picker; full entity list | 6 |
| `/api/entities` payload builder | 6 |
| Auth: PIN check, session token, lockout after 5, expiry | 7 |
| PIN 6-digit generation | 7 |
| ConfigServer routes (`GET /`+assets, login, GET/PUT config, entities), cookie auth, 401/400/429 | 8 |
| Asset access injected `(String) -> ByteArray?`; JVM end-to-end test on ephemeral port with OkHttp | 8 |
| Device IP for Configure menu | 8 (`localIpAddress`) |
| Web page (PIN overlay, panels, entities pickers, light groups, home + per-panel settings, Save) | 9 |
| Rail order/enable from config (Home first) | 10 (`railViews`, IconRail, DashboardShell) |
| LightsModel explicit groups; ClimatePanel `thermostatStep`; WeatherPanel `forecastDays`; clock `clockFormat`; IdleReturnTimer `idleReturnSeconds`; slideshow gate | 10, 11 |
| PIN persisted in prefs; Configure menu shows URL+PIN | 11 |
| Server lifecycle (start with dashboard, port-in-use survival, stop on logout) | 11 |
| First-run seeding trigger | 11 |
| README (config page, migration, LAN security) | 12 |
| VACA/kiosk/OAuth untouched | Global Constraints (no task modifies `vaca/`, `KioskController`, `MainActivity`, `SetupScreen`) |

**2. Placeholder scan** — no `TBD`/`TODO`/"handle edge cases"/"similar to Task N". Every code step is verbatim; every command has expected output; the web assets (HTML/CSS/JS) are complete. The Task 10 temporary `configUrl = "", configPin = ""` is an explicit, documented compile-green stopgap replaced in Task 11 — not a placeholder deliverable. NanoHTTPD 2.3.1 APIs used (`NanoHTTPD(port)`, `start()`, `stop()`, `getListeningPort()`, `serve(IHTTPSession)`, `Method`, `session.uri`, `session.headers["cookie"]`, `session.parseBody` → `"postData"` for POST / temp-file path under `"content"` for PUT (verified against 2.3.1 source), `newFixedLengthResponse`, `Response.Status`, custom `Response.IStatus` for 429) are all present in that release.

**3. Type consistency** — signatures verified across tasks:
- `DashConfig`/`Entities`/`SolarConfig`/`LightGroup`/`Panels`/`PanelConfig`/`HomeSettings`/`PanelOptions`/`ClockFormat` (Task 1) used identically in Tasks 2, 3, 5, 6, 10, 11.
- `DashConfig.referencedEntityIds()` (Task 1) consumed by `EntityHub` (Task 6); `DashConfig.clamped()` by `ConfigStore` (Task 3).
- `ConfigStore(config, needsSeed, seedFrom, update)` (Task 3) consumed by `ConfigServer` (Task 8) and `AppDeps` (Tasks 5, 6, 11).
- `seedConfig(RegistryIndex): DashConfig` (Task 2) is `ConfigStore`'s default `seeder` (Task 3).
- `rotatingSubset(listing, cachedKeys, cap, random): PhotoPlan` (Task 4) called by `PhotoStore.sync()` (Task 5) with `RemotePhoto`/`cacheKey` (existing).
- `RegistryIndex.allEntities`/`RegistryEntity` (Task 6) used by `buildEntityListJson` (Task 6); `parseEntityRegistry` still returns `labelToEntities` for `seedConfig` (Task 2).
- `SessionManager.login/isValidSession` + `LoginResult.Ok/Invalid/LockedOut` (Task 7) used by `ConfigServer` (Task 8); `generatePin` (Task 7) used by `AppDeps` (Task 11).
- `ConfigServer(port, store, sessions, pin, entitiesJson, assetReader)` (Task 8) constructed in `AppDeps` (Task 11).
- `lightSections`/`thermostats`/`weatherPill`/`solarFlow` (Task 10) match `DashboardShell` call sites (Task 10); `railViews(Panels)`/`clockPattern(ClockFormat, Boolean)` (Task 10) match `DashboardShell`/`HomeView`/`DashViewsTest`.
- `IconRail(current, views, onSelect, modifier)` (Task 10) matches `DashboardShell` (Task 10); `HomeView(photos, pill, clockFormat, connState, configUrl, configPin, onLogout, modifier)` (Tasks 10–11) matches `DashboardShell` (Task 10); `WeatherPanel(weather, weatherEntityId, forecastDays, fetchForecast)` (Task 10) matches `DashboardShell` (Task 10).
- `ThermostatState.step` (Task 10) consumed by `ClimatePanel` debouncer reset (existing) — no ClimatePanel signature change.
- `SettingsStore.configPin` (Task 11) read by `AppDeps.ensuredPin`; `clearAuth()` leaves it intact (Task 11 test).
- `EntityHub(client, scope, config, clock)` (Task 6) constructed in `AppDeps` (Task 6); `PhotoStore(client, downloader, cacheDir, scope, config, syncIntervalMs, random)` (Task 5) constructed in `AppDeps` (Task 5).

Ambiguities resolved (see final report) are recorded against the tasks that implement them. No spec requirement is left without a task.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-11-web-config.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration (REQUIRED SUB-SKILL: superpowers:subagent-driven-development).
2. **Inline Execution** — execute tasks in this session with checkpoints (REQUIRED SUB-SKILL: superpowers:executing-plans).

Which approach?
