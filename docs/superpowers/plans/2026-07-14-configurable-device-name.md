# Configurable Device Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded "Echo Dashboard" device identity with a configurable, device-local name (default `"Hearth (<MODEL> <ID4>)"`) that feeds mDNS, VACA, and the Wyoming voice satellite, editable from a new PIN-gated Device card on the web config page.

**Architecture:** The effective name is computed in `App.kt` (the only Android-aware layer) and threaded as `() -> String` lambdas to every consumer (`NsdAdvertiser` ×2, `VacaOutgoing.info`, `SatelliteServer`→`SatelliteSession`) so live components always read the current value. A new PIN-gated `PUT /api/name` on the Android-free `ConfigServer` clamps and stores the name via injected callbacks; `App.kt` persists it in `SettingsStore` and bounces the live mDNS/VACA/voice sessions so HA re-reads the identity without an app restart. The web config page gains a Device card that reads `/api/status`'s `deviceName` and posts renames.

**Tech Stack:** Kotlin 2.1.0 (Android, Compose), NanoHTTPD 2.3.1, kotlinx.serialization JSON, kotlinx.coroutines Flow; plain-JVM JUnit4 for `ConfigServer`; vanilla JS (ES2020, `"use strict"`) for the config page.

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 (NEVER bump); minSdk 28.
- Dependency whitelist: NanoHTTPD 2.3.1 + `org.tensorflow:tensorflow-lite:2.14.0` only — this feature adds NO dependencies.
- Tests are plain-JVM JUnit4 (no Robolectric/instrumented). `ConfigServer` and `SatelliteSession` MUST stay free of Android imports; `Build.MODEL` / `Settings.Secure.ANDROID_ID` touch ONLY `App.kt`.
- Build gate after every Kotlin change: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` (exit 0).
- After JS edits: `node --check app/src/main/assets/config/app.js`.
- Work directly on master.
- UI copy: the app is called "Hearth"; user-facing text must not say "Echo Dashboard".
- Every commit message MUST end with this trailer line:
  ```
  Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
  ```

---

### Task 1: Kotlin — device name storage, threading, endpoint, and rename bounce

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/data/SettingsStore.kt` — add `deviceName` to interface + both impls.
- Modify: `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt:11,22` — `name: () -> String` ctor param, read in `register()`.
- Modify: `app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt:66,72,74` — `info(appVersion, name)`.
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt:60,362,392,394,411` — `name: () -> String` ctor param; delete `SATELLITE_NAME`.
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt:33,67,82` — thread `name` param to both `SatelliteSession(...)`.
- Modify: `app/src/main/java/com/rar/echodash/web/ConfigServer.kt:23,63,103` — `deviceName`/`setDeviceName` ctor params, `PUT /api/name`, `deviceName` in `/api/status`.
- Modify: `app/src/main/java/com/rar/echodash/App.kt:127,208,242,260,278,292,304` — `deviceName()`, `applyDeviceName()`, wire all lambdas, rename-bounce.
- Test: `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt` — new `/api/name` + status tests (TDD).
- Test (keep-green edits): `app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt:70,76`, `app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt:64`, `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt:21,35`, `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt:53,161`, `app/src/test/java/com/rar/echodash/web/BrowserFlowReproTest.kt:32`, `app/src/test/java/com/rar/echodash/web/ConfigServerSetupTest.kt:46`.

**Interfaces:**
- Produces (consumed by Task 2 via HTTP, and by internal callers):
  - `SettingsStore.deviceName: String?` — device-local, `null` = unset. NOT touched by `clearAuth()`.
  - `NsdAdvertiser(context, port, serviceType = "_vaca._tcp.", name: () -> String)`.
  - `VacaOutgoing.info(appVersion: String, name: String): WyomingEvent`.
  - `SatelliteSession(appVersion: String, name: () -> String, localWake: Boolean = false)`.
  - `SatelliteServer(scope, port = PORT, appVersion, name: () -> String, out)`.
  - `ConfigServer(..., deviceName: () -> String, setDeviceName: (String?) -> Unit, ...)`.
  - `PUT /api/name` (PIN-gated) body `{"name":"..."}` → `200 {"name":"<effective>"}`, malformed JSON → `400 {"error":...}`.
  - `GET /api/status` JSON gains `"deviceName":"<effective>"`.
  - `App.deviceName(): String` — single source of truth; `App.applyDeviceName(String?)` — persist + bounce live identities.

