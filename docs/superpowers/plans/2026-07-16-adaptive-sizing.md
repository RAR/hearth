# Per-Screen Adaptive Sizing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the dashboard's four growable regions (home overlay caps, now-playing takeover, agenda day count, EV/solar card width) more content on bigger screens while every fixed-size element keeps its dp size — so the Echo Show 5 renders pixel-identically and the Show 8 / Tab M9 use their extra room.

**Architecture:** One new pure-Kotlin module `ui/model/AdaptiveGeometry.kt` (no Android/Compose imports, plain-JVM testable) holds all the sizing math as `floor`-then-`clamp` functions taking Float dp and returning whole-dp Int. Four consuming Compose sites wrap their root in `BoxWithConstraints`, read the measured `maxWidth`/`maxHeight` (Dp; call `.value` for Float), feed them to the pure functions, and apply the results with `.dp`. Free space is computed by subtraction (`screen − fixed reserves`), not percentages, because the fixed neighbours (paddings, card column, clock block) do not grow with the screen. Every constant is chosen so the Show 5's 787×394dp canvas reproduces today's shipped layout.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (`BoxWithConstraints`), plain-JVM JUnit4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-16-adaptive-sizing-design.md` (read it first — it is the source of truth for every formula, constant, and golden-table value).

## Global Constraints

- `compileSdk` / `targetSdk` stay at **34**; `minSdk` stays at **28**. Never bump.
- **No new dependencies** (rules out material3 `windowSizeClass`; its 600/840dp buckets fit this fleet badly anyway).
- App tests are **plain-JVM JUnit4 only** — no Robolectric, no instrumentation. Style: `org.junit.Assert.*` + `@Test fun`, one bundled test per surface (see `app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt`).
- Composables are **not unit-tested** in this repo (`testOptions.unitTests.isReturnDefaultValues = true`; no Compose test harness). Tasks 3–6 therefore have no "write failing test" step — their pure logic is fully tested in Task 1; their gate is compile + no test regressions + the Task 6 on-device verify. This is a deliberate, spec-endorsed convention ("composables stay thin: measure → pure fn → modifier").
- **Golden rule (non-negotiable):** at 787×394 every function reproduces today's shipped values exactly (pinned by test). The Show 5's rendering must not change anywhere.
- **`AdaptiveGeometry.kt` is pure Kotlin: NO `android.*` or `androidx.*` imports.** Functions take Float dp, return Int dp (`floor` then clamp).
- Comments explain **why** (physical constraints), not what. Match surrounding idiom.
- **Gate before EVERY commit:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — expect `BUILD SUCCESSFUL` and 0 test failures. If Gradle can't find Java, prefix `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto`.
- **Every commit message ends with this trailer line exactly:**
  `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- This repo works directly on the current branch; keep commits small and focused.

## File Structure

```
Create:
  app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt      (Task 1 — pure sizing math)
  app/src/test/java/com/rar/echodash/ui/model/AdaptiveGeometryTest.kt  (Task 1 — golden table)

Modify:
  app/src/main/java/com/rar/echodash/ui/model/CalendarModel.kt         (Task 2 — agendaDays dayCount)
  app/src/main/java/com/rar/echodash/ha/EntityHub.kt                   (Task 2 — fetch window 3→5 days)
  app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt     (Task 2 — +2 dayCount=5 tests)
  app/src/main/java/com/rar/echodash/ui/HomeView.kt                    (Task 3 cards/gauge; Task 4 root+overlays)
  app/src/main/java/com/rar/echodash/ui/DashboardShell.kt              (Task 4 — passes reserveCardColumn)
  app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt              (Task 5 — takeover split)
  app/src/main/java/com/rar/echodash/ui/panels/CalendarPanel.kt        (Task 6 — agendaDayCount by width)
```

Each modified composable file has exactly one caller structure; all `EvCardView`/`SolarCardView`/`GaugeBar`/`NowPlayingHome` callsites live inside `HomeView.kt`, and `agendaDays` has one production caller (`CalendarPanel`) plus the two tests — verified by grep, so the defaulted-parameter changes compile with no other callsites to touch.

**Verified landmarks (line numbers current as of this plan):**
- `HomeView.kt` (633 lines): root `Box` at **194**; EV/solar callsites at **321–322**; notification `widthIn(max=460.dp)`/`heightIn(max=200.dp)` + the 787-math comment at **326–350**; next-event `widthIn(max=420.dp)` + comment at **352–389**; `EvCardView` at **424** (hard `width(248.dp)` at 427); `GaugeBar` at **465–530** (hard `216` in `.size()` at 476, shimmer offset at 504, tick offset at 523); `SolarCardView` at **533–633** (hard `width(248.dp)` at 537, compact stats row at 573–631).
- `NowPlayingHome.kt` (179 lines): root `Box` at **66**; art `.size(360.dp)` at **89**; metadata `padding(start=48.dp, end=440.dp)` at **107** + `widthIn(max=460.dp)` at **108**.
- `CalendarPanel.kt` (114 lines): `agendaDays(events, nowMs, zone)` call at **52**; the `Row` of weight(1f) `DayColumn`s at **53–57**.
- `CalendarModel.kt`: `agendaDays` at **93**, hard `(0 until 3)` at **95**.
- `EntityHub.kt`: `val end = now.plusDays(3)` at **165**; doc comment "now .. now+3 days" at **160**.
- `DashboardShell.kt`: `HomeView(...)` callsite at **215–241**; `config: DashConfig` is param at line 74 (in scope).
- `DashConfig.kt`: `EvConfig.ids()` at 47, `SolarConfig.ids()` at 33 — **both public** (no visibility modifier), callable from `DashboardShell`; `entities.evs: List<EvConfig>` at 94, `entities.solar: SolarConfig` at 90.

---

## Task 1: AdaptiveGeometry pure module + golden-table tests

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/AdaptiveGeometryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (later tasks rely on these exact names/types):
  ```kotlin
  data class HomeOverlayCaps(val notifMaxWidthDp: Int, val notifMaxHeightDp: Int, val nextEventMaxWidthDp: Int)
  data class TakeoverLayout(val artSizeDp: Int, val metaMaxWidthDp: Int)
  fun homeCardWidthDp(screenWidthDp: Float): Int
  fun solarStatsCompact(cardWidthDp: Int): Boolean
  fun homeOverlayCaps(screenWidthDp: Float, screenHeightDp: Float, reserveCardColumn: Boolean): HomeOverlayCaps
  fun takeoverLayout(screenWidthDp: Float, screenHeightDp: Float): TakeoverLayout
  fun agendaDayCount(panelContentWidthDp: Float): Int
  ```

