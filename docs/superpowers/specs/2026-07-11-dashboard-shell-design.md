# Dashboard Shell & Panels — Design

**Date:** 2026-07-11
**Target device:** Amazon Echo Show 5, LineageOS 18.1 (Android 11), 960×480 landscape
**Goal:** Replace the single clock/temperature screen with a multi-view dashboard modeled on the reference kiosk photo: a right-side touch rail switching between a photo-backed home clock view and five control/info panels, all fed by HA labels over the existing authenticated WebSocket.

## Background & decisions

- Reference: Printables kiosk photo — centered large clock, date below, small weather pill, vertical icon rail on the right with a highlighted active button.
- Entity configuration is **label-driven**: the user tags entities in the HA UI; the app resolves labels from the entity registry. No on-device pickers; the existing "Change sensor" picker flow is retired.
- Home background is a **photo slideshow** sourced from HA's media folder, falling back to the current dusk gradient.
- Panels chosen by user: Lights (grouped), Climate (thermostat control), Media (this device only), Weather (current + forecast), Solar (power flow + today's energy).
- Auto-return to Home after 60 s without touch.
- Font: bundled **Nunito** (Light/Regular/SemiBold TTFs in `res/font`), applied app-wide via Compose Typography. Clock uses Nunito Light.

## Shell layout

- Right rail: ~72 dp wide, translucent dark (~55% black) rounded panel inset from the screen edge, vertically centered. Six icon buttons top-to-bottom: **Home, Lights, Climate, Media, Weather, Solar**. Active view's icon sits in an accent-colored rounded square (like the reference's highlighted home button). Material icons; no text labels.
- The active view fills the whole screen; the rail floats above it. View switches crossfade.
- Every touch anywhere (rail, panels, home) calls `KioskController.onUserInteraction()` (existing VACA hook: wake + screensaver reset + timeout re-arm).
- Idle timer: 60 s after the last touch, any non-Home view returns to Home. Timer logic is a plain testable class, not buried in composables.
- The amber offline dot (top-right, shown when `ConnState != CONNECTED`) and the long-press dropdown menu (Android settings, Log out — "Change sensor" removed) remain on the Home view only.
- `EntityPickerScreen.kt` and the stored sensor-id setting are removed along with the `TempReading` single-entity path.

## Home view

Layered bottom-up:

1. **Photo backdrop** (see Photo slideshow) or dusk-gradient fallback.
2. **Scrim:** full-screen 35% black so text stays legible on any photo.
3. **Center column:** clock (`HH:mm` / `h:mm a` per system 24-h setting, 96 sp, Nunito Light) → date line (`EEEE d MMMM`, e.g. "Tuesday 30 June", ~24 sp) → **weather pill**: rounded translucent chip with condition icon + condition text + temperature, e.g. "🌧 Rainy · 14.1 °C".
   - Pill temperature: first `echo-temp`-labeled sensor's state + unit; if none, the `echo-weather` entity's `temperature` attribute; if neither, the pill hides.
   - Pill condition: `echo-weather` entity state mapped to a small icon set (sunny, clear-night, partly cloudy, cloudy, rain, snow, storm, fog, wind; unknown → generic). If no weather entity, pill shows temperature only.
   - Staleness: if the temp sensor's last update is >15 min old, dim the pill (existing `isStale` rule).

## Label scheme & EntityHub

Labels the user creates in HA (matched by label **id/slug**, case-insensitive):

| Label | Role |
|---|---|
| `echo-temp` | Home-pill temperature sensor |
| `echo-weather` | Weather entity for pill + weather panel |
| `echo-lights` and any `echo-lights-<group>` | Lights panel; suffix = on-screen group |
| `echo-climate` | Thermostat (`climate.*`) entities for climate panel |
| `echo-solar-pv` / `echo-solar-load` / `echo-solar-grid` | Solar live power sensors (W); grid optional |
| `echo-solar-pv-today` / `echo-solar-load-today` | Solar today's energy sensors (kWh) |

**`EntityHub`** (new, `ha/` package, pure JVM logic behind the existing WebSocket client):

- On (re)connect: sends `config/entity_registry/list`; builds `label → [entity_id]` from each entry's `labels` array, keeping only `echo-*` labels. Where a friendly display name is needed the registry `name`/`original_name` is used, falling back to the state's `friendly_name` attribute.
- Subscribes once via `subscribe_entities` with the full matched entity-id list; maintains `StateFlow<Map<String, EntityState>>` where `EntityState` = state string + attributes + last-updated. Applies the command's initial `add` snapshot and subsequent `change` deltas (`+` additive attr/state changes, `-` removals).
- Listens for `subscribe_events` of `entity_registry_updated`; on any such event, re-lists the registry and, if the matched set changed, tears down and re-opens the entity subscription. Live re-labeling in HA updates the device without restart.
- Exposes `callService(domain, service, serviceData, target)` (fire-and-forget with error logging) and `getForecasts(entityId)` (`weather.get_forecasts`, `type: "daily"`, `return_response: true`).
- All panel models derive from these flows; the old single-temperature subscription code is deleted.

## Panels

### Lights

- Matches every label starting with `echo-lights`. Bare `echo-lights` → ungrouped section listed first; `echo-lights-<suffix>` → a group headed by the title-cased suffix ("living-room" → "Living Room"). Groups sort alphabetically. An entity in multiple groups appears in each.
- Each entity renders a toggle tile: domain icon (lightbulb / power plug / fan by domain), friendly name, accent-filled background when state is `on`, muted when `off`, dimmed+disabled when `unavailable` or disconnected.
- Tap → `homeassistant.toggle` targeting the entity. No optimistic UI: the tile changes when the subscription delta lands.
- Layout: vertically scrolling column of group sections, tiles flowing in rows (~4 per row beside the rail).