**Note on TDD sequencing.** The whole Kotlin change compiles as one unit (`:app:testDebugUnitTest` compiles main + test sources together), so intermediate build gates cannot pass until every signature change and its call sites are consistent. The new `ConfigServer` behavior is authored test-first (Step 6 before Step 7); the single build gate at Step 9 is the red→green transition for all of Task 1. Do not run the build gate before Step 9 — it will fail to compile mid-task by design.

- [ ] **Step 1: Add `deviceName` to `SettingsStore`**

In `app/src/main/java/com/rar/echodash/data/SettingsStore.kt`, add `deviceName` to the interface (after `notifyToken`):

```kotlin
interface SettingsStore {
    var baseUrl: String?
    var accessToken: String?
    var accessTokenExpiresAt: Long
    var refreshToken: String?
    var authClientId: String?
    var vacaSettingsJson: String?
    var configPin: String?
    var notifyToken: String?
    var deviceName: String?
    fun clearAuth()
}
```

Add the `InMemorySettingsStore` field (after `notifyToken`):

```kotlin
    override var configPin: String? = null
    override var notifyToken: String? = null
    override var deviceName: String? = null
```

Add the `PrefsSettingsStore` accessor (after the `notifyToken` accessor, before `clearAuth`):

```kotlin
    override var notifyToken: String?
        get() = string("notify_token"); set(v) = put("notify_token", v)
    override var deviceName: String?
        get() = string("device_name"); set(v) = put("device_name", v)
```

`clearAuth()` in both impls is left UNCHANGED — the name survives an HA disconnect.

- [ ] **Step 2: Add `name` lambda to `NsdAdvertiser`**

In `app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt`, add the ctor param (line 11) and read it in `register()` (line 22):

```kotlin
/** Advertises the VACA server via mDNS so HA auto-discovers the device (retries every 30 s on failure). */
class NsdAdvertiser(
    context: Context,
    private val port: Int,
    private val serviceType: String = "_vaca._tcp.",
    private val name: () -> String,
) {
```

```kotlin
        val info = NsdServiceInfo().apply {
            serviceName = name()
            serviceType = this@NsdAdvertiser.serviceType
            setPort(this@NsdAdvertiser.port)
        }
```

`name()` is read on every `register()`, so an `unregister()` + `register()` bounce re-announces the current name.

- [ ] **Step 3: Add `name` to `VacaOutgoing.info` and keep its tests green**

In `app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt`, change the `info` signature (line 66) and both `put("name", ...)` sites (lines 72, 74):

```kotlin
    fun info(appVersion: String, name: String): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "wake", "mic", "snd")) {
                putJsonArray(key) {}
            }
            putJsonObject("satellite") {
                put("name", name)
                putJsonObject("attribution") {
                    put("name", name)
                    put("url", "https://github.com/rar/echo-dashboard")
                }
                put("installed", true)
                put("description", "Native Home Assistant dashboard")
                put("version", appVersion)
                put("area", JsonNull)
                put("has_vad", false)
                putJsonArray("active_wake_words") {}
                put("max_active_wake_words", 0)
                put("supports_trigger", false)
            }
        }
        return WyomingEvent("info", data)
    }
```

In `app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt`, update the call (line 70) and assertion (line 76):

```kotlin
        val e = VacaOutgoing.info("0.2", "Test Device")
```

```kotlin
        assertEquals("Test Device", sat["name"]!!.jsonPrimitive.content)
```

In `app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt`, update the `infoEvent` lambda (line 64):

```kotlin
            infoEvent = { VacaOutgoing.info("0.2", "Test Device") },
```

- [ ] **Step 4: Add `name` to `SatelliteSession`, delete `SATELLITE_NAME`, keep its test green**

In `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt`, change the ctor (line 60):

```kotlin
class SatelliteSession(
    private val appVersion: String,
    private val name: () -> String,
    private val localWake: Boolean = false,
) {
```

Replace the three `SATELLITE_NAME` uses with `name()` — the wake-section attribution (line 362) and both satellite-section names (lines 392, 394):