- [ ] **Step 1: Write the failing test** — create `app/src/test/java/com/rar/echodash/ui/model/AdaptiveGeometryTest.kt`:

```kotlin
package com.rar.echodash.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the design spec's golden value table verbatim. The Show 5 (787×394) rows ARE today's shipped
 * layout — the golden rule: they must never change. Show 8 / Tab M9 rows document the growth; the
 * tiny 500×300 row documents floor/clamp behaviour only (no such device exists in the fleet).
 */
class AdaptiveGeometryTest {

    // ---- homeCardWidthDp: tier table + boundaries ----

    @Test
    fun homeCardWidthTierTableAndBoundaries() {
        // Golden column: Show 5 → 248, Show 8 → 300, Tab M9 → 320, tiny 500 → 248.
        assertEquals(248, homeCardWidthDp(787f))
        assertEquals(300, homeCardWidthDp(961f))
        assertEquals(320, homeCardWidthDp(1340f))
        assertEquals(248, homeCardWidthDp(500f))
        // Tier boundaries are strict: <900, <1200, else.
        assertEquals(248, homeCardWidthDp(899f))
        assertEquals(300, homeCardWidthDp(900f))
        assertEquals(300, homeCardWidthDp(1199f))
        assertEquals(320, homeCardWidthDp(1200f))
    }

    // ---- solarStatsCompact ----

    @Test
    fun solarStatsCompactThreshold() {
        assertTrue(solarStatsCompact(248))   // today's 248 card keeps the 12sp squeeze
        assertFalse(solarStatsCompact(300))  // 300dp+ relaxes to 14sp
    }

    // ---- homeOverlayCaps: all five golden rows ----

    @Test
    fun homeOverlayCapsGoldenTable() {
        // Show 5 787×394, cards configured — today's shipped caps (golden rule).
        assertEquals(HomeOverlayCaps(460, 200, 420), homeOverlayCaps(787f, 394f, reserveCardColumn = true))
        // Show 8 961×601, cards configured.
        assertEquals(HomeOverlayCaps(582, 407, 594), homeOverlayCaps(961f, 601f, reserveCardColumn = true))
        // Tab M9 1340×800, cards configured — notif width & next-event hit their caps (700 / 640).
        assertEquals(HomeOverlayCaps(700, 606, 640), homeOverlayCaps(1340f, 800f, reserveCardColumn = true))
        // Show 5, no cards configured — notif width reclaims the card column (731 → capped 700).
        assertEquals(HomeOverlayCaps(700, 200, 420), homeOverlayCaps(787f, 394f, reserveCardColumn = false))
        // Tiny 500×300 — every field floors to its minimum.
        assertEquals(HomeOverlayCaps(300, 120, 240), homeOverlayCaps(500f, 300f, reserveCardColumn = true))
    }

    // ---- takeoverLayout: golden rows (the no-cards row has no takeover values) ----

    @Test
    fun takeoverLayoutGoldenTable() {
        // Show 5 — height-limited art (360) and today's effective 299dp metadata width.
        assertEquals(TakeoverLayout(360, 299), takeoverLayout(787f, 394f))
        // Show 8 — width-limited art (442), wider metadata (391). metaMax uses the floored art Int.
        assertEquals(TakeoverLayout(442, 391), takeoverLayout(961f, 601f))
        // Tab M9.
        assertEquals(TakeoverLayout(616, 596), takeoverLayout(1340f, 800f))
        // Tiny — art floors via the width fraction (230), metadata clamps up to 240.
        assertEquals(TakeoverLayout(230, 240), takeoverLayout(500f, 300f))
    }

    // ---- agendaDayCount ----

    @Test
    fun agendaDayCountGoldenTable() {
        // Panel CONTENT widths (screen − 2×24 PanelSurface pads): Show 5 739 → 3, Show 8 913 → 4,
        // Tab M9 1292 → 5; the tiny 452 clamps up to the 3 floor.
        assertEquals(3, agendaDayCount(739f))
        assertEquals(4, agendaDayCount(913f))
        assertEquals(5, agendaDayCount(1292f))
        assertEquals(3, agendaDayCount(452f))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.AdaptiveGeometryTest'`
Expected: FAIL — compilation error, `unresolved reference: homeCardWidthDp` (and the other functions/classes) because `AdaptiveGeometry.kt` does not exist yet.

- [ ] **Step 3: Write the implementation** — create `app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt`:

```kotlin
package com.rar.echodash.ui.model

import kotlin.math.floor

/**
 * Per-screen adaptive geometry: the sizing math for the four growable dashboard regions. Pure
 * Kotlin — NO Android/Compose imports — so it is plain-JVM unit-testable like its ui/model siblings.
 * The composables measure their local space with BoxWithConstraints, pass the dp values in as Float,
 * and apply the whole-dp Int results with `.dp`.
 *
 * Design rule (see docs/superpowers/specs/2026-07-16-adaptive-sizing-design.md): element sizes stay
 * fixed dp everywhere; only the growable regions absorb the extra room. Free space is therefore
 * `screen − fixed reserves` (a subtraction, not a percentage), because the fixed neighbours
 * (paddings, card column, clock block) do not grow with the screen. Every constant is chosen so the
 * Echo Show 5's 787×394dp canvas reproduces today's shipped layout exactly (the golden rule).
 *
 * Outputs are floor()ed then clamped so a fractional dp never rounds a region past its neighbour.
 */

data class HomeOverlayCaps(
    val notifMaxWidthDp: Int,
    val notifMaxHeightDp: Int,
    val nextEventMaxWidthDp: Int,
)

data class TakeoverLayout(
    val artSizeDp: Int,
    val metaMaxWidthDp: Int,
)

// ---- reserves, each named for the physical thing it holds ----

// Home overlays draw at a 28dp start pad and a 28dp end pad; 56 is both edges.
private const val EDGE_PADS = 56
// Gap between the notification stack's right edge and the EV/solar card column.
private const val CARD_GAP = 23
// The pills row plus its top pad — the notification stack's top offset today (padding top = 70dp).
private const val TOP_ROW = 70
// Bottom-left clock block height: the big time line + the date line + its 20dp bottom pad.
private const val CLOCK_BLOCK_H = 110
// Breathing room kept between the bottom of the notification stack and the top of the clock block.
private const val NOTIF_CLOCK_GAP = 14
// Home overlay end pad (the next-event card is right-aligned at 28dp).
private const val END_PAD = 28
// Worst-case width of the bottom-left clock's date line (a long weekday + full date).
private const val CLOCK_BLOCK_W = 230
// Horizontal clearance kept between the next-event card and the clock block; the value that
// reproduces today's 420dp cap on the Show 5 (787 − 28 − 230 − 109 = 420).
private const val CLOCK_CLEAR = 109

// Takeover: vertical margins around the art card (today's 2×17 around the 360dp card).
private const val ART_VMARGINS = 34
// Art width as a fraction of the screen — just above 360/787 so the Show 5 stays HEIGHT-limited (its
// art is capped by height, not width) while wider screens grow the art. The art-vs-metadata split.
private const val ART_FRACTION = 0.46f
// Metadata left-margin budget: 48 start pad + 48 art end pad + 32 art↔meta clearance = today's
// padding(end = 440) on the Show 5 (= 360 art + 48 + 32).
private const val META_MARGINS = 128

// Agenda: target per-column width; floor(contentW / this) keeps columns in the 219–249dp band
// around the Show 5's current 238dp.
private const val AGENDA_COL_TARGET = 228

/** EV/solar card width tier: <900 → 248 (today's), <1200 → 300, else 320. Discrete because the
 *  cards are fixed-size elements by design — they do not scale, wider screens just fit more of them. */
fun homeCardWidthDp(screenWidthDp: Float): Int = when {
    screenWidthDp < 900f -> 248
    screenWidthDp < 1200f -> 300
    else -> 320
}

/** True below a 300dp card: the solar stats row keeps the 12sp/14dp/3dp squeeze from 2beecaf; at
 *  300dp+ the row relaxes to 14sp/16dp/4dp. */
fun solarStatsCompact(cardWidthDp: Int): Boolean = cardWidthDp < 300

/**
 * Home overlay caps for a [screenWidthDp]×[screenHeightDp] canvas. When [reserveCardColumn] the
 * notification width subtracts the EV/solar card column so a row never slides under the cards; pass
 * CONFIG presence (not current card visibility) so a card fading in/out never jumps the width. The
 * next-event and height caps are independent of the card column.
 */
fun homeOverlayCaps(
    screenWidthDp: Float,
    screenHeightDp: Float,
    reserveCardColumn: Boolean,
): HomeOverlayCaps {
    val reserve = if (reserveCardColumn) homeCardWidthDp(screenWidthDp) + CARD_GAP else 0
    val notifW = floorDp(screenWidthDp - EDGE_PADS - reserve).coerceIn(300, 700)
    val notifH = floorDp(screenHeightDp - TOP_ROW - CLOCK_BLOCK_H - NOTIF_CLOCK_GAP).coerceAtLeast(120)
    val nextEventW = floorDp(screenWidthDp - END_PAD - CLOCK_BLOCK_W - CLOCK_CLEAR).coerceIn(240, 640)
    return HomeOverlayCaps(notifW, notifH, nextEventW)
}

/**
 * Now-playing takeover split: a square art card sized by the smaller of the height budget and a
 * width fraction, and the metadata column filling the width the art leaves. These two growable
 * regions dividing the width are the one genuine proportional split in the design.
 */
fun takeoverLayout(screenWidthDp: Float, screenHeightDp: Float): TakeoverLayout {
    val art = floorDp(minOf(screenHeightDp - ART_VMARGINS, screenWidthDp * ART_FRACTION)).coerceAtLeast(200)
    // metaMax subtracts the FLOORED art (Int) so the clearance the art actually occupies is what's
    // reserved — matches the golden table (Show 8: 961 − 442 − 128 = 391, not 961 − 442.06 − 128).
    val metaMax = floorDp(screenWidthDp - art - META_MARGINS).coerceAtLeast(240)
    return TakeoverLayout(art, metaMax)
}

/** Agenda columns from panel CONTENT width (inside PanelSurface's 24dp pads): floor(w / 228),
 *  clamped 3..5 so the Show 5 keeps 3 and no screen shows a lonely 1–2 or over-thin 6+ columns. */
fun agendaDayCount(panelContentWidthDp: Float): Int =
    floorDp(panelContentWidthDp / AGENDA_COL_TARGET).coerceIn(3, 5)

// floor then Int: outputs never round a region past its fixed neighbour. toDouble() keeps floor
// exact for the Float inputs (a Float multiply like 961 × 0.46f lands at 442.06, flooring to 442).
private fun floorDp(value: Float): Int = floor(value.toDouble()).toInt()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.AdaptiveGeometryTest'`
Expected: PASS — 5 tests green (`homeCardWidthTierTableAndBoundaries`, `solarStatsCompactThreshold`, `homeOverlayCapsGoldenTable`, `takeoverLayoutGoldenTable`, `agendaDayCountGoldenTable`).

- [ ] **Step 5: Run the full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures (record the total test count as the baseline for later tasks).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt \
        app/src/test/java/com/rar/echodash/ui/model/AdaptiveGeometryTest.kt
git commit -m "$(cat <<'EOF'
feat(ui): AdaptiveGeometry — pure per-screen sizing math + golden-table tests

Adds the pure-Kotlin (no Android/Compose) sizing module for the four growable
dashboard regions: home overlay caps, now-playing takeover, EV/solar card width,
agenda day count. Every constant is derived so the Echo Show 5's 787×394dp canvas
reproduces today's shipped layout exactly; AdaptiveGeometryTest pins the spec's
golden value table verbatim plus the tier boundaries. No wiring yet.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 2: 5-day agenda horizon — `agendaDays` dayCount + EntityHub window

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/model/CalendarModel.kt:88-109`
- Modify: `app/src/main/java/com/rar/echodash/ha/EntityHub.kt:156-165`
- Test: `app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt` (add 2 tests after line 143)

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId, dayCount: Int = 3): List<AgendaDay>` — the default keeps the existing production caller (`CalendarPanel:52`) and the two existing tests compiling and unchanged.

Note: `EntityHub.getCalendarEvents`'s fetch window has no unit test (it is a service-call window, not a pure function) — grep confirms no test pins `plusDays(3)` or the 3-day window, so nothing breaks. The wider window is verified on-device in Task 6. The next-event home card gaining a 5-day horizon (an event 4 days out now shows when nothing sooner exists) is the intended behaviour change; it does not affect any 787×394 SIZE and so does not violate the golden rule.

