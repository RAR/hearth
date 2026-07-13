# Night Mode (Ambient-Light Night Clock) — Design

**Date:** 2026-07-12
**Status:** Approved by user ("looks good", autonomous overnight execution authorized)

## Goal

When the room gets dark, replace the dashboard with a huge, dim, time-only clock on pure
black and drop the backlight to a configured (near-minimum) level. When the room lightens,
or something needs the screen, return to the normal dashboard at normal brightness.

User's words: "When the light sensor gets low enough change the screen to a large clock
with the back light very low."

## User decisions (locked)

- **Touch at night:** wakes to the normal dashboard at normal brightness; after 60 s with
  no interaction, drops back to the night clock if the room is still dark.
- **Overrides (all four):** music takeover, doorbell popup, voice interaction overlay, and
  active timers each suppress night mode at normal brightness; when the override ends in a
  still-dark room, night returns immediately.
- **Config:** enable toggle + entry lux threshold (live lux reading shown beside it) +
  night backlight level. Exit hysteresis and dwell times are built-in constants.
- **Night clock content:** time only. Huge dim-gray clock centered on pure black. No date,
  no pills, nothing else.

## Existing plumbing (verified on device 2026-07-12)

- The ROM exposes a working ambient light sensor (`android.sensor.light`, on-change,
  reading ~70 lux in evening room light).
- `vaca/LightSensorReporter.kt` already reads it, throttled: emits on ≥20 % change
  (min 5 lux) or every 30 s. So the controller receives a sample **at least every 30 s**.
- `App.kt:170` wires the reporter: `{ lux -> mainScope.launch { kiosk.onLightLevel(lux) } }`
  and also reports lux to HA via VACA sensors.
- `vaca/KioskController.onLightLevel(lux)` maps lux 0–400 → 10–100 % window brightness
  while auto-brightness is on.
- `vaca/AndroidKioskDevice.setBrightness(percent)` maps 0–100 onto window brightness with
  a 0.01 floor (via MainActivity WindowHooks); negative restores system default.
- Override signals all live in the `Dashboard` composable in `App.kt`:
  `takeoverVisible` (~line 362), `doorbellPopup != null` (~line 377),
  `voiceOverlayState` (`voice.VoiceOverlayState`, ~line 465),
  `timersState` (`voice.TimersUiState`, ~line 466).
- Config page status endpoint: `web/ConfigServer.kt` `handleStatus()` serves
  `GET /api/status`; `assets/config/app.js` polls it.

## Architecture

Four pieces, one new package:

1. **`night/NightModeController.kt` (new, plain Kotlin, no Android imports)** — the state
   machine. Unit-testable on the JVM like `NowPlayingStore`.
2. **`KioskController.setNightDim(active: Boolean, percent: Int)` (new method)** — the
   brightness handoff. KioskController remains the single brightness owner.
3. **`ui/NightClockOverlay.kt` (new composable)** — the black screen + big dim clock,
   rendered above the dashboard content in `DashboardShell`.
4. **`config/DashConfig.kt` `NightSettings` + config-page Night mode card** — settings,
   with the live lux reading added to `/api/status`.

### 1. NightModeController

Plain-Kotlin class; all inputs called on the main thread (from the Dashboard composable
and the sensor callback hop, both main-dispatched), so no locking is required — but state
mutation is confined to those callers.

```kotlin
class NightModeController {
    val nightActive: StateFlow<Boolean>

    fun onConfig(enabled: Boolean, thresholdLux: Int)
    fun onLux(lux: Float, nowMs: Long)
    fun onOverride(active: Boolean, nowMs: Long)   // takeover || doorbell || voice || timers
    fun onUserInteraction(nowMs: Long)             // any touch while night is active or not
    fun onTick(nowMs: Long)                        // periodic re-evaluation (see cadence note)
}
```

Timing is **timestamp-based** (callers pass `nowMs` = `SystemClock.elapsedRealtime()` from
App wiring; tests pass fake clocks). Constants:

- `ENTER_DWELL_MS = 30_000` — lux must stay below the entry threshold this long to enter.
- `EXIT_DWELL_MS = 10_000` — lux must stay above the exit threshold this long to exit.
- `TOUCH_HOLD_MS = 60_000` — after a touch, night stays off at least this long.
- Exit threshold = `max(threshold * 2, threshold + 10)` lux — the gap absorbs the screen's
  own glow so backlight changes cannot oscillate the sensor across the boundary.

Transition rules:

- **Enter** when: enabled, no override active, `nowMs - lastTouchMs >= TOUCH_HOLD_MS`, and
  every lux sample for the past `ENTER_DWELL_MS` was `< thresholdLux` (tracked as
  `belowSinceMs`; a sample at/above the entry threshold clears it; a sample above resets).
- **Exit immediately** when: a touch arrives, an override turns on, or the feature is
  disabled via config.
