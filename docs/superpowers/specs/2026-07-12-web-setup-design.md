# Web-Based HA Setup + Label Seeding Removal — Design

**Date:** 2026-07-12
**Status:** Approved (scoped with user 2026-07-12: browser OAuth round-trip; device Setup screen becomes a pointer card; user additionally directed: remove the HA-label seeding entirely — "we can just use the web").

## Problem

First-time setup (HA URL + OAuth login) happens on the device in a WebView — painful on the Echo's screen and the reason the config server originally couldn't start before HA auth. And the `echo-*` label seeding is now redundant: the web config page is the sole configuration surface, so the one-shot label import adds code, a registry dependency, and a parsing quirk (`_`→`-` normalization) for no ongoing benefit.

## Goals

1. First-time HA setup happens in the browser on the config page (`http://<device-ip>:8080`), via an OAuth round-trip through the user's HA instance.
2. The device's Setup screen becomes a pointer card: "Set up at http://<ip>:8080 · PIN nnnnnn". No WebView, no on-device URL typing.
3. When setup completes in the browser, the device flips to the Dashboard automatically.
4. Existing installs (authenticated with the legacy Android client_id) keep refreshing tokens without re-auth.
5. All `echo-*` label seeding code is removed. Fresh installs start from `DashConfig()` defaults and are configured entirely via the web page.

## Non-Goals

- Re-running setup from the web while already configured ("reconnect" card). Re-setup path stays: device long-press menu → Logout → device shows pointer card → set up in browser.
- HTTPS for the config server, SameSite cookies, constant-time PIN compare (already on the note-and-ship list).
- Any HA-side changes.

## How the OAuth round-trip works

HA implements IndieAuth: `client_id` is a URL, and `redirect_uri` must be on the same host as `client_id` (same-host needs no link-rel verification). The config page uses **its own origin** as both:

- `client_id` = `location.origin + "/"` (e.g. `http://10.75.1.98:8080/`) — sent by the **page**, because only the browser knows how it addresses the device (IP vs hostname).
- `redirect_uri` = same value. HA redirects back to the config page root with `?code=...&state=...`.

The `state` token binds the round trip to a server-side pending record so a forged callback can't inject a code.

### Flow

1. User opens config page, logs in with PIN (existing session flow).
2. Page calls `GET /api/status` (session-gated). Response: `{"configured": false, "connState": "OFFLINE"}` → page shows a **Setup card** (HA URL input + Connect button) above the config sections.
3. User enters HA URL → page POSTs `/api/setup/begin` `{"haUrl": "...", "clientId": location.origin + "/"}`.
   - Server normalizes/validates the URL (reuse `normalizeBaseUrl`, moved out of UI code), generates a random `state` token (128-bit hex from `SecureRandom`), stores ONE in-memory pending record `{haUrl, clientId, state, createdAt}` (a new begin overwrites it; expires after 10 minutes), and returns `{"authorizeUrl": "<haUrl>/auth/authorize?client_id=<enc(clientId)>&redirect_uri=<enc(clientId)>&state=<state>"}`.
   - Invalid URL → 400 `{"error": "..."}` shown in the card.
4. Page navigates the browser to `authorizeUrl`. User logs into HA. HA redirects to `http://<ip>:8080/?code=...&state=...`.
5. Page boot code detects `code`+`state` query params. After the normal auth gate (if the PIN session expired during the round trip, the login overlay shows first — params survive in `location.search`), it POSTs `/api/setup/complete` `{"code": "...", "state": "..."}`.
   - Server checks the pending record exists, is unexpired, and `state` matches (else 400).
   - Server exchanges the code at `<pending.haUrl>/auth/token` with `client_id = pending.clientId` (grant_type `authorization_code`). On success it persists, in this order: `settings.baseUrl = pending.haUrl`, `settings.authClientId = pending.clientId`, tokens (existing `store()` path). Pending record cleared. Fires the auth-completed signal. Returns `{"ok": true}`.
   - Exchange failure → 502 `{"error": "Home Assistant rejected the login: ..."}`; pending record kept (user can retry Connect). Code exchange runs on a NanoHTTPD request thread — it's blocking HTTP via OkHttp, acceptable (same as PUT /api/config file IO).
6. Page strips the query params (`history.replaceState`) and re-runs `tryLoad()`; status now `configured: true`, setup card hidden.
7. **Device side:** the app observes the auth-completed signal and flips Setup → Dashboard (`screen = Screen.Dashboard`, which triggers the existing `startDashboard()`).

### `GET /api/status` (session-gated, like all /api routes)

```json
{"configured": true, "connState": "CONNECTED"}
```
- `configured` = `settings.refreshToken != null`.
- `connState` = `ws.connectionState.value.name` (`OFFLINE | CONNECTING | CONNECTED | AUTH_FAILED`). The page may show it in the status pill; minimal UI is fine.

## Component changes

### `SettingsStore` (+ `PrefsSettingsStore`, `InMemorySettingsStore`)
- New `var authClientId: String?` (pref key `auth_client_id`). `clearAuth()` also removes it.

