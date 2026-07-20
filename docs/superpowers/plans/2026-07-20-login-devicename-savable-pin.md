# Web-Config Login: Device Name + Browser-Savable PIN + Custom PIN — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the web-config login friendlier — show the device name on the login page, let the browser save/autofill the PIN, and let the user set a custom PIN or reset to a random one, with the changed PIN reflected on the device screen without an app restart.

**Architecture:** Three layers. (1) A pure Kotlin validator `isValidCustomPin` in `Pin.kt`. (2) `ConfigServer` (NanoHTTPD) gains a pre-auth `GET /api/hello`, gated `PUT /api/pin` + `POST /api/pin/reset`, and a `pin` field on `/api/status`; `App.kt` makes the PIN source live (`settings.configPin` read fresh, published through a `MutableStateFlow` so the on-device Setup/Home PIN display updates without restart). (3) The static web assets (`index.html` / `app.js` / `style.css`) gain a device-name username field, a masked PIN input with an eye toggle, and a Device-page PIN card.

**Tech Stack:** Kotlin + NanoHTTPD (embedded LAN config server), kotlinx.serialization JSON, JUnit4 (plain-JVM unit tests via an OkHttp round-trip harness), Jetpack Compose (device screens only, not unit-tested), plain HTML/CSS/JS for the config page (validated with `node --check` only). Gradle build (`:app`).

## Global Constraints

- **Gate before EVERY commit** (RC captured immediately, output redirected to a scratchpad log, NEVER piped to `tail`/`head`):
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"` — require `RC=0`.
- **PLUS** `node --check app/src/main/assets/config/app.js` must pass before any commit that touches `app.js`.
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- No new dependencies. Plain-JVM JUnit4 tests only. HTML/JS is NOT unit-tested beyond `node --check`. Compose is not touched (login/config are HTML/JS).
- `applicationId` / package names are NOT touched by this feature.
- Inspect `gate.log` with `grep` (e.g. `grep -nE "BUILD (SUCCESSFUL|FAILED)|unresolved|FAILED" <log>`); never `tail`/`head` the gate command itself.

## File Structure

- `app/src/main/java/com/rar/hearth/web/Pin.kt` — **Modify.** Add top-level `isValidCustomPin(s: String): Boolean` (`^\d{4,8}$`). No other change.
- `app/src/test/java/com/rar/hearth/web/PinTest.kt` — **Modify.** Add JUnit4 cases for `isValidCustomPin`.
- `app/src/main/java/com/rar/hearth/web/ConfigServer.kt` — **Modify.** New constructor params `setPin` / `resetPin` (defaulted); pre-auth `GET /api/hello`; gated `PUT /api/pin` + `POST /api/pin/reset`; `pin` added to `/api/status`.
- `app/src/main/java/com/rar/hearth/App.kt` — **Modify.** `configPin()` reads `settings.configPin` fresh; new `pinState` StateFlow; `applyConfigPin` / `resetConfigPin`; wire `setPin` / `resetPin` into `ConfigServer`; collect `pinState` in `HearthApp` and feed the Setup screen + dashboard PIN display so a change shows without restart.
- `app/src/test/java/com/rar/hearth/web/ConfigServerTest.kt` — **Modify.** Spies for `setPin` / `resetPin`, a live `currentPin`, and tests for hello / pin / pin-reset / status-pin.
- `app/src/main/assets/config/index.html` — **Modify.** Login card (device-name username field, masked PIN input + eye toggle, maxlength 8); Device-page PIN card section.
- `app/src/main/assets/config/app.js` — **Modify.** `/api/hello` fetch + username populate; eye toggle; PIN change/reset handlers + client validation; `renderPin()` wired into `render()`.
- `app/src/main/assets/config/style.css` — **Modify.** Device-name heading field, eye toggle, PIN-field layout (ember theme).

**No files are created.** `SetupCoordinator.kt` is **not** touched: it never displays the PIN. The stale PIN on the device screen came from `App.kt` caching `deps.configPin()` inside `remember { }`; that is fixed by the `pinState` StateFlow in Task 2 (see Task 2 Steps 5-8).

---

