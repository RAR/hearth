# Shuffle/Repeat Toggles + Up-Next Line — Design

**Date:** 2026-07-19
**Status:** Approved by user (design conversation 2026-07-19)

## Goal

Two takeover/queue polish features for the SendSpin (Music Assistant) source:

1. **Shuffle + repeat toggles** — in the MusicBrowser queue pane header AND on the
   now-playing takeover. The app already advertises `repeat`/`shuffle` in its SendSpin
   `supported_commands` and already parses both states; it just has no UI for them.
2. **Up-next line on the takeover** — a single dimmed "Up next: Title — Artist" line so
   the wall panel answers "what's coming?" without leaving the home screen.

Both degrade to *absent* for the companion `media_player` source (no queue concept there;
companion shuffle/repeat is explicitly out of scope).

## Existing plumbing (verified 2026-07-19)

- `SendSpin.kt` (vendored engine) already has `setRepeatMode(mode: String)` (line ~737)
  and `setShuffle(enabled: Boolean)` (line ~747), which route through
  `SendSpinProtocolHandler.sendCommand()`. Commands not present in the server-advertised
  `supported_commands` are dropped with a warning (handler line ~450).
- `SendSpin.controllerState: StateFlow<ControllerState?>` push-updates group-level
  `repeat` ("off"/"one"/"all"), `shuffle` (Boolean), `supportedCommands`, volume, mute
  from `server/state` frames, with delta merge (`ControllerState.mergedWith`).
- `NowPlayingState` (media/NowPlayingStore.kt) already has `sendspin: Boolean` and
  `muted` (published by `SendspinEndpoint` via `nowPlaying.onSendspin(...)`).
  **It has hand-written `equals`/`hashCode`** (ByteArray art field) — StateFlow dedups
  on it.
- `MaLibrary` (sendspin/MaLibrary.kt) is app-scoped and already passed to
  `DashboardShell` as `library: MaLibrary?`. `MaLibrary.queue()` returns
  `MaQueueState(items, currentIndex, shuffleEnabled, repeatMode)`; `MaQueueItem` has
  `isCurrentItem`.
- `MusicBrowser.kt` queue pane polls `library.queue()` every 5 s while visible;
  mutations bump `queueVersion` for an immediate refetch. `QueuePane` header row:
  "Queue" label + "Clear" chip.
- App.kt wires transport callbacks with the
  `if (nowPlayingState.sendspin) deps.sendspin.transportX() else <companion service>`
  pattern (~line 876).

## Command path decision

**Primary: SendSpin socket.** `SendspinEndpoint` gains `transportSetRepeat(mode: String)`
and `transportSetShuffle(enabled: Boolean)` wrappers (same shape as `transportNext`)
calling the engine's `setRepeatMode`/`setShuffle`. No new protocol code, no MA token
needed, display state arrives by push.

**Documented fallback (do NOT build now):** if the implementation-time live check shows
Music Assistant does not advertise `repeat_off/repeat_one/repeat_all/shuffle/unshuffle`
in `supported_commands` over SendSpin, swap ONLY the command dispatch to two new
`MaCommandClient` commands — `player_queues/shuffle` `{queue_id, shuffle_enabled}` and
`player_queues/repeat` `{queue_id, repeat_mode}` — exposed as `MaLibrary.setShuffle`/
`setRepeat` via the existing `withQueue` guard. All UI, state fields, and visibility
gates are identical under either path.

## UI

### Takeover (NowPlayingHome.kt) — SendSpin source only

- **Toggles:** below the volume row, inside the existing centered `width(224.dp)` group:
  a `Row` of two `NpTransportButton`-style 40 dp circles (icon 20 dp), spacing 16 dp,
  centered. Icons `Icons.Outlined.Shuffle` and `Icons.Outlined.Repeat`
  (`Icons.Outlined.RepeatOne` when mode == "one").
  - Off state: icon tint `Color.White.copy(alpha = 0.45f)`, chip background `#2A2F3C`
    (the existing transport chip color).
  - On state: icon tint `#4FC3F7` (the voice/media accent), same chip background.
  - Repeat tap cycles off → all → one → off (`nextRepeatMode`).
  - Visible only when `state.sendspin` AND the corresponding state field is non-null
    AND the command set allows it (see Data flow). Each toggle gates independently.