```kotlin
                        put("name", "openWakeWord")
                        putJsonObject("attribution") {
                            put("name", name())
                            put("url", "https://github.com/rar/echo-dashboard")
                        }
```

```kotlin
            putJsonObject("satellite") {
                put("name", name())
                putJsonObject("attribution") {
                    put("name", name())
                    put("url", "https://github.com/rar/echo-dashboard")
                }
```

Delete the `SATELLITE_NAME` constant from the companion (line 411) — leave the rest of the companion intact:

```kotlin
    companion object {
        const val AUDIO_RATE = 16000
        const val AUDIO_WIDTH = 2
        const val AUDIO_CHANNELS = 1
        const val DISMISS_MS = 4000L
        const val ALERT_SILENCE_MS = 60000L

        /** The three bundled wake-word model ids and their friendly phrases (HA display only). */
        val WAKE_MODELS = listOf(
            "okay_nabu" to "Okay Nabu",
            "hey_jarvis" to "Hey Jarvis",
            "alexa" to "Alexa",
        )
    }
```

(No other file references `SatelliteSession.SATELLITE_NAME` — verified by grep. The only usages were these three sites plus the declaration.)

In `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`, update the `session()` helper (line 21) and the describe assertion (line 35):

```kotlin
    private fun session() = SatelliteSession(appVersion = "9.9", name = { "Test Sat" })
```

```kotlin
        assertEquals("Test Sat", sat["name"]!!.jsonPrimitive.content)
```

- [ ] **Step 5: Thread `name` through `SatelliteServer` and keep its tests green**

In `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt`, add the ctor param (line 33):

```kotlin
class SatelliteServer(
    private val scope: CoroutineScope,
    private val port: Int = PORT,
    private val appVersion: String,
    private val name: () -> String,
    private val out: Out,
) {
```

Pass `name` to the field-init `SatelliteSession` (line 67) and the `start()` `SatelliteSession` (line 82):

```kotlin
    @Volatile private var session = SatelliteSession(appVersion, name)
```

```kotlin
    fun start(localWake: Boolean = false, detector: WakeDetector? = null, wakeWord: String = "okay_nabu") {
        if (acceptJob?.isActive == true) return
        session = SatelliteSession(appVersion, name, localWake)
```

In `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt`, update both `SatelliteServer(...)` constructions (lines 53 and 161) to pass `name`:

```kotlin
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", name = { "Test Sat" }, out = out)
```

(Both lines are identical text; apply the same edit to each.)

- [ ] **Step 6: Write the failing `ConfigServer` tests for `/api/name` and status (RED)**

In `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`, add these fake-tracking fields to the class body (next to `previewCalls`, near line 31):

```kotlin
    private var customName: String? = null
    private val setNameCalls = mutableListOf<String?>()
    private val defaultName = "Hearth (Pixel 1234)"
```

Add the two new constructor arguments to the `ConfigServer(...)` in `setUp()` (immediately after the `notifyToken = { "testtoken" },` line):

```kotlin
            notifyToken = { "testtoken" },
            deviceName = { customName ?: defaultName },
            setDeviceName = { v -> setNameCalls += v; customName = v },
```

Add this import to the file's import block (alphabetically, after `import org.junit.Assert.assertNotNull`):

```kotlin
import org.junit.Assert.assertNull
```

Then add these test methods (place them after `statusIncludesNotifyToken`, before the closing brace of the class):