### Task 1: `isValidCustomPin` validator (pure function, full TDD cycle)

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/web/Pin.kt`
- Test: `app/src/test/java/com/rar/hearth/web/PinTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun isValidCustomPin(s: String): Boolean` — top-level in package `com.rar.hearth.web`. Returns `true` iff `s` is exactly 4–8 ASCII digits. Consumed by `ConfigServer.handlePutPin` in Task 2 (same package, no import needed).

- [ ] **Step 1: Write the failing tests**

Add these two `@Test` methods inside the existing `class PinTest` in `app/src/test/java/com/rar/hearth/web/PinTest.kt` (after `notifyTokenIs32LowercaseHex`, before the closing `}`), and add the `assertFalse` import next to the existing `assertEquals` / `assertTrue` imports:

```kotlin
import org.junit.Assert.assertFalse
```

```kotlin
    @Test
    fun customPinAcceptsFourToEightDigits() {
        assertTrue(isValidCustomPin("1234"))
        assertTrue(isValidCustomPin("123456"))
        assertTrue(isValidCustomPin("12345678"))
    }

    @Test
    fun customPinRejectsWrongLengthOrNonDigits() {
        assertFalse(isValidCustomPin("123"))        // too short (3)
        assertFalse(isValidCustomPin("123456789"))  // too long (9)
        assertFalse(isValidCustomPin("12ab34"))     // letters
        assertFalse(isValidCustomPin(""))           // empty
        assertFalse(isValidCustomPin("12 34"))      // embedded space
    }
```

- [ ] **Step 2: Run the gate to verify it fails (red)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=1`. Confirm the red is the missing function, not something else:
```bash
grep -c "unresolved reference: isValidCustomPin" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: a count `> 0`.

- [ ] **Step 3: Implement `isValidCustomPin`**

In `app/src/main/java/com/rar/hearth/web/Pin.kt`, insert the regex + function **between** the `generatePin` function and the `generateNotifyToken` doc comment (i.e. after the closing line of `generatePin` and before `/**` of `generateNotifyToken`):

```kotlin
/** Matches a user-chosen config PIN: 4–8 ASCII digits, nothing else. `\d` here is `[0-9]` only. */
private val CUSTOM_PIN_REGEX = Regex("^\\d{4,8}$")

/** True when [s] is a valid custom PIN (exactly 4–8 ASCII digits). Used to validate PUT /api/pin. */
fun isValidCustomPin(s: String): Boolean = CUSTOM_PIN_REGEX.matches(s)
```

- [ ] **Step 4: Run the gate to verify it passes (green)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=0`. Confirm:
```bash
grep -nE "BUILD SUCCESSFUL" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: one `BUILD SUCCESSFUL` line. (No `node --check` — this task does not touch `app.js`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/hearth/web/Pin.kt app/src/test/java/com/rar/hearth/web/PinTest.kt
git commit -m "feat(web): add isValidCustomPin (4-8 digit custom PIN validator)" -m "Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 2: Backend — hello / pin / pin-reset endpoints + live PIN source

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/web/ConfigServer.kt`
- Modify: `app/src/main/java/com/rar/hearth/App.kt`
- Test: `app/src/test/java/com/rar/hearth/web/ConfigServerTest.kt`

**Interfaces:**
- Consumes: `isValidCustomPin(s: String): Boolean` (Task 1, same package); existing `generatePin()`; existing helpers `ok(...)`, `error(...)`, `buildJsonObject { put(...) }`, `readBody(session)`, `ConfigJson.json.parseToJsonElement`, `jsonPrimitive.contentOrNull`, `authed(session)`.
- Produces (relied on by Task 3):
  - `GET /api/hello` (pre-auth) → `200 {"name":<deviceName>,"configured":<bool>}`.
  - `PUT /api/pin` (gated) body `{"pin":"1234"}` → `200 {"pin":<newPin>}` on valid; `400 {"error":"invalid pin"}` on invalid; `401` without a session. Does not clear the session cookie.
  - `POST /api/pin/reset` (gated) → `200 {"pin":<newPin>}`; `401` without a session.
  - `GET /api/status` (gated) now includes `"pin":<currentPin>`.
  - New `ConfigServer` constructor params `setPin: (String) -> Unit = {}` and `resetPin: () -> String = { "" }`.
  - `AppDeps.pinState: MutableStateFlow<String>` (current PIN for the device display).

- [ ] **Step 1: Write the failing tests (red-first via the ConfigServer round-trip harness)**

In `app/src/test/java/com/rar/hearth/web/ConfigServerTest.kt`:

(a) Add two fields to the class, next to the existing `private var customName: String? = null` / `private val setNameCalls = mutableListOf<String?>()` declarations:

```kotlin
    private var currentPin = "123456"
    private val setPinCalls = mutableListOf<String>()
```

(b) In `setUp()`'s `ConfigServer(...)` construction, change the `pin` argument from `pin = { "123456" },` to read the live holder, and add the two new spies immediately after the `setDeviceName = ...` line:

```kotlin
            pin = { currentPin },
```

```kotlin
            setPin = { p -> setPinCalls += p; currentPin = p },
            resetPin = { currentPin = "654321"; currentPin },
```

(c) Add these tests inside `class ConfigServerTest` (e.g. after `statusIncludesDeviceName`):

```kotlin
    @Test
    fun helloReturnsNameAndConfiguredPreAuth() {
        // No cookie: /api/hello is pre-auth.
        http.newCall(Request.Builder().url("$base/api/hello").build()).execute().use { r ->
            assertEquals(200, r.code)
            val body = r.body!!.string()
            assertTrue(body.contains("\"name\":\"$defaultName\""))
            assertTrue(body.contains("\"configured\":false"))
        }
    }

    @Test
    fun statusIncludesPin() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"pin\":\"123456\""))
            }
    }

    @Test
    fun putPinValidChangesPinAndReturnsIt() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/pin").header("Cookie", cookie)
            .put("""{"pin":"4321"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("""{"pin":"4321"}""", r.body!!.string())
            }
        assertEquals(listOf("4321"), setPinCalls)
        assertEquals("4321", currentPin)
        // The new PIN authenticates without a restart (the pin lambda reads currentPin live).
        login("4321").use { assertEquals(200, it.code) }
    }

    @Test
    fun putPinInvalidReturns400AndLeavesPinUntouched() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/pin").header("Cookie", cookie)
            .put("""{"pin":"12"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(400, r.code)
                assertTrue(r.body!!.string().contains("\"error\":\"invalid pin\""))
            }
        assertTrue(setPinCalls.isEmpty())
        assertEquals("123456", currentPin)
    }

    @Test
    fun putPinRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/pin")
            .put("""{"pin":"4321"}""".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
        assertTrue(setPinCalls.isEmpty())
    }

    @Test
    fun pinResetReturnsNewPinAndPersists() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/pin/reset").header("Cookie", cookie)
            .post("{}".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("""{"pin":"654321"}""", r.body!!.string())
            }
        assertEquals("654321", currentPin)
    }

    @Test
    fun pinResetRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/pin/reset")
            .post("{}".toRequestBody(json)).build()).execute().use { r ->
                assertEquals(401, r.code)
            }
    }
```

- [ ] **Step 2: Run the gate to verify it fails (red)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=1`. Confirm the red is the missing constructor params (the spies reference `setPin` / `resetPin`, which don't exist yet):
```bash
grep -nE "cannot find a parameter with this name: (setPin|resetPin)|no value passed" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: at least one match.

- [ ] **Step 3: Add the `ConfigServer` constructor params, routes, and handlers**

In `app/src/main/java/com/rar/hearth/web/ConfigServer.kt`:

(a) Add the two new params to the constructor, immediately after `private val setDeviceName: (String?) -> Unit,`:

```kotlin
    private val setDeviceName: (String?) -> Unit,
    private val setPin: (String) -> Unit = {},
    private val resetPin: () -> String = { "" },
```

(b) In `route()`, register `GET /api/hello` **above** the auth gate — add it directly after the `handleNotifyClear` pre-auth line and before `if (uri.startsWith("/api/")) {`:

```kotlin
        if (uri == "/api/notify/clear" && method == Method.POST) return handleNotifyClear(session)
        if (uri == "/api/hello" && method == Method.GET) return handleHello()
```

(c) In the gated `when { ... }` block of `route()`, add the two PIN routes next to the `/api/name` route:

```kotlin
                uri == "/api/name" && method == Method.PUT -> handlePutName(session)
                uri == "/api/pin" && method == Method.PUT -> handlePutPin(session)
                uri == "/api/pin/reset" && method == Method.POST -> handlePinReset()
```

(d) Add `pin` to `handleStatus()` (the JSON object built there), directly after the `deviceName` line:

```kotlin
            put("deviceName", deviceName())
            put("pin", pin())
            put("sendspin", sendspinStatus())
```

(e) Add the three new handlers. Place them immediately after `handlePutName(...)` / `clampDeviceName(...)` (before `bearerAuthed`):

```kotlin
    /** Pre-auth: the device name + configured flag for the login card. Name is not a secret. */
    private fun handleHello(): Response =
        ok(buildJsonObject {
            put("name", deviceName())
            put("configured", configured())
        }.toString())

    /** Gated: set a custom 4–8 digit PIN. Does NOT clear the session cookie. */
    private fun handlePutPin(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }
            .getOrNull() ?: return error(Response.Status.BAD_REQUEST, "invalid request")
        val raw = obj["pin"]?.jsonPrimitive?.contentOrNull ?: ""
        if (!isValidCustomPin(raw)) return error(Response.Status.BAD_REQUEST, "invalid pin")
        setPin(raw)
        return ok(buildJsonObject { put("pin", raw) }.toString())
    }

    /** Gated: replace the PIN with a fresh random one and return it. */
    private fun handlePinReset(): Response {
        val newPin = resetPin()
        return ok(buildJsonObject { put("pin", newPin) }.toString())
    }
```

`isValidCustomPin` is top-level in package `com.rar.hearth.web` (same package as `ConfigServer`), so no import is needed.

- [ ] **Step 4: Run the gate to verify the backend tests pass (green)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=0` (App.kt still compiles because `setPin`/`resetPin` are defaulted; the seven new `ConfigServerTest` tests pass). Confirm:
```bash
grep -nE "BUILD SUCCESSFUL" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: one `BUILD SUCCESSFUL`.

- [ ] **Step 5: Make the PIN source live in `App.kt` (`configPin()` + `pinState`)**

In `app/src/main/java/com/rar/hearth/App.kt`:

(a) Add `pinState` right after the `ensuredNotifyToken` `by lazy { ... }` block and before `val pushStore = ...`. It is seeded by calling `configPin()` (safe here: `settings` and the `ensuredPin` lazy delegate are both declared above this point):

```kotlin
    private val ensuredNotifyToken: String by lazy {
        settings.notifyToken ?: generateNotifyToken().also { settings.notifyToken = it }
    }
    /**
     * Live config PIN for the on-device display (Setup screen + Home menu). Seeded from the
     * persisted/first-boot PIN and re-published by [applyConfigPin] / [resetConfigPin] so a PIN
     * change made in the web config shows on the device screen without an app restart.
     */
    val pinState = MutableStateFlow(configPin())
    val pushStore = com.rar.hearth.notify.PushNotificationStore()
```

(b) Wire `setPin` / `resetPin` into the `ConfigServer(...)` construction, immediately after the `setDeviceName = { applyDeviceName(it) },` line:

```kotlin
        setDeviceName = { applyDeviceName(it) },
        setPin = { applyConfigPin(it) },
        resetPin = { resetConfigPin() },
```

(c) Change `configPin()` to read `settings.configPin` fresh (still generating once on first boot via `ensuredPin`):

```kotlin
    /** The config PIN: a custom/persisted value if set, else the generate-once default. */
    fun configPin(): String = settings.configPin ?: ensuredPin
```

(d) Add the two new private methods immediately after `applyDeviceName(...)`:

```kotlin
    /** Persist a user-chosen PIN and publish it to the live on-device display. */
    private fun applyConfigPin(pin: String) {
        settings.configPin = pin
        pinState.value = pin
    }

    /** Generate a fresh random PIN, persist + publish it, and return it. */
    private fun resetConfigPin(): String {
        val pin = generatePin()
        settings.configPin = pin
        pinState.value = pin
        return pin
    }
```

`MutableStateFlow` (line ~112) and `generatePin` (line ~101) are already imported in `App.kt`; no new imports.

- [ ] **Step 6: Feed the live PIN into the on-device display (`HearthApp`)**

In `app/src/main/java/com/rar/hearth/App.kt`, inside `@Composable fun HearthApp(deps: AppDeps)`:

(a) Collect the PIN once, right after the existing `val connState by deps.ws.connectionState.collectAsStateWithLifecycle()` line:

```kotlin
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()
    val configPin by deps.pinState.collectAsStateWithLifecycle()
```

(b) In the `Screen.Setup ->` branch, replace the cached PIN with the collected one:

```kotlin
                Screen.Setup -> SetupScreen(
                    configUrl = remember { deps.configUrl() },
                    configPin = configPin,
                )
```

(c) In the `Screen.Dashboard ->` branch, replace the `remember`-cached PIN value (the `val configPinValue = remember { deps.configPin() }` line) with the collected one:

```kotlin
                    val configUrl = remember { deps.configUrl() }
                    val configPinValue = configPin
```

`collectAsStateWithLifecycle` and `getValue` are already imported in `App.kt`. `configPinValue` is still consumed downstream (passed to `DashboardShell(configPin = configPinValue, ...)`), so no other edit in this branch is needed.

- [ ] **Step 7: Run the gate to verify everything still builds + passes (green)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=0`. Confirm:
```bash
grep -nE "BUILD SUCCESSFUL" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: one `BUILD SUCCESSFUL`. (No `node --check` — this task does not touch `app.js`.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rar/hearth/web/ConfigServer.kt app/src/main/java/com/rar/hearth/App.kt app/src/test/java/com/rar/hearth/web/ConfigServerTest.kt
git commit -m "feat(web): savable/custom PIN endpoints + live PIN source" -m "GET /api/hello (pre-auth name); gated PUT /api/pin + POST /api/pin/reset; pin added to /api/status. App.kt reads settings.configPin live via pinState so a changed PIN shows on the device screen without a restart." -m "Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 3: Frontend — login device name, savable PIN, and PIN card

**Files:**
- Modify: `app/src/main/assets/config/index.html`
- Modify: `app/src/main/assets/config/app.js`
- Modify: `app/src/main/assets/config/style.css`

**Interfaces:**
- Consumes (from Task 2): `GET /api/hello` → `{name, configured}`; `PUT /api/pin {pin}` → `{pin}` or `400 {error}`; `POST /api/pin/reset` → `{pin}`; `pin` on `GET /api/status`.
- Produces: no code consumed by later tasks (final task). New DOM ids: `login-device-row`, `login-device`, `pin-toggle`, `pin-card`. New JS: `loadHello()`, `togglePinVisibility()`, `renderPin()`, `changePin(input, currentEl, err)`, `resetPinToRandom(currentEl, changeEl, err)`, and constants `EYE_ICON` / `EYE_OFF_ICON`.

- [ ] **Step 1: Update the login card + add the PIN card section (`index.html`)**

(a) In `app/src/main/assets/config/index.html`, replace the login-card heading + PIN input block. Find:

```html
      <h1>Hearth</h1>
      <label for="pin">Enter the PIN shown on the device</label>
      <input id="pin" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="••••••">
```

Replace with:

```html
      <h1>Hearth</h1>
      <div id="login-device-row" class="login-device-row" hidden>
        <span class="login-caption">Configuring</span>
        <input id="login-device" class="login-device" name="username" autocomplete="username" readonly tabindex="-1" aria-label="Device name">
      </div>
      <label for="pin">Enter the PIN shown on the device</label>
      <div class="pin-field">
        <input id="pin" type="password" inputmode="numeric" autocomplete="current-password" maxlength="8" placeholder="••••••">
        <button id="pin-toggle" type="button" class="pin-eye" aria-label="Show PIN" aria-pressed="false"></button>
      </div>
```

(b) Add the PIN card section on the Device page. Find the end of the Device section and the start of the Backup section:

```html
          <div id="device"></div>
        </section>

        <section id="backup-section" class="card-section">
```

Replace with (inserts `pin-section` between them):

```html
          <div id="device"></div>
        </section>

        <section id="pin-section" class="card-section">
          <div class="card-head">
            <span class="ic" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <rect x="4.5" y="10.5" width="15" height="9.5" rx="2.2"/>
                <path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>
                <circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/>
              </svg>
            </span>
            <div class="card-titles">
              <h2>Config PIN</h2>
              <p>The PIN for signing in to this configuration page. It is also shown on the device screen.</p>
            </div>
          </div>
          <div id="pin-card"></div>
        </section>

        <section id="backup-section" class="card-section">
```

- [ ] **Step 2: Add eye icons + hello/username + eye toggle + PIN card handlers (`app.js`)**

In `app/src/main/assets/config/app.js`:

(a) Add the eye-icon constants immediately after the `ICONS = { ... };` object literal (i.e. after its closing `};`, before the `// ---------- tiny DOM helpers ----------` comment):

```javascript
// Eye glyphs for the login PIN reveal toggle (currentColor, same stroke style as ICONS).
const EYE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10.6 6.2A9.7 9.7 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3.2 3.7M6.2 6.2A17 17 0 0 0 2 12s3.5 6 10 6a9.6 9.6 0 0 0 4-.9"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/><path d="M3 3l18 18"/></svg>';
```

(b) Make `showLogin()` load the device name, and add `loadHello()`. Replace the existing `showLogin` function:

```javascript
function showLogin() {
  document.getElementById("login").hidden = false;
  document.getElementById("app").hidden = true;
}
```

with:

```javascript
function showLogin() {
  document.getElementById("login").hidden = false;
  document.getElementById("app").hidden = true;
  loadHello();
}

// Pre-auth device name for the login card + password-manager username field. Fails soft:
// on any error the login shows without a name and never blocks sign-in.
async function loadHello() {
  try {
    const r = await api("GET", "/api/hello");
    if (!r.ok) return;
    const b = await r.json();
    const name = (b && b.name) || "";
    const row = document.getElementById("login-device-row");
    const field = document.getElementById("login-device");
    if (name) { field.value = name; row.hidden = false; }
    else { row.hidden = true; }
  } catch (e) { /* fail soft: show login without the device name */ }
}

// Reveal/hide the PIN. type=button so it never submits the login form.
function togglePinVisibility() {
  const pin = document.getElementById("pin");
  const btn = document.getElementById("pin-toggle");
  const reveal = pin.type === "password";
  pin.type = reveal ? "text" : "password";
  btn.setAttribute("aria-label", reveal ? "Hide PIN" : "Show PIN");
  btn.setAttribute("aria-pressed", reveal ? "true" : "false");
  btn.innerHTML = reveal ? EYE_OFF_ICON : EYE_ICON;
}
```

(c) Add the PIN-card render + handlers. Insert them immediately after the existing `renameDevice` function (the `async function renameDevice(input) { ... }` block), before `function renderPanels()`:

```javascript
function renderPin() {
  const host = document.getElementById("pin-card");
  clear(host);

  const cur = el("input");
  cur.readOnly = true;
  cur.value = (lastStatus && lastStatus.pin) || "";
  cur.setAttribute("aria-label", "Current config PIN");
  cur.addEventListener("focus", () => cur.select());   // select-on-focus, read-only
  host.appendChild(labeledRow("Current PIN", cur));

  const next = el("input");
  next.type = "text";
  next.inputMode = "numeric";
  next.maxLength = 8;
  next.placeholder = "4–8 digits";
  next.setAttribute("autocomplete", "off");
  next.setAttribute("aria-label", "New PIN");
  host.appendChild(labeledRow("Change PIN", next));

  const err = el("div", "error");

  const row = el("div", "row");
  const saveBtn = el("button", "ghost", "Save PIN");
  saveBtn.type = "button";
  saveBtn.addEventListener("click", () => changePin(next, cur, err));
  row.appendChild(saveBtn);

  const resetBtn = el("button", "ghost", "Reset to random");
  resetBtn.type = "button";
  resetBtn.addEventListener("click", () => resetPinToRandom(cur, next, err));
  row.appendChild(resetBtn);
  host.appendChild(row);

  host.appendChild(err);

  host.appendChild(el("div", "muted",
    "This PIN protects the configuration page and is shown on the device screen. Choose 4 to 8 " +
    "digits, or reset to a new random 6-digit PIN. Changes take effect immediately — you stay " +
    "signed in on this browser, but the new PIN is required next time."));
}

async function changePin(input, currentEl, err) {
  err.textContent = "";
  const pin = input.value.trim();
  if (!/^\d{4,8}$/.test(pin)) {
    err.textContent = "PIN must be 4 to 8 digits.";
    return;
  }
  setStatus("Saving PIN…", "busy");
  try {
    const r = await api("PUT", "/api/pin", { pin });
    if (r.status === 401) { showLogin(); return; }
    const b = await r.json().catch(() => ({}));
    if (r.ok && b.pin) {
      currentEl.value = b.pin;
      if (lastStatus) lastStatus.pin = b.pin;
      input.value = "";
      setStatus("PIN changed", "ok");
    } else {
      err.textContent = b.error === "invalid pin"
        ? "PIN must be 4 to 8 digits."
        : ("Error: " + (b.error || r.status));
      setStatus("PIN not changed", "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — PIN not changed.", "err");
  }
}

async function resetPinToRandom(currentEl, changeEl, err) {
  err.textContent = "";
  if (!confirm("Reset the config PIN to a new random 6-digit PIN?")) return;
  setStatus("Resetting PIN…", "busy");
  try {
    const r = await api("POST", "/api/pin/reset");
    if (r.status === 401) { showLogin(); return; }
    const b = await r.json().catch(() => ({}));
    if (r.ok && b.pin) {
      currentEl.value = b.pin;
      if (lastStatus) lastStatus.pin = b.pin;
      changeEl.value = "";
      setStatus("PIN reset", "ok");
    } else {
      setStatus("PIN not reset", "err");
    }
  } catch (e) {
    setStatus("Can't reach the device — PIN not reset.", "err");
  }
}
```

(d) Call `renderPin()` from `render()`. Find:

```javascript
  renderHa();
  renderDevice();
  renderBackup();
```

Replace with:

```javascript
  renderHa();
  renderDevice();
  renderPin();
  renderBackup();
```

(e) Wire the eye toggle at boot (initial icon + click handler). Find the boot block:

```javascript
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("save").addEventListener("click", save);
```

Replace with:

```javascript
document.getElementById("login-form").addEventListener("submit", doLogin);
document.getElementById("pin-toggle").innerHTML = EYE_ICON;
document.getElementById("pin-toggle").addEventListener("click", togglePinVisibility);
document.getElementById("save").addEventListener("click", save);
```

**Do not** add any code that clears `#pin` in `doLogin` — the existing `doLogin` reads the field and never clears it, so the browser's save prompt can fire after a successful submit (Part B requirement). No change to `doLogin` is needed.

- [ ] **Step 3: Style the device-name field, eye toggle, and PIN field (`style.css`)**

In `app/src/main/assets/config/style.css`, add the following rules immediately after the `#pin::placeholder { ... }` rule (end of the Login-overlay block, before the `/* App shell */` divider):

```css
/* Device-name (username) heading — a real, submittable form field styled as a heading so
   password managers key the saved PIN to this device. */
.login-device-row {
  display: flex; flex-direction: column; align-items: center; gap: .05rem;
  margin-top: -.35rem;
}
.login-caption {
  font-size: .68rem; font-weight: 800; text-transform: uppercase; letter-spacing: .1em;
  color: var(--text-dim);
}
.login-device {
  width: 100%; text-align: center;
  background: transparent; border: 0; padding: .05rem .2rem;
  color: var(--text); font-weight: 800; font-size: 1.05rem; font-family: inherit;
}
.login-device:hover { border: 0; }
.login-device:focus, .login-device:focus-visible { outline: none; box-shadow: none; border: 0; }

/* PIN input + reveal (eye) toggle */
.pin-field { position: relative; display: flex; }
.pin-field #pin { flex: 1; width: 100%; padding-left: 3rem; padding-right: 3rem; }
.pin-eye {
  position: absolute; top: 50%; right: .45rem; transform: translateY(-50%);
  width: 2.1rem; height: 2.1rem; padding: 0;
  display: grid; place-items: center;
  background: transparent; color: var(--text-dim);
  border: 0; border-radius: 8px;
}
.pin-eye:hover { color: var(--text); background: rgba(255,255,255,.06); }
.pin-eye svg { width: 1.25rem; height: 1.25rem; }
```

- [ ] **Step 4: Verify the JS parses**

Run:
```bash
node --check app/src/main/assets/config/app.js; echo "NODE_RC=$?"
```
Expected: `NODE_RC=0` with no other output.

- [ ] **Step 5: Run the gate (assets packaged into the build)**

Run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; echo "RC=$?"
```
Expected: `RC=0`. Confirm:
```bash
grep -nE "BUILD SUCCESSFUL" /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log
```
Expected: one `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/config/index.html app/src/main/assets/config/app.js app/src/main/assets/config/style.css
git commit -m "feat(web-config): login device name + browser-savable PIN + PIN card" -m "Login shows the device name as a readonly username field; PIN input is masked with a current-password autocomplete and an eye reveal toggle; the Device page gains a PIN change/reset card backed by /api/hello, /api/pin, /api/pin/reset, and status.pin." -m "Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

## Post-implementation manual verification (after flashing a device)

Not part of the gate; run once on a real device (or via `curl` against the LAN IP:8080):

1. `curl -s http://<device-ip>:8080/api/hello` → `{"name":"<device name>","configured":<bool>}` with **no** session cookie.
2. In a browser, open the config page → the login card shows "Configuring / <device name>"; the eye toggle reveals/hides the PIN; after a correct PIN the browser offers to save the credential (a "not secure" note on plain-HTTP LAN is expected and unavoidable).
3. Device page → Config PIN card → set a custom PIN (e.g. `4321`) → "PIN changed"; the device screen's Setup/Home PIN display updates **without** an app restart; the new PIN authenticates on the next sign-in.
4. "Reset to random" → a new 6-digit PIN appears in the card and on the device screen.
5. Invalid PIN (`12`, `abc`, `123456789`) → inline "PIN must be 4 to 8 digits." and the server returns `400 {"error":"invalid pin"}`.

---

## Self-Review

**1. Spec coverage:**
- Part A (`GET /api/hello` pre-auth, login renders name, fail soft): Task 2 Step 3(b)(e) (endpoint, registered above the gate) + Task 3 Step 2(b) (`loadHello` on login show, fail-soft). ✓
- Part B (readonly username field `autocomplete="username"`; PIN `type=password` + `inputmode=numeric` + `autocomplete=current-password` + `maxlength=8`, drop `autocomplete=off`; eye toggle `type=button` + `aria-label`; keep inside `<form id="login-form">`; don't clear PIN before save-detect): Task 3 Step 1(a) (HTML), Step 2(a)(b)(e) (icons, toggle, boot wiring), Step 2 note (no PIN clearing). ✓
- Part C (gated `PUT /api/pin` validate `isValidCustomPin`, persist, return `{pin}`, `400 {"error":"invalid pin"}`, keep session; gated `POST /api/pin/reset` via `generatePin()`; `pin` on `/api/status`; Device-page PIN card with current PIN, Change PIN input + Save, Reset button; client-validate 4–8 digits): Task 2 Step 3(c)(d)(e) + Step 5(d) (`resetConfigPin` calls `generatePin`), Task 3 Step 1(b) + Step 2(c). ✓
- Live PIN source fix (`configPin()` reads `settings.configPin` fresh, `setPin`/`resetPin` callbacks, device-screen reflects change): Task 2 Steps 5-6. ✓
- `isValidCustomPin` + `PinTest`: Task 1. ✓
- SetupCoordinator: verified it never displays the PIN — no change; the cache was `App.kt`'s `remember`, fixed by `pinState` (documented in File Structure + Task 2 Steps 5-6). ✓

**2. Placeholder scan:** No `TODO`/`TBD`/"similar to Task N"/"add validation" placeholders. Every code step contains complete code. The only ellipses are inside prose/`muted` copy strings (literal UI text), not code stubs. ✓

**3. Type/name consistency:**
- `isValidCustomPin` — defined Task 1, called Task 2 Step 3(e), same package (no import). ✓
- `setPin` / `resetPin` — ConfigServer params (Task 2 Step 3a) match test spies (Step 1b) and App.kt wiring (Step 5b). ✓
- `applyConfigPin` / `resetConfigPin` — defined Task 2 Step 5(d), referenced Step 5(b). ✓
- `pinState` — declared Task 2 Step 5(a), collected Task 2 Step 6(a), consumed 6(b)(c). ✓
- Routes `/api/hello`, `/api/pin`, `/api/pin/reset`, `status.pin` — identical strings across ConfigServer (Task 2), tests (Task 2 Step 1), and app.js (Task 3 Steps 2b, 2c). ✓
- JS symbols `loadHello`, `togglePinVisibility`, `renderPin`, `changePin(input, currentEl, err)`, `resetPinToRandom(currentEl, changeEl, err)`, `EYE_ICON`, `EYE_OFF_ICON`, ids `login-device-row`/`login-device`/`pin-toggle`/`pin-card` — call sites match definitions (Task 3 Step 2). ✓
- `configPinValue` still passed to `DashboardShell(configPin = configPinValue)` — unchanged downstream (Task 2 Step 6c). ✓
