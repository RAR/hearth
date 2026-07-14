# HA Calendars on Hearth — Design

**Date:** 2026-07-14
**Status:** Approved by user (surface: both; multi-calendar color-coded; 3-day window; home card visible whenever anything is within the window)

## Goal

Show Home Assistant calendar events (e.g. `calendar.andrew_s_personal`) on the Hearth dashboard in two places: a compact next-event card on the home screen and a 3-day agenda panel on the rail.

## Data flow

- `EntityHub` gains `getCalendarEvents(entityIds: List<String>): JsonElement?` — one websocket `call_service` frame, mirroring the existing `getForecasts` (EntityHub.kt:142):
  - `domain: "calendar"`, `service: "get_events"`, `return_response: true`
  - `target.entity_id`: JSON array of all configured calendar entities (one call for all calendars)
  - `service_data.start_date_time`: now, `service_data.end_date_time`: now + 3 days, both RFC3339 with local offset (e.g. `2026-07-14T11:30:00-04:00`)
  - Response shape: `{"response": {"calendar.x": {"events": [{"summary", "start", "end", ...}]}}}`. Timed events carry RFC3339 `start`/`end`; all-day events carry bare dates (`2026-07-15`).
- Fetch lives at Dashboard scope (App.kt, next to the expiry-prune effect), NOT panel-local, because the home card needs data without the panel opening:
  - `LaunchedEffect` keyed on the configured calendar list: fetch immediately, then every 15 minutes.
  - Parse errors / null responses keep the previous good list (same policy as the forecast fetch).
  - No configured calendars → no fetch, empty list.
- Parsed events land in a `MutableState<List<CalendarEvent>>` passed down to DashboardShell → HomeView + CalendarPanel.

## Model — `ui/model/CalendarModel.kt` (plain JVM, no android.*)

```kotlin
data class CalendarEvent(
    val calendarName: String,   // display name from config
    val colorArgb: Long,        // resolved palette color from config
    val title: String,          // event summary; blank summary -> "(untitled)"
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)
```

- `parseCalendarEvents(result: JsonElement?, configs: List<CalendarConfig>, zone: ZoneId): List<CalendarEvent>`
  - Walks `response.<entity>.events`; entities missing from the response are skipped.
  - Timed: `OffsetDateTime.parse` on `start`/`end`. All-day: `LocalDate.parse` → `atStartOfDay(zone)`; `allDay = true` when the `start` string has no `T`.
  - Malformed entries (unparseable dates, missing start) are dropped, never throw.
  - Output sorted by `startMs` ascending.
- `agendaDays(events, nowMs, zone): List<AgendaDay>` where `AgendaDay(label: String, events: List<CalendarEvent>)`:
  - Exactly 3 entries: labels `"Today"`, `"Tomorrow"`, then day name (`"Wednesday"`).
  - An event appears on every day-column its `[startMs, endMs)` span overlaps within the window.
  - Within a day: all-day events first, then by `startMs`; ties keep input order (stable sort).
  - Events already ended (`endMs <= nowMs`) are excluded everywhere.
- `nextEventCard(events, nowMs): CalendarEvent?` — first event with `endMs > nowMs` (a currently-running event counts); null when none.
- `eventTimeLabel(event, nowMs, zone, is24h): String` — home-card/panel time text:
  - Running now → `"Now"`; all-day → `"All day"` (home card: `"Today"` / `"Tomorrow"` prefix as below)
  - Panel rows: start time only, `"9:00 AM"` (or `"09:00"` when the device clock format resolves 24h — reuse the existing `ClockFormat` resolution used by the home clock).
  - Home card day prefix: same-day → time only; next day → `"Tomorrow 9:00 AM"`; later → `"Wednesday 9:00 AM"`.

## Config — `config/DashConfig.kt` + web page

```kotlin
@Serializable
data class CalendarConfig(
    val entity: String = "",     // calendar.* entity id
    val name: String = "",       // display name; blank -> entity id tail
    val color: String = "blue",  // palette key
)
```

- `Entities.calendars: List<CalendarConfig> = emptyList()`.
- Palette (fixed, model-side map `calendarColorArgb(key)`): `blue 0xFF64B5F6`, `green 0xFF81C784`, `amber 0xFFFFD54F`, `red 0xFFE57373`, `purple 0xFFBA68C8`, `teal 0xFF4DB6AC`, `orange 0xFFFFB74D`, `pink 0xFFF06292`. Unknown key → blue.
- `clamped()`: drop entries with blank entity, cap list at 6, unknown color → `"blue"`.
- Calendar entities are NOT added to the entity-subscription set (`ids()` collectors) — events come from the service call, not state subscriptions.
- Config page (app.js): "Calendars" card mirroring the EVs card — rows of entity id + display name + color `<select>` of the 8 palette names, add/remove buttons, defensive default `if (!e.calendars) e.calendars = []`.

## Agenda panel — `ui/panels/CalendarPanel.kt`

- New `DashView.CALENDAR` between MEDIA and WEATHER in the enum (DashViews.kt:30); rail icon `Icons.Outlined.CalendarMonth`; panel registered alongside the others. The rail simply gains one entry; no config toggle (panel shows the standard hint when no calendars are configured).
- `PanelSurface` with three equal-weight day columns (same tile styling as the forecast strip: `RoundedCornerShape(14.dp)`, `Color(0xFF1B1F2A)` background), each headed by the day label.
- Event row: 10dp calendar-color dot, time label, title (`maxLines = 1`, ellipsis). Rows in a `verticalScroll` column per day.
- Day with no events: muted `"—"` centered. No calendars configured: `EmptyHint("Add calendars in the web config")`.

## Home card — in HomeView

- Position: `Alignment.BottomEnd`, `padding(bottom = 20.dp, end = 28.dp)` — diagonal from the clock; width-capped `widthIn(max = 300.dp)` so it never approaches the bottom-left clock block.
- Style: the standard pill (`Color.Black.copy(alpha = 0.35f)`, `RoundedCornerShape(20.dp)`, padding 16×10).
- Content: one line — color dot (10dp), `eventTimeLabel` in white 0.7 alpha, title in white (15sp, `maxLines = 1`, ellipsis).
- Visibility: `AnimatedVisibility` (fade 600ms like the EV/solar stack) whenever `nextEventCard(...) != null`; hidden otherwise and when no calendars configured.
- The card re-derives on a minute tick (reuse `rememberMinuteTicker`) so "Tomorrow" flips to a time and "Now" appears without waiting for the next fetch.

## Error handling

- Fetch failure/null → keep last good events (log to logcat only).
- Clock skew safe: all comparisons via `nowMs` parameters, no wall-clock reads inside model functions (testability).
- Events with `end < start` are dropped by the parser.

## Testing (plain-JVM JUnit4, no android.*)

- Parser: timed event, all-day event (`allDay` flag + midnight expansion), multi-calendar merge with per-config name/color, malformed date dropped, missing entity key skipped, blank summary → "(untitled)", `end < start` dropped.
- `agendaDays`: 3 labeled days; multi-day event appears on each overlapped day; ended events excluded; all-day-first ordering.
- `nextEventCard`: running event wins over later event; ended events skipped; empty → null.
- `eventTimeLabel`: Now / All day / Tomorrow prefix / day-name prefix, 12h vs 24h.
- Config clamp: blank entity dropped, cap 6, unknown color → blue.
- Build gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0.

## Out of scope (YAGNI)

- Creating/editing events from the dashboard.
- Location/description display, attendee info.
- Per-calendar enable toggles beyond removing the row.
- Notifications for upcoming events (the HA push-notification path already covers that from HA automations).
