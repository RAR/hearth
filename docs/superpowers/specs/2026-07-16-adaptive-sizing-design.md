# Per-Screen Adaptive Sizing — Design Spec

Date: 2026-07-16. Backlog item #12 (queued 2026-07-15 after the solar stats row overflowed its fixed card and got a 12sp stopgap squeeze @ `2beecaf`).

## Overview

The dashboard was designed on the Echo Show 5 and its layout constants are back-derived from that panel's 787×394dp canvas — the HomeView comments literally do arithmetic against 787 and 394. The fleet now spans three very different screens:

| Device | Pixels | Density | dp canvas |
|---|---|---|---|
| Echo Show 5 | 960×480 | 195 | **787×394** (measured) |
| Echo Show 8 | 1280×800 | 213 | **961×601** (measured) |
| Lenovo Tab M9 | 1340×800 | ~160 (unverified) | **~1340×800** (estimate) |

User-approved direction (2026-07-16): **more content, same sizes** — element sizes (text, icons, buttons, cards) stay fixed dp everywhere; the growable regions use the extra room. Explicitly not uniform scale-up, and not percentage-based sizing: since the fixed-size neighbors (paddings, card column, clock block) don't grow with the screen, free space is `screen − fixed reserves`, a subtraction. Percentages would keep re-reserving space for overhead that didn't grow (e.g. 51% of the Show 8's height gives the notification stack 305dp when 407dp is genuinely free). The one true proportional split is takeover art vs. metadata — two growable regions dividing the width.

User-approved growth scope (all four): home overlay caps, now-playing takeover, agenda day count, EV/solar card width.

## Global Constraints

- compileSdk/targetSdk 34, minSdk 28 — unchanged. No new dependencies (rules out the material3 `windowSizeClass` artifact; its phone-centric 600/840dp buckets fit this fleet badly anyway).
- Plain-JVM JUnit4 tests only; gate `./gradlew :app:testDebugUnitTest :app:assembleDebug` before every commit.
- **Golden rule: at 787×394 every formula reproduces today's values exactly (pinned by test). The Show 5's rendering does not change.**
- No new config knobs — everything derives from measured constraints. No `LocalConfiguration`; sizes come from `BoxWithConstraints` at the consuming site so they reflect actual local space.

## Architecture

One new pure-Kotlin module, `app/src/main/java/com/rar/echodash/ui/model/AdaptiveGeometry.kt` (no Android/Compose imports, plain-JVM testable like its `ui/model` siblings), plus `BoxWithConstraints` wiring at four consuming sites. Functions take Float dp in, return whole-dp Int out (`floor` then clamp); composables convert with `.dp`.

```kotlin
data class HomeOverlayCaps(
    val notifMaxWidthDp: Int,
    val notifMaxHeightDp: Int,
    val nextEventMaxWidthDp: Int,
)

data class TakeoverLayout(
    val artSizeDp: Int,
    val metaMaxWidthDp: Int,
)

/** EV/solar card width tier: <900 → 248 (today's), <1200 → 300, else 320. */
fun homeCardWidthDp(screenWidthDp: Float): Int

/** True below 300dp cards: solar stats row keeps the 12sp/14dp/3dp squeeze from 2beecaf. */
fun solarStatsCompact(cardWidthDp: Int): Boolean

fun homeOverlayCaps(screenWidthDp: Float, screenHeightDp: Float, reserveCardColumn: Boolean): HomeOverlayCaps

fun takeoverLayout(screenWidthDp: Float, screenHeightDp: Float): TakeoverLayout

/** Agenda columns from panel CONTENT width (inside PanelSurface's 24dp pads): floor(w/228), clamped 3..5. */
fun agendaDayCount(panelContentWidthDp: Float): Int
```

### Formulas and named constants

Constants are named for the physical thing they reserve, each with its derivation in a comment. Values chosen so the Show 5 column of the golden table is exact.

| Output | Formula | Constants |
|---|---|---|
| `notifMaxWidthDp` | `w − EDGE_PADS − reserve`, min 300, cap 700 | `EDGE_PADS = 56` (28dp start + end); `reserve = homeCardWidthDp(w) + CARD_GAP` when `reserveCardColumn`, else 0; `CARD_GAP = 23` |
| `notifMaxHeightDp` | `h − TOP_ROW − CLOCK_BLOCK_H − NOTIF_CLOCK_GAP`, min 120 | `TOP_ROW = 70` (pills row + its pad; the stack's top offset today), `CLOCK_BLOCK_H = 110` (clock + date + bottom pad), `NOTIF_CLOCK_GAP = 14` |
| `nextEventMaxWidthDp` | `w − END_PAD − CLOCK_BLOCK_W − CLOCK_CLEAR`, min 240, cap 640 | `END_PAD = 28`, `CLOCK_BLOCK_W = 230` (worst-case date line), `CLOCK_CLEAR = 109` (reproduces today's 420) |
| `artSizeDp` | `min(h − ART_VMARGINS, w × ART_FRACTION)`, min 200 | `ART_VMARGINS = 34` (today's 2×17 around the 360 card), `ART_FRACTION = 0.46` (just above 360/787 so the Show 5 stays height-limited; the art-vs-metadata width split) |
| `metaMaxWidthDp` | `w − artSizeDp − META_MARGINS`, min 240 | `META_MARGINS = 128` = 48 start pad + 48 art end pad + 32 art↔meta clearance (exactly today's `padding(end = 440)` = 360 + 48 + 32) |
| `agendaDayCount` | `floor(contentW / AGENDA_COL_TARGET)` clamped 3..5 | `AGENDA_COL_TARGET = 228` — keeps per-column width in the 219–249dp band around the Show 5's current 238dp |
| `homeCardWidthDp` | tier table `<900 → 248`, `<1200 → 300`, `≥1200 → 320` | discrete because cards are fixed-size elements by design |

### Golden value table (pinned verbatim in `AdaptiveGeometryTest`)

| Input | notifW | notifH | nextEvent | art | meta | cardW | agenda (contentW) |
|---|---|---|---|---|---|---|---|
| Show 5: 787×394, reserve | **460** | **200** | **420** | **360** | **299** | **248** | 739 → **3** |
| Show 8: 961×601, reserve | 582 | 407 | 594 | 442 | 391 | 300 | 913 → 4 |
| Tab M9: 1340×800, reserve | 700 (cap) | 606 | 640 (cap) | 616 | 596 | 320 | 1292 → 5 |
| Show 5, no cards configured | 700 (731 capped) | 200 | 420 | — | — | — | — |
| Tiny 500×300, reserve | 300 (min) | 120 (min) | 240 (min) | 230 | 240 (min) | 248 | 452 → 3 (clamp) |

The Show 5 row is the golden rule made concrete: those are today's shipped values (299 is the current *effective* metadata width: `787 − 48 start − 440 end`). The caps (700/640) are readability limits for text rows, deliberately absolute. The tiny row only documents floor behavior — no such device exists in the fleet.

## Wiring (four sites)

**`HomeView.kt`** — root `Box` becomes `BoxWithConstraints` (all existing modifiers, gestures, children preserved). Once per composition:

- `val cardWidth = homeCardWidthDp(maxWidth.value)`
- `val caps = homeOverlayCaps(maxWidth.value, maxHeight.value, reserveCardColumn)`
- Notification stack: `widthIn(max = 460.dp)` / `heightIn(max = 200.dp)` → the caps values. The hand-written 787-math comment is replaced by a pointer to `AdaptiveGeometry`.
- Next-event card: `widthIn(max = 420.dp)` → `caps.nextEventMaxWidthDp.dp`, comment likewise.
- New parameter `reserveCardColumn: Boolean`, passed from `DashboardShell`: `config.entities.evs.isNotEmpty() || config.entities.solar.ids().isNotEmpty()` — CONFIG presence, not current card visibility, so a card fading in/out never makes the notification stack jump width. (Exact solar accessor per `DashConfig.kt:232`; verify `ids()` visibility at plan time.)

**`EvCardView` / `SolarCardView` / `GaugeBar`** (all in `HomeView.kt`):

- Both cards gain a `cardWidth: Dp` parameter replacing the hard `width(248.dp)`.
- Solar stats row: when `solarStatsCompact(cardWidth)` keep today's compact 12sp text / 14dp icons / 3dp gaps; otherwise relax to the pre-stopgap 14sp / 16dp / 4dp (the "·" separator stays removed). Battery/home/grid segments identical in both variants.
- `GaugeBar` drops its hard-coded 216: `.fillMaxWidth().size(width = 216.dp, height = 8.dp)` → `.fillMaxWidth().height(8.dp)`, and the body wraps in `BoxWithConstraints` so the shimmer sweep (`fraction × (trackW + 24) − 24`) and the limit tick (`trackW × tickPct / 100 − 1`, **integer division** — today's tick math truncates, and keeping Int semantics is what makes 216dp bit-identical) use the measured track width. Behavior at 216dp is bit-identical to today.

**`NowPlayingHome.kt`** — root `Box` becomes `BoxWithConstraints`; `val layout = takeoverLayout(maxWidth.value, maxHeight.value)`.

- Art card: `.size(360.dp)` → `.size(layout.artSizeDp.dp)`; keeps `padding(end = 48.dp)`.
- Metadata column: `padding(start = 48.dp, end = 440.dp)` + `widthIn(max = 460.dp)` → `padding(start = 48.dp)` + `widthIn(max = layout.metaMaxWidthDp.dp)`. Geometry guarantees 32dp clearance to the art card at any width. Browse button, transport, volume unchanged.

**`CalendarPanel.kt` + `CalendarModel.kt` + `EntityHub.kt`**:

- `CalendarPanel` wraps its content in `BoxWithConstraints` (inside `PanelSurface`, so `maxWidth` IS content width) and passes `agendaDayCount(maxWidth.value)` down.
- `agendaDays(events, nowMs, zone)` gains `dayCount: Int = 3` (default keeps existing callers and tests untouched); the `(0 until 3)` loop and its "exactly 3 day columns" doc update accordingly.
- `EntityHub.kt:165` fetch window: `now.plusDays(3)` → `now.plusDays(5)` unconditionally — the agenda can now display 5 days, and the home next-event card gets a longer horizon for free. Update any test pinning the 3-day window.

## Testing

- `AdaptiveGeometryTest` (plain JVM): every row of the golden table as exact Int assertions, one test per surface plus the no-cards and floor rows; tier boundary tests for `homeCardWidthDp` (899/900/1199/1200) and `solarStatsCompact` (248 → true, 300 → false).
- `CalendarModelTest`: `agendaDays` with `dayCount = 5` produces 5 consecutive days with correct membership; default-parameter call still produces today's 3-day result (existing tests unmodified).
- Composables stay thin (measure → pure fn → modifier), consistent with the codebase's untested-composable convention.

## Non-goals

- Typography or element scaling (user chose "same sizes"), and no percentage-based sizing (see Overview).
- MusicBrowser cell sizes — LazyRows already show more cells on wider screens for free.
- MediaPanel classic layout, VoiceOverlay (edge bands are screen-relative), DoorbellPopup, NightClockOverlay, IconRail, SetupScreen — verified content-sized or screen-relative; unchanged.
- Takeover marquee/centering/scrub (backlog #11) and any new web-config surface.

## Verification

1. Gate green; flash both Echos.
2. Show 5: **no visible change anywhere** (home, takeover, calendar, solar card) — the golden rule, checked by eye.
3. Show 8: notification rows noticeably wider (582dp) and more rows before clipping; next-event card wider; takeover art visibly larger (442dp) with a wider metadata column; calendar shows 4 day columns; EV/solar cards 300dp with the relaxed 14sp stats row.
4. Tab M9 (when back on USB): verify real dp canvas via `wm size`/`wm density`, then expect the M9 golden-table column (5 agenda days, 320dp cards, capped notification/next-event widths).

## Open checks

- Tab M9 dp canvas is an estimate until measured; the formulas are indifferent, only the expected-values column moves.
- ~~`SolarConfig.ids()` visibility from `DashboardShell`~~ — RESOLVED at plan time: public (no modifier) on both `SolarConfig` (DashConfig.kt:33) and `EvConfig` (:47), already called cross-file.
- ~~Whether any existing test pins the 3-day calendar fetch window~~ — RESOLVED at plan time: none does; the two `agendaDays` tests use the default `dayCount` and are untouched.