```kotlin
    @Test
    fun renameStripsControlCharsAndCollapsesWhitespace() {
        val cookie = cookieFrom(login("123456"))
        // JSON-escaped NUL ( ) and DEL () are decoded to real control chars by the
        // parser, then stripped; the run of spaces collapses to one. Written as \\u.... so the
        // wire bytes are the JSON escape, not a raw control char (the parser is not lenient).
        val body = "{\"name\":\"  My\\u0000Kitchen\\u007f   Hearth  \"}"
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put(body.toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"MyKitchen Hearth\""))
            }
        assertEquals("MyKitchen Hearth", setNameCalls.last())
        assertEquals("MyKitchen Hearth", customName)
    }

    @Test
    fun renameTruncatesToFortyChars() {
        val cookie = cookieFrom(login("123456"))
        val fifty = "A".repeat(50)
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("""{"name":"$fifty"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"${"A".repeat(40)}\""))
            }
        assertEquals("A".repeat(40), setNameCalls.last())
    }

    @Test
    fun renameEmptyResetsToDefault() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("""{"name":"    "}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"Hearth (Pixel 1234)\""))
            }
        assertNull(setNameCalls.last())   // setter received null = reset to default
    }

    @Test
    fun renameMissingNameResetsToDefault() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("{}".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"name\":\"Hearth (Pixel 1234)\""))
            }
        assertNull(setNameCalls.last())
    }

    @Test
    fun renameMalformedBodyReturns400() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/name").header("Cookie", cookie)
            .put("{ not json".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
            }
        assertTrue(setNameCalls.isEmpty())
    }

    @Test
    fun renameRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/name")
            .put("""{"name":"Study"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(setNameCalls.isEmpty())
    }

    @Test
    fun statusIncludesDeviceName() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"deviceName\":\"Hearth (Pixel 1234)\""))
            }
    }
```

Also add the two new constructor arguments to the OTHER two `ConfigServer(...)` test setups so they still compile (neither asserts on the name):

In `app/src/test/java/com/rar/echodash/web/BrowserFlowReproTest.kt` `setUp()` (after `notifyToken = { "testtoken" },`, line ~38):

```kotlin
            notifyToken = { "testtoken" },
            deviceName = { "Hearth" },
            setDeviceName = { },
```

In `app/src/test/java/com/rar/echodash/web/ConfigServerSetupTest.kt` `setUp()` (after `notifyToken = { "testtoken" },`, line ~53):

```kotlin
            notifyToken = { "testtoken" },
            deviceName = { "Hearth" },
            setDeviceName = { },
```

Expected at this point: the module does not compile — `ConfigServer` has no `deviceName`/`setDeviceName` params and no `/api/name` route. This is the intended RED; do NOT run the gate yet (Step 9 runs it).

- [ ] **Step 7: Implement the `ConfigServer` endpoint and status field**

In `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`, add the two ctor params (after `notifyToken`, line 29):

```kotlin
    private val notifyToken: () -> String,
    private val deviceName: () -> String,
    private val setDeviceName: (String?) -> Unit,
```

Add the route inside the authed `when` block (after the `/api/status` line, ~line 63):

```kotlin
                uri == "/api/status" && method == Method.GET -> handleStatus()
                uri == "/api/name" && method == Method.PUT -> handlePutName(session)
```

Add the `deviceName` field to `handleStatus()` (line 103):

```kotlin
    private fun handleStatus(): Response =
        ok(buildJsonObject {
            put("configured", configured())
            put("connState", connState())
            put("lux", lux())            // int, or JSON null when no sensor reading yet
            put("notifyToken", notifyToken())
            put("deviceName", deviceName())
        }.toString())
```

Add the handler and clamp helper (place `handlePutName` right after `handleStatus`, and `clampDeviceName` next to it). No Android imports — `Regex`, `buildString`, `ifEmpty` are Kotlin stdlib; `jsonPrimitive`/`contentOrNull`/`buildJsonObject`/`put`/`JsonObject` are already imported:

```kotlin
    private fun handlePutName(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val raw = obj["name"]?.jsonPrimitive?.contentOrNull   // JSON null / missing -> null -> default
        setDeviceName(clampDeviceName(raw))
        return ok(buildJsonObject { put("name", deviceName()) }.toString())
    }

    /**
     * Clamp a raw device name to storage form, or null to reset to the computed default.
     * Order (per spec): trim; strip ASCII control chars (< 0x20 and 0x7F); collapse whitespace
     * runs to single spaces; truncate to 40; trim again. Empty after clamping (or null input) -> null.
     */
    private fun clampDeviceName(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        s = buildString { for (c in s) if (c.code >= 0x20 && c.code != 0x7F) append(c) }
        s = s.replace(Regex("\\s+"), " ")
        if (s.length > 40) s = s.substring(0, 40)
        s = s.trim()
        return s.ifEmpty { null }
    }
```

Expected still-not-compiling: `App.kt` constructs `ConfigServer`, `NsdAdvertiser`, `VacaOutgoing.info`, and `SatelliteServer` with the OLD signatures. Step 8 fixes that; the gate runs at Step 9.

