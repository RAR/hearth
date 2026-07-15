# Hearth View Select + Notify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HA→device `set-view` / `notify` / `notify-clear` actions and a device→HA `current_view` status to the Hearth app, and a matching `select` entity + `notify` platform + `hearth.notify`/`hearth.notify_clear` services to the `custom_components/hearth/` integration.

**Architecture:** The app parses the three new custom-event actions in a plain-JVM-testable unit (`DashActionParser`) and applies their effects in `AppDeps` (the composition root that already dispatches VACA actions). The current `DashView` is hoisted out of the dashboard composable into an `AppDeps` `MutableStateFlow`, written by both the rail and HA-initiated `set-view`, and mirrored to HA via `current_view` status on change and in `statusSnapshot()`. The integration adds two thin entity platforms (copying the `switch.py` listener/availability pattern) and two services (copying the `hearth.toast` registration pattern); no protocol-layer (`codec.py`/`client.py`) change.

**Tech Stack:** Kotlin 2.1.0 + kotlinx.serialization.json + Jetpack Compose (app); Python + Home Assistant entity/service APIs + voluptuous (integration). Plain-JVM JUnit4 for app tests; py_compile + JSON validation + the existing pytest suite for the integration.

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 (NEVER bump); minSdk 28.
- App dependency whitelist: NanoHTTPD 2.3.1 + tensorflow-lite:2.14.0 only — this feature adds NO dependencies on either side.
- Integration: `requirements: []` (zero pip). `codec.py`/`client.py` stay HA-import-free AND unmodified by this feature.
- UI / user-facing copy says "Hearth" (never "Echo Dashboard").
- Work directly on master. Every commit message ends with the trailer line:
  `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- App build gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` (433 tests currently green; this feature adds parser tests, so the total rises — the gate is "all green", not a fixed count).
- Integration gates: `python3 -m pytest tests/integration -q` (23 tests green); `python3 -m py_compile custom_components/hearth/*.py`; JSON validity via `python3 -c "import json; json.load(open(...))"`.
- `strings.json` and `translations/en.json` are byte-identical today and MUST stay byte-identical (verify with `diff`).
- Plain-JVM JUnit4 unit tests only (no Robolectric/instrumented). New Kotlin parsing/dispatch logic lives in a plain-JVM-testable unit with no `android.*` imports.
- NEVER reach the user's HA (the HA MCP bridge points at a foreign demo). HACS redownload + HA restart are the user's MORNING steps.

---

### Task 1: `DashActionParser` — plain-JVM action parsing + allowed-view policy

