# Notification Mini-Player Card — Design (sweep Batch F)

**Date:** 2026-07-19
**Status:** Approved scope (user: pinned row "will never dismiss — even if you stop or
switch speakers. might be fun to make it taller w/ larger art and add
stop/play/next/back buttons").

## Goal

Replace the pinned `NowPlayingRow` (feature 50) with a **mini-player card** in the home
notification area, and fix its lifecycle so it actually goes away:

1. **Card**: taller, 56dp album art, title + artist lines, and a transport row —
   back · play/pause · next · stop.
2. **Lifecycle fix**: the row never dismissed because SendSpin/MA reports `stopped` for
   BOTH pause and stop, so `NowPlayingState.active` stays true forever. New rules:
   - Visible while `active && !takeoverVisible && !dismissed && (playing || within grace)`.
   - **Grace**: once `playing` goes false, the card lingers `MINIPLAYER_GRACE_MS = 5 min`
     (measured from the pause/stop moment), then auto-hides. The takeover's own
     paused-timeout (default 60 s) hands off to the card, which then covers minutes
     61–300 of a pause; a stop/speaker-switch — indistinguishable from pause on the
     wire — now disappears after at most 5 min instead of never.
   - **Swipe-to-dismiss**: the card is swipe-left dismissable exactly like a
     notification row (fire-and-forget, local state — no server op, no failure path).
     `dismissed` resets when `active` goes false (next session gets a fresh card).
   - **Stop button**: ends playback for real (the existing stop transport), which drops
     `active` → card gone immediately, both flags reset.

## Model (ui/model/NotificationModel.kt — pure, JVM-tested)

```kotlin
const val MINIPLAYER_GRACE_MS = 5 * 60_000L

/**
 * Mini-player visibility. [pausedSinceMs] = device-clock moment playing last went false
 * (0 = never observed paused this session, treated as within grace only if playing).
 */
fun miniPlayerVisible(
    active: Boolean,
    playing: Boolean,
    takeoverVisible: Boolean,
    dismissed: Boolean,
    pausedSinceMs: Long,
    nowMs: Long,
): Boolean {
    if (!active || takeoverVisible || dismissed) return false
    if (playing) return true
    if (pausedSinceMs <= 0) return false // active-but-never-playing session: nothing to resume
    return nowMs - pausedSinceMs < MINIPLAYER_GRACE_MS
}
```

State feeding it (HomeView, which already owns the minute `now` ticker and receives
`nowPlaying`):
- `var miniDismissed by remember { mutableStateOf(false) }` — set by the swipe; reset by
  `LaunchedEffect(nowPlaying.active) { if (!nowPlaying.active) miniDismissed = false }`.
- `var pausedSinceMs by remember { mutableStateOf(0L) }` — `LaunchedEffect(nowPlaying.playing,
  nowPlaying.active)`: playing→false while active stamps `System.currentTimeMillis()`;
  playing→true or active→false resets to 0.
- The existing `showNowPlayingRow = nowPlaying.active && !takeoverVisible` is REPLACED by
  `miniPlayerVisible(...)` (the `now` minute-tick recomposition naturally re-evaluates the
  grace expiry; ±1 min precision on a 5-min window is fine).

## UI (NotificationArea.kt)

`NowPlayingRow` is REPLACED by `MiniPlayerCard`:

```kotlin
@Composable
fun MiniPlayerCard(
    label: String,            // nowPlayingRowLabel as today ("Title — Artist" / "Now playing")
    artist: String?,          // second line; null/blank = single-line label only
    artThumb: ImageBitmap?,   // 56dp, MusicNote fallback (existing pattern)
    playing: Boolean,
    onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onStop: () -> Unit,
    onTap: () -> Unit,        // body tap restores the takeover (unchanged)
    onDismiss: () -> Unit,    // swipe-left completion
)
```

- Chrome: same `RoundedCornerShape(14.dp)` on `Color.Black.copy(alpha = 0.35f)`.
- Layout: `Column(spacedBy 8, padding 12/10)`:
  - Row 1 (tap = `onTap`): 56dp art thumbnail (8dp clip, Crop / MusicNote fallback) +
    `Column`: title line (16sp, White, Medium, ellipsized — the TITLE ONLY now, since
    artist gets its own line: pass `nowPlaying.title ?: "Now playing"`) and artist line
    (13sp, White 0.6f, ellipsized, omitted when null/blank).
  - Row 2: four transport chips, 36dp circles / 18dp icons (a smaller `NpTransportButton`
    clone local to this file — `MiniTransportButton`, same #2A2F3C bg), centered,
    spacedBy 12: `SkipPrevious`, `Pause`/`PlayArrow` by [playing], `SkipNext`,
    `Stop` (`Icons.Outlined.Stop`).
- Swipe: the exact `NotificationRow` mechanics (Animatable offset, left-only, 0.30f
  threshold, animate-off then `onDismiss()`, snap back on cancel/below-threshold) wrapped
  around the whole card. Fire-and-forget (local dismiss) — NO suspend/await (unlike the
  queue rows; nothing can fail).
- The transport chips consume their own taps (they sit inside the swipe area; normal
  Compose click handling already wins over the drag detector for taps).

### Label change note

`nowPlayingRowLabel(title, artist)` stays for the single-line fallback case but the card
now renders title and artist as separate lines. Reuse: title line =
`nowPlaying.title?.takeIf { it.isNotBlank() } ?: "Now playing"`, artist line =
`nowPlaying.artist` — no new model fn needed; the existing `nowPlayingRowLabel` tests
stand (the fn keeps backing any future single-line use; it is NOT deleted).

## Threading (HomeView.kt / DashboardShell.kt)

- HomeView gains `onMediaStop: () -> Unit = {}` (DashboardShell already has it; forward
  like the other transport callbacks).
- Card wiring: `onPrev = onMediaPrev`, `onNext = onMediaNext`,
  `onPlayPause = { if (nowPlaying.playing) onMediaPause() else onMediaPlay() }`,
  `onStop = onMediaStop`, `onTap = onTakeoverRestore` (unchanged),
  `onDismiss = { miniDismissed = true }`.

## Clock cap

The card is taller: 12+56+8+36+10 ≈ 122dp + 8dp Column gap ⇒ the notification stack's
height-cap shrink becomes **130** (was 62), same `coerceAtLeast(60)` floor, same comment
discipline (derivation spelled out at the constant).

## Degradation

| Condition | Behavior |
|---|---|
| Stop/speaker-switch (wire "stopped") | Card lingers ≤5 min grace, then hides; swipe or Stop kills it instantly |
| Pause then resume within grace | Card stays; play/pause chip flips |
| Paused past takeover timeout (60s) but < 5 min | Card shows (rescue path preserved, now bounded) |
| Swipe while playing | Card hides for the session; returns on next session (`active` cycle) |
| Companion source | Identical logic (state fields are source-agnostic); transport routes to companion services as the takeover does |
| No artist | Single title line, no second line |
| Real notifications present | Card pinned above them; stack cap shrinks by 130 |

## Out of scope (deliberate)

- Volume on the card, progress bar on the card, seek.
- Distinguishing pause from stop on the SendSpin wire (impossible — MA sends "stopped"
  for both; the grace window is the design answer).
- Config knob for the grace window (constant; revisit only if 5 min feels wrong live).

## Testing (plain-JVM JUnit4)

- `miniPlayerVisible`: playing→true (each gate flag individually hides); paused within
  grace → true; paused at/after grace → false; pausedSinceMs=0 && !playing → false;
  dismissed → false regardless; !active → false regardless.
- Compose card + swipe + threading: untested by convention (compile-gated).

## Live-verify checklist

1. Music playing, tap takeover Home → card (art, two lines, 4 chips) instead of the old
   row; body tap restores takeover.
2. Play/pause chip toggles playback and its own glyph; back/next skip; Stop ends the
   session and the card disappears.
3. Pause playback → takeover self-dismisses at ~60s → card shows; wait past 5 min → card
   auto-hides.
4. Stop from MA/another room ("switch speakers") → card gone within 5 min without touching
   the panel.
5. Swipe the card left → gone; start a new session → card eligibility returns.
6. Notification stack under the card scrolls within its shrunken cap; clock untouched.