- [ ] **Step 8: Wire the name through `App.kt` and add the rename bounce**

All edits are in `app/src/main/java/com/rar/echodash/App.kt`.

(a) Add imports (with the other `android.*` and coroutines-flow imports near the top):

```kotlin
import android.os.Build
import android.provider.Settings
```

```kotlin
import kotlinx.coroutines.flow.combine
```

(b) Add the effective-name source of truth. Place this trio right after `configUrl()` (after line 150). `androidIdSuffix` is `lazy` so ANDROID_ID is read once:

```kotlin
    /** Last 4 chars of ANDROID_ID (lowercase hex as returned); "0000" if unavailable. Read once. */
    private val androidIdSuffix: String by lazy {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        if (id.isNullOrBlank()) "0000" else id.takeLast(4)
    }

    /** Single source of truth for the device identity. Custom name verbatim, else computed default. */
    fun deviceName(): String =
        settings.deviceName ?: "Hearth (${Build.MODEL} $androidIdSuffix)"

    /** Persist a clamped name (null = reset to default) and re-announce every live identity. */
    private fun applyDeviceName(name: String?) {
        settings.deviceName = name
        if (vacaRunning) {
            nsd.unregister(); nsd.register()   // re-announce _vaca._tcp mDNS with the new name
            vaca.stop(); vaca.start()          // drop HA's VACA session so it re-reads info on reconnect
        }
        voiceRestartTick.value += 1            // reactive voice collect tears down + rebuilds (voiceNsd + satellite)
    }
```

(c) Add the two `ConfigServer` arguments (in the `configServer = ConfigServer(...)` block, after `notifyToken = { ensuredNotifyToken },`, line 131):

```kotlin
        notifyToken = { ensuredNotifyToken },
        deviceName = { deviceName() },
        setDeviceName = { applyDeviceName(it) },
```

(d) Update the VACA `infoEvent` lambda (line 208):

```kotlin
        infoEvent = { VacaOutgoing.info(BuildConfig.VERSION_NAME, deviceName()) },
```

(e) Update the `nsd` advertiser (line 242):

```kotlin
    private val nsd = NsdAdvertiser(appContext, VacaServer.DEFAULT_PORT, name = { deviceName() })
```

(f) Add `name` to the `satellite` `SatelliteServer` (in the `satellite = SatelliteServer(...)` block, after `appVersion = BuildConfig.VERSION_NAME,`, line 262):

```kotlin
    val satellite: SatelliteServer = SatelliteServer(
        scope = scope,
        appVersion = BuildConfig.VERSION_NAME,
        name = { deviceName() },
        out = object : SatelliteServer.Out {
```

(g) Update the `voiceNsd` advertiser (line 278) and add the `voiceRestartTick` field right after it:

```kotlin
    private val voiceNsd = NsdAdvertiser(appContext, SatelliteServer.PORT, "_wyoming._tcp.", name = { deviceName() })

    // Bumped by applyDeviceName() to force the reactive voice collect to restart the satellite +
    // re-register voiceNsd so HA re-reads the (lambda-sourced) name — even when voice settings are unchanged.
    private val voiceRestartTick = MutableStateFlow(0)
```

(h) Add the `vacaRunning` flag and set it in `startVaca()` (lines 292–296). Declare the flag just above `startVaca()`:

```kotlin
    @Volatile private var vacaRunning = false

    fun startVaca() {
        vaca.start()
        nsd.register()
        lightSensor.start()
        vacaRunning = true
    }
```

(i) Make `startVoice()`'s collect also fire on `voiceRestartTick`, without re-running on unrelated config edits. The voice-settings triple is deduped BEFORE the `combine`, so `combine` emits only when the triple actually changes OR the tick bumps. The collect body is unchanged (lines 309–330). Replace the whole `startVoice()` function:

```kotlin
    fun startVoice() {
        scope.launch {
            val voiceSettings = configStore.config
                .map { Triple(it.voice.enabled, it.voice.wakeWord, it.voice.wakeThreshold) }
                .distinctUntilChanged()
            combine(voiceSettings, voiceRestartTick) { s, _ -> s }
                .collect { (enabled, wakeWord, threshold) ->
                    // Tear down any running instance first so a config change fully restarts it.
                    voiceNsd.unregister()
                    satellite.stop()
                    micStreamer.stop()
                    if (enabled) {
                        val graphs = TfliteWakeGraphs.load(appContext.assets, wakeWord)
                        val detector = if (graphs != null) {
                            WakeDetector(graphs.first, graphs.second, graphs.third, threshold) {
                                System.currentTimeMillis()
                            }
                        } else {
                            android.util.Log.w("AppDeps", "wake models failed to load; falling back to HA-side wake")
                            null
                        }
                        satellite.start(localWake = detector != null, detector = detector, wakeWord = wakeWord)
                        voiceNsd.register()
                    } else {
                        timerChime.stop()
                        voiceOverlay.value = VoiceOverlayState()
                        timersUi.value = TimersUiState()
                    }
                }
        }
    }
```

- [ ] **Step 9: Run the build gate (RED → GREEN)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: exit 0. All unit tests pass, including the seven new `ConfigServerTest` methods (`renameStripsControlCharsAndCollapsesWhitespace`, `renameTruncatesToFortyChars`, `renameEmptyResetsToDefault`, `renameMissingNameResetsToDefault`, `renameMalformedBodyReturns400`, `renameRequiresSession`, `statusIncludesDeviceName`) and the updated `VacaMessagesTest`/`SatelliteSessionTest` name assertions.

If a test fails, read the failure and fix the implementation (do NOT weaken the test). If it does not compile, reconcile the changed signature against every call site listed in this task's **Files** block.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/rar/echodash/data/SettingsStore.kt \
        app/src/main/java/com/rar/echodash/vaca/NsdAdvertiser.kt \
        app/src/main/java/com/rar/echodash/vaca/VacaMessages.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt \
        app/src/main/java/com/rar/echodash/web/ConfigServer.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt \
        app/src/test/java/com/rar/echodash/web/BrowserFlowReproTest.kt \
        app/src/test/java/com/rar/echodash/web/ConfigServerSetupTest.kt \
        app/src/test/java/com/rar/echodash/vaca/VacaMessagesTest.kt \
        app/src/test/java/com/rar/echodash/vaca/VacaServerTest.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt
git commit -m "feat: configurable device name (storage, threading, /api/name, rename bounce)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 2: Web UI — Device card

**Files:**
- Modify: `app/src/main/assets/config/index.html` — insert a new `<section id="device-section">` FIRST after the setup section: immediately after the setup-section closing `</section>` (line 79) and before `<section id="panels-section" ...>` (line 81).
- Modify: `app/src/main/assets/config/app.js` — add `renderDevice()` + `renameDevice(input)`; call `renderDevice()` first in `render()`.
- Test: none (no JS test harness). Gate is `node --check` plus manual browser verification (final steps).

**Interfaces:**
- Consumes (already in `app.js`): `api(method, path, body)` → `fetch` Response; `setStatus(msg, kind)` with `kind` in `"ok"|"err"|"busy"|"info"`; `showLogin()`; `el(tag, cls, text)`; `clear(node)`; `labeledRow(labelText, control)`; global `lastStatus` (the `/api/status` body, now carrying `deviceName` from Task 1).
- Consumes (HTTP, from Task 1): `PUT /api/name` `{"name":"..."}` → `200 {"name":"<effective>"}` / `401` / `400`; `GET /api/status` `.deviceName`.
- Produces: new functions `renderDevice()`, `renameDevice(input)`; new DOM ids `device-section`, `device`.

- [ ] **Step 1: Add the Device `<section>` to `index.html`**

In `app/src/main/assets/config/index.html`, insert this block between the setup-section's closing `</section>` (line 79) and the `<section id="panels-section"...>` opening (line 81) — the Device card is FIRST after setup, before Panels. The inline SVG is a stroke-1.7 tag/label glyph:

```html
      <section id="device-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0l-6.6-6.6A2 2 0 0 1 3.4 12.6V5a1.6 1.6 0 0 1 1.6-1.6h7.6a2 2 0 0 1 1.4.6l6.6 6.6a2 2 0 0 1 0 2.8Z"/>
              <circle cx="8" cy="8" r="1.4"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Device</h2>
            <p>How this device identifies itself to Home Assistant and on the network.</p>
          </div>
        </div>
        <div id="device"></div>
      </section>
```

