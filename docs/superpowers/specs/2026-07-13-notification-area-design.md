# Home-View Notification Area (NWS Alerts) — Design

**Date:** 2026-07-13
**Status:** Approved pending user review

## Goal

A general-purpose notification area on the home view. First producer: NWS weather
alerts from the `nws_alerts` custom integration (`sensor.nws_alerts_alerts`,
https://github.com/finity69x2/nws_alerts). Future producers (HA text notifications,
backlog #7) reuse the same model and UI without rework.

## Non-goals (YAGNI)

- No dismissal persistence across app restarts (in-memory only).
- No read-state, notification history, or notification sounds.
- No alerts on the weather panel — home view only.
- No HA-push notification producer yet (backlog #7 adds it later).
- No client-side expiry filtering — the sensor prunes expired alerts itself.

## Entity contract (verified against integration source, coordinator.py @ master)

- State: number of active alerts as a string (`"0"`, `"2"`, …), or
  `unavailable`/`unknown`.
- Attribute `Alerts`: JSON array of objects, each with string fields
  `Event`, `ID`, `URL`, `Headline`, `Type`, `NWSCode`, `Status`, `Severity`
  (`Extreme` | `Severe` | `Moderate` | `Minor` | `Unknown`), `Certainty`,
  `Sent`, `Onset`, `Expires`, `Ends` (ISO-8601 with offset; may be null/absent),
  `AreasAffected`, `Description`, `Instruction` (may be null).
- `ID` is stable per issued alert (regenerated when NWS updates an alert).

Note: at design time the live entity reported `unavailable` on the device —
integration config may need finishing in HA before on-device verification.

## Architecture

Same pattern as the EV/solar cards: a pure-JVM model function derives display
data from config + entity snapshot; DashboardShell computes it; HomeView renders.

### Model — `ui/model/NotificationModel.kt` (new, pure JVM)

```kotlin
enum class NotifSeverity { INFO, WARNING, CRITICAL }   // display order: CRITICAL first

data class NotificationItem(
    val key: String,        // stable identity (NWS alert ID); dismissal + list keys
    val severity: NotifSeverity,
    val title: String,      // one line, e.g. "Winter Storm Warning · until Tue 7 PM"
    val detail: String?,    // expanded text; null = row not expandable
)

fun nwsNotifications(
    sensorId: String?,
    minSeverity: NotifSeverity,
    entities: Map<String, EntityState>,
): List<NotificationItem>
```

Rules:
- `sensorId` null, entity missing, state non-numeric (`unavailable` etc.), or
  `Alerts` attribute absent/not an array → empty list.
- Severity mapping: `Extreme`/`Severe` → CRITICAL, `Moderate` → WARNING,
  `Minor`/`Unknown`/anything else → INFO.
- Filter: keep items with severity ≥ `minSeverity` (INFO < WARNING < CRITICAL).
- Title: `Event`, then `" · until <time>"` from `Ends` (fallback `Expires`)
  when parseable — format `EEE h:mm a` local time (e.g. "until Tue 7:00 PM"),
  omitting the day when it ends today (`h:mm a`).
- Detail: `Headline`, `Description`, `Instruction` joined with blank lines,
  null/blank parts skipped; whole thing null if all blank.
- Malformed array entries (missing `Event` or `ID`) are skipped, never throw.
- Sort: severity (CRITICAL first), then `Onset` ascending (unparseable last),
  then title.
- Key: `ID` field.

### Config — `config/DashConfig.kt`

```kotlin
data class NotificationsConfig(
    val nwsAlerts: String? = null,        // sensor entity id
    val nwsMinSeverity: String = "minor", // "minor" | "moderate" | "severe"
)
```

- New top-level `notifications: NotificationsConfig` on DashConfig, with the
  same trim/ifBlank + clamp treatment as other sections (`nwsMinSeverity`
  clamps to the valid set, defaulting to `"minor"`).
- `nwsAlerts` joins the EntityHub watch-list (`ids()`).
- Severity strings map: minor→INFO, moderate→WARNING, severe→CRITICAL.

### Config page — `assets/config/app.js`

New **Notifications** card:
- "NWS alerts sensor" entity picker (sensor domain), muted hint linking the
  behavior ("shows active alerts under the weather; swipe left to dismiss").
- "Minimum severity" select: Minor (default) / Moderate / Severe.

### UI — `ui/NotificationArea.kt` (new composable) wired into HomeView

- Anchored TopStart, `padding(start = 28.dp, top = 70.dp)` (just below the
  weather/AQI pill row), `widthIn(max = 640.dp)` so it never collides with the
  EV/solar stack, `heightIn(max = 280.dp)` with clipping so the bottom-left
  clock/date block is never covered.
- Hidden while the media takeover owns the screen (same condition as the pills)
  and when the list is empty. Fade in/out 600ms like the cards.
- Row: rounded pill styling matching existing pills (black 35% background,
  RoundedCornerShape), a 4dp severity accent bar on the left edge
  (CRITICAL 0xFFE05555 red, WARNING 0xFFE0A030 amber — existing GaugeAmber,
  INFO 0xFF9E9E9E gray), title single line ellipsized, 18sp white.
- Tap toggles in-place expansion: detail text at 14sp, 90% white, max 6 lines
  ellipsized. Only one row expanded at a time (tapping another collapses the
  first). Rows with null detail don't expand.
- Max 4 rows shown; if more remain after dismissals, a final non-interactive
  muted row: "+N more".
- Swipe left ≥ ~30% of row width → row animates off-screen left and is
  dismissed. Right swipe does nothing (snaps back).

### Dismissal state — DashboardShell level

- `dismissedKeys: MutableSet<String>` held in a `remember { mutableStateOf }`
  at DashboardShell scope (survives view switches and takeover unmounts;
  process-lifetime only).
- Applied as a filter after `nwsNotifications()`; pruned each recompute to
  keys still present in the un-dismissed list's source (so the set can't grow
  unboundedly). Pruning is skipped while the sensor is not reporting a numeric
  state (e.g. `unavailable` during an HA restart): an empty list then means
  "unknown", not "no alerts", and pruning would resurrect dismissed alerts.
- A dismissed alert reappears only if NWS reissues it under a new `ID`
  (or the app restarts) — intended behavior.

## Data flow

EntityHub subscribe (sensor id in watch-list) → entity snapshot →
`nwsNotifications(cfg.notifications.nwsAlerts, minSev, entities)` in
DashboardShell → minus `dismissedKeys` → `HomeView(notifications = …,
onDismiss = { key -> dismissedKeys += key })` → `NotificationArea`.

## Error handling

- Any parse failure at any level degrades to "fewer/no notifications", never a
  crash: model returns empty or skips entries.
- Entity `unavailable` (integration down / HA restarting) → area disappears;
  it returns when the sensor recovers.

## Testing

Plain-JVM JUnit4 (no android.*), `NotificationModelTest`:
- severity mapping incl. unknown strings → INFO
- min-severity filtering at each threshold
- sort order (severity, then onset, then title)
- title "until" formatting: Ends present, Ends null + Expires fallback,
  both unparseable → bare Event; same-day vs other-day format
- detail assembly with null Instruction / all-blank → null
- `unavailable` state → empty; missing Alerts attr → empty
- malformed entry (no ID) skipped, valid siblings kept
- config clamp: bad `nwsMinSeverity` string → "minor" (in DashConfigTest)

On-device verification: build gate + flash; visual check needs a live alert or
temporarily pointing the picker at a hand-built test entity in HA (e.g. a
template sensor mimicking the attribute shape). Swipe/tap verified whenever an
alert is available; layout (clock never covered) verifiable with any alert.
