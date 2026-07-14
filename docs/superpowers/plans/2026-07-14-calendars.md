# HA Calendars on Hearth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Home Assistant calendar events on the Hearth dashboard in two places — a compact next-event card on the home screen and a 3-day color-coded agenda panel on the rail — driven by a single `calendar.get_events` websocket call refreshed every 15 minutes.

**Architecture:** A plain-JVM `CalendarModel` (parser + agenda/next-event/label helpers + fixed palette) holds all logic and is unit-tested with no Android imports. `EntityHub.getCalendarEvents` mirrors the existing `getForecasts` service-call pattern. A Dashboard-scope `LaunchedEffect` in `App.kt` fetches + parses into a `MutableState<List<CalendarEvent>>` that flows down through `DashboardShell` to `HomeView` (next-event card) and a new `CalendarPanel` (agenda). Config is a new `CalendarConfig` list on `Entities`, edited via a "Calendars" card on the web config page.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, kotlinx.serialization, java.time, JUnit4 plain-JVM tests, vanilla JS config page.

## Global Constraints
- Kotlin 2.1.0; compileSdk 34 (NEVER bump); minSdk 28; device is API 30.
- Dependency whitelist: NanoHTTPD 2.3.1 + org.tensorflow:tensorflow-lite:2.14.0 ONLY — NO new dependencies (material-icons-extended is already present).
- Unit tests are plain-JVM JUnit4; test subjects must not import `android.*` (the `ui/model` + `config` classes must stay Android-free; `java.time` is fine).
- Work happens directly on `master` (the repo's established flow); every commit message ends with the final line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Build gate after each task: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` (exit 0).
- After editing `app/src/main/assets/config/app.js` run: `node --check app/src/main/assets/config/app.js`

### Verified facts (read before implementing)
- `EntityHub.getForecasts(entityId: String): JsonElement?` (`app/src/main/java/com/rar/echodash/ha/EntityHub.kt:142`) is the exact websocket `call_service` + `return_response` shape to mirror. `client.request(...)`, `buildJsonObject`, `put`, `putJsonObject`, `putJsonArray`, `add`, and `JsonPrimitive` are already imported in that file.
- The forecast response the parser walks is `{"response": {"<entity>": {"forecast": [...]}}}`; the calendar response is `{"response": {"<entity>": {"events": [{"summary","start","end", ...}]}}}`. Timed events carry RFC3339 `start`/`end`; all-day events carry bare dates (`2026-07-15`), with `end` exclusive (an event on the 15th–16th has `end` `2026-07-17`).
- `WeatherModel` (`app/src/main/java/com/rar/echodash/ui/model/WeatherModel.kt`) already uses `Locale.ENGLISH` for day-of-week names; the calendar labels `"Today"` / `"Tomorrow"` are hard-coded English, so the third day name also uses `Locale.ENGLISH` for consistency and test determinism.
- `Color(colorArgb: Long)` is the established way to turn a stored `0xFFRRGGBB` Long into a Compose color — see `Color(aqi.band.colorArgb)` in `HomeView.kt` and `AqiBand(val colorArgb: Long)` in `AqiModel.kt`. A hex literal like `0xFF64B5F6` exceeds `Int` range so Kotlin types it as `Long` automatically.
- `clockIs24(format: ClockFormat, systemIs24: Boolean): Boolean` lives in `app/src/main/java/com/rar/echodash/ui/DashViews.kt`; the home clock resolves 24h with `clockIs24(clockFormat, DateFormat.is24HourFormat(context))`. Reuse this to derive the `is24h` boolean the model functions take.
- `railViews(...)` in `DashViews.kt` returns `listOf(DashView.HOME) + configured`; `DashViewsTest.kt` asserts exact rail lists, so those expectations must be updated when `railViews` gains the always-on CALENDAR entry.
- `rememberMinuteTicker()` in `HomeView.kt` already exposes `val now by rememberMinuteTicker()`; the home card re-derives on that tick.
- App.kt's `Screen.Dashboard` branch already imports `mutableStateOf`, `getValue`, `setValue`, `remember`, `LaunchedEffect`, and `kotlinx.coroutines.delay`.

### Decisions resolved from the spec (documented for the reviewer)
- **Rail position of CALENDAR.** The spec places `DashView.CALENDAR` between `MEDIA` and `WEATHER` in the *enum declaration* and says the rail "simply gains one entry" with no config toggle. Because rail order is driven by user-reorderable panel `order` values and Calendar is not a panel, CALENDAR is appended as the **last** rail entry (stable regardless of panel reordering). Enum declaration order still follows the spec (between MEDIA and WEATHER).
- **`eventTimeLabel` checks all-day before running.** An all-day event technically "spans now" all day; the spec clearly wants `"All day"` (not `"Now"`) for it, so the all-day branch is evaluated first, then the running-timed branch, then a plain start time.
- **Two label helpers.** The home card wants a day-prefixed label (`"Tomorrow 9:00 AM"`); the panel groups by day column and wants the bare time (`"9:00 AM"`). `eventTimeLabel(...)` produces the day-prefixed home-card string; `eventClockLabel(...)` produces the bare panel string. Both share one private time formatter.
- **Fetch keep-last policy.** `getCalendarEvents` returns `null` on any request failure (the common failure). The effect updates the state only when the result is non-null (`if (result != null)`), so a failed fetch keeps the last good list while a genuinely empty response clears the card. This is the practical reading of "parse errors / null responses keep the previous good list (same policy as the forecast fetch)".

---

## Task 1 — CalendarConfig (DashConfig + web config page)

**Files:**
- Modify `app/src/main/java/com/rar/echodash/config/DashConfig.kt` (add `CalendarConfig`; add `Entities.calendars`; add clamp rule in `clamped()`)
- Modify `app/src/main/assets/config/index.html` (add a `calendars-section` after `ev-section`)
- Modify `app/src/main/assets/config/app.js` (add `CALENDAR_COLORS`, `renderCalendars`, `renderCalendar`; call `renderCalendars()` from `render()`)
- Test (modify) `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`

**Interfaces:**
- Produces:
  - `data class CalendarConfig(val entity: String = "", val name: String = "", val color: String = "blue")` with `companion object { val COLORS: Set<String> }`.
  - `Entities.calendars: List<CalendarConfig>` (default `emptyList()`), NOT added to `referencedEntityIds()`.
  - `clamped()` drops blank-entity rows, caps the list at 6, lower-cases/trims `color` and maps unknown → `"blue"`.

### Steps

- [ ] **Step 1: Write the failing config tests** — add these methods to `class DashConfigTest` in `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (append after the existing methods, before the closing brace):

```kotlin
    @Test
    fun calendarsDefaultEmptyAndRoundTrip() {
        assertEquals(emptyList<CalendarConfig>(), DashConfig().entities.calendars)
        // old config document with no "calendars" key -> defaults to empty
        val old = decodeConfig("""{"version":1}""")
        assertEquals(emptyList<CalendarConfig>(), old.entities.calendars)
        val cfg = DashConfig(
            entities = Entities(calendars = listOf(CalendarConfig("calendar.a", "Home", "teal"))),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("teal", decodeConfig(text).entities.calendars[0].color)
    }

    @Test
    fun calendarsClampedTrimColorValidatedBlankDroppedAndCapped() {
        val cals = DashConfig(
            entities = Entities(
                calendars = listOf(
                    CalendarConfig(entity = "  calendar.a  ", name = "  Personal  ", color = "  Green "),
                    CalendarConfig(entity = "calendar.b", name = "", color = "chartreuse"), // unknown -> blue
                    CalendarConfig(entity = "   ", name = "Blank", color = "red"),           // blank entity -> dropped
                    CalendarConfig(entity = "calendar.c"),
                    CalendarConfig(entity = "calendar.d"),
                    CalendarConfig(entity = "calendar.e"),
                    CalendarConfig(entity = "calendar.f"),
                    CalendarConfig(entity = "calendar.g"),                                    // 7th valid -> capped out
                ),
            ),
        ).clamped().entities.calendars
        assertEquals(6, cals.size)
        assertEquals(CalendarConfig(entity = "calendar.a", name = "Personal", color = "green"), cals[0])
        assertEquals("blue", cals[1].color) // unknown color normalized
        assertEquals(
            listOf("calendar.a", "calendar.b", "calendar.c", "calendar.d", "calendar.e", "calendar.f"),
            cals.map { it.entity },
        )
    }

    @Test
    fun calendarsAreNotWatchedEntities() {
        // Calendar events come from the service call, not state subscriptions, so calendars
        // must never enter the EntityHub watched set.
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                calendars = listOf(CalendarConfig("calendar.a"), CalendarConfig("calendar.b")),
            ),
        )
        assertEquals(listOf("sensor.t"), cfg.referencedEntityIds())
    }