- **Exit on light** when lux samples have stayed `>= exitThreshold` for `EXIT_DWELL_MS`
  (tracked as `aboveSinceMs`; a sample below the exit threshold clears it).
- **Dwell tracking continues while suppressed** (override on, or inside touch-hold): the
  `belowSinceMs` clock keeps running, so when the override ends or the touch-hold expires
  in a still-dark room, night re-enters on the next evaluation without a fresh 30 s wait.
- Lux samples between the entry and exit thresholds clear `aboveSinceMs` but do **not**
  clear `belowSinceMs` once night is active (they only block *entry* — entry requires
  samples strictly below the entry threshold; exit requires samples at/above the exit
  threshold; the dead band in between holds the current state).
- A threshold/enabled change from config re-evaluates immediately: disabling exits;
  a threshold change resets both dwell clocks (`belowSinceMs`/`aboveSinceMs`).
- No lux sample ever received (`hasSensor == false`, or sensor silent) → never enters.

**Evaluation cadence:** transitions are evaluated inside every input call (`onLux`,
`onOverride`, `onUserInteraction`, `onConfig`, `onTick`). Because the reporter guarantees
a sample at least every 30 s, entry happens within ~30–60 s of the room going dark with no
extra timer. The touch-hold expiry, however, needs a nudge when the room is silent and
dark (no lux change events): App wiring runs a lightweight ticker — a `LaunchedEffect`
calling `onTick(now)` every 5 s **only while** (night is suppressed by touch-hold or
override) or night is active. When night is fully off and unsuppressed, no ticker runs.

### 2. Brightness handoff — KioskController.setNightDim

```kotlin
/** Night clock dimming: while active, pins brightness to [percent] and ignores
 *  auto-brightness lux updates; clearing restores the normal auto/manual value. */
fun setNightDim(active: Boolean, percent: Int)
```

- `setNightDim(true, p)` → remembers night state, calls `device.setBrightness(p)`.
- While night-dim is active, `onLightLevel()` returns early (auto-brightness suspended)
  and `screen_brightness` settings from HA update the stored value but are not applied.
- `setNightDim(false, _)` → reapplies: `device.setBrightness(brightness)` if manual, else
  nothing (the next `onLightLevel` sample restores auto within ≤30 s — acceptable because
  the exit paths are either a touch/override, where the *following* lux sample corrects
  it, or a lit room, where samples are flowing anyway). To avoid a stale-dim window,
  `setNightDim(false, _)` also immediately applies the auto formula using the last lux
  value it saw: KioskController keeps `lastLux` updated in `onLightLevel` even while
  returning early, and on clear applies `10 + (lastLux 0..400 → ×90)` when auto is on.
- Night-dim state is runtime-only: not persisted, not in `currentSettings()`.

### 3. NightClockOverlay

New `ui/NightClockOverlay.kt`, rendered in `DashboardShell` as a top layer (above panels
and rail; the doorbell popup / voice overlay / timer alert never coexist with it because
their activity suppresses `nightActive` before they draw).

- `Box(fillMaxSize().background(Color.Black))` with a centered clock: the same time
  format as HomeView's clock (12-hour, AM/PM suffix), ~120 sp, `FontWeight.Light`,
  color `Color(0xFF777777)` (dim gray), AM/PM smaller (~28 sp) in `0xFF555555`.
- The minute ticker reuses the same pattern HomeView uses for its clock updates.
- A `pointerInput` on the overlay consumes all touches: the waking tap calls the
  wake callback (`onWake`) and must NOT reach panels underneath.
- Wrapped in `Crossfade` (or `AnimatedVisibility` fade) keyed on `nightActive`, matching
  the takeover transition style.
- The shell's rail auto-hide and idle-return logic must treat night as "not home
  interaction": entering night does not need special-casing beyond the overlay sitting on
  top; the overlay swallows input so idle timers simply never re-arm from ghost touches.

### 4. Config

`DashConfig.kt`:

```kotlin
@Serializable
data class NightSettings(
    val enabled: Boolean = false,
    val thresholdLux: Int = 10,
    val brightness: Int = 0,      // 0 = minimum backlight (window-brightness floor 0.01)
) {
    fun clamped() = copy(
        thresholdLux = thresholdLux.coerceIn(1, 1000),
        brightness = brightness.coerceIn(0, 100),
    )
}
```

- `DashConfig` gains `val night: NightSettings = NightSettings()`; `DashConfig.clamped()`
  calls `night.clamped()`. (No entity references — `referencedEntityIds()` unchanged.)

Config page (`index.html` + `app.js`):

- New **Night mode** card: enable toggle, "Enter below (lux)" number input, "Night
  brightness (%)" number input, defensive defaults for old configs (same pattern as the
  Media card), help text explaining the behavior and the live reading.
