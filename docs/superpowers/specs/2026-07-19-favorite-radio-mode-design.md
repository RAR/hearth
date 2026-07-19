# Favorite Current Song + Radio Mode — Design (SendSpin sweep, Batch A)

**Date:** 2026-07-19
**Status:** Approved scope (user chose "Takeover + queue pane" for the heart; sweep batches
pre-approved "just work through them"). API facts from `.superpowers/sdd/ma-api-recon.md`;
live server confirmed **Music Assistant 2.9.9** via `http://10.75.1.54:8095/info`.

## Goal

1. **Favorite (heart) control** for the currently playing song — a chip beside the
   shuffle/repeat toggles on the now-playing takeover AND a mini-chip in the MusicBrowser
   queue-pane header. Lit when the current track is already a library favorite; tap toggles
   (add / remove).
2. **Radio mode** — a third entry, "Start radio", in the library browser's existing
   long-press menu (currently "Play next" / "Add to queue"): plays the item with MA's
   dynamic-radio queue refill (`radio_mode: true` — native and non-deprecated on 2.9.9).

Both are MA-API features (the SendSpin socket has no favorite/radio concepts), so they gate
on the MA library socket being connected — unlike shuffle/repeat, which ride SendSpin.

## API surface (confirmed, recon §1 & §2)

- `players/add_currently_playing_to_favorites` `{player_id}` — server resolves the current
  queue item itself (even resolves radio stream titles to tracks). Raises
  `PlayerCommandFailed` when nothing is resolvable — surfaced to us as a failed Result.
- `music/favorites/remove_item` `{media_type, library_item_id}` — for un-favoriting.
- Favorite display state: `QueueItem.media_item.favorite` (top-level `favorite` bool on every
  MediaItem) — NOT on the queue-item wrapper. `media_item.item_id` + `media_item.media_type`
  are what remove_item needs.
- `player_queues/play_media` gains `radio_mode: true` alongside the existing
  `queue_id`/`media`/`option` args. Option omitted (= PLAY: replace queue + start).

## Data flow

### MaQueueItem (musicassistant/MaQueueItem.kt) — three new fields

```kotlin
val favorite: Boolean = false,      // media_item.favorite; false when media_item absent
val mediaItemId: String? = null,    // media_item.item_id (string-or-int on wire -> string)
val mediaType: String? = null,      // media_item.media_type ("track"|"radio"|...)
```

Parsed in `MaCommandClient.parseQueueState()`'s item loop from the nested `media_item`
object (which the parser already reads for the `uri` fallback). Defaults keep every
existing construction site compiling.

### MaCommandClient

- `playMedia(...)` gains `radioMode: Boolean = false`; when true adds `"radio_mode" to true`
  to the args map. Existing callers unchanged (defaulted).
- New `addCurrentToFavorites(playerId: String)` → `players/add_currently_playing_to_favorites`.
- New `removeFavorite(mediaType: String, libraryItemId: String)` → `music/favorites/remove_item`.

### MaLibrary (sendspin/MaLibrary.kt)

Following the existing `withClient` pattern (none of these need `withQueue`'s effective-queue
resolution — favorite takes the device's own player id, the server resolves the active queue):

```kotlin
suspend fun favoriteCurrentSong(): Result<Unit>          // withClient { addCurrentToFavorites(playerId) }
suspend fun unfavorite(mediaType: String, libraryItemId: String): Result<Unit>
suspend fun playRadio(uri: String, mediaType: String?): Result<Unit>  // withQueue playMedia(radioMode = true)
```

(`playerId` = the same device player id `MaLibrary` already holds for
`getEffectiveQueueId`; `playRadio` mirrors the existing `play()` which IS `withQueue`.)

### Current-track favorite state (takeover)

DashboardShell's existing 10s up-next queue poll (gated: takeover visible + sendspin + MA
connected) already fetches `MaQueueState`. Extend its extraction: alongside
`upNext = upNextOf(q)`, derive `currentItem = q.items.firstOrNull { it.isCurrentItem }` and
hold `favState: MaQueueItem?` state. New pure fn in `ui/model/QueueModel.kt`:

```kotlin
/** The queue's current item, or null. */
fun currentItemOf(q: MaQueueState): MaQueueItem? = q.items.firstOrNull { it.isCurrentItem }
```

