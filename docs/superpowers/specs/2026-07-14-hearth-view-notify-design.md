# Hearth Integration Sub-project B: View Select + Notify — Design

**Date:** 2026-07-14
**Status:** Approved (user chose both recommended options; execution authorized as an
autonomous overnight run)
**Motivation:** The payoff entities that justified owning the integration
(sub-project A shipped and cut over same day; VACA removed from HA). B adds:
a **select entity** for the current dashboard view (enables voice view-switching
via HA sentence-trigger automations) and a **notify platform** (replaces the
`rest_command` YAML round-trip). Both need small app-side wire handlers.

**User decisions:**
- **Notify shape: entity + rich service.** A standard notify entity per device
  (stock automation `notify:` actions, title+message) PLUS `hearth.notify` /
  `hearth.notify_clear` services carrying the full notification-area fields
  (severity, timeout, stable id). The existing token-gated HTTP `/api/notify`
  and the user's `rest_command` keep working — retire later at leisure.
- **View + wake: HA-initiated view changes wake the screen** (and exit night
  mode) so "show me the cameras" always produces visible cameras; normal idle
  return and night re-entry resume afterwards.

## Wire protocol additions (existing custom-event envelope; NO codec/framing change)

New HA→device actions (flat `{event_type:"action", action, payload}`):
- `set-view` `{view: "home"|"lights"|"climate"|"media"|"calendar"|"weather"|"solar"|"cameras"}`
  — lowercase names of `DashView` (DashViews.kt:31). Unknown view → log + ignore.
  View whose panel is disabled in config → log + ignore (the select entity snaps
  back on the next status event). Always wakes the screen / exits night mode and
  re-arms the idle timer, exactly as a user touch would.
- `notify` `{id?, title, message?, severity?, timeout?}` — same field semantics
  as HTTP `POST /api/notify`: blank/missing title → ignore + log; severity one of
  info/warning/critical (unknown → INFO, `PushNotificationStore.severityOf`);
  timeout seconds ≤0/absent → persistent; re-posting an id replaces that row.
  Feeds `PushNotificationStore.post(...)`.
- `notify-clear` `{id}` or `{all: true}` — mirrors HTTP `POST /api/notify/clear`
  (`clear(id)` idempotent, `clearAll()`).

New device→HA status content:
- `{sensors: {current_view: "<lowercase DashView>"}}` — sent whenever the
  current view changes AND included in the post-run-satellite snapshot
  (`statusSnapshot()`, App.kt:370 — replaces the hardcoded
  `current_path: "dashboard"`, which nothing consumes since VACA's removal;
  `orientation` stays).

## App side

- The current `DashView` lives in the dashboard UI (`DashboardShell(current,
  onSelect)`); hoist/observe it in `AppDeps` (a `MutableStateFlow<DashView>` the
  UI writes and a collector that calls `vaca.sendStatus` on change — same
  pattern as the existing light-sensor status sends). HA-initiated `set-view`
  flows the other way into the same state, so the select entity and the rail
  stay in lockstep regardless of who switched.
- Wake-on-set-view reuses the existing user-interaction path
  (`KioskController.onUserInteraction()` semantics: clears screensaver, wakes
  screen, re-arms timeout); night mode's touch-wake behavior applies (wakes for
  its 60 s window, re-enters if still dark — acceptable and consistent).
- Action handling extends the existing `VacaServer.Listener.onAction` dispatch
  in `AppDeps` (`kiosk.handleAction` / `media.handleAction` pattern): notify
  actions route to `PushNotificationStore` with `System.currentTimeMillis()`;
  set-view routes to the view state + wake. Pure parsing/clamping logic goes in
  a plain-JVM-testable unit, following the repo's testing philosophy.

## Integration side (`custom_components/hearth/`, version 0.1.0 → 0.2.0)

- **`select.py`** — one select entity per device: options = the 8 lowercase
  view names in `DashView` declaration order; `current_option` from status
  `sensors.current_view` (None until first report); `async_select_option` →
  `async_send_action("set-view", {"view": option})`. Availability/listener
  lifecycle identical to the existing switch platform. Options are static —
  the app ignores disabled panels and the entity snaps back (documented in the
  entity description).
- **`notify.py`** — one stock `NotifyEntity` per device:
  `async_send_message(message, title=None)` → action
  `notify {"title": title or "Notification", "message": message}`.
- **Services** (registered like `hearth.toast`, targeting device/entity):
  - `hearth.notify`: `title` (required), `message?`, `severity?`
    (selector: info/warning/critical), `timeout?` (seconds), `id?` → action
    `notify {...}` per matched device.
  - `hearth.notify_clear`: `id?`, `all?` (bool; exactly one required —
    validated in the handler) → action `notify-clear`.
- `services.yaml`, `strings.json`, `translations/en.json` extended; `PLATFORMS`
  gains `select` and `notify`; manifest `version: "0.2.0"` so HACS offers the
  update.
- **No changes to `codec.py`/`client.py`** — everything rides
  `async_send_action` and the existing status dispatch + snapshot-replay cache.

## Testing & verification (overnight-run constraints: no access to the user's HA)

- Kotlin: plain-JVM tests for the new action parsing/dispatch and view-report
  logic; full gradle gate.
- Python: `py_compile` + JSON validation over changed files; pytest suite must
  stay green (no protocol-layer changes expected — if the plan does touch
  client.py, it needs fake-server tests).
- **Live app-side verification WITHOUT HA**: a throwaway Python script (scratchpad)
  imports the repo's own `client.py` via the `hearth_proto` loading trick and
  connects to a real device (tablet 10.75.0.183:10700): send `set-view cameras`
  → adb screenshot shows the Cameras panel + status event carries
  `current_view: "cameras"`; send `notify` → screenshot shows the notification
  row; `notify-clear` removes it; `set-view` for a disabled panel is ignored.
  NOTE: this connection evicts HA's live session (newest-wins) — deliberate and
  self-healing (the integration reconnects with backoff ≤60 s after the script
  disconnects).
- **Morning user steps (cannot be automated):** HACS → redownload Hearth
  (0.2.0) → restart HA → per device the new select + notify entities appear;
  try the select from the HA UI, a stock `notify:` action, and `hearth.notify`
  with severity/timeout; optionally wire a sentence-trigger automation
  ("show the cameras" → `select.select_option` using the triggering device).

## Out of scope (YAGNI)

- Occupancy/presence entity.
- Removing the HTTP `/api/notify` path or the config page's rest_command YAML
  (works, user-installed; retire separately if ever).
- Dynamic select options filtered by enabled panels (static list + app-side
  ignore is simpler and self-corrects).
- Voice sentence automations themselves (HA-side user config).
- Renaming the app's legacy `vaca` package (cosmetic, separate cleanup).