- **Live lux:** `handleStatus()` in `ConfigServer.kt` adds `"lux": <int or null>` to the
  status JSON. App wiring keeps a `@Volatile var lastLux: Int?` (or an atomic holder)
  updated from the existing sensor callback and hands a `() -> Int?` provider to
  ConfigServer at construction (same style as existing providers). `app.js` already polls
  `/api/status`; the Night card shows "Current reading: N lux" (or "no sensor") and
  refreshes with the poll.

### App wiring (Dashboard composable in App.kt)

- `AppDeps` gains `val nightMode = NightModeController()`.
- The existing sensor callback (App.kt:170) additionally calls
  `deps.nightMode.onLux(lux, SystemClock.elapsedRealtime())` (inside the same
  `mainScope.launch` hop) and updates the `lastLux` holder for ConfigServer.
- In the Dashboard composable:
  - `LaunchedEffect(config.night)` → `nightMode.onConfig(enabled, thresholdLux)`.
  - Override flag: `LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState,
    timersState)` → `nightMode.onOverride(takeoverVisible || doorbellPopup != null ||
    voiceOverlayState is active-phase || timersState has running/alerting timers, now)`.
    "Voice active" = the overlay would render (its phase is not idle); "timers active" =
    any timer chip visible or an alert ringing — exact field names resolved at plan time
    from `VoiceOverlayState`/`TimersUiState`.
  - Touch: the shell's existing Initial-pass `pointerInput` (the one that counts
    `railTouches`) also calls `nightMode.onUserInteraction(now)`; the NightClockOverlay's
    own waking tap calls it too (and `kiosk.onUserInteraction()` so the kiosk timeout
    logic stays consistent).
  - Conditional ticker `LaunchedEffect` per the cadence note above.
  - Mirror: `LaunchedEffect(nightActive, config.night.brightness)` →
    `kiosk.setNightDim(nightActive, config.night.brightness)`.
  - `nightActive` threads into `DashboardShell`, which renders `NightClockOverlay`.
- **Screen-off interplay:** if HA has turned the screen off (`screen_on = false`), the
  kiosk's black screen-off state already covers everything; night mode may be "active"
  underneath but `setNightDim` only touches brightness while the screen is on
  (`AndroidKioskDevice.setBrightness` is already a no-op while `ui.screenOff`). No extra
  coupling.

## Error handling

- **No sensor / sensor silent:** no lux samples → `belowSinceMs` never set → never enters.
  Config page shows "no sensor" for the live reading if lux is null.
- **Light spikes (headlights, phone screens):** shorter than `EXIT_DWELL_MS` (10 s) — the
  spike clears and `aboveSinceMs` resets; night persists.
- **Brief dark dips (shadow over sensor):** shorter than `ENTER_DWELL_MS` (30 s) — no entry.
- **Config edits at night:** disable exits immediately; threshold changes reset dwell
  clocks and re-evaluate.

## Testing (plain-JVM JUnit4, no Android classes)

`night/NightModeControllerTest.kt` — fake clock (plain Long ms), drive `onLux`/`onTick`:

1. Enters after lux below threshold for 30 s; not before.
2. A single sample at/above threshold during the dwell resets it (brief dip → no entry).
3. Exits after lux ≥ exit threshold for 10 s; a 5 s spike does not exit.
4. Dead-band samples (between thresholds) hold the current state in both directions.
5. Touch exits immediately; no re-entry until 60 s passes; re-enters right after the hold
   expires when the room stayed dark (dwell continued during suppression).
6. Override on exits immediately; override off in a dark room re-enters immediately.
7. Disabled never enters; disabling while active exits.
8. Threshold change resets dwell clocks.
9. No lux samples → `onTick` alone never enters.

`vaca/KioskControllerTest.kt` additions:

10. `setNightDim(true, 0)` pins brightness 0 and suppresses `onLightLevel` application.
11. `setNightDim(false, _)` with auto-brightness on immediately reapplies the auto formula
    from the last seen lux; with manual, reapplies the stored manual value.
12. HA `screen_brightness` while night-dim active updates stored state without applying;
    applied on clear.

`config/DashConfigTest.kt` (or existing config test file) additions:

13. `NightSettings.clamped()` bounds, defaults on old configs missing the `night` key.

Build gate (unchanged): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q
:app:testDebugUnitTest :app:assembleDebug` from repo root.

## Global constraints (inherited, binding)

- Kotlin 2.1.0; compileSdk 34 (never bump); media3 exactly 1.4.1; NanoHTTPD 2.3.1;
  **no new dependencies**.
- Device is Android 11 / API 30 (LineageOS 18.1, Echo Show 5, 960×480 landscape).
- Plain-JVM JUnit4 tests only; no Robolectric; no Android classes in tests.
- Commit trailer on every commit:
  `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`

## Out of scope

- Reporting night state to HA (could ride VACA feedback later).
- Scheduling (time-of-day rules) — purely sensor-driven.
- Changes to the kiosk screen-off/timeout behavior.