- [ ] **Step 1: Write the failing tests** — add these two tests to `CalendarModelTest.kt` immediately after `multiDayEventSpansColumnsAllDayFirstEndedExcluded` (after line 143), inside the `// ---- agendaDays ----` section. (2026-07-14 is a Tuesday, per the existing tests.)

```kotlin
    @Test
    fun agendaProducesFiveLabeledDaysWhenAsked() {
        val now = ms("2026-07-14T12:00:00-04:00") // Tuesday
        val days = agendaDays(emptyList(), now, zone, dayCount = 5)
        assertEquals(5, days.size)
        assertEquals("Today", days[0].label)
        assertEquals("Tomorrow", days[1].label)
        assertEquals("Thursday", days[2].label) // 2026-07-16
        assertEquals("Friday", days[3].label)   // 2026-07-17
        assertEquals("Saturday", days[4].label) // 2026-07-18
    }

    @Test
    fun fiveDayAgendaPlacesEventsInTheirColumns() {
        val now = ms("2026-07-14T12:00:00-04:00")
        val events = listOf(
            timed("2026-07-17T09:00:00-04:00", "2026-07-17T10:00:00-04:00", "Fri"),
            timed("2026-07-18T09:00:00-04:00", "2026-07-18T10:00:00-04:00", "Sat"),
        )
        val days = agendaDays(events, now, zone, dayCount = 5)
        assertEquals(listOf("Fri"), days[3].events.map { it.title }) // day 3 = Friday
        assertEquals(listOf("Sat"), days[4].events.map { it.title }) // day 4 = Saturday
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.CalendarModelTest'`
Expected: FAIL — compilation error, `too many arguments for public fun agendaDays(...)` because the `dayCount` parameter does not exist yet.

- [ ] **Step 3: Add the `dayCount` parameter** — in `CalendarModel.kt`, replace the doc comment and function header at lines 88-95.

Replace this:

```kotlin
/**
 * Exactly 3 day columns starting at [nowMs]'s local date. An event appears in every column its
 * `[startMs, endMs)` span overlaps; already-ended events (`endMs <= nowMs`) are excluded everywhere.
 * Within a column: all-day events first, then by `startMs`, stable (ties keep input order).
 */
fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId): List<AgendaDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0 until 3).map { offset ->
```

with this:

```kotlin
/**
 * [dayCount] day columns (default 3) starting at [nowMs]'s local date. An event appears in every
 * column its `[startMs, endMs)` span overlaps; already-ended events (`endMs <= nowMs`) are excluded
 * everywhere. Within a column: all-day events first, then by `startMs`, stable (ties keep input order).
 */
fun agendaDays(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId, dayCount: Int = 3): List<AgendaDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0 until dayCount).map { offset ->
```

