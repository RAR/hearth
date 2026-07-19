# Takeover Manual Dismiss (Home Button + Now-Playing Row) — Design

**Date:** 2026-07-19
**Status:** Approved by user (design conversation 2026-07-19)

## Goal

Let the user dismiss the now-playing takeover from the takeover itself — returning to the
normal home dashboard (photos, cards, clock) — **without stopping playback**, and give them
a one-tap way back. User request: "a home button on the takeover ... dismiss it from time
to time but allow it to play."

Approved semantics:

- **Dismiss sticks for the whole listening session.** Track changes do NOT resurrect the
  takeover. It re-asserts only when the current session ends (`NowPlayingState.active`
  goes false) and a new one starts.
- **Re-entry surface = a pinned now-playing row** above the home notification stack
  (user's idea), shown whenever music is active but the takeover isn't visible. Tap
  restores the takeover.

## Existing plumbing (verified 2026-07-19)

- `App.kt:749-760`: `pausedTimedOut` (a `remember` flag set by a `LaunchedEffect` after
  `config.media.pausedDismissSeconds` of paused playback) already implements
  "hide the takeover, keep the session alive underneath";
  `val takeoverVisible = nowPlayingState.active && !pausedTimedOut`.
- `NowPlayingHome.kt:102-106`: browse chip (`NpTransportButton`, 48dp chip / 24dp icon,
  `Icons.AutoMirrored.Outlined.QueueMusic`) sits alone at `TopEnd`
  (`padding(top = 8.dp, end = 16.dp)`); the 48dp size clears the art card on the smallest
  canvas.
- `HomeView.kt:385-402`: notification stack anchored `TopStart`,
  `padding(start = 28.dp, top = 70.dp)`, inside
  `AnimatedVisibility(visible = notifications.isNotEmpty())`, capped by
  `caps.notifMaxWidthDp` / `caps.notifMaxHeightDp` + `clipToBounds()`.
- `NotificationArea.kt`: rows are `RoundedCornerShape(14.dp)` on
  `Color.Black.copy(alpha = 0.35f)`, 18sp white medium title, swipe-left to dismiss.
- `HomeView` already receives `nowPlaying: NowPlayingState` and `art: ArtBitmaps?`
  (`.sharp`/`.blurred` bitmaps, already decoded — no new decode work).
- Callback threading precedent: App.kt → DashboardShell → HomeView (→ NowPlayingHome),
  all defaulted params.

## Behavior / state (App.kt)

```kotlin
var manualDismissed by remember { mutableStateOf(false) }
// Session end clears the manual dismiss so the NEXT session takes over again.
LaunchedEffect(nowPlayingState.active) {
    if (!nowPlayingState.active) manualDismissed = false
}
val takeoverVisible = nowPlayingState.active && !pausedTimedOut && !manualDismissed
```

Two new callbacks threaded to HomeView (via DashboardShell, defaulted like the rest):

- `onTakeoverDismiss` → `manualDismissed = true` (wired to the takeover home button).
- `onTakeoverRestore` → `manualDismissed = false; pausedTimedOut = false` (wired to the
  now-playing row tap). Clearing `pausedTimedOut` too makes the row the way back from the
  **existing paused-timeout dismissal** — which today has no on-device re-entry at all.

Known nuance (accepted): restoring while paused re-shows the takeover and the paused-dismiss
`LaunchedEffect` does not re-run (its keys haven't changed), so the re-summoned takeover
stays up until the next play/pause transition re-arms the timer. The user explicitly asked
for it back; leaving it up is correct.

The visibility formula lives in a pure fn for pinning (see Testing):
`takeoverVisibleOf(active, pausedTimedOut, manualDismissed)` — App.kt calls it instead of
inlining the `&&` chain.

## UI

### Takeover home button (NowPlayingHome.kt)

New defaulted param `onHome: () -> Unit = {}`. The `TopEnd` box becomes a
`Row(horizontalArrangement = Arrangement.spacedBy(12.dp))` of two 48dp `NpTransportButton`s:

- Browse (existing `QueueMusic` glyph, unchanged behavior) — first, i.e. shifted left.
- **Home** (`Icons.Outlined.Home`) — outermost corner position (exit lives in the corner).

Same 48dp/24dp sizing so the art-card clearance comment at `NowPlayingHome.kt:98-101`
stays true (the row grows leftward along the top edge, which is empty).

### Now-playing row (NotificationArea.kt + HomeView.kt)

New public composable in `NotificationArea.kt`:

```kotlin
@Composable
fun NowPlayingRow(label: String, artThumb: Bitmap?, onTap: () -> Unit)
```

- Same visual language as `NotificationRow`: `RoundedCornerShape(14.dp)`,
  `Color.Black.copy(alpha = 0.35f)` background, `fillMaxWidth()`.
- Leading 34dp square thumbnail, `RoundedCornerShape(8.dp)` clip: `artThumb` (the
  already-decoded `art.sharp`) with `ContentScale.Crop`, else
  `Icons.Outlined.MusicNote` at `Color.White.copy(alpha = 0.5f)`.
- One line, 18sp, white, `FontWeight.Medium`, `maxLines = 1`, `TextOverflow.Ellipsis`.
- Whole row `clickable { onTap() }`. **No** swipe-dismiss, **no** timestamp, **no**
  severity accent bar.

HomeView placement: inside the existing notification `AnimatedVisibility` block, whose
gate widens to `notifications.isNotEmpty() || showNowPlayingRow`, wrap a
`Column(verticalArrangement = Arrangement.spacedBy(8.dp))` holding `NowPlayingRow` (when
`showNowPlayingRow`) above the existing `NotificationArea`. The width cap
(`caps.notifMaxWidthDp`) applies to the Column; the height cap + `clipToBounds` move to
the `NotificationArea` inside it so real notifications scroll while the pinned row never
clips. The row consumes 62dp above the stack (34dp thumb + 2×10dp padding + 8dp Column
gap); since `homeOverlayCaps` sizes `notifMaxHeightDp` so the stack ends exactly
`NOTIF_CLOCK_GAP` above the clock block, the stack's height cap shrinks by the same 62dp
while the row shows (floor 60dp) — otherwise a full stack would overlap the clock.

`showNowPlayingRow = nowPlaying.active && !takeoverVisible` — computed in HomeView from
params it already has. This intentionally covers BOTH dismissal paths (manual and
paused-timeout).

## Model layer (ui/model/NotificationModel.kt)

```kotlin
/** Label for the pinned now-playing row: "Title — Artist", artist omitted when null/blank;
 *  blank/null title falls back to "Now playing" (pre-metadata streams). */
fun nowPlayingRowLabel(title: String?, artist: String?): String
```

Same "—" join convention as the takeover's up-next line.

## Degradation

| Condition | Behavior |
|---|---|
| Companion source vs SendSpin | Identical — `active`/`title`/`artist`/art are source-agnostic |
| No art | MusicNote glyph thumbnail |
| Stream with no title yet | Row shows "Now playing" |
| Session ends while dismissed | Row disappears (`active` false); flag resets; next session takes over normally |
| Paused-timeout dismissal (existing) | Same row appears; tap restores (new capability) |
| Real notifications present | Row pinned above them, non-scrolling; notifications scroll under their own cap |

## Out of scope (deliberate)

- Config toggle for the row or button (always on).
- Showing the row on non-home views.
- Any change to `NowPlayingState.active` semantics or playback behavior.
- Auto-comeback timers or track-change resurrection (user chose session-scoped dismiss).

## Testing (plain-JVM JUnit4, no new deps)

- `takeoverVisibleOf`: all 8 combinations pinned (active false → always hidden; active
  true hidden by either flag; visible only with both flags false).
- `nowPlayingRowLabel`: title+artist join, title only, blank artist treated as absent,
  null/blank title → "Now playing" (with and without artist — artist without title still
  yields just "Now playing").
- Compose wiring (button, row rendering, flag threading) is UI: not unit-tested, per
  project convention; verified on-device.

## Live-verify checklist (implementation end)

1. Music playing → tap home button → dashboard returns, audio continues.
2. Track advances while dismissed → takeover stays away; row label updates.
3. Tap row → takeover returns with correct state.
4. Stop playback (or let queue end) while dismissed, start new music → takeover asserts.
5. Pause → wait pausedDismissSeconds → row appears → tap → takeover returns paused.
6. Companion source: same button/row behavior.