```

- [ ] **Step 2: Run to see it fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: FAIL — compilation error, `CalendarConfig` is an unresolved reference.

- [ ] **Step 3: Add `CalendarConfig` and `Entities.calendars`** — in `app/src/main/java/com/rar/echodash/config/DashConfig.kt`, add the data class just after `EvConfig` (after its closing brace, before `LightGroup`):

```kotlin
/** A configured HA calendar. [entity] is a calendar.* id; [name] is the display name (blank ->
 *  entity-id tail, resolved in the model); [color] is one of [CalendarConfig.COLORS]. */
@Serializable
data class CalendarConfig(
    val entity: String = "",     // calendar.* entity id
    val name: String = "",       // display name; blank -> entity id tail
    val color: String = "blue",  // palette key
) {
    companion object {
        /** The eight recognized palette keys (ARGB values live in the model's calendarColorArgb). */
        val COLORS: Set<String> = setOf("blue", "green", "amber", "red", "purple", "teal", "orange", "pink")
    }
}
```

  Add the `calendars` field to `Entities` (after `evs`):

```kotlin
@Serializable
data class Entities(
    val tempSensor: String? = null,
    val weather: String? = null,
    val aqiSensor: String? = null,
    val rainEvent: String? = null,  // event-rain total sensor; > 0 shows the home rain pill
    val climate: List<String> = emptyList(),
    val solar: SolarConfig = SolarConfig(),
    val lightGroups: List<LightGroup> = emptyList(),
    val cameras: List<CameraConfig> = emptyList(),
    val doorbells: List<DoorbellConfig> = emptyList(),
    val evs: List<EvConfig> = emptyList(),
    val calendars: List<CalendarConfig> = emptyList(),
)
```

- [ ] **Step 4: Add the clamp rule** — in `DashConfig.clamped()`, add the cleaned-calendars computation alongside `cleanedEvs` (just before the `return copy(...)`):

```kotlin
        val cleanedCalendars = entities.calendars
            .map { c ->
                c.copy(
                    entity = c.entity.trim(),
                    name = c.name.trim(),
                    color = c.color.trim().lowercase().let { if (it in CalendarConfig.COLORS) it else "blue" },
                )
            }
            .filter { it.entity.isNotBlank() }
            .take(6)
```

  Add `calendars = cleanedCalendars,` to the `entities = entities.copy(...)` block (after `evs = cleanedEvs,`):

```kotlin
                cameras = cleanedCameras,
                doorbells = cleanedDoorbells,
                evs = cleanedEvs,
                calendars = cleanedCalendars,
            ),
```

  (Do NOT touch `referencedEntityIds()` — calendars are intentionally excluded from the watched set.)

- [ ] **Step 5: Run to see the config tests pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest"`
Expected: PASS.

- [ ] **Step 6: Add the config-page Calendars card** — edit `app/src/main/assets/config/index.html`, adding a new section immediately after the `ev-section` `</section>` (before the closing `</div>` of `.content`):