(The rest of the function body — `day`, `dayStart`, `dayEnd`, the `label` when-block, `dayEvents`, `AgendaDay(...)` — is unchanged; the `else -> day.dayOfWeek...` branch already labels offsets ≥ 2 by weekday, so days 3 and 4 read "Friday"/"Saturday" for free.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.CalendarModelTest'`
Expected: PASS — the two new tests plus the two pre-existing agenda tests (`agendaProducesThreeLabeledDays`, `multiDayEventSpansColumnsAllDayFirstEndedExcluded`) all green (the default-3 call is unchanged).

- [ ] **Step 5: Widen the EntityHub fetch window** — in `EntityHub.kt`, update the doc comment and the `end` line (lines 156-165).

Replace this:

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
```

with this:

```kotlin
    /**
     * One calendar.get_events call for all configured calendars. Returns the raw
     * {"response":{"<entity>":{"events":[...]}}} element, or null on any failure (caller keeps last
     * good list). Window is now .. now+5 days, RFC3339 with the device's local offset
     * (e.g. 2026-07-14T11:30:00-04:00). Events come from this service call, NOT state subscriptions.
     */
    suspend fun getCalendarEvents(entityIds: List<String>): JsonElement? =
        runCatching {
            val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            // 5-day window: the agenda can now show up to 5 day columns (AdaptiveGeometry.agendaDayCount
            // caps at 5) and the home next-event card gets the longer horizon for free.
            val end = now.plusDays(5)
```

- [ ] **Step 6: Run the full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures (baseline + 2 new tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/model/CalendarModel.kt \
        app/src/main/java/com/rar/echodash/ha/EntityHub.kt \
        app/src/test/java/com/rar/echodash/ui/model/CalendarModelTest.kt
git commit -m "$(cat <<'EOF'
feat(calendar): 5-day agenda horizon — agendaDays dayCount + EntityHub window

agendaDays gains dayCount (default 3, so existing callers/tests are unchanged);
the fetch window widens 3→5 days so a 5-column agenda and the home next-event card
both have the longer horizon. Wiring of the by-width day count lands in Task 6.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 3: Make EV/solar cards + gauge width-flexible (no behaviour change)

**Files:** Modify `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — imports, `GaugeBar` (465–530), `EvCardView` (424–459), `SolarCardView` (533–633). Callsites at 321–322 and 449/566 stay as they are (they use the new default `cardWidth = 248.dp`, so rendering is identical).

**Interfaces:**
- Consumes: `solarStatsCompact(Int)` from Task 1.
- Produces: `private fun EvCardView(card: EvCard, cardWidth: Dp = 248.dp)`, `private fun SolarCardView(card: SolarCard, cardWidth: Dp = 248.dp)`. `GaugeBar`'s signature is unchanged — it now fills the measured card content width instead of a hard 216dp.

No test step: these are composables (untested by convention). Correctness here = compiles, existing tests stay green, and behaviour at a 248dp card is bit-identical (216dp gauge track, compact stats row) so the Show 5 is unchanged.

- [ ] **Step 1: Add imports** — in `HomeView.kt`, add these four imports (alongside the existing ones; keep the file's import ordering):

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import com.rar.echodash.ui.model.solarStatsCompact
```

- [ ] **Step 2: Rewrite `GaugeBar`** — replace the whole composable (the doc comment at 464 through the closing brace at 530).

Replace this:

```kotlin
/** 216dp gauge track: colored fill, optional directional shimmer, optional limit tick. */
@Composable
private fun GaugeBar(
    fillPct: Int,
    shimmer: Boolean,
    tickPct: Int? = null,
    fill: Color = GaugeGreen,
    reverse: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .size(width = 216.dp, height = 8.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fillPct / 100f)
                .fillMaxHeight()
                // clip() is required: background() draws a rounded shape but does
                // not clip children, so the shimmer band would bleed past the fill.
                .clip(RoundedCornerShape(4.dp))
                .background(fill),
        ) {
            // Only run the infinite animation while actually charging.
            if (shimmer) {
                val transition = rememberInfiniteTransition(label = "gaugeShimmer")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "gaugeShimmerX",
                )
                Box(
                    Modifier
                        // Sweep a 24dp band left-to-right across the full 216dp track
                        // width; the fill's clip keeps it inside the filled region.
                        .offset(x = ((if (reverse) 1f - fraction else fraction) * (216 + 24)).dp - 24.dp)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }
        if (tickPct != null) {
            Box(
                Modifier
                    // 2dp tick at the vehicle's charge limit; -1dp centers it on the fraction.
                    .offset(x = (216 * tickPct / 100 - 1).dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(1.dp)),
            )
        }
    }
}
```

with this:

```kotlin
/** Gauge track filling the card's content width: colored fill, optional directional shimmer,
 *  optional limit tick. BoxWithConstraints so the shimmer sweep and tick math use the MEASURED
 *  track width instead of a hard 216 — at a 248dp card (216dp track) behaviour is bit-identical. */
@Composable
private fun GaugeBar(
    fillPct: Int,
    shimmer: Boolean,
    tickPct: Int? = null,
    fill: Color = GaugeGreen,
    reverse: Boolean = false,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    ) {
        val trackW = maxWidth.value // measured track width in dp
        Box(
            Modifier
                .fillMaxWidth(fillPct / 100f)
                .fillMaxHeight()
                // clip() is required: background() draws a rounded shape but does
                // not clip children, so the shimmer band would bleed past the fill.
                .clip(RoundedCornerShape(4.dp))
                .background(fill),
        ) {
            // Only run the infinite animation while actually charging.
            if (shimmer) {
                val transition = rememberInfiniteTransition(label = "gaugeShimmer")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "gaugeShimmerX",
                )
                Box(
                    Modifier
                        // Sweep a 24dp band left-to-right across the full track width;
                        // the fill's clip keeps it inside the filled region.
                        .offset(x = ((if (reverse) 1f - fraction else fraction) * (trackW + 24)).dp - 24.dp)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }
        if (tickPct != null) {
            // Integer track width preserves the old integer-division tick math (216*tickPct/100
            // truncates); a Float would shift the tick a sub-dp fraction off today's position.
            val trackWi = maxWidth.value.toInt()
            Box(
                Modifier
                    // 2dp tick at the vehicle's charge limit; -1dp centers it on the fraction.
                    .offset(x = (trackWi * tickPct / 100 - 1).dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(1.dp)),
            )
        }
    }
}
```

- [ ] **Step 3: Parametrize `EvCardView` width** — replace the function header + `Column` opening at lines 422-431.

Replace this:

```kotlin
/** One EV charging pill: EV-station icon + name, a power/energy line, then a battery gauge + SOC/eta. */
@Composable
private fun EvCardView(card: EvCard) {
    Column(
        Modifier
            .width(248.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
```

with this:

```kotlin
/** One EV charging pill: EV-station icon + name, a power/energy line, then a battery gauge + SOC/eta.
 *  [cardWidth] defaults to today's 248dp; HomeView passes the per-screen width. */
@Composable
private fun EvCardView(card: EvCard, cardWidth: Dp = 248.dp) {
    Column(
        Modifier
            .width(cardWidth)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
```

- [ ] **Step 4: Parametrize `SolarCardView` width** — replace the function header + `Column` opening at lines 533-541.

Replace this:

```kotlin
/** Home solar pill: sun icon + PV output, battery gauge, home/grid line. */
@Composable
private fun SolarCardView(card: SolarCard) {
    Column(
        Modifier
            .width(248.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
```

with this:

```kotlin
/** Home solar pill: sun icon + PV output, battery gauge, home/grid line. [cardWidth] defaults to
 *  today's 248dp; HomeView passes the per-screen width. */
@Composable
private fun SolarCardView(card: SolarCard, cardWidth: Dp = 248.dp) {
    Column(
        Modifier
            .width(cardWidth)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
```

- [ ] **Step 5: Switch the solar stats row between compact and relaxed** — replace the stats block at lines 573-631 (the `if (card.battText != null || ...)` block).

Replace this:

```kotlin
        if (card.battText != null || card.homeText != null || card.gridText != null) {
            val statsWhite = Color.White.copy(alpha = 0.9f)
            // Compact sizing so battery + home + grid all fit one line in the fixed-width card.
            // Stopgap until per-screen adaptive sizing (see [[echo-dashboard-future-features]]).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (card.battText != null) {
                    Icon(
                        Icons.Outlined.BatteryStd, contentDescription = "Battery",
                        tint = statsWhite, modifier = Modifier.size(14.dp),
                    )
                    Text(card.battText, color = statsWhite, fontSize = 12.sp, maxLines = 1)
                    // Direction arrow mirrors the grid: into the battery = points at it (charging),
                    // away from it = out of it (discharging), dash = idle.
                    Icon(
                        when (card.battFlow) {
                            BattFlow.CHARGING -> Icons.AutoMirrored.Outlined.ArrowBack
                            BattFlow.DISCHARGING -> Icons.AutoMirrored.Outlined.ArrowForward
                            BattFlow.IDLE -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.battFlow) {
                            BattFlow.CHARGING -> "Charging"
                            BattFlow.DISCHARGING -> "Discharging"
                            BattFlow.IDLE -> "Idle"
                        },
                        tint = statsWhite, modifier = Modifier.size(14.dp),
                    )
                }
                if (card.homeText != null) {
                    Icon(
                        Icons.Outlined.Home, contentDescription = "Home",
                        tint = statsWhite, modifier = Modifier.size(14.dp),
                    )
                    Text(card.homeText, color = statsWhite, fontSize = 12.sp, maxLines = 1)
                }
                if (card.gridText != null) {
                    Icon(
                        when (card.gridImporting) {
                            true -> Icons.AutoMirrored.Outlined.ArrowBack
                            false -> Icons.AutoMirrored.Outlined.ArrowForward
                            null -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.gridImporting) {
                            true -> "Import"
                            false -> "Export"
                            null -> "Balanced"
                        },
                        tint = statsWhite, modifier = Modifier.size(14.dp),
                    )
                    Icon(
                        Icons.Outlined.ElectricMeter, contentDescription = "Grid",
                        tint = statsWhite, modifier = Modifier.size(14.dp),
                    )
                    Text(card.gridText, color = statsWhite, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
```

with this:

```kotlin
        if (card.battText != null || card.homeText != null || card.gridText != null) {
            val statsWhite = Color.White.copy(alpha = 0.9f)
            // Battery + home + grid on one line. Below a 300dp card the row keeps the compact
            // 12sp/14dp/3dp squeeze (all three segments won't otherwise fit the fixed 248dp card);
            // at 300dp+ it relaxes to 14sp/16dp/4dp. AdaptiveGeometry.solarStatsCompact decides.
            val compact = solarStatsCompact(cardWidth.value.toInt())
            val statsFont = if (compact) 12.sp else 14.sp
            val statsIcon = if (compact) 14.dp else 16.dp
            val statsGap = if (compact) 3.dp else 4.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(statsGap),
            ) {
                if (card.battText != null) {
                    Icon(
                        Icons.Outlined.BatteryStd, contentDescription = "Battery",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.battText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                    // Direction arrow mirrors the grid: into the battery = points at it (charging),
                    // away from it = out of it (discharging), dash = idle.
                    Icon(
                        when (card.battFlow) {
                            BattFlow.CHARGING -> Icons.AutoMirrored.Outlined.ArrowBack
                            BattFlow.DISCHARGING -> Icons.AutoMirrored.Outlined.ArrowForward
                            BattFlow.IDLE -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.battFlow) {
                            BattFlow.CHARGING -> "Charging"
                            BattFlow.DISCHARGING -> "Discharging"
                            BattFlow.IDLE -> "Idle"
                        },
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                }
                if (card.homeText != null) {
                    Icon(
                        Icons.Outlined.Home, contentDescription = "Home",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.homeText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                }
                if (card.gridText != null) {
                    Icon(
                        when (card.gridImporting) {
                            true -> Icons.AutoMirrored.Outlined.ArrowBack
                            false -> Icons.AutoMirrored.Outlined.ArrowForward
                            null -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.gridImporting) {
                            true -> "Import"
                            false -> "Export"
                            null -> "Balanced"
                        },
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Icon(
                        Icons.Outlined.ElectricMeter, contentDescription = "Grid",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.gridText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                }
            }
        }
```

- [ ] **Step 6: Run the full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures (no new tests; callsites use `cardWidth = 248.dp` so a 248 card is unchanged — the gauge track is 216dp and the stats row is compact, exactly as today).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/HomeView.kt
git commit -m "$(cat <<'EOF'
refactor(home): make EV/solar cards + gauge width-flexible (no behaviour change)

EvCardView/SolarCardView gain a cardWidth param (default 248dp); GaugeBar wraps in
BoxWithConstraints so its shimmer sweep and limit tick use the measured track width
instead of a hard 216; the solar stats row picks compact (12sp/14dp/3dp) vs relaxed
(14sp/16dp/4dp) via solarStatsCompact. At a 248dp card everything is bit-identical
to today — HomeView still passes 248, so nothing renders differently yet.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 4: HomeView root BoxWithConstraints + overlay caps wiring

**Files:** Modify `app/src/main/java/com/rar/echodash/ui/HomeView.kt` (imports, signature at 150-177, root `Box` at 194, callsites 321-322, notification site 326-350, next-event site 352-369) and `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` (HomeView callsite 215-241).

**Interfaces:**
- Consumes: `homeCardWidthDp`, `homeOverlayCaps`, `HomeOverlayCaps` (Task 1); `EvCardView(card, cardWidth)`, `SolarCardView(card, cardWidth)` (Task 3).
- Produces: `HomeView(..., reserveCardColumn: Boolean, ...)` — a new parameter DashboardShell passes.

No test step (composable). Correctness = compiles, tests stay green, and at 787×394 with cards configured the caps are `(460, 200, 420)` and cardWidth is 248 — identical to today.

- [ ] **Step 1: Add imports** — in `HomeView.kt`, add these two (BoxWithConstraints was already added in Task 3):

```kotlin
import com.rar.echodash.ui.model.homeCardWidthDp
import com.rar.echodash.ui.model.homeOverlayCaps
```

- [ ] **Step 2: Add the `reserveCardColumn` parameter** — in the `HomeView` signature, insert the new parameter immediately after `onDismiss: (String) -> Unit = {},` (line 160) and before `clockFormat: ClockFormat,` (line 161).

Replace this:

```kotlin
    notifications: List<NotificationItem> = emptyList(),
    onDismiss: (String) -> Unit = {},
    clockFormat: ClockFormat,
```

with this:

```kotlin
    notifications: List<NotificationItem> = emptyList(),
    onDismiss: (String) -> Unit = {},
    // CONFIG presence of EV/solar cards (not current visibility): reserves the card column in the
    // notification width cap so a card fading in never makes the notification stack jump width.
    reserveCardColumn: Boolean = false,
    clockFormat: ClockFormat,
```

- [ ] **Step 3: Swap the root `Box` for `BoxWithConstraints` and derive the two values** — at line 194, change `Box(` to `BoxWithConstraints(` (the modifier chain and all children are unchanged — `BoxWithConstraintsScope` is a `BoxScope`, so every `.align(...)` child still compiles), then insert the two derived vals at the top of the content lambda.

Replace this:

```kotlin
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .pointerInput(order) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (order.size > 1 && abs(dx) > 60.dp.toPx()) {
                            photoIndex += if (dx < 0) 1 else -1
                        }
                    },
                ) { _, dragAmount -> dx += dragAmount }
            }
    ) {
        Crossfade(targetState = takeoverVisible, animationSpec = tween(1000), label = "home-backdrop") { active ->
```

with this:

```kotlin
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .pointerInput(order) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (order.size > 1 && abs(dx) > 60.dp.toPx()) {
                            photoIndex += if (dx < 0) 1 else -1
                        }
                    },
                ) { _, dragAmount -> dx += dragAmount }
            }
    ) {
        // Per-screen sizing from the measured canvas (787×394 on the Show 5). Cheap; recomputed only
        // when the constraints or card-config presence change.
        val cardWidth = homeCardWidthDp(maxWidth.value).dp
        val caps = homeOverlayCaps(maxWidth.value, maxHeight.value, reserveCardColumn)

        Crossfade(targetState = takeoverVisible, animationSpec = tween(1000), label = "home-backdrop") { active ->
```

- [ ] **Step 4: Pass `cardWidth` to the card views** — at lines 321-322, replace:

```kotlin
                    evs.forEach { EvCardView(it) }
                    if (solar != null) SolarCardView(solar)
```

with this:

```kotlin
                    evs.forEach { EvCardView(it, cardWidth) }
                    if (solar != null) SolarCardView(solar, cardWidth)
```

- [ ] **Step 5: Wire the notification caps** — replace the comment + `AnimatedVisibility` notification block at lines 326-350.

Replace this:

```kotlin
            // Notification area: just below the weather/AQI pill row (top = 70dp), width-capped so
            // it never collides with the EV/solar stack, height-capped + clipped so the bottom-left
            // clock is never covered.
            AnimatedVisibility(
                visible = notifications.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 70.dp),
            ) {
                NotificationArea(
                    notifications = notifications,
                    onDismiss = onDismiss,
                    // The Echo panel is 960x480 px at 195 dpi = ~787x394dp. The EV/solar column's
                    // left edge is at ~787 - 28 (end pad) - 248 (card) = ~511dp, so a start of 28dp
                    // leaves ~471dp before a row would slide under the cards; 460 keeps a gap.
                    // Height: the bottom-left clock's top edge is at ~394 - 20 (pad) - 90 (clock +
                    // date) = ~284dp; starting at 70dp, a 200dp cap keeps the scrolling stack clear.
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .heightIn(max = 200.dp)
                        .clipToBounds(),
                )
            }
```

with this:

```kotlin
            // Notification area: just below the weather/AQI pill row (top = 70dp). Its width and
            // height caps come from AdaptiveGeometry.homeOverlayCaps: width so a row never slides
            // under the EV/solar card column, height (+ clipToBounds) so the bottom-left clock is
            // never covered. Both grow with the screen (see the design spec's golden table).
            AnimatedVisibility(
                visible = notifications.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 70.dp),
            ) {
                NotificationArea(
                    notifications = notifications,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = caps.notifMaxWidthDp.dp)
                        .heightIn(max = caps.notifMaxHeightDp.dp)
                        .clipToBounds(),
                )
            }
```

- [ ] **Step 6: Wire the next-event cap** — replace the comment at lines 352-356 and the `widthIn` at line 369.

Replace this:

```kotlin
            // Next-event card: bottom-right, diagonal from the clock, width-capped so it never
            // approaches the bottom-left clock block (worst-case date line ends ~230dp from the
            // left; a 420dp cap puts the card's left edge at 787-28-420 = ~339dp, >100dp clear).
            // Re-derives on the minute tick so "Tomorrow" flips to a time and "Now" appears
            // without waiting for the next 15-minute fetch.
            val nextEvent = remember(calendarEvents, now) { nextEventCard(calendarEvents, now) }
```

with this:

```kotlin
            // Next-event card: bottom-right, diagonal from the clock. Width-capped by
            // AdaptiveGeometry.homeOverlayCaps.nextEventMaxWidthDp so it never approaches the
            // bottom-left clock block (the cap reserves the worst-case date-line width + clearance);
            // the cap widens on larger screens. Re-derives on the minute tick so "Tomorrow" flips
            // to a time and "Now" appears without waiting for the next 15-minute fetch.
            val nextEvent = remember(calendarEvents, now) { nextEventCard(calendarEvents, now) }
```

Then replace the next-event card's width modifier at line 369:

```kotlin
                            .widthIn(max = 420.dp)
```

with this:

```kotlin
                            .widthIn(max = caps.nextEventMaxWidthDp.dp)
```

- [ ] **Step 7: Pass `reserveCardColumn` from DashboardShell** — in `DashboardShell.kt`, in the `HomeView(...)` call (215-241), add the argument after `onDismiss = dismissKey,` (line 224).

Replace this:

```kotlin
                        notifications = notifications,
                        onDismiss = dismissKey,
                        calendarEvents = calendarEvents,
```

with this:

```kotlin
                        notifications = notifications,
                        onDismiss = dismissKey,
                        // CONFIG presence, not current card visibility, so the notification width
                        // never jumps when a card fades in/out. ids() is public on both configs.
                        reserveCardColumn = config.entities.evs.isNotEmpty() || config.entities.solar.ids().isNotEmpty(),
                        calendarEvents = calendarEvents,
```

- [ ] **Step 8: Run the full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/HomeView.kt \
        app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
git commit -m "$(cat <<'EOF'
feat(home): adaptive overlay caps + card width via BoxWithConstraints

HomeView's root becomes BoxWithConstraints; the notification stack, next-event card,
and EV/solar card column now size from AdaptiveGeometry.homeOverlayCaps /
homeCardWidthDp against the measured canvas. New reserveCardColumn param (config
presence of EV/solar) fed from DashboardShell keeps the notification width stable as
cards fade. At 787×394 with cards the caps are (460,200,420) and cards 248dp — the
Show 5 is unchanged; wider screens widen the stack and grow the cards.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 5: Adaptive now-playing takeover (art vs metadata split)

**Files:** Modify `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` (imports, root `Box` at 66, art `.size(360.dp)` at 89, metadata padding/widthIn at 104-108).

**Interfaces:**
- Consumes: `takeoverLayout`, `TakeoverLayout` (Task 1).
- Produces: nothing new — `NowPlayingHome`'s signature is unchanged; only its internal geometry is now measured. (The browse button, transport, and volume are unchanged per spec.)

No test step (composable). Correctness = compiles, tests stay green, and at 787×394 art is 360dp and the metadata width is 299dp (= 787 − 48 − 440) — identical to today.

- [ ] **Step 1: Add imports** — in `NowPlayingHome.kt`, add:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import com.rar.echodash.ui.model.takeoverLayout
```

- [ ] **Step 2: Swap the root `Box` for `BoxWithConstraints` and derive the layout** — at line 66, replace:

```kotlin
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
```

with this:

```kotlin
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two growable regions dividing the width — the one true proportional split in the design.
        val layout = takeoverLayout(maxWidth.value, maxHeight.value)
        if (art != null) {
```

- [ ] **Step 3: Size the art card from the layout** — at line 89, replace:

```kotlin
                .padding(end = 48.dp)
                .size(360.dp)
```

with this:

```kotlin
                .padding(end = 48.dp)
                .size(layout.artSizeDp.dp)
```

- [ ] **Step 4: Size the metadata column from the layout** — replace the metadata `Column` modifier at lines 104-109.

Replace this:

```kotlin
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, end = 440.dp)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
```

with this:

```kotlin
        Column(
            Modifier
                .align(Alignment.CenterStart)
                // No end pad: metaMaxWidthDp already reserves the art width + 32dp clearance, so the
                // column can't reach the art card at any screen size (Show 5: 787 − 360 − 128 = 299).
                .padding(start = 48.dp)
                .widthIn(max = layout.metaMaxWidthDp.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
```

- [ ] **Step 5: Run the full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt
git commit -m "$(cat <<'EOF'
feat(media): adaptive now-playing takeover (art vs metadata split)

NowPlayingHome's root becomes BoxWithConstraints; the art card and metadata column
size from AdaptiveGeometry.takeoverLayout — the art takes min(height budget, 0.46×w)
and the metadata fills the rest with a guaranteed 32dp clearance. At 787×394 art is
360dp and metadata 299dp (today's effective width); the Show 8 grows art to 442dp
with a wider metadata column.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Task 6: Adaptive agenda day-count + final gate + on-device verify

**Files:** Modify `app/src/main/java/com/rar/echodash/ui/panels/CalendarPanel.kt` (imports, doc comment at 36, body at 43-58).

**Interfaces:**
- Consumes: `agendaDayCount` (Task 1), `agendaDays(..., dayCount)` (Task 2).
- Produces: running feature.

No test step (composable). Correctness = compiles, tests stay green; at the Show 5's 739dp panel content width `agendaDayCount` returns 3 — identical to today.

- [ ] **Step 1: Add imports** — in `CalendarPanel.kt`, add:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import com.rar.echodash.ui.model.agendaDayCount
```

- [ ] **Step 2: Wrap the agenda in BoxWithConstraints and pass the day count** — replace the doc comment (line 36) and the panel body (lines 43-58).

Replace this:

```kotlin
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
```

with this:

```kotlin
/** Adaptive agenda (3–5 equal-weight day columns, chosen by panel width): color-coded event rows. */
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
        // Inside PanelSurface (its 24dp pads already applied), so maxWidth IS the agenda's content
        // width — the day count grows with it: 739dp → 3 (Show 5), 913 → 4 (Show 8), 1292 → 5 (M9).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val days = agendaDays(events, nowMs, zone, dayCount = agendaDayCount(maxWidth.value))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                days.forEach { day ->
                    DayColumn(day, nowMs, zone, is24, Modifier.weight(1f))
                }
            }
        }
    }
}
```

- [ ] **Step 3: Run the final full gate**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 test failures. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/panels/CalendarPanel.kt
git commit -m "$(cat <<'EOF'
feat(calendar): adaptive agenda day-count by panel width

CalendarPanel wraps its content in BoxWithConstraints (inside PanelSurface, so
maxWidth is the content width) and asks AdaptiveGeometry.agendaDayCount how many day
columns fit: 3 on the Show 5 (unchanged), 4 on the Show 8, 5 on the Tab M9. Pairs
with the 5-day fetch window from the earlier commit.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

- [ ] **Step 5: Flash both Echos** (Tab M9 is offline — deferred; note it, do not block on it). Build once (Step 3 already produced the APK).

```bash
# Echo Show 5 (10.75.1.98) — connect over Wi-Fi ADB, then install.
adb connect 10.75.1.98:5555
adb -s 10.75.1.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10.75.1.98:5555 shell am start -n com.rar.echodash/.MainActivity

# Echo Show 8 (USB serial G6G16D10041406ME).
adb -s G6G16D10041406ME install -r app/build/outputs/apk/debug/app-debug.apk
adb -s G6G16D10041406ME shell am start -n com.rar.echodash/.MainActivity
```

Relaunch is normally automatic (the app is the kiosk HOME app), but the explicit `am start` above forces the freshly-installed build forward. `MainActivity`'s FQN is `com.rar.echodash.MainActivity` (manifest `.MainActivity`, package `com.rar.echodash`) — verified.

**HARD RULES for device work:** logcat by **dump only** (`adb -s <dev> logcat -d | grep ...`), never streaming. **NEVER run `dumpsys media.audio_flinger`** — it crashes the Echo audio HAL. The Echo's `screencap` returns a stale window-background buffer for the Compose layer, so verify the Echo by eye (or diff against the Show 8's working screencap).

- [ ] **Step 6: Verify on the Echo Show 5 — the golden rule (report results):**

  Look at home, the now-playing takeover, the calendar panel, and the solar card. **Nothing may look different from the previous build:** notification stack width/height unchanged, next-event card unchanged, EV/solar cards 248dp with the compact 12sp stats row, takeover art 360dp with the same metadata width, agenda still 3 columns. Any visible change on the Show 5 is a golden-rule violation — stop and investigate.

- [ ] **Step 7: Verify on the Echo Show 8 — the growth column (report results):**

  Confirm the spec's Show-8 golden values by eye: notification rows noticeably wider (**~582dp**) with more rows before clipping; next-event card wider (**~594dp**); takeover art visibly larger (**~442dp**) with a wider metadata column; calendar shows **4** day columns; EV/solar cards **300dp** wide with the **relaxed 14sp** stats row (battery + home + grid, roomier icons/gaps). Then check for regressions: `adb -s G6G16D10041406ME logcat -d | grep -iE "AndroidRuntime|FATAL|echodash"` should show no new crashes.

- [ ] **Step 8: Note Tab M9 as deferred (report):** it is off USB. When it returns, measure its real dp canvas (`adb -s <serial> shell wm size` / `wm density`), then expect the M9 golden column: **5** agenda days, **320dp** cards, and the capped **700dp** notification / **640dp** next-event widths. The formulas are indifferent to the exact canvas; only the expected-value column moves.

---

## Notes for the implementer

- **Task ordering matters for "renders identically after each task":** Tasks 1–2 add unused pure code; Task 3 makes HomeView's internals flexible but keeps 248dp defaults; Tasks 4–6 wire the measured widths one surface at a time. After every task the gate is green and (Tasks 3–5) the Show 5 renders identically — the actual Show 8/M9 growth only becomes visible once its surface is wired.
- **Why-comments, not narration:** every comment added above states a physical constraint (why 32dp clearance, why integer tick math, why config-presence). Keep them; do not add "what" narration.
- **`floorDp` uses the FLOORED art Int in `takeoverLayout`** — this is load-bearing (Show 8 metadata is 391, not 390). Do not "simplify" it to use the Float art.
- **`GaugeBar`'s tick uses `maxWidth.value.toInt()`** while the shimmer uses the Float `maxWidth.value` — the tick's original math was integer division, and matching it keeps the Show 5 bit-identical. Do not unify them to Float.
