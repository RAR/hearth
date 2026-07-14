# HA Push Notifications — Design

**Date:** 2026-07-13
**Status:** Approved (user delegated design decisions; approach pre-chosen in backlog #7)

## Goal

Let Home Assistant push text notifications onto the Echo Dashboard's home-screen
notification area (shipped 2026-07-13 as the NWS alerts display) via a token-gated
HTTP endpoint on the existing config server, driven from HA by `rest_command`.
This makes HA the second producer feeding the general-purpose notification area.

## Background / constraints

- The VACA integration exposes no service for its `toast-message` action (verified in
  its source), so the HA → app path must be our own. Chosen route (recorded in the
  backlog when this was queued): `POST /api/notify` on the config server (:8080),
  gated by a static token independent of the PIN session, called from HA `rest_command`.
- Display layer already exists: `NotificationItem` model, `NotificationArea` composable
  (swipe-dismiss, tap-to-expand, severity accent bars), wired into `HomeView` via
  `DashboardShell`.
- Trust model: LAN-only HTTP kiosk. The PIN already travels in plaintext on this LAN;
  the notify token gets the same treatment. Token lives in EncryptedSharedPreferences
  on-device (same store as the PIN).
- No new dependencies. Plain-JVM-testable units (no `android.*` in logic).

## Approaches considered

1. **Token-gated `/api/notify` + `rest_command`** *(chosen — pre-decided in backlog)*:
   direct, stateless on the HA side, no HA custom component, testable end-to-end
   with `curl`.
2. HA entity the app watches (input_text/sensor): abuses state (255-char limit), no
   natural dismiss/clear semantics, extra HA-side helper management. Rejected.
3. VACA toast path: no service exists to trigger it. Dead end.

## Architecture

```
HA automation ── rest_command (POST /api/notify, Bearer token) ──▶ ConfigServer
                                                                        │
                                                                 PushNotificationStore
                                                                 (in-memory StateFlow)
                                                                        │
App.kt collects items ──▶ DashboardShell merges with NWS items ──▶ NotificationArea
```

### 1. Token (`SettingsStore` + `web/Pin.kt`)

- `generateNotifyToken(random: java.security.SecureRandom = SecureRandom()): String`
  — 32 lowercase hex chars, alongside `generatePin` in `web/Pin.kt`.
- `SettingsStore` gains `var notifyToken: String?` (interface + `InMemorySettingsStore`
  + `PrefsSettingsStore`, prefs key `notify_token`).
- `AppDeps` ensures it lazily exactly like `ensuredPin`.
- No rotation UI in v1 (clearing app data regenerates).

### 2. `PushNotificationStore` (new file `notify/PushNotificationStore.kt`)

Plain Kotlin, thread-safe (synchronized like ConfigStore), no Android imports.

- `data class PushedNotification(val id: String, val severity: NotifSeverity,
  val title: String, val message: String?, val expiresAtMs: Long?)`
- `val items: StateFlow<List<PushedNotification>>` — newest first.
- `fun post(id: String?, title: String, message: String?, severity: String?,
  timeoutSeconds: Int?, nowMs: Long): String` — returns the effective id.
  - `title` trimmed; caller guarantees non-blank (server validates). Clamp to 120 chars.
  - `message` trimmed, blank → null, clamp to 2000 chars.
  - `severity`: "critical"/"warning"/"info" (trim/case-insensitive); unknown/absent → INFO.
  - `timeoutSeconds`: null/absent → persistent; otherwise clamped to 5..86400,
    `expiresAtMs = nowMs + seconds*1000`.
  - `id` trimmed; blank/null → auto id `auto-<counter>` (process-lifetime counter).
  - Re-posting an existing id replaces the item and moves it to the front.
  - Cap: 20 items; posting beyond the cap drops the oldest.
- `fun dismiss(id: String)` — remove (UI swipe).
- `fun clear(id: String)` / `fun clearAll()` — remove (HA-side).
- `fun prune(nowMs: Long)` — drop items whose `expiresAtMs` has passed.

In-memory only: pushed notifications do not survive an app restart. Accepted for v1
(HA automations can re-send; matches how transient device notifications behave).

### 3. `ConfigServer` endpoints

Routed BEFORE the session-cookie gate (like `/api/login`); authenticated instead by
`Authorization: Bearer <token>` (NanoHTTPD lowercases header names; compare with
`MessageDigest.isEqual` for constant time). New constructor params:
`notifyToken: () -> String` and the `PushNotificationStore`.

- `POST /api/notify` — body `{"id"?: str, "title": str, "message"?: str,
  "severity"?: "info"|"warning"|"critical", "timeout"?: int-seconds}`.
  - Missing/bad token → 401. Malformed JSON or blank `title` → 400.
  - Success → 200 `{"ok":true,"id":"<effective-id>"}`.
- `POST /api/notify/clear` — body `{"id": str}` or `{"all": true}`.
  - `all:true` → clearAll; else non-blank `id` → clear(id); neither → 400.
  - Success → 200 `{"ok":true}` (clearing an unknown id is still ok:true — idempotent).
- The notify token grants ONLY these two routes; all other `/api/*` still require the
  PIN session cookie. `GET /api/status` (session-gated) additionally returns
  `"notifyToken"` so the config page can display it.

### 4. UI merge (`NotificationModel.kt`, `DashboardShell`, `App.kt`)

- `NotificationModel.kt` gains:
  - `const val PUSH_KEY_PREFIX = "push:"`
  - `fun pushedNotificationItems(items: List<PushedNotification>): List<NotificationItem>`
    — key = `PUSH_KEY_PREFIX + id`, detail = message.
  - `fun mergeNotifications(pushed: List<NotificationItem>, nws: List<NotificationItem>):
    List<NotificationItem>` — concatenate pushed-then-NWS, stable-sort by severity
    descending (so within a severity band pushed items come first, newest first, and
    NWS keeps its existing order).
- `App.kt` (`AppDeps`): construct the store, pass to ConfigServer; in the Dashboard
  composable collect `items`, and run a pruning effect: while any item has an
  `expiresAtMs`, delay until the nearest expiry (capped at 30 s per wait) then call
  `prune(System.currentTimeMillis())`.
- `DashboardShell` gains `pushed: List<NotificationItem>` and
  `onPushDismiss: (String) -> Unit` params. HOME branch:
  `allNotifications = mergeNotifications(pushed, nwsNotifications(...))`; dismiss
  handler routes keys with `PUSH_KEY_PREFIX` to `onPushDismiss` (which calls
  `store.dismiss(id-without-prefix)`), others into `dismissedKeys` as today.
  Pushed keys never enter `dismissedKeys` (removal from the store IS the dismissal),
  so the NWS unavailable-prune guard is untouched.
- Notification area only renders on HOME (existing behavior); a push that arrives
  while another panel is up becomes visible on idle-return. No night-mode wake,
  no toast flash (out of scope v1).

### 5. Config page (`index.html` + `app.js`)

Notifications card grows a "Push from Home Assistant" block under the NWS rows:

- The token, shown in a readonly monospace input (value from `/api/status`'s
  `notifyToken`, which the page already fetches at load into `lastStatus`).
- A `<pre>` with a ready-to-paste `rest_command` example, device URL and token
  filled in:

```yaml
rest_command:
  echo_notify:
    url: "http://<device-ip>:8080/api/notify"
    method: POST
    headers:
      authorization: "Bearer <token>"
    content_type: "application/json"
    payload: >-
      {"title": {{ title | tojson }}, "message": {{ message | default('') | tojson }},
       "severity": {{ severity | default('info') | tojson }},
       "id": {{ id | default('') | tojson }}, "timeout": {{ timeout | default(0) }}}
```

  (`timeout: 0`/absent means persistent — the server treats `timeout <= 0` as null.)
- A muted hint: call `rest_command.echo_notify` from automations; re-using an `id`
  updates the row; `POST /api/notify/clear` removes it.

## Error handling

- Server never throws to the client: existing `serve()` catch-all returns 500 JSON.
- Store clamps everything (lengths, timeout, severity, cap) — no invalid state.
- 401 before body parsing (no token → no work).
- Config page renders the block only when `lastStatus.notifyToken` is present
  (older app builds → block absent, no error).

## Testing

- `PushNotificationStoreTest` (plain JVM): post defaults/clamps, auto-id uniqueness,
  replace-and-bump on same id, cap eviction, dismiss/clear/clearAll idempotence,
  prune expiry boundary, severity mapping, timeout ≤ 0 → persistent.
- `ConfigServerTest` additions (ephemeral-port server, existing pattern): 401 on
  missing/wrong token, 200 + store contents on valid post, 400 on blank title or
  malformed body, clear by id / all, notify token does NOT authorize `/api/config`,
  session cookie does NOT replace the bearer token for `/api/notify`,
  `/api/status` includes `notifyToken`.
- `NotificationModelTest` additions: pushed→item mapping (prefix, detail), merge
  ordering (severity bands, pushed-first within band, stability).
- Manual end-to-end after flash: `curl -X POST -H "Authorization: Bearer <tok>"`
  against the real device, screenshot the row, swipe-dismiss, clear via API.

## Out of scope (v1)

- Token rotation UI, toast/pill flash on arrival, night-mode wake, persistence of
  pushed items across app restart, HA-side notify *platform* (a real `notify.` service
  needs a custom integration — that's backlog #8's trigger territory).