- **Up-next line:** directly under the progress row (or under the artist/album line when
  no duration → no progress row): 14 sp, `Color.White.copy(alpha = 0.55f)`, single
  line, `TextOverflow.Ellipsis` (no marquee), text `Up next: <Title> — <Artist>`
  (artist part omitted when null). Shown only when `state.sendspin` and a next item is
  known. Tapping it calls `onUpNextTap()` → DashboardShell switches to the MEDIA view
  and opens the queue overlay.

### Queue pane (MusicBrowser.kt)

Two icon chips in the `QueuePane` header row between the "Queue" label and "Clear",
28 dp circles (icon 16 dp), same glyph/lit rules as the takeover. Displayed state from
the pane's `MaQueueState` (`shuffleEnabled`, `repeatMode`). Tap → same App-level
callbacks → bump `queueVersion` (immediate refetch, matching jump/clear; the 5 s poll
self-corrects any race with the server applying the command). Chips render whenever the
queue is loaded — the queue pane only exists for the MA/SendSpin path, so no source gate
is needed there.

## Data flow

### NowPlayingState (media/NowPlayingStore.kt)

New fields, both defaulting to null = unknown:

```kotlin
/** Group repeat mode from SendSpin controller state: "off"|"one"|"all"; null = unknown. */
val repeatMode: String? = null,
/** Group shuffle from SendSpin controller state; null = unknown. */
val shuffle: Boolean? = null,
/** Repeat/shuffle commands the server advertises (gates toggle visibility). */
val canRepeat: Boolean = false,
val canShuffle: Boolean = false,
```

⚠️ **The hand-written `equals` and `hashCode` MUST include every new field** — missing
one means StateFlow dedup silently swallows updates (the exact bug class the `muted`
field comment warns about).

Up-next deliberately does NOT extend NowPlayingState: it comes from the MA API poll
owned by DashboardShell, not from SendspinEndpoint, and each producer keeps one source
of truth. DashboardShell holds the queue poll result locally and passes
`upNext: MaQueueItem?` straight to HomeView → NowPlayingHome as a parameter.

### SendspinEndpoint

- Collect `engine.controllerState` in the endpoint's scope on start; on each update map
  into the publish path (same hop-to-mainScope as `onMutedChanged`):
  `repeatMode = cs.repeat`, `shuffle = cs.shuffle`.
  Gates: `canRepeat` = ANY `repeat_*` command advertised (MA may advertise a subset;
  cycling still sends unadvertised modes into the engine's drop guard harmlessly, and
  the pushed state keeps the UI truthful); `canShuffle` = `"shuffle"` or `"unshuffle"`
  advertised. A null `supportedCommands` (server never sent a set) → both true
  (optimistic, matching the engine-side guard, which also passes unknown sets through).
- Reset all four to defaults on deactivate/stop, alongside the existing muted reset
  (both `onSendspin(false, ...)` call sites).
- `transportSetRepeat(mode: String)` / `transportSetShuffle(enabled: Boolean)` wrappers
  delegating to the engine instance (no-ops when the engine is absent), same idiom as
  `transportNext`.

### App.kt wiring

Two new DashboardShell callbacks, sendspin-branch only (companion branch = no-op):

```kotlin
onMediaCycleRepeat = {
    if (nowPlayingState.sendspin)
        deps.sendspin.transportSetRepeat(nextRepeatMode(nowPlayingState.repeatMode))
},
onMediaToggleShuffle = {
    if (nowPlayingState.sendspin)
        deps.sendspin.transportSetShuffle(!(nowPlayingState.shuffle ?: false))
},
```