- [ ] **Step 2: Add `renderDevice()` and `renameDevice()` to `app.js`**

In `app/src/main/assets/config/app.js`, add these two functions immediately after the `render()` function (after its closing `}` on line 312) and before `function renderPanels()` (line 314):

```js
function renderDevice() {
  const host = document.getElementById("device");
  clear(host);

  const nameInput = el("input");
  nameInput.type = "text";
  nameInput.maxLength = 40;
  nameInput.value = (lastStatus && lastStatus.deviceName) || "";
  nameInput.setAttribute("aria-label", "Device name");
  host.appendChild(labeledRow("Device name", nameInput));

  const row = el("div", "row");
  const renameBtn = el("button", "ghost", "Rename");
  renameBtn.type = "button";
  renameBtn.addEventListener("click", () => renameDevice(nameInput));
  row.appendChild(renameBtn);
  host.appendChild(row);

  host.appendChild(el("div", "muted",
    "This name appears in Home Assistant (voice satellite and VACA) and on the network (mDNS). " +
    "It is stored only on this device and is never included in config export/import. Leave the " +
    "field empty and rename to restore the default. Home Assistant may keep showing the old name " +
    "on entries it already knows until you rename or re-add the device there."));
}

async function renameDevice(input) {
  setStatus("Renaming…", "busy");
  try {
    const r = await api("PUT", "/api/name", { name: input.value });
    if (r.ok) {
      const b = await r.json();
      input.value = b.name || "";              // adopt the server's effective (clamped/default) name
      if (lastStatus) lastStatus.deviceName = b.name;
      setStatus("Renamed", "ok");
    } else if (r.status === 401) {
      showLogin();
    } else {
      const b = await r.json().catch(() => ({}));
      setStatus("Error: " + (b.error || r.status), "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — not renamed.", "err");
  }
}
```

- [ ] **Step 3: Wire `renderDevice()` into `render()`**

In `app/src/main/assets/config/app.js`, add `renderDevice()` as the FIRST call in `render()` (lines 300–312), matching the card's position on the page:

```js
function render() {
  renderDevice();
  renderPanels();
  renderEntities();
  renderMedia();
  renderNotifications();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
  renderCalendars();
  renderBackup();
}
```

- [ ] **Step 4: Run the syntax gate**

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit code 0 (any syntax error fails the task — fix and re-run).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "feat: Device card on the web config page (rename device name)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

- [ ] **Step 6: Manual verification (browser + device)**

No automated harness exists for the JS; verify by hand against a running device (Echo at 10.75.1.98 PIN 379199, tablet at 10.75.0.183 PIN 489165):

1. Open the config page, unlock with the PIN. The **Device** card appears FIRST (before Panels) with the tag icon, title "Device", and a text input pre-filled with the current name.
2. On a device with no custom name set, confirm the input shows the default shape `Hearth (<MODEL> xxxx)` — e.g. `Hearth (TB310FU xxxx)` on the tablet (4 hex chars from ANDROID_ID).
3. Type a new name (e.g. `Kitchen Hearth`) and click **Rename**. Status goes `Renaming…` then `Renamed`; the input keeps the effective name.
4. Enter a name with leading/trailing spaces and a run of internal spaces (e.g. `  Study    Room  `). After Rename the input shows `Study Room` (trimmed, collapsed).
5. Enter a >40-char name; after Rename the input shows it truncated to 40 chars.
6. Clear the field entirely and click **Rename**; the input returns to the default `Hearth (<MODEL> xxxx)` shape (reset to default).
7. mDNS: from a machine on the LAN, run `avahi-browse -rt _vaca._tcp` (and `_wyoming._tcp` if voice is enabled) and confirm the advertised service name matches the new name shortly after a rename (the advertiser re-registers on rename). Alternatively confirm HA's discovery shows the new name.
8. Confirm the name is per-device: it is NOT changed by a config export/import (Task-independent — device name lives in `SettingsStore`, not `DashConfig`).

---

## Self-Review

