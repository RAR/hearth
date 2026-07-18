# Timer Takeover Panel

**Date:** 2026-07-17
**Status:** Approved (AskUserQuestion: any-running-timer trigger / all devices / voice names + tap-to-rename)

## Goal

Kitchen-first big-countdown view: whenever ≥1 Assist timer is running, a full-screen takeover
shows every running timer with its name and a live countdown. Dismissable back to the
dashboard; re-appears only for NEW timers. Names come from the voice command ("set a pasta
timer…"); tapping a name renames it locally.

## Existing plumbing (unchanged)

`SatelliteSession` already tracks timers (`TimerRec`: id, name, anchorRemainingSec, anchorMs,
active) and publishes `TimersUiState` via `SatelliteAction.Timers` → `AppDeps.timersUi`
StateFlow → today's `TimerChips` + `TimerFinishedOverlay` (both stay). `TimersUiState.timers`
is the source of truth; the 500 ms tick re-emits while timers run. Timer cancel/pause remain
voice actions (Wyoming timer events are HA→satellite one-way) — no on-screen cancel in v1
(noted future work: a hearth-integration action could add it).

## New pure model — `ui/model/TimerTakeover.kt` (plain-JVM)

```kotlin
data class TakeoverTimer(val id: String, val label: String, val remainingSec: Long, val active: Boolean)

class TimerTakeoverModel {
    // renames: id -> local display label (transient; timers are ephemeral)
    // dismissedIds: ids present when the user tapped ✕
    fun update(timers: List<TimerInfo>): List<TakeoverTimer>  // maps name/rename/fallback label
    fun dismiss()                                             // records current ids
    fun rename(id: String, label: String)
    val visible: Boolean                                      // any timer id NOT in dismissedIds
}
```
Rules:
- Label precedence: local rename > voice `name` (non-blank) > formatted original duration
  ("10 min timer"). (`TimerInfo` is whatever `TimersUiState` carries per timer — reuse it.)
- `visible` = timers.isNotEmpty() && any id ∉ dismissedIds. A NEW timer id therefore re-shows
  the takeover after a dismiss. When the timer list becomes empty, dismissedIds and renames
  clear (fresh session next time).
- Stale rename/dismiss entries for ids no longer present are pruned on `update`.

## UI — `ui/TimersTakeoverView.kt`

- Full-screen dark surface (same family as NowPlaying takeover / night styling), drawn in the
  root Box in App.kt AFTER `DashboardShell` and BEFORE WakeGlow/VoiceOverlay/TimerChips/
  doorbell (voice pill and doorbell stay on top; chips are hidden while the takeover shows).
- 1 timer: name (~28sp) above a giant countdown (~120sp, `FontFeature tabular-nums` via
  `fontFeatureSettings`/`FontVariation` — whichever the codebase's Compose version supports;
  monospace digits so they don't wobble). 2–4 timers: 2-column grid of cards, countdown
  ~64sp. 5+: same grid, scrollable (unlikely).
- Paused timers (`active=false`) show their remaining time dimmed with a "paused" tag.
- ✕ top-right → `model.dismiss()` (returns to dashboard; chips visible again).
- Tap a timer's name → rename dialog: preset chips (Pasta, Eggs, Tea, Oven, Laundry) + free
  text `OutlinedTextField` + Save/Cancel. Presets are a constant list (not configurable, YAGNI).
- Countdown text derives from the existing chip formatter (extract/reuse `formatTimer`).

## App.kt wiring

- `val timerTakeover = remember { TimerTakeoverModel() }` in the composable scope (or a
  StateFlow-holder if recomposition-safety demands; implementer follows existing state
  patterns). Recompute on every `timersUi` emission.
- Visibility: `if (timerTakeoverVisible) TimersTakeoverView(...)`.
- Night mode: takeover-visible joins the existing "suppress night dim" condition (same list
  as voice-overlay phase != HIDDEN) and pokes the screen-wake/idle timer the same way.
- `TimerChips(...)` call becomes conditional: hidden while the takeover is visible.
- TimerFinishedOverlay unchanged (it already sits above everything and has its own dismiss).

## Tests (plain-JVM JUnit4, new `TimerTakeoverModelTest`)

1. Empty timers → not visible; adding one → visible with voice name as label.
2. Blank name → duration-based fallback label; rename overrides; rename pruned when the id
   disappears.
3. dismiss() hides; same timers stay hidden; a NEW id re-shows (old ids still marked).
4. All timers gone → dismissedIds+renames reset (next timer shows fresh).
5. Paused timer keeps remaining seconds and `active=false` passes through.
6. Formatter: 45s → "0:45", 605s → "10:05", 3661s → "1:01:01" (match TimerChips semantics).

## Verification

- Gate per commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Live (desk Echo): "okay nabu, set a pasta timer for 2 minutes" → takeover with "pasta" +
  countdown; second timer joins as grid; ✕ returns to dashboard; new timer re-takes-over;
  tap-rename works; timer finish → existing alarm overlay → after dismiss+expiry, takeover
  clears when no timers remain. Screencap-verify on Show 8 via timer events if convenient.
