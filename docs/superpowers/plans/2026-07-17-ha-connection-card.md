# Home Assistant Connection Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a configured device a "Home Assistant" card on the Device page — live connection status, the HA server URL, and a confirm-gated Disconnect that returns the device to setup with everything except auth intact. Implements `docs/superpowers/specs/2026-07-17-ha-connection-card-design.md`.

**Architecture:** Two layers. (1) The embedded NanoHTTPD config server (`ConfigServer.kt`) grows a `haUrl` field in its `/api/status` JSON and a new session-gated `POST /api/disconnect` endpoint backed by a `disconnect: () -> Unit` callback; `App.kt` wires those to `settings` + a new `logoutEvents` flow whose collector performs the same thread-safe teardown the in-app logout menu does. (2) The vanilla-JS config page (`index.html` + `app.js`) grows an `ha-section` card, complementary to the existing setup card, whose visibility is owned by `tryLoad()`/`render()` and whose status pill live-updates on the existing 5 s poll.

**Tech Stack:** Kotlin (NanoHTTPD, kotlinx.serialization) served on-device; plain-JVM JUnit4 tests (real HTTP against a started server on port 0, OkHttp client); vanilla ES (no modules/build) + static HTML assets baked into the APK via Gradle `assembleDebug`.

## Global Constraints
- Only these files may change: `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`, `app/src/main/java/com/rar/echodash/App.kt`, `app/src/test/java/com/rar/echodash/web/` (one new test file — see Task 1), `app/src/main/assets/config/index.html`, `app/src/main/assets/config/app.js`. Nothing else.
- No new dependencies; plain-JVM JUnit4 tests only; vanilla JS/CSS only — no build step, no framework. **No CSS changes** (all classes reused: `.status`/`.status.ok`/`.status.busy`/`.status.err`/`.status.info`, `.ghost`, `.danger`, `.muted`, `.row`, `.card-section`, `.card-head`, `.ic`, `.card-titles` all already exist).
- Gate before EVERY commit: `node --check app/src/main/assets/config/app.js` then `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` (must be green — the JS/HTML assets ship inside the APK).
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Work directly on master (user's standing instruction). One commit per task.

### Cross-task naming contract (must match everywhere)
- Kotlin: constructor params `haUrl: () -> String?` and `disconnect: () -> Unit`; status JSON key `haUrl`; endpoint `POST /api/disconnect`; handler `handleDisconnect()`; App.kt flow `logoutEvents`.
- Web: card section id `ha-section`; host div id `ha`; status pill id `ha-conn`; render function `renderHa()`; live-update function `updateHaConn(status)`; click handler `disconnectHa()`.

---

## Task 1 — Server + app wiring + tests

Add the `haUrl` status field, the `POST /api/disconnect` endpoint, and the App.kt `logoutEvents` plumbing; cover them with a new plain-JVM test. After this task the API is complete and green; no web changes yet. One commit.

### Files
- `app/src/main/java/com/rar/echodash/web/ConfigServer.kt` — two new constructor params, one line in `handleStatus`, one route line, one handler.
- `app/src/main/java/com/rar/echodash/App.kt` — `logoutEvents` flow, two new wiring lines, one new `LaunchedEffect` collector.
- `app/src/test/java/com/rar/echodash/web/ConfigServerDisconnectTest.kt` — NEW file (full source below).

### Interfaces (Task 2 relies on these)
- `GET /api/status` JSON contains `"haUrl":"<url>"` when `settings.baseUrl` is set, `"haUrl":null` when unset.
- `POST /api/disconnect` returns `200 {"ok":true}` with a valid session, `401` without one.
- A disconnect clears auth server-side so the next `/api/status` poll reports `"configured":false` (this is what makes the web card flip back to setup on the next `tryLoad()`).

### Preserve these quirks (a careless implementer will break them)
- **New params MUST have safe defaults.** Three test files construct `ConfigServer(...)` — `ConfigServerSetupTest`, `BrowserFlowReproTest`, `ConfigServerTest` — and this task may touch only ONE test file. If `haUrl`/`disconnect` had no defaults, the two untouched test files would fail to compile. Defaults are also the file's established style (`lux = { null }`, `sendspinStatus = { "disconnected" }`). Use `haUrl: () -> String? = { null }` and `disconnect: () -> Unit = {}`.
- **Session-gating order in the routing table.** `/api/disconnect` goes INSIDE the `if (uri.startsWith("/api/")) { if (!authed(session)) return 401 … when { … } }` block — NOT above it. The three routes handled before the gate (`/api/login`, `/api/notify`, `/api/notify/clear`) are special: login mints the session, the two notify routes use bearer-token auth. `/api/disconnect` is a plain session-gated route; putting it above the gate would make it callable with no session.
- **The thread split is deliberate.** The `disconnect` callback runs `settings.clearAuth()` on the NanoHTTPD worker thread (SharedPreferences is thread-safe) so the web's very next `/api/status` poll already reports `configured:false`. It must NOT call `ws.stop()` there — `ws.stop()` runs on the main Compose collector (`deps.logoutEvents.collect { deps.ws.stop(); … }`), exactly like the existing `onLogout` menu action. Crossing these wires (stopping the socket off the main thread, or clearing auth in the collector) diverges from the proven logout path.
- **`clearAuth()` keeps `baseUrl`.** Verified in `SettingsStore.kt`: `clearAuth()` removes access/refresh tokens + `auth_client_id` only; `base_url` survives. This is intentional — the web setup card prefills the retained URL (Task 2). Do not "tidy up" by also clearing `baseUrl`.
- **The existing `AUTH_FAILED → Setup` effect stays** as the safety net if the socket races a token refresh before the new collector runs. Do not remove or fold it into the new collector.

### Steps

- [ ] **1.1 — Add the two constructor params to `ConfigServer`.** Anchor (ConfigServer.kt lines 36–37):

  ```kotlin
      private val connState: () -> String,
      private val lux: () -> Int? = { null },
  ```

  Replace with:

  ```kotlin
      private val connState: () -> String,
      private val haUrl: () -> String? = { null },
      private val disconnect: () -> Unit = {},
      private val lux: () -> Int? = { null },
  ```

- [ ] **1.2 — Add `haUrl` to the status JSON.** Anchor (`handleStatus`, ConfigServer.kt lines 115–123):

  ```kotlin
      private fun handleStatus(): Response =
          ok(buildJsonObject {
              put("configured", configured())
              put("connState", connState())
              put("lux", lux())            // int, or JSON null when no sensor reading yet
              put("notifyToken", notifyToken())
              put("deviceName", deviceName())
              put("sendspin", sendspinStatus())
          }.toString())
  ```

  Replace with (insert the `haUrl` line directly under `connState`):

  ```kotlin
      private fun handleStatus(): Response =
          ok(buildJsonObject {
              put("configured", configured())
              put("connState", connState())
              put("haUrl", haUrl())        // stored HA base URL, or JSON null when unset
              put("lux", lux())            // int, or JSON null when no sensor reading yet
              put("notifyToken", notifyToken())
              put("deviceName", deviceName())
              put("sendspin", sendspinStatus())
          }.toString())
  ```

  Note: `put(String, String?)` is a real `buildJsonObject` overload (same nullable-put family as the existing `put("lux", lux())` which passes an `Int?`); a null value serializes to `"haUrl":null`.

- [ ] **1.3 — Add the `POST /api/disconnect` route (inside the session-gated `when`).** Anchor (ConfigServer.kt routing, lines 72–73):

  ```kotlin
                  uri == "/api/status" && method == Method.GET -> handleStatus()
                  uri == "/api/name" && method == Method.PUT -> handlePutName(session)
  ```

  Replace with:

  ```kotlin
                  uri == "/api/status" && method == Method.GET -> handleStatus()
                  uri == "/api/disconnect" && method == Method.POST -> handleDisconnect()
                  uri == "/api/name" && method == Method.PUT -> handlePutName(session)
  ```

- [ ] **1.4 — Add the `handleDisconnect` handler.** Anchor (ConfigServer.kt, the whole `handleStatus` function, lines 115–123, as it now reads after step 1.2):

  ```kotlin
      private fun handleStatus(): Response =
          ok(buildJsonObject {
              put("configured", configured())
              put("connState", connState())
              put("haUrl", haUrl())        // stored HA base URL, or JSON null when unset
              put("lux", lux())            // int, or JSON null when no sensor reading yet
              put("notifyToken", notifyToken())
              put("deviceName", deviceName())
              put("sendspin", sendspinStatus())
          }.toString())
  ```

  Replace with (append the new handler immediately after; no request body is read):

  ```kotlin
      private fun handleStatus(): Response =
          ok(buildJsonObject {
              put("configured", configured())
              put("connState", connState())
              put("haUrl", haUrl())        // stored HA base URL, or JSON null when unset
              put("lux", lux())            // int, or JSON null when no sensor reading yet
              put("notifyToken", notifyToken())
              put("deviceName", deviceName())
              put("sendspin", sendspinStatus())
          }.toString())

      /** Session-gated (see route()). Clears auth device-side and returns the device to setup. */
      private fun handleDisconnect(): Response {
          disconnect()
          return ok("""{"ok":true}""")
      }
  ```

- [ ] **1.5 — Add the `logoutEvents` flow in `AppDeps`.** Anchor (App.kt line 155):

  ```kotlin
      val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  ```

  Replace with:

  ```kotlin
      val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
      val logoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  ```

  (`MutableSharedFlow` is already imported — `setupEvents` uses it.)

- [ ] **1.6 — Wire the two new callbacks into the `ConfigServer(...)` construction.** Anchor (App.kt lines 175–176):

  ```kotlin
          connState = { ws.connectionState.value.name },
          lux = { lastLux },
  ```

  Replace with:

  ```kotlin
          connState = { ws.connectionState.value.name },
          haUrl = { settings.baseUrl },
          // clearAuth() on the server (worker) thread so the web's next /api/status poll
          // already reports configured:false; ws.stop() happens in the main-thread collector.
          disconnect = { settings.clearAuth(); logoutEvents.tryEmit(Unit) },
          lux = { lastLux },
  ```

- [ ] **1.7 — Add the `logoutEvents` collector in `EchoDashApp`.** Anchor (App.kt lines 625–627):

  ```kotlin
      LaunchedEffect(Unit) {
          deps.setupEvents.collect { screen = Screen.Dashboard }
      }
  ```

  Replace with:

  ```kotlin
      LaunchedEffect(Unit) {
          deps.setupEvents.collect { screen = Screen.Dashboard }
      }

      LaunchedEffect(Unit) {
          deps.logoutEvents.collect { deps.ws.stop(); screen = Screen.Setup }
      }
  ```

  (Leave the existing `AUTH_FAILED → Setup` effect at lines 618–623 untouched — it is the race safety net.)

- [ ] **1.8 — Create the test file `app/src/test/java/com/rar/echodash/web/ConfigServerDisconnectTest.kt`.** A NEW file with its own minimal harness. Rationale for a new file rather than extending `ConfigServerSetupTest`: that harness hard-wires `configured = { settings.refreshToken != null }`, and its existing tests depend on that wiring; the disconnect test needs `configured`/`haUrl`/`disconnect` bound to test-controlled state instead, so a self-contained harness is cleaner than contorting the shared `setUp`. No `FakeHa`/OAuth is needed here (this test never hits the setup routes); `SetupCoordinator` is still constructed only because it is a required (non-defaulted) `ConfigServer` param. Full source:

  ```kotlin
  package com.rar.echodash.web

  import com.rar.echodash.config.ConfigStore
  import com.rar.echodash.data.InMemorySettingsStore
  import com.rar.echodash.ha.AuthManager
  import okhttp3.MediaType.Companion.toMediaType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import okhttp3.RequestBody.Companion.toRequestBody
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test
  import java.io.File
  import kotlin.random.Random

  class ConfigServerDisconnectTest {
      private val json = "application/json".toMediaType()
      private val http = OkHttpClient()
      private lateinit var server: ConfigServer
      private lateinit var base: String

      // Test-controlled server state the ConfigServer callbacks read/mutate.
      private var haUrlValue: String? = null
      private var configuredFlag = true
      private var disconnectCount = 0

      private fun tempDir(): File =
          File.createTempFile("cfgdisc", "").let { it.delete(); it.mkdirs(); it }

      @Before
      fun setUp() {
          val settings = InMemorySettingsStore()
          val setup = SetupCoordinator(AuthManager(settings, OkHttpClient()), onConfigured = {})
          server = ConfigServer(
              port = 0,
              store = ConfigStore(tempDir()),
              sessions = SessionManager(random = Random(1)),
              pin = { "123456" },
              notifyToken = { "testtoken" },
              deviceName = { "Hearth" },
              setDeviceName = { },
              pushStore = com.rar.echodash.notify.PushNotificationStore(),
              entitiesJson = { "[]" },
              setup = setup,
              configured = { configuredFlag },
              connState = { "CONNECTED" },
              haUrl = { haUrlValue },
              disconnect = { disconnectCount++; configuredFlag = false },
              previewChime = { _, _ -> },
              previewEarcon = { },
              assetReader = { null },
          )
          server.start()
          base = "http://127.0.0.1:${server.listeningPort}"
      }

      @After
      fun tearDown() { server.stop() }

      private fun login(): String =
          http.newCall(Request.Builder().url("$base/api/login")
              .post("""{"pin":"123456"}""".toRequestBody(json)).build())
              .execute().use { it.header("Set-Cookie")!!.substringBefore(";") }

      private fun post(path: String, body: String, cookie: String) =
          http.newCall(Request.Builder().url("$base$path").header("Cookie", cookie)
              .post(body.toRequestBody(json)).build()).execute()

      private fun status(cookie: String): String =
          http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
              .execute().use { it.body!!.string() }

      @Test
      fun statusHaUrlIsNullWhenUnsetAndStringWhenSet() {
          val cookie = login()
          assertTrue(status(cookie).contains("\"haUrl\":null"))
          haUrlValue = "http://homeassistant.local:8123"
          assertTrue(status(cookie).contains("\"haUrl\":\"http://homeassistant.local:8123\""))
      }

      @Test
      fun disconnectRequiresSession() {
          post("/api/disconnect", "", "session=nope").use {
              assertEquals(401, it.code)
          }
          assertEquals(0, disconnectCount)
      }

      @Test
      fun disconnectWithSessionInvokesCallbackAndFlipsConfigured() {
          val cookie = login()
          assertTrue(status(cookie).contains("\"configured\":true"))
          post("/api/disconnect", "", cookie).use {
              assertEquals(200, it.code)
              assertTrue(it.body!!.string().contains("\"ok\":true"))
          }
          assertEquals(1, disconnectCount)
          assertTrue(status(cookie).contains("\"configured\":false"))
      }
  }
  ```

### Verification (Task 1)
- [ ] Greps (expected output shown):
  - `grep -n 'haUrl: () -> String?\|disconnect: () -> Unit' app/src/main/java/com/rar/echodash/web/ConfigServer.kt` → two lines (the two new params).
  - `grep -n '/api/disconnect' app/src/main/java/com/rar/echodash/web/ConfigServer.kt` → one line (the route inside the gated `when`).
  - `grep -n 'put("haUrl"' app/src/main/java/com/rar/echodash/web/ConfigServer.kt` → one line, inside `handleStatus`.
  - `grep -n 'logoutEvents' app/src/main/java/com/rar/echodash/App.kt` → three lines (flow decl, `disconnect` wiring, collector).
  - `grep -rn 'ConfigServer(' app/src/test/java/com/rar/echodash/web/` → three construction sites still present (only `ConfigServerDisconnectTest.kt` is new; the other two test files are unchanged and still compile thanks to the param defaults).
- [ ] Gate (app.js is untouched this task, so `node --check` is a harmless no-op; the real gate is gradle): `node --check app/src/main/assets/config/app.js` then `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` — both green, including the three new `ConfigServerDisconnectTest` cases.
- [ ] Commit: `feat(config): HA connection status field + disconnect endpoint (server + app wiring)` with the required trailer.

---

## Task 2 — Web "Home Assistant" card

Add the `ha-section` card to the Device page and its `renderHa`/`updateHaConn`/`disconnectHa` JS. Complementary to the setup card: exactly one of setup-section / ha-section shows. One commit.

### Files
- `app/src/main/assets/config/index.html` — insert `ha-section` between `setup-section` and `device-section` inside `page-device`.
- `app/src/main/assets/config/app.js` — add `renderHa`, `updateHaConn`, `disconnectHa`; add `renderHa();` to `render()`; add `updateHaConn(lastStatus);` to the poll; add the setup-url prefill to `renderSetup`.

### Preserve these quirks (a careless implementer will break them)
- **`tryLoad()`/`render()` own card visibility — the poll never toggles it.** `renderHa()` sets `ha-section.hidden` (called from `render()`, which runs only from `tryLoad()`/`completeSetup()`). `updateHaConn()` sets ONLY the pill text/class and is what the 5 s poll calls — it must not touch `.hidden`. Disconnect flips visibility by calling `tryLoad()` (status now `configured:false` → `render()` → `renderHa()` hides the card), never by poking `.hidden` directly.
- **Complementary setup/ha visibility.** `renderSetup(show)` sets `setup-section.hidden = !show` (where `show = status.configured === false`); `renderHa()` sets `ha-section.hidden = !(configured)`. The two are exact opposites — never both visible, never both hidden. Do not add a second visibility owner.
- **No `.mono` class.** The old Push card's token input used `el("input", "mono")`, but `.mono` was removed from `style.css` with that card (grep confirms it is gone). The URL input is a plain `el("input")` — same read-only + select-on-focus behavior, no dead class, honoring "no CSS changes."
- **`renderHa()` calls `updateHaConn(lastStatus)` after building the pill** so the first paint shows the right status immediately (not the placeholder "Unknown") — the poll only refreshes it every 5 s.
- **`disconnectHa()` mirrors the spec's three outcomes:** confirm-cancel → do nothing; `401` → `showLogin()`; ok → `tryLoad()`; network failure (and any non-ok, non-401 response) → `setStatus("Can't reach the device — not disconnected.", "err")`. It does NOT flip the card itself — `tryLoad()` does.

### Steps

- [ ] **2.1 — Insert the `ha-section` card in `index.html`.** Anchor (index.html lines 132–134 — the setup-section close, the blank line, and the device-section open):

  ```html
            </section>

            <section id="device-section" class="card-section">
  ```

  Replace with:

  ```html
            </section>

            <section id="ha-section" class="card-section" hidden>
              <div class="card-head">
                <span class="ic" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M9.5 13.5a3 3 0 0 0 4.5.3l3-3a3 3 0 0 0-4.2-4.2l-1.7 1.7"/>
                    <path d="M14.5 10.5a3 3 0 0 0-4.5-.3l-3 3a3 3 0 0 0 4.2 4.2l1.7-1.7"/>
                  </svg>
                </span>
                <div class="card-titles">
                  <h2>Home Assistant</h2>
                  <p>The connection this dashboard is built on.</p>
                </div>
              </div>
              <div id="ha"></div>
            </section>

            <section id="device-section" class="card-section">
  ```

  (The card starts `hidden`; `renderHa()` reveals it once `lastStatus.configured` is known. The two `<path>` elements are the standard two-link chain glyph, matching the 24×24 stroke-1.7 style used by every other card head.)

- [ ] **2.2 — Add `renderHa();` to `render()`.** Anchor (app.js lines 327–328):

  ```js
  function render() {
    renderDevice();
  ```

  Replace with (call `renderHa()` first — it mirrors the card's DOM position at the top of `page-device`, directly under the setup card):

  ```js
  function render() {
    renderHa();
    renderDevice();
  ```

- [ ] **2.3 — Add `renderHa()` and `updateHaConn()`.** Anchor (app.js — the whole `renderDevice` function opening, lines 348–350):

  ```js
  function renderDevice() {
    const host = document.getElementById("device");
    clear(host);
  ```

  Replace with (define the two new functions immediately BEFORE `renderDevice`):

  ```js
  function renderHa() {
    // Complementary to the setup card: shown only when configured. Visibility is owned here
    // (render()/tryLoad()), never by the poll.
    document.getElementById("ha-section").hidden = !(lastStatus && lastStatus.configured);
    const host = document.getElementById("ha");
    clear(host);

    const pill = el("span", "status info", "Unknown");
    pill.id = "ha-conn";
    host.appendChild(labeledRow("Status", pill));
    updateHaConn(lastStatus);   // paint the real state now; the 5s poll refreshes it after

    const urlInput = el("input");
    urlInput.readOnly = true;
    urlInput.value = (lastStatus && lastStatus.haUrl) || "";
    urlInput.setAttribute("aria-label", "Home Assistant server address");
    urlInput.addEventListener("focus", () => urlInput.select());   // select-on-focus, read-only
    host.appendChild(labeledRow("Server", urlInput));

    const row = el("div", "row");
    const disc = el("button", "ghost danger", "Disconnect…");
    disc.type = "button";
    disc.addEventListener("click", disconnectHa);
    row.appendChild(disc);
    host.appendChild(row);

    host.appendChild(el("div", "muted",
      "Signs this device out of Home Assistant and returns it to setup. Panels, entities, and " +
      "all other settings are kept; the server address stays filled in for reconnecting."));
  }

  // Live status pill; called after render and from the 5s poll. Sets text + class ONLY — never
  // toggles card visibility (that is render()/tryLoad()'s job).
  function updateHaConn(status) {
    const node = document.getElementById("ha-conn");
    if (!node) return;                       // card not rendered
    const map = {
      CONNECTED: ["ok", "Connected"],
      CONNECTING: ["busy", "Connecting…"],
      OFFLINE: ["err", "Offline"],
      AUTH_FAILED: ["err", "Authentication failed"],
    };
    const [kind, label] = map[status && status.connState] || ["info", "Unknown"];
    node.className = "status " + kind;
    node.textContent = label;
  }

  async function disconnectHa() {
    if (!confirm("Disconnect from Home Assistant? The dashboard stops until you reconnect.")) return;
    setStatus("Disconnecting…", "busy");
    try {
      const r = await api("POST", "/api/disconnect");
      if (r.status === 401) { showLogin(); return; }
      // On ok the device has cleared auth; tryLoad() re-pulls status (now configured:false),
      // which hides this card, shows setup, and forces the #device page.
      if (r.ok) { await tryLoad(); return; }
      setStatus("Can't reach the device — not disconnected.", "err");
    } catch (e) {
      setStatus("Can't reach the device — not disconnected.", "err");
    }
  }

  function renderDevice() {
    const host = document.getElementById("device");
    clear(host);
  ```

  (`labeledRow("Status", pill)` works with a bare `<span>`: `labeledRow` only wires a `for` when the control is/contains an `input`/`select`, so the pill just renders inside a `.row`. `api("POST", "/api/disconnect")` with no `body` sends an empty POST — `api()` omits the body/Content-Type when `body === undefined` — matching the endpoint's "no request body" contract.)

- [ ] **2.4 — Add `updateHaConn(lastStatus);` to the poll.** Anchor (app.js line 1178):

  ```js
        if (r.ok) { lastStatus = await r.json(); updateNightLux(lastStatus); updateSendspinStatus(lastStatus); }
  ```

  Replace with:

  ```js
        if (r.ok) { lastStatus = await r.json(); updateNightLux(lastStatus); updateSendspinStatus(lastStatus); updateHaConn(lastStatus); }
  ```

- [ ] **2.5 — Add the setup-url prefill to `renderSetup`.** Anchor (app.js lines 175–177):

  ```js
  function renderSetup(show) {
    document.getElementById("setup-section").hidden = !show;
  }
  ```

  Replace with:

  ```js
  function renderSetup(show) {
    document.getElementById("setup-section").hidden = !show;
    // After a disconnect baseUrl is retained (clearAuth keeps it); prefill the empty field so
    // reconnecting is Connect -> HA login -> done.
    if (show) {
      const urlEl = document.getElementById("setup-url");
      if (urlEl && !urlEl.value && lastStatus && lastStatus.haUrl) urlEl.value = lastStatus.haUrl;
    }
  }
  ```

### Verification (Task 2)
- [ ] `node --check app/src/main/assets/config/app.js` → exit 0 (no syntax error).
- [ ] Greps (expected output shown):
  - `grep -n 'id="ha-section"\|id="ha"' app/src/main/assets/config/index.html` → two lines (section wrapper + host div).
  - `grep -c 'function renderHa\|function updateHaConn\|function disconnectHa' app/src/main/assets/config/app.js` → `3`.
  - `grep -n 'renderHa();' app/src/main/assets/config/app.js` → one line (inside `render()`).
  - `grep -n 'updateHaConn(lastStatus)' app/src/main/assets/config/app.js` → two lines (in `renderHa` and in the poll callback).
  - `grep -n 'ha-conn' app/src/main/assets/config/app.js` → two lines (`pill.id` set, `getElementById` in `updateHaConn`).
  - `grep -n 'getElementById("ha-section")' app/src/main/assets/config/app.js` → one line (only `renderHa` toggles visibility — confirms the poll does not).
- [ ] Gate: `node --check app/src/main/assets/config/app.js` then `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` — both green (the assets ship inside the APK).
- [ ] Commit: `feat(web-config): Home Assistant connection card (status + disconnect)` with the required trailer.

### Live check (post-merge, per spec — no code)
- Flash a device → Device page shows the card with green "Connected" + the HA URL; watch the pill flip through "Connecting…" on an app restart to confirm live-update. Test Disconnect ONLY on a device the user approves re-authing (it requires redoing HA OAuth there): Disconnect → confirm → app returns to setup, the setup card reappears with the URL prefilled, reconnect via HA login.