### Climate

- `echo-climate`-labeled `climate.*` entities. One thermostat fills the panel; multiple sit side by side.
- Per thermostat: large current temperature (from `current_temperature` attribute), target setpoint with **+ / −** buttons stepping ±0.5° (respecting `min_temp`/`max_temp` attributes) → `climate.set_temperature`; status line from `hvac_action` attribute ("heating" / "idle" / …); a mode row built from the entity's `hvac_modes` attribute, current mode highlighted, tap → `climate.set_hvac_mode`.
- Setpoint buttons debounce: rapid taps accumulate locally and send once 800 ms after the last tap, so five quick +taps make one service call.

### Media (this device)

- Shows the Echo's own VACA media player only. `MediaBridge` gains an observable state (`StateFlow<MediaUiState>`: playing flag, current URL or "Nothing playing", effective volume 0–100) — a read-side addition; the VACA protocol behavior is unchanged.
- Controls: play/pause and stop buttons (drive `MediaBridge` exactly as the VACA `play`/`pause`/`stop` actions do, so HA's media_player entity stays in sync via the existing `sendStatus` feedback), volume slider 0–100 mapped to the same path as `set-volume`.

### Weather

- `echo-weather` entity. Left: large condition icon, condition text, current temperature and humidity attributes. Right: 5 forecast columns (day-of-week, condition icon, high/low) from `getForecasts`.
- Forecast refreshes on panel open and every 30 min while the app runs; a failed call keeps the previous forecast (or shows current-conditions-only on first failure).

### Solar

- Three-node power flow, left-to-right: **Solar → Home ↔ Grid**, each node a labeled circle with live watts beneath (values from the `echo-solar-pv` / `echo-solar-load` / `echo-solar-grid` sensors; W vs kW formatted from the sensor's unit attribute). Arrows between nodes indicate direction; the grid arrow flips with the grid sensor's sign (positive = import — if the user's sensor is inverted they can template it in HA).
- No `echo-solar-grid` sensor → two-node Solar → Home layout.
- Bottom row: "Today: X kWh produced · Y kWh used" from the `-today` sensors; each hides if unlabeled.

### Empty states

Any panel whose labels match nothing shows a centered hint, e.g. "Label entities with `echo-lights` in Home Assistant". The rail button still works.

## Photo slideshow

- Source folder: HA media dir `media/echo-frame/` (user drops photos via Samba/HA UI).
- **`PhotoStore`** (new component): via WebSocket, `media_source/browse_media` on `media-source://media_source/local/echo-frame`; for each image child, `media_source/resolve_media` → authenticated URL; downloads with the existing OkHttp client into the app cache dir, decoding/downsampling to ≤960×480 before saving. Skips already-cached files (keyed by media content id), deletes cached files no longer listed.
- Sync triggers: app start (once connected), reconnect, and every 6 h. Failures skip the file and keep the existing cache.
- **Backdrop:** cycles cached photos in shuffled order, advancing every 5 min with a 1 s crossfade, center-cropped. Zero cached photos → the existing `DuskBackground` gradient. Constants (5 min cycle, 6 h sync, folder name) live in one place.

## Error handling

| Condition | Behavior |
|---|---|
| WebSocket disconnected | Panels render last-known state; toggle/setpoint/mode controls disabled; offline dot on Home |
| Entity in matched set goes `unavailable` | Tile/value dimmed, controls for it disabled |
| Registry has no `echo-*` labels at all | Home works (gradient, clock, date, no pill); panels show hints |
| Service call error response | Log; UI simply won't change (no snackbars) |
| Forecast call fails | Keep last forecast; current conditions still render |
| Photo folder missing / browse fails | Log, gradient fallback, retry at next sync trigger |
| Malformed photo file | Decode failure → delete from cache, skip |

## Testing

Plain-JVM JUnit4, as established:

- `EntityHub`: label resolution from registry JSON (echo-prefix filtering, lights grouping incl. multi-label), `subscribe_entities` add/change/remove delta application, re-subscribe on registry change.
- Idle-return timer: touch resets, fires at 60 s, Home exempt.
- Lights grouping/ordering; climate setpoint clamp to min/max and debounce accumulation; solar model (grid present/absent, sign→direction, W/kW formatting); forecast response parsing; weather-pill fallback chain (sensor → weather attr → hidden).
- `PhotoStore`: list-diffing (new/removed files), sync-trigger scheduling logic. Downloads/decoding stay thin and untested.
- UI composables stay thin; `MediaUiState` derivation tested via `MediaBridge`'s existing fake-engine tests.

Manual verification: label entities in HA → panels populate live; toggle lights; adjust thermostat; play media from HA and control on-device; weather forecast renders; solar flow tracks a sunny moment; drop photos into `media/echo-frame/` → slideshow appears within a sync cycle; idle 60 s returns Home; VACA controls (screensaver, announce) still work over the new UI.

## Out of scope

Multiple photo sources (Immich etc.), controlling other HA media players, per-panel VACA `current_path` reporting, camera views, astronomy, on-device configuration UI, portrait layouts.

## References

- Reference photo: Printables print 75771ccf (Echo Show-style kiosk, right-rail dashboard).
- Prior specs: `2026-07-10-echo-ha-dashboard-design.md` (MVP), `2026-07-11-vaca-protocol-support-design.md` (VACA).