`nextRepeatMode(cur: String?): String` — pure, in the model layer:
"off"/null → "all", "all" → "one", "one" → "off".

### DashboardShell up-next poll

`LaunchedEffect(takeoverActive, np.sendspin, np.title, maConnected)`:
while takeover active AND `np.sendspin` AND `library != null` AND MA state Connected:
fetch `library.queue()` immediately, then every 10 s. Keying on `np.title` restarts the
poll on track advance so the line refreshes right away. Derive
`upNext = upNextOf(queueState)`; on any fetch failure set null (line hides, no error
UI — the takeover is glanceable, not a diagnostics surface). Clear to null when the
effect's gate goes false (takeover dismissed / source switches / MA drops).

`upNextOf(q: MaQueueState): MaQueueItem?` — pure, in the model layer:
item after the one flagged `isCurrentItem`; null when no flag or current is last.
(Deliberate 200-item page limitation: with `currentIndex` ≥ page end the line hides —
acceptable, matches the queue pane's existing page behavior.)

### Open-queue jump

`MusicBrowser` gains `openQueueSignal: Int = 0`; a `LaunchedEffect(openQueueSignal)`
runs `if (openQueueSignal > 0) queueVisible = true` — 0 is the never-requested value, so
a fresh composition with signal 0 stays on the browser. DashboardShell holds a counter
(starting at 0), increments it AND switches to the MEDIA view when the up-next line is
tapped. MusicBrowser state is `remember{}`-scoped, so entering MEDIA with a bumped
signal opens the queue on first composition; an unchanged nonzero value re-runs only if
MusicBrowser recomposes from scratch (leaving and re-entering MEDIA), where re-opening
the queue is acceptable.

## Degradation / error handling

| Condition | Behavior |
|---|---|
| Companion source | No toggles, no up-next (existing takeover unchanged) |
| SendSpin source, MA never sent controller state | Toggles hidden (`repeatMode`/`shuffle` null) |
| `supported_commands` lacks repeat/shuffle | That toggle hidden (`canRepeat`/`canShuffle` false) |
| No MA token / MA socket down | Up-next hidden; toggles unaffected (socket path) |
| Queue fetch fails / empty / current-is-last | Up-next hidden, silent |
| Toggle tapped, server ignores | Engine drop-log; pushed state never flips → UI stays truthful |

## Out of scope (deliberate)

- Companion-entity shuffle/repeat (`media_player.shuffle_set`/`repeat_set`) — YAGNI,
  MA is the music path.
- MA-API command fallback — documented above, built only if the live check fails.
- Up-next thumbnails / multi-item list — user chose the single line.
- Queue event subscription (push) — polling matches the established MusicBrowser idiom.

## Testing (plain-JVM JUnit4, no new deps)

- `nextRepeatMode`: null→all, off→all, all→one, one→off, garbage→all.
- `upNextOf`: current mid-list, current last → null, no `isCurrentItem` → null,
  empty list → null.
- `NowPlayingState`: equality/hashCode differ when only `repeatMode` (and each new
  field) differs — pins the hand-written equals extension.
- `SendspinEndpoint` (existing fake-engine seams): controller-state update publishes
  repeat/shuffle/gates; deactivate resets them; `transportSetRepeat/Shuffle` reach the
  engine; absent engine → no-op.
- Visibility gate pins: `canRepeat`/`canShuffle` derivation from supported-command sets
  (null set → true; partial sets).

## Live-verify checklist (implementation end)

1. Play MA music on a device → confirm `supported_commands` includes repeat/shuffle
   (logcat; if absent, build the documented MA-API fallback).
2. Toggle shuffle on takeover → MA UI reflects it; MA-side toggle → takeover follows.
3. Repeat cycle off→all→one→off; glyph switches to RepeatOne on "one".
4. Up-next line correct; advances on track change; tap → MEDIA view, queue open.
5. Queue pane chips mirror takeover state; queue order visibly reshuffles on toggle.
6. Companion source (desk Echo with companion entity): takeover unchanged.