**1. Spec coverage:**
- Storage device-local in `SettingsStore`, key `device_name`, null = unset, not touched by `clearAuth()` — Task 1 Step 1. ✓
- Effective name computed in `App.kt`: `settings.deviceName ?: "Hearth (${Build.MODEL} <ID4>)"`, ID4 = last 4 chars of `Settings.Secure.ANDROID_ID`, "0000" fallback if null/blank — Task 1 Step 8(b). `Build`/ANDROID_ID only in `App.kt`. ✓
- `() -> String` lambdas to all four consumers: `NsdAdvertiser` ×2 (`_vaca._tcp` + `_wyoming._tcp`), `VacaOutgoing.info` call site, `SatelliteServer`→`SatelliteSession` (all three name sites incl. wake-section attribution) — Steps 2, 3, 4, 5, 8(d,e,f,g). ✓
- `SATELLITE_NAME` deleted; no external references (verified by grep) — Step 4. ✓
- `PUT /api/name` PIN-gated, clamp (trim → strip control <0x20 and 0x7F → collapse whitespace → truncate 40 → trim), empty/missing/null → store null = default, `200 {"name":"<effective>"}`, malformed → `400 {"error":...}` — Step 7 `handlePutName`/`clampDeviceName`, inside the `authed` block. ✓
- `GET /api/status` gains `deviceName` — Step 7. ✓
- Rename bounce: re-register `_vaca._tcp` advertiser if registered (`unregister()`+`register()`), restart VACA server (`vaca.stop()`/`start()`) and voice satellite (via `voiceRestartTick` → reactive collect re-registers `voiceNsd` + restarts `satellite`) — Step 8(b,g,h,i). Mechanism picked against real code and written explicitly. ✓
- `ConfigServer` stays Android-free: clamp uses only Kotlin stdlib; no new imports; tests are plain-JVM — Step 6/7. ✓
- Web UI: `deviceName` in status; Device card first after setup with `card-section`/`card-head`/stroke-1.7 tag SVG, title "Device", spec subtitle; body input + Rename `ghost` button + muted copy; on 200 write returned name into input + `setStatus("Renamed","ok")`, 401 → `showLogin()`, other → `setStatus(..., "err")` — Task 2 Steps 1–3. ✓
- Testing: plain-JVM `ConfigServer` tests (clamp incl. control chars/whitespace/40-char, empty & missing → setter null + default response, auth required, status includes deviceName), `node --check`, live tablet verify (rename, default shape, mDNS) — Task 1 Step 6, Task 2 Steps 4 & 6. ✓
- Out of scope (per-protocol names, HA-side auto-rename, Echo migration, exporting the name) — none added. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". Every code step shows complete final code; every test shows full method bodies. ✓

**3. Type/name consistency:**
- `deviceName: () -> String` and `setDeviceName: (String?) -> Unit` — defined identically in `ConfigServer` ctor (Step 7), the `ConfigServer(...)` in `App.kt` (Step 8c), and all three test setups (Step 6). ✓
- `NsdAdvertiser(context, port, serviceType = "_vaca._tcp.", name: () -> String)` — the `_vaca` site uses `name =` (serviceType defaulted), the `_wyoming` site passes serviceType positionally then `name =` (Step 8e,g). ✓
- `VacaOutgoing.info(appVersion, name)` — call sites in `App.kt` (Step 8d), `VacaMessagesTest` and `VacaServerTest` (Step 3) all pass two args. ✓
- `SatelliteSession(appVersion, name, localWake = false)` — constructed in `SatelliteServer` ×2 (Step 5) and `SatelliteSessionTest` (Step 4); `SatelliteServer(scope, port, appVersion, name, out)` — constructed in `App.kt` (Step 8f) and `SatelliteServerTest` ×2 (Step 5). ✓
- `App.deviceName()` / `App.applyDeviceName(String?)` / `vacaRunning` / `voiceRestartTick` — all defined in Step 8 and referenced only within `App.kt`. `combine` imported in Step 8(a). ✓
- `renderDevice()` / `renameDevice(input)` — defined in Task 2 Step 2, referenced in Step 2 (button) and Step 3 (`render()`); DOM ids `device-section`/`device` match between Task 2 Steps 1 and 2. ✓

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-14-configurable-device-name.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute the tasks in this session using executing-plans, with checkpoints before each commit.

**Which approach?**