**Files:**
- Create: `app/src/main/java/com/rar/echodash/vaca/DashActionParser.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/DashActionParserTest.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.ui.DashView` (enum `HOME, LIGHTS, CLIMATE, MEDIA, CALENDAR, WEATHER, SOLAR, CAMERAS`); `com.rar.echodash.ui.railViews(panels: Panels, camerasConfigured: Boolean): List<DashView>`; `com.rar.echodash.config.Panels`, `com.rar.echodash.config.PanelConfig`. (Both `DashView` and `railViews` are already exercised by plain-JVM tests in `DashViewsTest`, so referencing them from a unit test is safe despite `DashViews.kt`'s Compose imports.)
- Produces (used by Task 2):
  - `DashActionParser.parseSetView(payload: JsonElement?): DashView?` — lowercase `view` name → `DashView`; `null` when missing/unparseable/unknown.
  - `DashActionParser.isViewAllowed(view: DashView, panels: Panels, camerasConfigured: Boolean): Boolean` — true iff `railViews(...)` contains `view`.
  - `DashActionParser.parseNotify(payload: JsonElement?): DashActionParser.NotifyCommand?` — `null` when title blank/missing; else `NotifyCommand(id: String?, title: String, message: String?, severity: String?, timeoutSeconds: Int?)`.
  - `DashActionParser.parseNotifyClear(payload: JsonElement?): DashActionParser.NotifyClear?` — `NotifyClear.All`, `NotifyClear.One(id: String)`, or `null` (neither present).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rar/echodash/vaca/DashActionParserTest.kt`:

```kotlin
package com.rar.echodash.vaca

import com.rar.echodash.config.PanelConfig
import com.rar.echodash.config.Panels
import com.rar.echodash.ui.DashView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashActionParserTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // --- set-view parsing ---

    @Test
    fun parseSetViewMapsLowercaseName() {
        assertEquals(DashView.CAMERAS, DashActionParser.parseSetView(json("""{"view":"cameras"}""")))
        assertEquals(DashView.HOME, DashActionParser.parseSetView(json("""{"view":"home"}""")))
    }

    @Test
    fun parseSetViewIsCaseInsensitiveAndTrims() {
        assertEquals(DashView.SOLAR, DashActionParser.parseSetView(json("""{"view":"  SOLAR "}""")))
    }

    @Test
    fun parseSetViewRejectsUnknownMissingOrNonObject() {
        assertNull(DashActionParser.parseSetView(json("""{"view":"bogus"}""")))
        assertNull(DashActionParser.parseSetView(json("""{}""")))
        assertNull(DashActionParser.parseSetView(json(""""cameras"""")))  // primitive, not object
        assertNull(DashActionParser.parseSetView(null))
    }

    // --- allowed-view policy (railViews oracle) ---

    @Test
    fun isViewAllowedFollowsRailViews() {
        val panels = Panels()  // defaults: HOME + lights/climate/media/weather/solar/calendar enabled, cameras off
        assertTrue(DashActionParser.isViewAllowed(DashView.HOME, panels, camerasConfigured = false))
        assertTrue(DashActionParser.isViewAllowed(DashView.LIGHTS, panels, camerasConfigured = false))
        // Cameras: off by default and not configured -> not allowed.
        assertFalse(DashActionParser.isViewAllowed(DashView.CAMERAS, panels, camerasConfigured = false))
        // A disabled panel is not allowed even though the view name is valid.
        val noLights = Panels(lights = PanelConfig(false, 2))
        assertFalse(DashActionParser.isViewAllowed(DashView.LIGHTS, noLights, camerasConfigured = false))
        // Cameras enabled AND configured -> allowed.
        val cams = Panels(cameras = PanelConfig(true, 6))
        assertTrue(DashActionParser.isViewAllowed(DashView.CAMERAS, cams, camerasConfigured = true))
    }

    // --- notify parsing (mirrors ConfigServer.handleNotify) ---

    @Test
    fun parseNotifyRejectsBlankOrMissingTitle() {
        assertNull(DashActionParser.parseNotify(json("""{"message":"hi"}""")))
        assertNull(DashActionParser.parseNotify(json("""{"title":"   "}""")))
        assertNull(DashActionParser.parseNotify(null))
    }

    @Test
    fun parseNotifyPassesFieldsThrough() {
        val cmd = DashActionParser.parseNotify(
            json("""{"id":"laundry","title":" Done ","message":"dry","severity":"warning","timeout":120}""")
        )!!
        assertEquals("laundry", cmd.id)
        assertEquals("Done", cmd.title)              // trimmed
        assertEquals("dry", cmd.message)
        assertEquals("warning", cmd.severity)        // raw string; store parses it
        assertEquals(120, cmd.timeoutSeconds)
    }

    @Test
    fun parseNotifyDropsNonPositiveTimeout() {
        val cmd = DashActionParser.parseNotify(json("""{"title":"A","timeout":0}"""))!!
        assertNull(cmd.timeoutSeconds)
        val cmd2 = DashActionParser.parseNotify(json("""{"title":"A","timeout":-5}"""))!!
        assertNull(cmd2.timeoutSeconds)
        val cmd3 = DashActionParser.parseNotify(json("""{"title":"A"}"""))!!
        assertNull(cmd3.timeoutSeconds)
    }

    // --- notify-clear parsing (mirrors ConfigServer.handleNotifyClear) ---

    @Test
    fun parseNotifyClearAllWins() {
        assertEquals(DashActionParser.NotifyClear.All,
            DashActionParser.parseNotifyClear(json("""{"all":true}""")))
        // all:true takes precedence even if an id is also present.
        assertEquals(DashActionParser.NotifyClear.All,
            DashActionParser.parseNotifyClear(json("""{"all":true,"id":"x"}""")))
    }

    @Test
    fun parseNotifyClearOneById() {
        assertEquals(DashActionParser.NotifyClear.One("laundry"),
            DashActionParser.parseNotifyClear(json("""{"id":" laundry "}""")))
    }

    @Test
    fun parseNotifyClearRejectsNeither() {
        assertNull(DashActionParser.parseNotifyClear(json("""{}""")))
        assertNull(DashActionParser.parseNotifyClear(json("""{"id":"   "}""")))
        assertNull(DashActionParser.parseNotifyClear(json("""{"all":false}""")))
        assertNull(DashActionParser.parseNotifyClear(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.vaca.DashActionParserTest"`
Expected: FAIL — compilation error, `Unresolved reference: DashActionParser`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/rar/echodash/vaca/DashActionParser.kt`:

```kotlin
package com.rar.echodash.vaca

import com.rar.echodash.config.Panels
import com.rar.echodash.ui.DashView
import com.rar.echodash.ui.railViews
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsing/policy for the HA->device actions this app owns (set-view / notify / notify-clear).
 * No android.* imports so it runs in plain-JVM unit tests; effects are applied by AppDeps. Field
 * semantics mirror the HTTP handlers in web/ConfigServer.kt so both paths behave identically.
 */
object DashActionParser {

    /** {view:"cameras"} -> DashView (case-insensitive, trimmed). Null when missing/unknown/non-object. */
    fun parseSetView(payload: JsonElement?): DashView? {
        val obj = payload as? JsonObject ?: return null
        val name = obj["view"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(Locale.US) ?: return null
        return DashView.entries.firstOrNull { it.name.lowercase(Locale.US) == name }
    }

    /** True when [view] is currently a rail destination (its panel enabled / cameras configured). */
    fun isViewAllowed(view: DashView, panels: Panels, camerasConfigured: Boolean): Boolean =
        railViews(panels, camerasConfigured).contains(view)

    data class NotifyCommand(
        val id: String?,
        val title: String,
        val message: String?,
        val severity: String?,
        val timeoutSeconds: Int?,
    )

    /** Mirrors ConfigServer.handleNotify: blank/missing title -> null (ignore); timeout <=0 -> null. */
    fun parseNotify(payload: JsonElement?): NotifyCommand? {
        val obj = payload as? JsonObject ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonPrimitive?.contentOrNull
        val severity = obj["severity"]?.jsonPrimitive?.contentOrNull
        val timeout = obj["timeout"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        return NotifyCommand(id, title, message, severity, timeout)
    }

    sealed interface NotifyClear {
        data object All : NotifyClear
        data class One(val id: String) : NotifyClear
    }

    /** Mirrors ConfigServer.handleNotifyClear: all==true wins; else a non-blank id; else null. */
    fun parseNotifyClear(payload: JsonElement?): NotifyClear? {
        val obj = payload as? JsonObject ?: return null
        if (obj["all"]?.jsonPrimitive?.booleanOrNull == true) return NotifyClear.All
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank()) return null
        return NotifyClear.One(id)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.vaca.DashActionParserTest"`
Expected: PASS (all 10 tests green, `BUILD SUCCESSFUL`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/vaca/DashActionParser.kt \
        app/src/test/java/com/rar/echodash/vaca/DashActionParserTest.kt
git commit -m "$(cat <<'EOF'
feat(app): DashActionParser for set-view/notify/notify-clear

Plain-JVM parsing + railViews-backed allowed-view policy for the new
HA->device actions; mirrors ConfigServer notify field semantics.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

### Task 2: Wire the actions + hoist `currentView` in `AppDeps`

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

**Interfaces:**
- Consumes (from Task 1): `DashActionParser.parseSetView/isViewAllowed/parseNotify/parseNotifyClear`, `DashActionParser.NotifyClear.All/One`.
- Consumes (existing, verified): `KioskController.onUserInteraction()`; `NightModeController.onUserInteraction(elapsedMs: Long)`; `PushNotificationStore.post(id, title, message, severity, timeoutSeconds, nowMs)`, `.clear(id)`, `.clearAll()`; `MediaBridge.handleAction(action, payload): Boolean`; `KioskController.handleAction(action, payload)`; `VacaServer.sendStatus(status: JsonObject)` (no-op when no session is active); `configStore.config.value.panels` and `.entities.cameras`.
- Produces: `AppDeps.currentView: MutableStateFlow<DashView>` — read by the composable, written by the rail (`onSelect`), the idle-return timer, and HA `set-view`.

**Note on testing:** this task is composition-root wiring (`AppDeps` has Android dependencies and no unit test, by repo convention). Its gate is the full app build + the entire test suite staying green, plus the live app-side verification in the run-brief. The pure logic it depends on is already covered by Task 1. No new unit test is added here.

- [ ] **Step 1: Add the `Locale` import**

In `app/src/main/java/com/rar/echodash/App.kt`, the existing imports include `import java.io.File` and `import java.time.ZoneId`. Add `Locale` alongside them:

```kotlin
import java.io.File
import java.time.ZoneId
import java.util.Locale
```

- [ ] **Step 2: Hoist `currentView` as a `MutableStateFlow`**

`DashView` is already imported (`import com.rar.echodash.ui.DashView`). In the `// --- VACA ---` section, immediately after `val nightMode = NightModeController()` (currently line 201), insert:

```kotlin
    val nightMode = NightModeController()

    /**
     * The dashboard view, hoisted out of the composable so HA (`set-view`) and the rail stay in
     * lockstep. The UI writes it (rail select / idle-return); a collector in [startVaca] reports
     * `current_view` to HA on change; [statusSnapshot] and `set-view` handling read/write it.
     */
    val currentView = MutableStateFlow(DashView.HOME)
```

- [ ] **Step 3: Route the new actions in `onAction`**

Replace the existing `onAction` override (currently lines 253-259):

```kotlin
            override fun onAction(action: String, payload: JsonElement?) {
                mainScope.launch {
                    if (!media.handleAction(action, payload)) {
                        kiosk.handleAction(action, payload)
                    }
                }
            }
```

with:

```kotlin
            override fun onAction(action: String, payload: JsonElement?) {
                mainScope.launch {
                    if (!handleDeviceAction(action, payload) &&
                        !media.handleAction(action, payload)
                    ) {
                        kiosk.handleAction(action, payload)
                    }
                }
            }
```

- [ ] **Step 4: Add `handleDeviceAction` + `wakeForHaView`, and replace `statusSnapshot`**

Replace the existing `statusSnapshot` method (currently lines 370-375):

```kotlin
    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_path", "dashboard")
        }
    }
```

with the following (the new dispatch/wake helpers plus the `current_view` snapshot):

```kotlin
    /**
     * Apply the HA->device actions this app owns (set-view / notify / notify-clear). Returns true
     * when the action was consumed (so [onAction] does not fall through to media/kiosk). Pure
     * parsing lives in [DashActionParser]; this method applies the effects on the main scope.
     */
    private fun handleDeviceAction(action: String, payload: JsonElement?): Boolean {
        // Parser calls are wrapped in runCatching: DashActionParser mirrors ConfigServer, whose
        // .jsonPrimitive access throws on object/array-valued fields — the HTTP path catches that
        // at the top-level parse, but here an arbitrary wire payload must never crash mainScope.
        when (action) {
            "set-view" -> {
                val view = runCatching { DashActionParser.parseSetView(payload) }.getOrNull()
                if (view == null) {
                    android.util.Log.i("AppDeps", "set-view ignored: unknown/invalid view $payload")
                    return true
                }
                val cfg = configStore.config.value
                if (!DashActionParser.isViewAllowed(view, cfg.panels, cfg.entities.cameras.isNotEmpty())) {
                    android.util.Log.i("AppDeps", "set-view ignored: panel disabled for $view")
                    return true
                }
                // A real, enabled view: switch to it (the composable re-arms idle on the change) and
                // wake / exit night mode exactly as a user touch would, even if it equals the current.
                currentView.value = view
                wakeForHaView()
                return true
            }
            "notify" -> {
                val cmd = runCatching { DashActionParser.parseNotify(payload) }.getOrNull()
                if (cmd == null) {
                    android.util.Log.i("AppDeps", "notify ignored: missing/blank title $payload")
                    return true
                }
                pushStore.post(
                    cmd.id, cmd.title, cmd.message, cmd.severity, cmd.timeoutSeconds,
                    System.currentTimeMillis(),
                )
                return true
            }
            "notify-clear" -> {
                when (val cmd = runCatching { DashActionParser.parseNotifyClear(payload) }.getOrNull()) {
                    DashActionParser.NotifyClear.All -> pushStore.clearAll()
                    is DashActionParser.NotifyClear.One -> pushStore.clear(cmd.id)
                    null -> android.util.Log.i("AppDeps", "notify-clear ignored: neither id nor all $payload")
                }
                return true
            }
            else -> return false
        }
    }

    /** Wake the screen / exit night mode for an HA-initiated view change, like a user touch. */
    private fun wakeForHaView() {
        kiosk.onUserInteraction()
        nightMode.onUserInteraction(SystemClock.elapsedRealtime())
    }

    private fun statusSnapshot(): JsonObject = buildJsonObject {
        putJsonObject("sensors") {
            put("orientation", "landscape")
            put("current_view", currentView.value.name.lowercase(Locale.US))
        }
    }
```

Add the import for `DashActionParser` alongside the other `com.rar.echodash.vaca.*` imports (e.g. right after `import com.rar.echodash.vaca.AndroidKioskDevice`):

```kotlin
import com.rar.echodash.vaca.DashActionParser
```

- [ ] **Step 5: Report `current_view` on change from `startVaca`**

Replace the existing `startVaca` method (currently lines 325-330):

```kotlin
    fun startVaca() {
        vaca.start()
        hearthNsd.register()
        lightSensor.start()
        vacaRunning = true
    }
```

with (adds a collector mirroring the light-sensor status-send pattern; `map`/`distinctUntilChanged` are already imported):

```kotlin
    fun startVaca() {
        vaca.start()
        hearthNsd.register()
        lightSensor.start()
        // Report the current view to HA whenever it changes (select entity mirror). sendStatus is a
        // no-op until a session connects, so launching before the first session is harmless.
        scope.launch {
            currentView
                .map { it.name.lowercase(Locale.US) }
                .distinctUntilChanged()
                .collect { view ->
                    vaca.sendStatus(buildJsonObject {
                        putJsonObject("sensors") { put("current_view", view) }
                    })
                }
        }
        vacaRunning = true
    }
```

- [ ] **Step 6: Bind the composable to `deps.currentView` (bidirectional hoist)**

In `EchoDashApp`, replace the local view state (currently line 471):

```kotlin
                    var view by remember { mutableStateOf(DashView.HOME) }
```

with a read-only collection of the hoisted flow:

```kotlin
                    val view by deps.currentView.collectAsStateWithLifecycle()
```

Then update the idle-return timer's reset lambda (currently line 475) — it can no longer assign the (now read-only) local `view`:

```kotlin
                    val idleTimer = remember(idleSeconds) {
                        IdleReturnTimer(uiScope, timeoutMs = idleSeconds * 1000L) { deps.currentView.value = DashView.HOME }
                    }
```

And update `DashboardShell`'s `onSelect` (currently lines 566-569) to write the hoisted flow:

```kotlin
                        onSelect = { v ->
                            deps.currentView.value = v
                            deps.kiosk.onUserInteraction()
                        },
```

(The existing `LaunchedEffect(idleTimer, view) { idleTimer.onViewChanged(view == DashView.HOME) }` at line 478 keeps working unchanged: it reads the collected `view`, so an actual view change — from the rail OR from `set-view` — still re-arms/cancels idle return. `collectAsStateWithLifecycle` is already imported at line 19; `remember`/`mutableStateOf`/`getValue` remain imported for other state in the file.)

- [ ] **Step 7: Run the full app gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; every unit test green (Task 1's `DashActionParserTest` included); debug APK assembles. No `current_path` remains: `grep -rn "current_path" app/src` returns nothing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rar/echodash/App.kt
git commit -m "$(cat <<'EOF'
feat(app): wire set-view/notify/notify-clear + hoist currentView

Hoist DashView into AppDeps.currentView (bidirectional with the rail),
route the new actions through DashActionParser + PushNotificationStore,
wake on HA set-view, and report sensors.current_view on change and in
statusSnapshot() (replacing the dead current_path field).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

### Task 3: Integration `select` entity (current view)

**Files:**
- Modify: `custom_components/hearth/const.py`
- Create: `custom_components/hearth/select.py`
- Modify: `custom_components/hearth/strings.json`
- Modify: `custom_components/hearth/translations/en.json`

**Interfaces:**
- Consumes (existing, verified): `HearthClient.add_listener(cb) -> unsub`, `.connected`, `.device_name`, `.app_version`, `.async_send_action(name, payload)`; status listener body shape `{"sensors": {"current_view": "home", "orientation": "landscape"}}`; the `switch.py` entity pattern.
- Produces: `const.ACTION_SET_VIEW = "set-view"`, `const.VIEW_OPTIONS` (the 8 lowercase view names in `DashView` order); `PLATFORMS` gains `"select"`; `HearthViewSelect` entity.

- [ ] **Step 1: Add the constants**

In `custom_components/hearth/const.py`, change the `PLATFORMS` line (currently line 9) to add `"select"`:

```python
PLATFORMS = ["media_player", "switch", "number", "button", "select"]
```

Then, after the existing `ACTION_TOAST = "toast-message"` line (in the "Action names." block), add:

```python
ACTION_SET_VIEW = "set-view"

# Dashboard views (lowercase DashView names, in enum declaration order) — the select options.
VIEW_OPTIONS = ["home", "lights", "climate", "media", "calendar", "weather", "solar", "cameras"]
```

- [ ] **Step 2: Create the select platform**

Create `custom_components/hearth/select.py`:

```python
"""Select entity for a Hearth device (current dashboard view)."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import ACTION_SET_VIEW, DOMAIN, MANUFACTURER, VIEW_OPTIONS


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthViewSelect(client, entry)])


class HearthViewSelect(SelectEntity):
    """The device's current dashboard view.

    Options are static (all eight views). The app ignores a `set-view` for a panel that is
    disabled in config, and reports its real view on the next status event, so the select
    snaps back on its own.
    """

    _attr_has_entity_name = True
    _attr_translation_key = "view"
    _attr_options = VIEW_OPTIONS

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._unsub = None
        self._attr_current_option: str | None = None
        self._attr_unique_id = f"{entry.unique_id}_view"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "status":
            sensors = data.get("sensors")
            if isinstance(sensors, dict) and "current_view" in sensors:
                view = sensors["current_view"]
                self._attr_current_option = view if view in self._attr_options else None
                self.async_write_ha_state()
        elif kind == "connection":
            self.async_write_ha_state()

    async def async_select_option(self, option: str) -> None:
        await self._client.async_send_action(ACTION_SET_VIEW, {"view": option})
```

- [ ] **Step 3: Add the select entity name to both translation files**

Overwrite `custom_components/hearth/strings.json` with (adds the `select` block under `entity`; everything else unchanged):

```json
{
  "config": {
    "step": {
      "user": {
        "title": "Add a Hearth device",
        "description": "Enter the device's host and port. The app serves one integration session at a time (newest wins): migrate from VACA per device by deleting its VACA entry first.",
        "data": {
          "host": "Host",
          "port": "Port"
        }
      },
      "zeroconf_confirm": {
        "title": "Add a Hearth device",
        "description": "Add {name} to Home Assistant? The app serves one integration session at a time (newest wins); if this device still has a VACA entry, delete it first."
      }
    },
    "error": {
      "cannot_connect": "Failed to connect"
    },
    "abort": {
      "already_configured": "Device is already configured"
    }
  },
  "entity": {
    "switch": {
      "screen": { "name": "Screen" },
      "auto_brightness": { "name": "Auto brightness" },
      "always_on": { "name": "Always on" },
      "screensaver": { "name": "Screen saver" },
      "dark_mode": { "name": "Dark mode" }
    },
    "number": {
      "brightness": { "name": "Brightness" },
      "screen_timeout": { "name": "Screen timeout" },
      "ducking_volume": { "name": "Ducking volume" }
    },
    "button": {
      "refresh": { "name": "Refresh" }
    },
    "select": {
      "view": { "name": "View" }
    }
  },
  "services": {
    "toast": {
      "name": "Show toast",
      "description": "Shows a short on-screen message on the Hearth device.",
      "fields": {
        "message": {
          "name": "Message",
          "description": "The text to show."
        }
      }
    }
  }
}
```

Then copy it verbatim to the translation file so the two stay byte-identical:

```bash
cp custom_components/hearth/strings.json custom_components/hearth/translations/en.json
```

- [ ] **Step 4: Run the integration gates**

Run:
```bash
python3 -m py_compile custom_components/hearth/*.py && \
python3 -c "import json; json.load(open('custom_components/hearth/strings.json')); json.load(open('custom_components/hearth/translations/en.json')); print('json ok')" && \
diff custom_components/hearth/strings.json custom_components/hearth/translations/en.json && echo "identical" && \
python3 -m pytest tests/integration -q
```
Expected: no py_compile output (success), `json ok`, `diff` prints nothing then `identical`, pytest reports `23 passed`.

- [ ] **Step 5: Commit**

```bash
git add custom_components/hearth/const.py custom_components/hearth/select.py \
        custom_components/hearth/strings.json custom_components/hearth/translations/en.json
git commit -m "$(cat <<'EOF'
feat(hearth): view select entity

One select per device (8 static view options) driven by
sensors.current_view; selecting sends set-view. Copies the switch.py
listener/availability lifecycle.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

### Task 4: Integration `notify` platform + `hearth.notify` / `hearth.notify_clear` services + manifest bump

**Files:**
- Modify: `custom_components/hearth/const.py`
- Create: `custom_components/hearth/notify.py`
- Modify: `custom_components/hearth/__init__.py`
- Modify: `custom_components/hearth/services.yaml`
- Modify: `custom_components/hearth/strings.json`
- Modify: `custom_components/hearth/translations/en.json`
- Modify: `custom_components/hearth/manifest.json`
- Modify: `README.md`

**Interfaces:**
- Consumes (existing, verified): `HearthClient.async_send_action`, `.connected`, listener lifecycle; the `hearth.toast` registration pattern in `async_setup` (`async_extract_referenced_entity_ids`, entity-registry → config-entry → client mapping); stock `NotifyEntity.async_send_message(self, message, title=None)`.
- Consumes (from Task 3): `PLATFORMS` (extended again to add `"notify"`).
- Produces: `const.ACTION_NOTIFY`, `ACTION_NOTIFY_CLEAR`, `SERVICE_NOTIFY`, `SERVICE_NOTIFY_CLEAR`, `ATTR_TITLE/ATTR_SEVERITY/ATTR_TIMEOUT/ATTR_ID/ATTR_ALL`; `HearthNotify` entity; `hearth.notify` + `hearth.notify_clear` services; manifest `version: "0.2.0"`.

- [ ] **Step 1: Add the constants**

In `custom_components/hearth/const.py`, change the `PLATFORMS` line (set in Task 3) to also add `"notify"`:

```python
PLATFORMS = ["media_player", "switch", "number", "button", "select", "notify"]
```

Then, after the `VIEW_OPTIONS = [...]` line added in Task 3, add:

```python
ACTION_NOTIFY = "notify"
ACTION_NOTIFY_CLEAR = "notify-clear"
```

And at the end of the file (after `ATTR_MESSAGE = "message"`), add:

```python
SERVICE_NOTIFY = "notify"
SERVICE_NOTIFY_CLEAR = "notify_clear"
ATTR_TITLE = "title"
ATTR_SEVERITY = "severity"
ATTR_TIMEOUT = "timeout"
ATTR_ID = "id"
ATTR_ALL = "all"
```

- [ ] **Step 2: Create the notify platform**

Create `custom_components/hearth/notify.py`:

```python
"""Notify entity for a Hearth device (stock notify platform)."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .client import HearthClient
from .const import ACTION_NOTIFY, DOMAIN, MANUFACTURER


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    client: HearthClient = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([HearthNotify(client, entry)])


class HearthNotify(NotifyEntity):
    """Posts a notification row to the device's notification area (title + message)."""

    _attr_has_entity_name = True
    _attr_translation_key = "notify"
    # Title support shipped with the notify entity platform's title parameter in HA 2024.6,
    # our floor (hacs.json) — without this flag core drops/rejects titles for this entity.
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, client: HearthClient, entry: ConfigEntry) -> None:
        self._client = client
        self._unsub = None
        self._attr_unique_id = f"{entry.unique_id}_notify"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=MANUFACTURER,
            name=client.device_name or entry.title,
            sw_version=client.app_version,
        )

    @property
    def available(self) -> bool:
        return self._client.connected

    async def async_added_to_hass(self) -> None:
        self._unsub = self._client.add_listener(self._on_event)

    async def async_will_remove_from_hass(self) -> None:
        if self._unsub is not None:
            self._unsub()

    @callback
    def _on_event(self, kind: str, data: dict) -> None:
        if kind == "connection":
            self.async_write_ha_state()

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        await self._client.async_send_action(
            ACTION_NOTIFY, {"title": title or "Notification", "message": message}
        )
```

- [ ] **Step 3: Register the services**

Overwrite `custom_components/hearth/__init__.py` (adds a shared `_clients_for` helper reused by all three handlers, plus the two new services; `toast` behaviour is unchanged):

```python
"""The Hearth integration."""

from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryNotReady, ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.service import async_extract_referenced_entity_ids
from homeassistant.helpers.typing import ConfigType

from .client import HearthClient
from .const import (
    ACTION_NOTIFY,
    ACTION_NOTIFY_CLEAR,
    ACTION_TOAST,
    ATTR_ALL,
    ATTR_ID,
    ATTR_MESSAGE,
    ATTR_SEVERITY,
    ATTR_TIMEOUT,
    ATTR_TITLE,
    DOMAIN,
    PLATFORMS,
    SERVICE_NOTIFY,
    SERVICE_NOTIFY_CLEAR,
    SERVICE_TOAST,
)

# Shared target selector fields for all hearth.* services.
_TARGET_FIELDS = {
    vol.Optional("entity_id"): cv.comp_entity_ids,
    vol.Optional("device_id"): vol.All(cv.ensure_list, [cv.string]),
    vol.Optional("area_id"): vol.All(cv.ensure_list, [cv.string]),
}

SERVICE_TOAST_SCHEMA = vol.Schema({vol.Required(ATTR_MESSAGE): cv.string, **_TARGET_FIELDS})

SERVICE_NOTIFY_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_TITLE): cv.string,
        vol.Optional(ATTR_MESSAGE): cv.string,
        vol.Optional(ATTR_SEVERITY): vol.In(["info", "warning", "critical"]),
        vol.Optional(ATTR_TIMEOUT): vol.Coerce(int),
        vol.Optional(ATTR_ID): cv.string,
        **_TARGET_FIELDS,
    }
)

SERVICE_NOTIFY_CLEAR_SCHEMA = vol.Schema(
    {
        vol.Optional(ATTR_ID): cv.string,
        vol.Optional(ATTR_ALL): cv.boolean,
        **_TARGET_FIELDS,
    }
)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the global hearth.* services once."""

    def _clients_for(call: ServiceCall) -> list[HearthClient]:
        ent_reg = er.async_get(hass)
        selected = async_extract_referenced_entity_ids(hass, call)
        entity_ids = selected.referenced | selected.indirectly_referenced
        entry_ids: set[str] = set()
        for entity_id in entity_ids:
            entry = ent_reg.async_get(entity_id)
            if entry is not None and entry.config_entry_id:
                entry_ids.add(entry.config_entry_id)
        clients = hass.data.get(DOMAIN, {})
        return [clients[e] for e in entry_ids if e in clients]

    async def _handle_toast(call: ServiceCall) -> None:
        message = call.data[ATTR_MESSAGE]
        for client in _clients_for(call):
            await client.async_send_action(ACTION_TOAST, {"message": message})

    async def _handle_notify(call: ServiceCall) -> None:
        payload: dict = {"title": call.data[ATTR_TITLE]}
        if ATTR_MESSAGE in call.data:
            payload["message"] = call.data[ATTR_MESSAGE]
        if ATTR_SEVERITY in call.data:
            payload["severity"] = call.data[ATTR_SEVERITY]
        if ATTR_TIMEOUT in call.data:
            payload["timeout"] = call.data[ATTR_TIMEOUT]
        if ATTR_ID in call.data:
            payload["id"] = call.data[ATTR_ID]
        for client in _clients_for(call):
            await client.async_send_action(ACTION_NOTIFY, payload)

    async def _handle_notify_clear(call: ServiceCall) -> None:
        notify_id = call.data.get(ATTR_ID)
        clear_all = call.data.get(ATTR_ALL, False)
        # Exactly one of id / all — validated here (the schema can't express XOR).
        if bool(notify_id) == bool(clear_all):
            raise ServiceValidationError("Provide exactly one of 'id' or 'all'.")
        payload = {"all": True} if clear_all else {"id": notify_id}
        for client in _clients_for(call):
            await client.async_send_action(ACTION_NOTIFY_CLEAR, payload)

    hass.services.async_register(DOMAIN, SERVICE_TOAST, _handle_toast, schema=SERVICE_TOAST_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_NOTIFY, _handle_notify, schema=SERVICE_NOTIFY_SCHEMA)
    hass.services.async_register(
        DOMAIN, SERVICE_NOTIFY_CLEAR, _handle_notify_clear, schema=SERVICE_NOTIFY_CLEAR_SCHEMA
    )
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Hearth from a config entry."""
    client = HearthClient(entry.data[CONF_HOST], entry.data[CONF_PORT])
    await client.async_start()
    try:
        await client.async_wait_connected(15)
    except TimeoutError as err:
        await client.async_stop()
        raise ConfigEntryNotReady(f"Could not connect to {entry.title}") from err

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        client = hass.data[DOMAIN].pop(entry.entry_id)
        await client.async_stop()
    return unloaded
```

- [ ] **Step 4: Extend `services.yaml`**

Append to `custom_components/hearth/services.yaml` (after the existing `toast:` block):

```yaml

notify:
  name: Notify
  description: Posts a notification to the Hearth device's notification area.
  target:
    entity:
      integration: hearth
    device:
      integration: hearth
  fields:
    title:
      name: Title
      description: The notification title (required).
      required: true
      example: Laundry done
      selector:
        text:
    message:
      name: Message
      description: Optional body text.
      example: The dryer has finished.
      selector:
        text:
    severity:
      name: Severity
      description: Visual severity of the notification row.
      selector:
        select:
          options:
            - info
            - warning
            - critical
    timeout:
      name: Timeout
      description: Seconds until the notification auto-dismisses. Omit or 0 for persistent.
      selector:
        number:
          min: 0
          max: 86400
          unit_of_measurement: seconds
          mode: box
    id:
      name: ID
      description: Stable id; re-posting the same id replaces that row.
      example: laundry
      selector:
        text:

notify_clear:
  name: Clear notification
  description: Clears one notification by id, or all notifications. Provide exactly one of id or all.
  target:
    entity:
      integration: hearth
    device:
      integration: hearth
  fields:
    id:
      name: ID
      description: The id to clear.
      example: laundry
      selector:
        text:
    all:
      name: All
      description: Clear all notifications on the device.
      selector:
        boolean:
```

- [ ] **Step 5: Extend both translation files**

Overwrite `custom_components/hearth/strings.json` with (adds the `notify` entity block and the `notify` + `notify_clear` services; the rest matches the Task 3 file):

```json
{
  "config": {
    "step": {
      "user": {
        "title": "Add a Hearth device",
        "description": "Enter the device's host and port. The app serves one integration session at a time (newest wins): migrate from VACA per device by deleting its VACA entry first.",
        "data": {
          "host": "Host",
          "port": "Port"
        }
      },
      "zeroconf_confirm": {
        "title": "Add a Hearth device",
        "description": "Add {name} to Home Assistant? The app serves one integration session at a time (newest wins); if this device still has a VACA entry, delete it first."
      }
    },
    "error": {
      "cannot_connect": "Failed to connect"
    },
    "abort": {
      "already_configured": "Device is already configured"
    }
  },
  "entity": {
    "switch": {
      "screen": { "name": "Screen" },
      "auto_brightness": { "name": "Auto brightness" },
      "always_on": { "name": "Always on" },
      "screensaver": { "name": "Screen saver" },
      "dark_mode": { "name": "Dark mode" }
    },
    "number": {
      "brightness": { "name": "Brightness" },
      "screen_timeout": { "name": "Screen timeout" },
      "ducking_volume": { "name": "Ducking volume" }
    },
    "button": {
      "refresh": { "name": "Refresh" }
    },
    "select": {
      "view": { "name": "View" }
    },
    "notify": {
      "notify": { "name": "Notify" }
    }
  },
  "services": {
    "toast": {
      "name": "Show toast",
      "description": "Shows a short on-screen message on the Hearth device.",
      "fields": {
        "message": {
          "name": "Message",
          "description": "The text to show."
        }
      }
    },
    "notify": {
      "name": "Notify",
      "description": "Posts a notification to the Hearth device's notification area.",
      "fields": {
        "title": {
          "name": "Title",
          "description": "The notification title (required)."
        },
        "message": {
          "name": "Message",
          "description": "Optional body text."
        },
        "severity": {
          "name": "Severity",
          "description": "Visual severity of the notification row."
        },
        "timeout": {
          "name": "Timeout",
          "description": "Seconds until the notification auto-dismisses. Omit or 0 for persistent."
        },
        "id": {
          "name": "ID",
          "description": "Stable id; re-posting the same id replaces that row."
        }
      }
    },
    "notify_clear": {
      "name": "Clear notification",
      "description": "Clears one notification by id, or all notifications. Provide exactly one of id or all.",
      "fields": {
        "id": {
          "name": "ID",
          "description": "The id to clear."
        },
        "all": {
          "name": "All",
          "description": "Clear all notifications on the device."
        }
      }
    }
  }
}
```

Then copy it verbatim to the translation file:

```bash
cp custom_components/hearth/strings.json custom_components/hearth/translations/en.json
```

- [ ] **Step 6: Bump the manifest version**

In `custom_components/hearth/manifest.json`, change the version line (currently line 12):

```json
  "version": "0.2.0",
```

- [ ] **Step 7: Update the README entity/service list**

In `README.md`, replace the "Each device gets" sentence (currently line 27):

```markdown
Each device gets: a **media player** (URLs/radio/Music Assistant via ExoPlayer, plus `announce` — TTS ducks the music instead of stopping it), **switches** for screen / auto-brightness / always-on / screensaver / dark mode, **numbers** for brightness / screen timeout / ducking volume, a **refresh button**, and a **`hearth.toast`** service.
```

with:

```markdown
Each device gets: a **media player** (URLs/radio/Music Assistant via ExoPlayer, plus `announce` — TTS ducks the music instead of stopping it), **switches** for screen / auto-brightness / always-on / screensaver / dark mode, **numbers** for brightness / screen timeout / ducking volume, a **refresh button**, a **View** select that mirrors and drives the on-screen dashboard view, a **notify** entity, and the **`hearth.toast`**, **`hearth.notify`** (title / message / severity / timeout / id), and **`hearth.notify_clear`** services.
```

- [ ] **Step 8: Run the integration gates**

Run:
```bash
python3 -m py_compile custom_components/hearth/*.py && \
python3 -c "import json; json.load(open('custom_components/hearth/strings.json')); json.load(open('custom_components/hearth/translations/en.json')); json.load(open('custom_components/hearth/manifest.json')); print('json ok')" && \
diff custom_components/hearth/strings.json custom_components/hearth/translations/en.json && echo "identical" && \
python3 -c "import yaml; yaml.safe_load(open('custom_components/hearth/services.yaml')); print('yaml ok')" && \
python3 -m pytest tests/integration -q
```
Expected: no py_compile output, `json ok`, `diff` prints nothing then `identical`, `yaml ok`, pytest `23 passed`. (If PyYAML is unavailable, skip the yaml line — the json/py_compile/pytest gates still apply.)

- [ ] **Step 9: Commit**

```bash
git add custom_components/hearth/const.py custom_components/hearth/notify.py \
        custom_components/hearth/__init__.py custom_components/hearth/services.yaml \
        custom_components/hearth/strings.json custom_components/hearth/translations/en.json \
        custom_components/hearth/manifest.json README.md
git commit -m "$(cat <<'EOF'
feat(hearth): notify platform + notify/notify_clear services, v0.2.0

Stock NotifyEntity per device plus hearth.notify (title/message/severity/
timeout/id) and hearth.notify_clear (id XOR all). Shared _clients_for
helper for all hearth.* services. PLATFORMS gains select+notify; manifest
0.2.0 so HACS offers the update; README entity/service list updated.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Final whole-feature gate (run once, after Task 4)

- [ ] Run the full app gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`, all tests green.
- [ ] Run the integration gates (Task 4 Step 8 command) → clean, `23 passed`.
- [ ] `grep -rn "current_path" app/src custom_components` → no matches (dead field fully removed).
- [ ] Live app-side verification per the run-brief / spec "Testing & verification" (throwaway scratchpad script importing `client.py` via the `hearth_proto` trick, against the tablet `10.75.0.183:10700`; screenshots via `adb exec-out screencap`): `set-view cameras` switches + wakes and the status event carries `current_view: "cameras"`; `set-view` for a disabled panel is ignored; `notify` posts a row, re-posting the same id replaces it, `notify-clear` removes it; a fresh session's snapshot carries `current_view`. (Evicting HA's live session is expected and self-heals.)

---

## Spec Ambiguities Resolved

1. **Does `set-view` wake for unknown or disabled-panel views?** The spec says a valid `set-view` "Always wakes the screen / exits night mode and re-arms the idle timer." Resolution: **wake fires only for a `set-view` that names a real, currently-enabled `DashView`** (including the current one — a same-view select still wakes, matching a user re-tapping the current rail item). Unknown/unparseable views and views whose panel is disabled are logged and fully ignored with **no** wake (there is nothing meaningful to show, and the select entity snaps back on the next status report). Idle re-arm happens only on an actual view change, via the existing `LaunchedEffect(idleTimer, view)`.

2. **`set-view` to the current view.** Per the run-brief's explicit guidance, wake still fires (via kiosk + night mode) but idle re-arm only occurs on an actual change. The implementation always assigns `currentView.value = view` (a no-op emit when unchanged, so `distinctUntilChanged` suppresses a duplicate status send and the composable's `LaunchedEffect(idleTimer, view)` does not re-fire) and always calls `wakeForHaView()`.

3. **`notify-clear` with both `id` and `all`.** The wire spec lists `{id}` OR `{all:true}`. App side mirrors `ConfigServer.handleNotifyClear`: `all==true` wins even if an id is also present. The `hearth.notify_clear` **service** is stricter per the spec ("exactly one required — validated in the handler"): it raises `ServiceValidationError` when both or neither are provided, before any action is sent.

4. **Select option validation.** `sensors.current_view` values not in `VIEW_OPTIONS` set `current_option` to `None` rather than raising, so a malformed/newer report never crashes the entity.