### `AuthManager`
- `refresh()` posts `client_id = settings.authClientId ?: CLIENT_ID` — the legacy constant remains only as the fallback for installs authenticated through the old WebView flow (the user's current device).
- New suspend fun `exchangeSetupCode(baseUrl: String, clientId: String, code: String)`: token request against `<baseUrl>/auth/token` (not `settings.baseUrl` — it isn't set yet), `client_id = clientId`; on success persists baseUrl, authClientId, and tokens.
- The old `authorizeUrl()` / `exchangeCode()` (WebView flow) and `REDIRECT_URI` are deleted along with the WebView.

### New `web/SetupCoordinator.kt`
Holds the pending record + state generation + expiry; calls `AuthManager.exchangeSetupCode`; exposes `begin(haUrl, clientId): BeginResult` and `complete(code, state): CompleteResult` (sealed results; plain-JVM testable with an injected clock and a fake token endpoint). Thread-safe (`synchronized` — NanoHTTPD request threads). On successful complete, invokes an injected `onConfigured: () -> Unit`.

### `ConfigServer`
- New routes (inside the existing session-gated `/api/` block): `GET /api/status`, `POST /api/setup/begin`, `POST /api/setup/complete`. Wired via injected lambdas/coordinator, keeping the server Android-free.
- `complete` is a suspend-bridged call: coordinator method is blocking (OkHttp sync call), so no bridge needed — keep it non-suspend.

### `App.kt` / `AppDeps`
- New `val setupEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)`; SetupCoordinator's `onConfigured` does `setupEvents.tryEmit(Unit)`.
- `EchoDashApp`: `LaunchedEffect` collecting `setupEvents` → `screen = Screen.Dashboard`.
- Remove the seed collector (`seedStarted` block) from `startDashboard()`.

### `SetupScreen.kt` → pointer card
Rewritten: full-screen card showing "Set up this dashboard from another device", the config URL (`deps.configUrl()`), and the PIN (`deps.configPin()`), in dashboard styling (Nunito, dark navy). No WebView, no URL input, no `onDone` from local flow (the flip comes from `setupEvents`). `normalizeBaseUrl` moves to `web/` (it's now server-side validation); its tests move with it. The AndroidView/WebView imports and `AuthWebView` are deleted.

### Label seeding removal
- Delete `config/Seeding.kt` and its tests.
- `ConfigStore`: remove `seeder` param, `needsSeed()`, `seedFrom()` (constructor takes only `dir`). Corrupt-file recovery keeps falling back to defaults.
- `EntityModels`: `RegistryIndex` drops `labelToEntities` and `allEntityIds`; `parseEntityRegistry` drops the labels block (including the `_`→`-` normalization — it existed only for labels). `registryNames` + `allEntities` stay (web picker + display names).
- Update every test that constructs `RegistryIndex`/uses labels.

### Web page (`assets/config/`)
- Setup card markup goes in the HTML comment slot the redesign left for it; styled to match the redesigned cards.
- `app.js`: on boot capture `code`/`state` from `location.search`; after successful auth+load, if present → `POST /api/setup/complete`, then `history.replaceState` to strip params and reload status. `tryLoad()` additionally fetches `/api/status`; setup card shown iff `configured === false`. Connect button → `/api/setup/begin` → `location.assign(authorizeUrl)`. Errors render inside the setup card. All fetches keep the existing "Can't reach the device…" catch pattern.

## Error handling

| Case | Behavior |
|---|---|
| Invalid HA URL in begin | 400, message in setup card |
| state mismatch / expired / no pending | 400 "setup session expired — try again", card reshown |
| HA token exchange fails (bad code, unreachable) | 502 with message; pending kept for retry |
| PIN session expired during round trip | login overlay first; code+state persist in URL; complete runs after login |
| Device flips mid-browser-flow (auth via another path) | begin/complete still work; `configured` just turns true |

## Testing (plain-JVM, JUnit4, as always)

- `SetupCoordinatorTest`: begin validates/normalizes URL, generates distinct states; complete rejects wrong/expired state; success persists baseUrl/clientId/tokens (InMemorySettingsStore) and fires callback; failure keeps pending. Fake HA token endpoint via a throwaway NanoHTTPD on port 0.
- `ConfigServerSetupTest`: routes gated by session; status shape; begin/complete happy path over HTTP.
- `AuthManagerTest` additions: refresh uses stored authClientId; falls back to legacy constant when absent.
- Removal fallout: `ConfigStoreTest`, registry parsing tests updated; `SeedingTest` deleted; `normalizeBaseUrl` tests relocated.
- Existing suites stay green: `:app:testDebugUnitTest`, `assembleDebug`.

## Global constraints (unchanged from web-config)

- Kotlin 2.1.0, compileSdk 34 (never bump), media3 1.4.1 pinned, NanoHTTPD 2.3.1 the only server dep — **no new dependencies**.
- `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto`; plain-JVM JUnit4 tests only.
- Config page: zero external resources; vanilla JS; the `[hidden]` display rule stays guarded.
- NanoHTTPD quirks apply: POST bodies under `files["postData"]`; header keys lowercased.
