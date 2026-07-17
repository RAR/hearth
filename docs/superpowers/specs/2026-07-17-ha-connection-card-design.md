# Config-Page "Home Assistant" Card — Connection Status + Disconnect

**Date:** 2026-07-17
**Status:** Approved (scope = status + disconnect, per AskUserQuestion)

## Goal

The Device page currently shows nothing about the HA connection on a configured device (the
setup card is hidden once connected, and there is no way to re-auth or re-point a device short
of reinstalling). Add a "Home Assistant" card: live connection status, the HA URL, and a
confirm-gated Disconnect that returns the device to setup with everything except auth intact.

## Server (Kotlin)

- **`/api/status` gains `haUrl`** — the stored HA base URL (`settings.baseUrl`), JSON null when
  unset. New `haUrl: () -> String?` constructor param on `ConfigServer`, wired in App.kt as
  `haUrl = { settings.baseUrl }`. (`connState` is already in status — CONNECTING / CONNECTED /
  OFFLINE / AUTH_FAILED via `ws.connectionState.value.name` — no server change needed for it.)
- **New endpoint `POST /api/disconnect`** — session-gated exactly like the other `/api/*`
  endpoints (401 without a valid session). Invokes a new `disconnect: () -> Unit` callback and
  returns `{"ok":true}`. No request body.
- **App.kt wiring** mirrors the existing in-app logout (`onLogout`), split across threads
  safely:
  - `AppDeps` gains `val logoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)`
    (same shape as `setupEvents`).
  - `disconnect = { settings.clearAuth(); logoutEvents.tryEmit(Unit) }` — `clearAuth()` runs
    on the server thread (SharedPreferences is thread-safe) so the web's next `/api/status`
    poll immediately reports `configured: false`. `clearAuth()` clears tokens + client id but
    KEEPS `settings.baseUrl` — deliberate, see setup prefill below.
  - In `EchoDashApp`: `LaunchedEffect(Unit) { deps.logoutEvents.collect { deps.ws.stop();
    screen = Screen.Setup } }` — `ws.stop()` runs on the main collector, exactly like
    `onLogout` does. The existing `AUTH_FAILED → Setup` effect is the safety net if the socket
    races a token refresh before the collector runs.
  - Reconnect needs no new code: the setup card re-runs the normal OAuth flow, and the
    existing `setupEvents → Screen.Dashboard` path brings the app back.
  - The config server stays up throughout (it already runs on the Setup screen —
    `stopConfigServer()` has no callers).

## Web (Device page)

- **New card `ha-section`** (host div `ha`) between `setup-section` and `device-section` inside
  `page-device`. Card-head: link/plug glyph (24×24 stroke-1.7 style, two chain links), title
  "Home Assistant", description "The connection this dashboard is built on."
- **`renderHa()`** (called from `render()`):
  - `document.getElementById("ha-section").hidden = !(lastStatus && lastStatus.configured)` —
    the card and the setup card are complementary: exactly one shows.
  - Status row: `labeledRow("Status", <span id="ha-conn" class="status …">…</span>)` reusing
    the topbar `.status` pill classes. Mapping: CONNECTED → `ok` "Connected" · CONNECTING →
    `busy` "Connecting…" · OFFLINE → `err` "Offline" · AUTH_FAILED → `err` "Authentication
    failed" · anything else/missing → `info` "Unknown".
  - Server row: `labeledRow("Server", <read-only input>)` showing `lastStatus.haUrl` (empty
    string when null). Read-only, select-on-focus (same pattern the old token input used).
  - Disconnect row: a `ghost danger` button "Disconnect…" followed by a muted note:
    "Signs this device out of Home Assistant and returns it to setup. Panels, entities, and
    all other settings are kept; the server address stays filled in for reconnecting."
  - Disconnect click: `confirm("Disconnect from Home Assistant? The dashboard stops until you
    reconnect.")` → `POST /api/disconnect` → on 401 `showLogin()`; on ok `tryLoad()` (status
    now reports `configured:false`, so the existing tryLoad logic shows the setup card and
    forces the `#device` page); on network failure `setStatus("Can't reach the device — not
    disconnected.", "err")`.
- **Live status:** `updateHaConn(lastStatus)` sets the pill's text + class; called after
  render and from the existing 5s `startStatusPoll()` callback (same pattern as
  `updateNightLux`/`updateSendspinStatus`). The poll does NOT toggle card visibility —
  visibility changes only through `tryLoad()`/`render()` (disconnect calls `tryLoad()`).
- **Setup prefill:** in `renderSetup(show)`, when showing the card and the `setup-url` input
  is empty and `lastStatus.haUrl` is non-null, prefill the input with it — after a disconnect
  the URL is retained, so reconnecting is Connect → HA login → done.
- **CSS:** none required (`.status`, `.ghost`, `.danger`, rows all exist). The `.status` pill
  renders fine inside a card row.

## Tests (plain-JVM, ConfigServerSetupTest style — real HTTP against a started server)

- `/api/status` includes `haUrl` when set, JSON null when not.
- `POST /api/disconnect` without a session → 401, callback NOT invoked.
- `POST /api/disconnect` with a session → 200 `{"ok":true}`, callback invoked once; when the
  fake's callback clears its configured flag, a follow-up `/api/status` reports
  `configured: false`.

## Out of scope

- No change to the in-app logout menu, the AUTH_FAILED flow, or the OAuth setup flow itself.
- No "reconnect without re-auth" button (OAuth re-run is the reconnect path).
- No HA version/user display (status + URL only).

## Verification

- Gate per commit: `node --check app/src/main/assets/config/app.js` (web task) and
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest
  :app:assembleDebug`.
- Live: flash a device → Device page shows the card with green "Connected" + URL; unplug HA
  test skipped (don't disrupt) — instead verify the pill live-updates by watching it through a
  CONNECTING blip on app restart. Disconnect tested live ONLY on a device the user approves
  re-authing (it requires redoing HA OAuth on that device).