Heart tap (takeover and queue pane both):
- If current item's `favorite` is false (or unknown) → `favoriteCurrentSong()`.
- If true AND `mediaItemId != null` → `unfavorite(mediaType ?: "track", mediaItemId)`.
- After either, bump the poll/refetch (DashboardShell: re-run the poll body immediately via
  a `favVersion` counter keyed into the LaunchedEffect; queue pane: bump the existing
  `queueVersion`). Optimistic UI: flip the local lit state immediately; the next fetch
  corrects if the server disagreed. Failures: log-only, state self-corrects on next poll
  (glanceable surface, no error UI — same policy as up-next).

## UI

### Takeover (NowPlayingHome.kt)

- The toggle row (currently shuffle + repeat) gains a heart as its FIRST chip: same
  `NpToggleButton` 40dp/20dp chrome, `Icons.Outlined.FavoriteBorder` when unlit /
  `Icons.Filled.Favorite` when lit, lit tint the same `#4FC3F7` accent.
- New params: `favorite: Boolean? = null` (null = unknown → render unlit),
  `showFavorite: Boolean = false`, `onToggleFavorite: () -> Unit = {}`.
- Row visibility becomes `showFavorite || showShuffle || showRepeat`.
- `showFavorite` is computed in DashboardShell: `nowPlaying.sendspin && maConnected`
  (companion source: hidden — MA can't resolve a companion player's current item).

### Queue pane (MusicBrowser.kt)

- Header chip row (shuffle/repeat `QueueToggleChip`s) gains a heart mini-chip FIRST (28dp/
  16dp), same glyph/lit rules, lit from `currentItemOf(queueState)?.favorite == true`.
  Tap → same toggle logic via new `onToggleFavorite: () -> Unit = {}` pass-through param
  (App-level callback like `onMediaSetRepeat`), then `queueVersion++`.
- Chip renders whenever the queue is loaded (the pane already implies MA connected).

### Browser long-press menu (MusicBrowser.kt)

`EnqueueMenu` gains a third item, **"Start radio"**, below "Play next" / "Add to queue",
shown for items whose `mediaType` seeds radio sensibly: track, artist, album, playlist
(NOT radio stations — MA radio-mode seeds from real media items). Tap →
`playItem(item, START_RADIO)` — extend the existing play plumbing with an enum value or a
parallel callback (implementer's choice; plan decides) that lands on
`MaLibrary.playRadio(item.uri, item.mediaType)`.

## Degradation

| Condition | Behavior |
|---|---|
| Companion source | No heart anywhere (takeover gate false; queue pane is MA-only already) |
| MA socket down | Heart hidden on takeover; queue pane already shows its error state |
| Nothing resolvable to favorite (PlayerCommandFailed) | Result.failure → log only; lit state unchanged |
| Current item has no media_item (favorite unknown) | Heart renders unlit; tap = add (harmless) |
| Lit heart but mediaItemId null | Tap falls back to add (idempotent) rather than remove |
| Radio-mode start on a radio station | Menu item not offered |
| MA 2.10+ future upgrade | `radio_mode` still works (deprecated-but-translated); note in code comment |

## Out of scope (deliberate)

- Favorite hearts on browser shelf cells / search rows (user chose takeover + queue pane).
- Un-favorite via long-press anywhere.
- Displaying favorite state via SendSpin metadata (protocol doesn't carry it).
- `radio_playlist://` URI scheme (2.10-only; server is 2.9.9).

## Testing (plain-JVM JUnit4, no new deps)

- `parseQueueState` pins: item with `media_item{favorite:true, item_id:123, media_type:"track"}`
  → favorite/mediaItemId("123")/mediaType parsed; item without media_item → defaults.
- `playMedia` arg-building pin (existing fake-transport test seam): radioMode=true adds
  `radio_mode:true`; default omits it entirely.
- `currentItemOf`: current mid-list, no current → null, empty → null.
- Toggle-decision pure fn if the plan extracts one (favorite==true && id!=null → remove,
  else add) — pin both branches.

## Live-verify checklist (implementation end)

1. Play MA music → heart appears on takeover beside shuffle/repeat; lit state matches MA's
   favorites for the current track.
2. Tap unlit heart → MA favorites gains the track (check MA UI); heart lights ≤10s (or
   instantly via optimistic flip).
3. Tap lit heart → removed from MA favorites; heart unlights.
4. Queue pane heart mirrors takeover; tap works there too.
5. Long-press a track in search results → "Start radio" → queue refills with similar tracks
   (MA queue shows radio mode); same from an artist card.
6. Companion source: no heart, menu unchanged.