```html
      <section id="calendars-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.5" y="4.5" width="17" height="16" rx="2.5"/>
              <path d="M3.5 9h17"/><path d="M8 3v3"/><path d="M16 3v3"/>
            </svg>
          </span>
          <div class="card-titles">
            <h2>Calendars</h2>
            <p>Home Assistant calendars for the home-screen card and agenda panel.</p>
          </div>
        </div>
        <div id="calendars"></div>
      </section>
```

  Edit `app/src/main/assets/config/app.js`. Add the color list next to the other option constants (after `AUTO_DISMISS_OPTIONS`, ~line 46):

```javascript
const CALENDAR_COLORS = ["blue", "green", "amber", "red", "purple", "teal", "orange", "pink"];
```

  Add `renderCalendars();` to `render()` (after `renderEv();`):

```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderNotifications();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
  renderEv();
  renderCalendars();
}
```

  Add the two render functions (place them just after `renderEv()`'s closing brace, before `updateNightLux`):

```javascript
function renderCalendars() {
  const host = document.getElementById("calendars");
  clear(host);
  const e = config.entities;
  // Defensive: old configs and server responses may omit the array entirely.
  if (!Array.isArray(e.calendars)) e.calendars = [];
  e.calendars.forEach((c, ci) => host.appendChild(renderCalendar(c, ci)));
  const add = el("button", "add", "Add calendar");
  add.type = "button";
  add.addEventListener("click", () => {
    if (e.calendars.length >= 6) return; // cap 6 (matches DashConfig.clamped)
    e.calendars.push({ entity: "", name: "", color: "blue" });
    renderCalendars();
  });
  host.appendChild(add);
  host.appendChild(el("div", "muted",
    "Up to 6 Home Assistant calendars, color-coded on the home-screen next-event card and the " +
    "3-day agenda panel. Display name defaults to the entity id when blank. Blank rows are dropped on save."));
}

function renderCalendar(c, ci) {
  const cals = config.entities.calendars;
  const box = el("div", "group");
  const head = el("div", "group-head");
  head.appendChild(el("span", "panel-name", "Calendar " + (ci + 1)));
  head.appendChild(reorderButtons(
    ci !== 0, ci !== cals.length - 1,
    () => { const t = cals[ci]; cals[ci] = cals[ci - 1]; cals[ci - 1] = t; renderCalendars(); },
    () => { const t = cals[ci]; cals[ci] = cals[ci + 1]; cals[ci + 1] = t; renderCalendars(); },
  ));
  const del = el("button", "ghost small danger", "Delete");
  del.type = "button";
  del.setAttribute("aria-label", "Delete calendar");
  del.addEventListener("click", () => { cals.splice(ci, 1); renderCalendars(); });
  head.appendChild(del);
  box.appendChild(head);

  box.appendChild(labeledRow("Calendar entity",
    entityPicker(["calendar"], c.entity, v => c.entity = v || "")));

  const name = el("input");
  name.value = c.name || "";
  name.setAttribute("autocomplete", "off");
  name.setAttribute("aria-label", "Calendar name");
  name.addEventListener("change", () => c.name = name.value.trim());
  box.appendChild(labeledRow("Display name", name));

  const color = el("select");
  CALENDAR_COLORS.forEach(key => {
    const o = el("option", null, key.charAt(0).toUpperCase() + key.slice(1));
    o.value = key;
    if ((c.color || "blue") === key) o.selected = true;
    color.appendChild(o);
  });
  color.addEventListener("change", () => c.color = color.value);
  box.appendChild(labeledRow("Color", color));
  return box;
}
```

- [ ] **Step 7: Syntax-check the JS**

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit 0.

- [ ] **Step 8: Build gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
Add CalendarConfig + web config Calendars card

New CalendarConfig (entity/name/color) list on Entities with clamped rules
(blank-entity drop, cap 6, unknown color -> blue). Calendars are excluded
from the watched entity set. Config page gains a Calendars card mirroring
the EVs card.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 2 — CalendarModel (parser, agenda, next-event, labels, palette)

**Files:**
- Create `app/src/main/java/com/rar/echodash/ui/model/CalendarModel.kt`
- Test (create) `app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.config.CalendarConfig` (Task 1).
- Produces:
  - `data class CalendarEvent(val calendarName: String, val colorArgb: Long, val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean)`
  - `data class AgendaDay(val label: String, val events: List<CalendarEvent>)`
  - `fun calendarColorArgb(key: String): Long`
  - `fun parseCalendarEvents(result: JsonElement?, configs: List<CalendarConfig>, zone: ZoneId): List<CalendarEvent>`
  - `fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId): List<AgendaDay>`
  - `fun nextEventCard(events: List<CalendarEvent>, nowMs: Long): CalendarEvent?`
  - `fun eventTimeLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String` (day-prefixed, for the home card)
  - `fun eventClockLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String` (bare time, for the panel)

### Steps

- [ ] **Step 1: Write the failing test** — create `app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.CalendarConfig
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarModelTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    private fun dayMs(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun timed(start: String, end: String, title: String = "E", color: Long = 0xFF64B5F6) =
        CalendarEvent("c", color, title, ms(start), ms(end), allDay = false)
    private fun allDay(start: String, end: String, title: String = "E") =
        CalendarEvent("c", 0xFF64B5F6, title, dayMs(start), dayMs(end), allDay = true)

    // ---- palette ----

    @Test
    fun paletteResolvesKnownKeysAndFallsBackToBlue() {
        assertEquals(0xFF64B5F6, calendarColorArgb("blue"))
        assertEquals(0xFF81C784, calendarColorArgb("green"))
        assertEquals(0xFFFFD54F, calendarColorArgb("amber"))
        assertEquals(0xFFE57373, calendarColorArgb("red"))
        assertEquals(0xFFBA68C8, calendarColorArgb("purple"))
        assertEquals(0xFF4DB6AC, calendarColorArgb("teal"))
        assertEquals(0xFFFFB74D, calendarColorArgb("orange"))
        assertEquals(0xFFF06292, calendarColorArgb("pink"))
        assertEquals(0xFF81C784, calendarColorArgb("  Green ")) // trim + lower-case
        assertEquals(0xFF64B5F6, calendarColorArgb("chartreuse")) // unknown -> blue
    }

    // ---- parseCalendarEvents ----

    @Test
    fun parsesTimedAndAllDayEventsWithConfigNameAndColor() {
        val result = json("""
          {"response":{"calendar.personal":{"events":[
            {"summary":"Dentist","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"Vacation","start":"2026-07-15","end":"2026-07-17"}
          ]}}}
        """)
        val configs = listOf(CalendarConfig(entity = "calendar.personal", name = "Personal", color = "green"))
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(2, events.size)
        assertEquals("Dentist", events[0].title)
        assertEquals("Personal", events[0].calendarName)
        assertEquals(0xFF81C784, events[0].colorArgb)
        assertEquals(false, events[0].allDay)
        assertEquals(ms("2026-07-14T09:00:00-04:00"), events[0].startMs)
        assertEquals(true, events[1].allDay)
        assertEquals(dayMs("2026-07-15"), events[1].startMs) // all-day expands to local midnight
        assertEquals(dayMs("2026-07-17"), events[1].endMs)
    }

    @Test
    fun blankNameFallsBackToEntityTail() {
        val result = json("""{"response":{"calendar.andrew_s_personal":{"events":[
            {"summary":"X","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"}]}}}""")
        val events = parseCalendarEvents(result, listOf(CalendarConfig(entity = "calendar.andrew_s_personal")), zone)
        assertEquals("andrew_s_personal", events[0].calendarName)
    }

    @Test
    fun blankSummaryBecomesUntitled() {
        val result = json("""{"response":{"calendar.x":{"events":[
            {"summary":"   ","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"},
            {"start":"2026-07-14T11:00:00-04:00","end":"2026-07-14T12:00:00-04:00"}
          ]}}}""")
        val events = parseCalendarEvents(result, listOf(CalendarConfig(entity = "calendar.x")), zone)
        assertEquals(2, events.size)
        assertEquals("(untitled)", events[0].title)
        assertEquals("(untitled)", events[1].title)
    }

    @Test
    fun dropsMalformedReversedAndSkipsMissingEntity() {
        val result = json("""{"response":{"calendar.x":{"events":[
            {"summary":"bad date","start":"not-a-date","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"no start","end":"2026-07-14T10:00:00-04:00"},
            {"summary":"reversed","start":"2026-07-14T12:00:00-04:00","end":"2026-07-14T11:00:00-04:00"},
            {"summary":"good","start":"2026-07-14T09:00:00-04:00","end":"2026-07-14T10:00:00-04:00"}
          ]}}}""")
        // calendar.missing is absent from the response -> skipped, no throw.
        val configs = listOf(CalendarConfig(entity = "calendar.x"), CalendarConfig(entity = "calendar.missing"))
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(1, events.size)
        assertEquals("good", events[0].title)
    }

    @Test
    fun mergesMultipleCalendarsSortedByStart() {
        val result = json("""{"response":{
            "calendar.a":{"events":[{"summary":"Late","start":"2026-07-14T15:00:00-04:00","end":"2026-07-14T16:00:00-04:00"}]},
            "calendar.b":{"events":[{"summary":"Early","start":"2026-07-14T08:00:00-04:00","end":"2026-07-14T09:00:00-04:00"}]}
          }}""")
        val configs = listOf(
            CalendarConfig(entity = "calendar.a", name = "A", color = "red"),
            CalendarConfig(entity = "calendar.b", name = "B", color = "teal"),
        )
        val events = parseCalendarEvents(result, configs, zone)
        assertEquals(listOf("Early", "Late"), events.map { it.title }) // sorted by startMs
        assertEquals(0xFF4DB6AC, events[0].colorArgb) // Early is from calendar.b (teal)
        assertEquals("A", events[1].calendarName)
    }

    @Test
    fun nullOrEmptyResponseYieldsEmpty() {
        val configs = listOf(CalendarConfig(entity = "calendar.x"))
        assertEquals(emptyList<CalendarEvent>(), parseCalendarEvents(null, configs, zone))
        assertEquals(emptyList<CalendarEvent>(), parseCalendarEvents(json("""{"response":{}}"""), configs, zone))
    }

    // ---- agendaDays ----

    @Test
    fun agendaProducesThreeLabeledDays() {
        val now = ms("2026-07-14T12:00:00-04:00") // Tuesday
        val days = agendaDays(emptyList(), now, zone)
        assertEquals(3, days.size)
        assertEquals("Today", days[0].label)
        assertEquals("Tomorrow", days[1].label)
        assertEquals("Thursday", days[2].label) // 2026-07-16
    }

    @Test
    fun multiDayEventSpansColumnsAllDayFirstEndedExcluded() {
        val now = ms("2026-07-14T12:00:00-04:00")
        val events = listOf(
            allDay("2026-07-14", "2026-07-16", "Conference"),            // end exclusive: Tue + Wed only
            timed("2026-07-14T13:00:00-04:00", "2026-07-14T14:00:00-04:00", "Lunch"),
            timed("2026-07-14T08:00:00-04:00", "2026-07-14T09:00:00-04:00", "Ended"),
        )
        val days = agendaDays(events, now, zone)
        assertEquals(listOf("Conference", "Lunch"), days[0].events.map { it.title }) // all-day first, ended dropped
        assertEquals(listOf("Conference"), days[1].events.map { it.title })          // still spans Wed
        assertEquals(emptyList<String>(), days[2].events.map { it.title })            // end exclusive -> not Thu
    }

    // ---- nextEventCard ----

    @Test
    fun nextEventPrefersRunningThenSkipsEnded() {
        val now = ms("2026-07-14T12:00:00-04:00")
        val running = timed("2026-07-14T11:30:00-04:00", "2026-07-14T12:30:00-04:00", "Running")
        val later = timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00", "Later")
        val ended = timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00", "Ended")
        assertEquals("Running", nextEventCard(listOf(ended, later, running), now)?.title)
        assertNull(nextEventCard(listOf(ended), now))
        assertNull(nextEventCard(emptyList(), now))
    }

    // ---- eventTimeLabel (home card) ----

    @Test
    fun eventTimeLabelVariants() {
        val now = ms("2026-07-14T12:00:00-04:00")
        assertEquals("Now", eventTimeLabel(timed("2026-07-14T11:00:00-04:00", "2026-07-14T13:00:00-04:00"), now, zone, is24h = false))
        assertEquals("All day", eventTimeLabel(allDay("2026-07-14", "2026-07-15"), now, zone, is24h = false))
        assertEquals("3:00 PM", eventTimeLabel(timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00"), now, zone, is24h = false))
        assertEquals("15:00", eventTimeLabel(timed("2026-07-14T15:00:00-04:00", "2026-07-14T16:00:00-04:00"), now, zone, is24h = true))
        assertEquals("Tomorrow 9:00 AM", eventTimeLabel(timed("2026-07-15T09:00:00-04:00", "2026-07-15T10:00:00-04:00"), now, zone, is24h = false))
        assertEquals("Thursday 9:00 AM", eventTimeLabel(timed("2026-07-16T09:00:00-04:00", "2026-07-16T10:00:00-04:00"), now, zone, is24h = false))
        assertEquals("Tomorrow All day", eventTimeLabel(allDay("2026-07-15", "2026-07-16"), now, zone, is24h = false))
    }

    // ---- eventClockLabel (panel) ----

    @Test
    fun eventClockLabelBareTimeNoDayPrefix() {
        val now = ms("2026-07-14T12:00:00-04:00")
        assertEquals("9:00 AM", eventClockLabel(timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00"), 0L, zone, is24h = false))
        assertEquals("09:00", eventClockLabel(timed("2026-07-14T09:00:00-04:00", "2026-07-14T10:00:00-04:00"), 0L, zone, is24h = true))
        assertEquals("All day", eventClockLabel(allDay("2026-07-14", "2026-07-15"), now, zone, is24h = false))
        assertEquals("Now", eventClockLabel(timed("2026-07-14T11:00:00-04:00", "2026-07-14T13:00:00-04:00"), now, zone, is24h = false))
        // A future event tomorrow shows only its time in the panel (no "Tomorrow" prefix).
        assertEquals("9:00 AM", eventClockLabel(timed("2026-07-15T09:00:00-04:00", "2026-07-15T10:00:00-04:00"), now, zone, is24h = false))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.CalendarModelTest"`
Expected: FAIL — compilation error, `CalendarEvent`, `parseCalendarEvents`, etc. are unresolved references.

- [ ] **Step 3: Implement the model** — create `app/src/main/java/com/rar/echodash/ui/model/CalendarModel.kt`:

```kotlin
package com.rar.echodash.ui.model

import com.rar.echodash.config.CalendarConfig
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One calendar event resolved for display. All comparisons are epoch-millis so the model never
 *  reads the wall clock (testability); [colorArgb] is a 0xFFRRGGBB Long for Compose's Color(Long). */
data class CalendarEvent(
    val calendarName: String,   // display name from config
    val colorArgb: Long,        // resolved palette color from config
    val title: String,          // event summary; blank summary -> "(untitled)"
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

/** One agenda column: a day [label] ("Today"/"Tomorrow"/day name) and the events overlapping it. */
data class AgendaDay(val label: String, val events: List<CalendarEvent>)

/** Fixed calendar palette (Material 300/400 tints). Unknown/blank key -> blue. */
fun calendarColorArgb(key: String): Long = when (key.trim().lowercase(Locale.US)) {
    "blue" -> 0xFF64B5F6
    "green" -> 0xFF81C784
    "amber" -> 0xFFFFD54F
    "red" -> 0xFFE57373
    "purple" -> 0xFFBA68C8
    "teal" -> 0xFF4DB6AC
    "orange" -> 0xFFFFB74D
    "pink" -> 0xFFF06292
    else -> 0xFF64B5F6
}

/**
 * Parse a calendar.get_events return_response into a flat, start-sorted event list. Walks
 * `response.<entity>.events` for each configured calendar; entities absent from the response are
 * skipped. Timed events parse via [OffsetDateTime]; all-day events (a `start` with no `T`) parse
 * via [LocalDate] expanded to local midnight in [zone]. Malformed/reversed/missing-date entries are
 * dropped, never thrown.
 */
fun parseCalendarEvents(
    result: JsonElement?,
    configs: List<CalendarConfig>,
    zone: ZoneId,
): List<CalendarEvent> {
    val response = ((result as? JsonObject)?.get("response") as? JsonObject) ?: return emptyList()
    val events = ArrayList<CalendarEvent>()
    for (config in configs) {
        val entry = response[config.entity] as? JsonObject ?: continue
        val arr = entry["events"] as? JsonArray ?: continue
        val name = config.name.ifBlank { config.entity.substringAfter('.') }
        val color = calendarColorArgb(config.color)
        for (element in arr) {
            val o = element as? JsonObject ?: continue
            val startStr = (o["start"] as? JsonPrimitive)?.contentOrNull ?: continue
            val endStr = (o["end"] as? JsonPrimitive)?.contentOrNull ?: continue
            val allDay = !startStr.contains('T')
            val startMs = parseCalMs(startStr, zone) ?: continue
            val endMs = parseCalMs(endStr, zone) ?: continue
            if (endMs < startMs) continue // drop reversed spans
            val title = (o["summary"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: "(untitled)"
            events.add(CalendarEvent(name, color, title, startMs, endMs, allDay))
        }
    }
    return events.sortedBy { it.startMs }
}

private fun parseCalMs(value: String, zone: ZoneId): Long? = runCatching {
    if (value.contains('T')) {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } else {
        LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}.getOrNull()

/**
 * Exactly 3 day columns starting at [nowMs]'s local date. An event appears in every column its
 * `[startMs, endMs)` span overlaps; already-ended events (`endMs <= nowMs`) are excluded everywhere.
 * Within a column: all-day events first, then by `startMs`, stable (ties keep input order).
 */
fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId): List<AgendaDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0 until 3).map { offset ->
        val day = today.plusDays(offset.toLong())
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val label = when (offset) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
        val dayEvents = events
            .filter { it.endMs > nowMs && it.startMs < dayEnd && it.endMs > dayStart }
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.startMs })
        AgendaDay(label, dayEvents)
    }
}

/** The earliest not-yet-ended event (a currently-running event counts); null when none. */
fun nextEventCard(events: List<CalendarEvent>, nowMs: Long): CalendarEvent? =
    events.filter { it.endMs > nowMs }.minByOrNull { it.startMs }

/**
 * Home-card time text with a day prefix: all-day -> "All day"; a running timed event -> "Now";
 * otherwise the start time. Same-day -> time only; next day -> "Tomorrow <time>"; later ->
 * "<Weekday> <time>". All-day is checked before running so an all-day event reads "All day".
 */
fun eventTimeLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String {
    val timePart = when {
        event.allDay -> "All day"
        event.startMs <= nowMs && event.endMs > nowMs -> "Now"
        else -> formatClockTime(event.startMs, zone, is24h)
    }
    if (timePart == "Now") return "Now" // a running timed event needs no day prefix
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val startDate = Instant.ofEpochMilli(event.startMs).atZone(zone).toLocalDate()
    val dayOffset = ChronoUnit.DAYS.between(today, startDate)
    return when {
        dayOffset <= 0L -> timePart
        dayOffset == 1L -> "Tomorrow $timePart"
        else -> "${startDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $timePart"
    }
}

/** Panel-row time text (no day prefix): all-day -> "All day"; running timed -> "Now"; else start time. */
fun eventClockLabel(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24h: Boolean): String = when {
    event.allDay -> "All day"
    event.startMs <= nowMs && event.endMs > nowMs -> "Now"
    else -> formatClockTime(event.startMs, zone, is24h)
}

private fun formatClockTime(ms: Long, zone: ZoneId, is24h: Boolean): String {
    val time = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    val pattern = if (is24h) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}
```

- [ ] **Step 4: Run to see it pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.model.CalendarModelTest"`
Expected: PASS.

- [ ] **Step 5: Build gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
Add CalendarModel: parser, agenda, next-event, labels, palette

Pure-JVM model for HA calendar events: parseCalendarEvents (timed + all-day,
malformed/reversed dropped), agendaDays (3-day overlap columns, all-day-first),
nextEventCard, eventTimeLabel (day-prefixed) + eventClockLabel (bare), and the
fixed 8-color palette. Clock-skew safe (nowMs params only).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 3 — Fetch wiring + home-screen next-event card

**Files:**
- Modify `app/src/main/java/com/rar/echodash/ha/EntityHub.kt` (add `getCalendarEvents`; add java.time imports)
- Modify `app/src/main/java/com/rar/echodash/App.kt` (Dashboard branch ~line 379-395: add `calendarEvents` state + fetch effect; DashboardShell call ~line 491-560: pass `calendarEvents`; add imports)
- Modify `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` (add `calendarEvents` param ~line 95-97; pass to `HomeView` ~line 190-213; add import)
- Modify `app/src/main/java/com/rar/echodash/ui/HomeView.kt` (add `calendarEvents` param; render the next-event card in the non-takeover branch; add imports)

**Interfaces:**
- Consumes: `CalendarEvent`, `parseCalendarEvents`, `nextEventCard`, `eventTimeLabel`, `CalendarConfig` (Tasks 1–2).
- Produces:
  - `suspend fun EntityHub.getCalendarEvents(entityIds: List<String>): JsonElement?`
  - `DashboardShell(..., calendarEvents: List<CalendarEvent> = emptyList())`
  - `HomeView(..., calendarEvents: List<CalendarEvent> = emptyList())`

### Steps

- [ ] **Step 1: Add `getCalendarEvents` to EntityHub** — edit `app/src/main/java/com/rar/echodash/ha/EntityHub.kt`. Add the three java.time imports (after `import java.io.IOException`):

```kotlin
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
```

  Add the function right after `getForecasts(...)` (before `cameraStream`):

```kotlin
    /**
     * One calendar.get_events call for all configured calendars. Returns the raw
     * {"response":{"<entity>":{"events":[...]}}} element, or null on any failure (caller keeps last
     * good list). Window is now .. now+3 days, RFC3339 with the device's local offset
     * (e.g. 2026-07-14T11:30:00-04:00). Events come from this service call, NOT state subscriptions.
     */
    suspend fun getCalendarEvents(entityIds: List<String>): JsonElement? =
        runCatching {
            val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val end = now.plusDays(3)
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            client.request("call_service", buildJsonObject {
                put("domain", "calendar")
                put("service", "get_events")
                putJsonObject("service_data") {
                    put("start_date_time", now.format(fmt))
                    put("end_date_time", end.format(fmt))
                }
                putJsonObject("target") {
                    putJsonArray("entity_id") { entityIds.forEach { add(it) } }
                }
                put("return_response", JsonPrimitive(true))
            })
        }.getOrNull()
```

- [ ] **Step 2: Thread `calendarEvents` through DashboardShell** — edit `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`. Add the import (with the other `com.rar.echodash.ui.model.*` imports):

```kotlin
import com.rar.echodash.ui.model.CalendarEvent
```

  Add the param to `DashboardShell(...)` (after `onPushDismiss`):

```kotlin
    pushed: List<NotificationItem> = emptyList(),
    onPushDismiss: (String) -> Unit = {},
    calendarEvents: List<CalendarEvent> = emptyList(),
) {
```

  Pass it into the `HomeView(...)` call (add after `onDismiss = dismissKey,`):

```kotlin
                        notifications = notifications,
                        onDismiss = dismissKey,
                        calendarEvents = calendarEvents,
```

- [ ] **Step 3: Add the home next-event card** — edit `app/src/main/java/com/rar/echodash/ui/HomeView.kt`. Add the imports (with the other `com.rar.echodash.ui.model.*` imports and the `java.*` imports respectively):

```kotlin
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.eventTimeLabel
import com.rar.echodash.ui.model.nextEventCard
```
```kotlin
import java.time.ZoneId
```

  Add the parameter to `HomeView(...)` (just before `modifier: Modifier = Modifier,`):

```kotlin
    onMediaVolume: (Int) -> Unit,
    calendarEvents: List<CalendarEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
```

  In the non-takeover `else` branch, add the card immediately after the notification-area `AnimatedVisibility` block (after its closing `}`, still inside the `else {` block, before the branch's closing brace that precedes `if (connState != ConnState.CONNECTED)`):

```kotlin
            // Next-event card: bottom-right, diagonal from the clock, width-capped so it never
            // approaches the bottom-left clock block. Re-derives on the minute tick so "Tomorrow"
            // flips to a time and "Now" appears without waiting for the next 15-minute fetch.
            val nextEvent = remember(calendarEvents, now) { nextEventCard(calendarEvents, now) }
            AnimatedVisibility(
                visible = nextEvent != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 28.dp),
            ) {
                // Guarded like the EV/solar stack above: nextEvent is non-null whenever visible=true.
                nextEvent?.let { ev ->
                    val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
                    Row(
                        Modifier
                            .widthIn(max = 300.dp)
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(ev.colorArgb)))
                        Text(
                            eventTimeLabel(ev, now, ZoneId.systemDefault(), is24),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                        )
                        Text(
                            ev.title, color = Color.White, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
```

- [ ] **Step 4: Add the fetch effect + wire the DashboardShell call** — edit `app/src/main/java/com/rar/echodash/App.kt`. Add the imports (with the other `com.rar.echodash.ui.model.*` and `java.*` imports):

```kotlin
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.parseCalendarEvents
```
```kotlin
import java.time.ZoneId
```

  In the `Screen.Dashboard` branch, add the state + fetch effect right after the existing pushed-notification prune `LaunchedEffect(pushedRaw) { ... }` block (just before `val configUrl = remember { deps.configUrl() }`):

```kotlin
                    // Calendar events at Dashboard scope so the home card has data without opening
                    // the panel. Immediate fetch, then every 15 minutes; a failed fetch (null) keeps
                    // the last good list, a non-null response updates (empty clears the card). No
                    // configured calendars -> no fetch, empty list.
                    var calendarEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
                    LaunchedEffect(config.entities.calendars) {
                        val cals = config.entities.calendars
                        if (cals.isEmpty()) {
                            calendarEvents = emptyList()
                            return@LaunchedEffect
                        }
                        while (true) {
                            val result = deps.entityHub.getCalendarEvents(cals.map { it.entity })
                            if (result != null) {
                                calendarEvents = parseCalendarEvents(result, cals, ZoneId.systemDefault())
                            }
                            delay(15 * 60_000L)
                        }
                    }
```

  Pass the state into the `DashboardShell(...)` call (add after `onPushDismiss = { id -> deps.pushStore.dismiss(id) },`):

```kotlin
                        pushed = pushed,
                        onPushDismiss = { id -> deps.pushStore.dismiss(id) },
                        calendarEvents = calendarEvents,
                    )
```

- [ ] **Step 5: Build gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0. (No new unit test here — the parser/labels are covered by Task 2; this task's deliverable is the wired home card, confirmed by the build gate and the on-device check in Final verification.)

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
Fetch calendar events + show home-screen next-event card

EntityHub.getCalendarEvents mirrors getForecasts (calendar.get_events,
return_response, now..+3d RFC3339 local offset). App fetches at Dashboard
scope every 15 min (keep-last on failure) and threads events through
DashboardShell into HomeView's bottom-right next-event card.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 4 — Agenda panel + CALENDAR rail entry

**Files:**
- Create `app/src/main/java/com/rar/echodash/ui/panels/CalendarPanel.kt`
- Modify `app/src/main/java/com/rar/echodash/ui/DashViews.kt` (add `DashView.CALENDAR`; `railIcon` case; `railViews` entry; import `CalendarMonth`)
- Modify `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` (add the `DashView.CALENDAR` branch; import `CalendarPanel`)
- Test (modify) `app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt`

**Interfaces:**
- Consumes: `CalendarEvent`, `agendaDays`, `AgendaDay`, `eventClockLabel` (Task 2); `clockIs24` (existing); `config.entities.calendars` + `config.home.clockFormat`; `calendarEvents` (Task 3).
- Produces: `DashView.CALENDAR` (always in the rail, last); `CalendarPanel(events, hasCalendars, clockFormat)`.

### Steps

- [ ] **Step 1: Write the failing rail test** — update the three `railViews` expectations in `app/src/test/java/com/rar/echodash/ui/DashViewsTest.kt` to include `DashView.CALENDAR` as the final entry:

```kotlin
    @Test
    fun railViewsPutHomeFirstThenEnabledPanelsByOrder() {
        val panels = Panels(
            lights = PanelConfig(true, 2),
            climate = PanelConfig(false, 1),   // disabled -> excluded
            media = PanelConfig(true, 3),
            weather = PanelConfig(true, 5),
            solar = PanelConfig(true, 4),
        )
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.MEDIA, DashView.SOLAR, DashView.WEATHER, DashView.CALENDAR),
            railViews(panels),
        )
    }

    @Test
    fun railViewsIncludesCamerasOnlyWhenEnabledAndConfigured() {
        val enabled = Panels(cameras = PanelConfig(true, 6))
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR, DashView.CAMERAS, DashView.CALENDAR),
            railViews(enabled, camerasConfigured = true),
        )
        // Enabled but no cameras configured -> excluded.
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR, DashView.CALENDAR),
            railViews(enabled, camerasConfigured = false),
        )
    }

    @Test
    fun railViewsExcludesDisabledCamerasEvenWhenConfigured() {
        val disabled = Panels(cameras = PanelConfig(false, 6))
        assertEquals(
            listOf(DashView.HOME, DashView.LIGHTS, DashView.CLIMATE, DashView.MEDIA,
                DashView.WEATHER, DashView.SOLAR, DashView.CALENDAR),
            railViews(disabled, camerasConfigured = true),
        )
    }
```

- [ ] **Step 2: Run to see it fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashViewsTest"`
Expected: FAIL — the test fails to compile, `DashView.CALENDAR` is an unresolved reference. (Main source still compiles; the enum + exhaustive `when` branch both land in Steps 3–5 before the test is run green.)

- [ ] **Step 3: Add CALENDAR to the enum, icon, and rail** — edit `app/src/main/java/com/rar/echodash/ui/DashViews.kt`. Add the icon import (keeping alphabetical order, after `import androidx.compose.material.icons.outlined.Air`):

```kotlin
import androidx.compose.material.icons.outlined.CalendarMonth
```

  Add `CALENDAR` to the enum, between `MEDIA` and `WEATHER`:

```kotlin
/** The rail destinations, top-to-bottom. */
enum class DashView { HOME, LIGHTS, CLIMATE, MEDIA, CALENDAR, WEATHER, SOLAR, CAMERAS }
```

  Add the `railIcon` case (in the `when`, after `DashView.MEDIA -> ...`):

```kotlin
    DashView.MEDIA -> Icons.Outlined.MusicNote
    DashView.CALENDAR -> Icons.Outlined.CalendarMonth
    DashView.WEATHER -> Icons.Outlined.WbCloudy
```

  Make `railViews` always append CALENDAR as the last entry (Calendar has no panel toggle):

```kotlin
    }.sortedBy { it.second.order }.map { it.first }
    // Calendar is always available (no panel toggle); it's appended last so panel reordering
    // never shifts it. The panel itself shows a hint when no calendars are configured.
    return listOf(DashView.HOME) + configured + DashView.CALENDAR
}
```

  (Do not run the test yet — the main source set is now non-exhaustive in `DashboardShell`'s `when (view)`; Steps 4–5 restore it before the test runs.)

- [ ] **Step 4: Create the CalendarPanel** — create `app/src/main/java/com/rar/echodash/ui/panels/CalendarPanel.kt`:

```kotlin
package com.rar.echodash.ui.panels

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.ui.clockIs24
import com.rar.echodash.ui.model.AgendaDay
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.agendaDays
import com.rar.echodash.ui.model.eventClockLabel
import java.time.ZoneId

/** 3-day agenda: three equal-weight day columns of color-coded event rows. */
@Composable
fun CalendarPanel(
    events: List<CalendarEvent>,
    hasCalendars: Boolean,
    clockFormat: ClockFormat,
) {
    PanelSurface {
        if (!hasCalendars) {
            EmptyHint("Add calendars in the web config")
            return@PanelSurface
        }
        val context = LocalContext.current
        val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val days = agendaDays(events, nowMs, zone)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            days.forEach { day ->
                DayColumn(day, nowMs, zone, is24, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: AgendaDay,
    nowMs: Long,
    zone: ZoneId,
    is24: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1F2A))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            day.label, color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
        if (day.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("—", color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                day.events.forEach { EventRow(it, nowMs, zone, is24) }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(event.colorArgb)))
        Column {
            Text(
                eventClockLabel(event, nowMs, zone, is24),
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            )
            Text(
                event.title, color = Color.White, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 5: Register the panel in DashboardShell** — edit `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`. Add the import (with the other `com.rar.echodash.ui.panels.*` imports):

```kotlin
import com.rar.echodash.ui.panels.CalendarPanel
```

  Add the `DashView.CALENDAR` branch to the `when (view)` (after the `DashView.MEDIA -> MediaPanel(...)` branch, matching the enum order):

```kotlin
                DashView.CALENDAR -> CalendarPanel(
                    events = calendarEvents,
                    hasCalendars = config.entities.calendars.isNotEmpty(),
                    clockFormat = config.home.clockFormat,
                )
```

- [ ] **Step 6: Run the rail test to pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.ui.DashViewsTest"`
Expected: PASS (main source is exhaustive again now that the CALENDAR branch exists).

- [ ] **Step 7: Build gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
Expected: exit 0.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
Add CALENDAR rail entry + 3-day agenda panel

DashView.CALENDAR (CalendarMonth icon, always last in the rail) renders a
3-day agenda: three equal-weight day columns of color-coded event rows
(dot + time + title), a muted dash for empty days, and a hint when no
calendars are configured.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Final verification (controller)

1. **Full gate:** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` — exit 0.
2. **JS check:** `node --check app/src/main/assets/config/app.js` — exit 0.
3. **Install to the device:**
   - `adb connect 10.75.1.98:5555`
   - `adb -s 10.75.1.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
4. **Config page:** open `http://10.75.1.98:8080`, log in, and confirm the **Calendars** card adds/edits/reorders rows (entity picker, display name, color select), caps at 6, and saves. Add `calendar.andrew_s_personal` (or any real HA calendar) with a color.
5. **Home card:** bring the dashboard to HOME and confirm the next-event card appears bottom-right (color dot + time label + title) whenever an event is within the 3-day window, and hides otherwise. Verify a currently-running event reads "Now" and a next-day event reads "Tomorrow <time>".
6. **Agenda panel:** open the Calendar rail entry (calendar icon, last in the rail) and confirm three columns labeled Today / Tomorrow / <weekday>, all-day events first, color dots matching the config, empty days showing "—", and — with no calendars configured — the "Add calendars in the web config" hint.
7. **Refresh:** confirm events update within ~15 minutes (or immediately after a config change re-keys the fetch effect).
8. **Push:** `git push origin master`.
