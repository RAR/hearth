# Multi-Room Speakers Pane (Grouping + Volume + Transfer) — Design (SendSpin sweep, Batch D)

**Date:** 2026-07-19
**Status:** Approved scope (user chose "Both": grouping + per-member volume + queue
transfer). API facts from `.superpowers/sdd/ma-api-recon.md` §5; server MA 2.9.9.

## Goal

A **Speakers** overlay pane in the MusicBrowser (sibling of the Queue pane, opened by a
"Speakers" chip next to the existing Queue chip) that shows every Music Assistant player
with:

- a **volume slider** per player (`players/cmd/volume_set`),
- **group controls**: join a player to this device's sync group / remove it
  (`players/cmd/group` / `players/cmd/ungroup`), gated by `can_group_with`,
- **queue transfer**: "Send my queue here" per row (`player_queues/transfer`).

## API surface (confirmed, recon §5)

- `players/all` — defaults (`return_unavailable=true`, `return_protocol_players=false`).
  Relevant `PlayerState` fields: `player_id`, `name` (wire alias `display_name`),
  `available`, `volume_level`, `volume_muted`, `group_members` (wire alias `group_childs`),
  `synced_to`, `can_group_with` (set of player ids / provider instance ids), `type`,
  `playback_state` (wire alias `state`), `active_source`, `current_media`.
- `players/cmd/volume_set` `{player_id, volume_level}` (0..100).
- `players/cmd/group` `{player_id, target_player}` — joins player_id onto target's group.
- `players/cmd/ungroup` `{player_id}` — removes from whatever it's in; no-op if ungrouped.
- `player_queues/transfer` `{source_queue_id, target_queue_id, auto_play}` — target
  pre-ungrouping is handled server-side; `auto_play: null` keeps the source's play state.

**RISK (flagged for live-verify, with a coded fallback):** whether SendSpin-provider
players (our Echos) appear under the default `return_protocol_players=false` is
unconfirmed. Mitigation: after parsing, if OUR OWN `playerId` is absent from the result,
refetch once with `return_protocol_players: true` and use that. Live-verify item 1 pins
the truth; the fallback is cheap either way.

**Queue-id convention:** MA queue ids equal the owning player's id. Transfer uses
`source_queue_id` = our effective queue id (the existing `getEffectiveQueueId` seam) and
`target_queue_id` = the target row's `player_id`. Unconfirmed edge cases land in
live-verify item 4.

## Data flow

### New model `MaPlayer` (musicassistant/MaPlayer.kt)

```kotlin
data class MaPlayer(
    val playerId: String,
    val name: String,
    val available: Boolean,
    val volumeLevel: Int?,          // null when the player reports none
    val muted: Boolean,
    val syncedTo: String?,          // sync-leader player_id when this row is a member
    val groupMembers: List<String>, // non-empty on a leader/group player
    val canGroupWith: List<String>, // player ids or provider-instance ids
    val playbackState: String?,     // "playing"|"paused"|"idle"|null
    val nowPlayingText: String?,    // current_media title — display only, nullable
)
```

Parsed by `MaCommandClient.parsePlayers(response)` (internal, testable): reads `name` (or
`display_name` fallback), `group_childs` fallback for `group_members`, `state` fallback
for `playback_state`.

### MaCommandClient — five new methods (try/log/Result shape)

```kotlin
suspend fun getPlayers(includeProtocol: Boolean = false): Result<List<MaPlayer>>
    // players/all; adds "return_protocol_players" to true only when includeProtocol
suspend fun setPlayerVolume(playerId: String, volume: Int): Result<Unit>
suspend fun groupPlayer(playerId: String, targetPlayer: String): Result<Unit>
suspend fun ungroupPlayer(playerId: String): Result<Unit>
suspend fun transferQueue(sourceQueueId: String, targetQueueId: String): Result<Unit>
    // auto_play deliberately omitted from the args map (server derives from source state)
```

### MaLibrary — wrappers

```kotlin
suspend fun players(): Result<List<MaPlayer>>
    // withClient: getPlayers(); if our playerId is missing from the list,
    // retry once with includeProtocol = true (the flagged fallback)
suspend fun setPlayerVolume(playerId: String, volume: Int): Result<Unit>   // withClient
suspend fun groupWithMe(playerId: String): Result<Unit>
    // withClient: groupPlayer(playerId, targetPlayer = OUR playerId)
suspend fun ungroupPlayer(playerId: String): Result<Unit>                  // withClient
suspend fun transferMyQueueTo(targetPlayerId: String): Result<Unit>
    // withQueue: transferQueue(sourceQueueId = effective queue id, targetQueueId = targetPlayerId)
```

### Pure helpers (ui/model/SpeakersModel.kt, plain-JVM tested)

```kotlin
/** Rows for the pane: available players first (self pinned to the top), A-Z within tiers. */
fun speakerRows(players: List<MaPlayer>, selfId: String): List<MaPlayer>

/** True when [player] is in the same sync group as self (either direction of leadership). */
fun inGroupWithSelf(player: MaPlayer, self: MaPlayer?): Boolean

/** True when the Group-with-me action may be offered (not self, available, not already
 *  grouped with self, and self or self's provider appears in player.canGroupWith —
 *  an EMPTY canGroupWith list is treated as permissive: MA omits the field for players
 *  with no restrictions). */
fun canOfferGroup(player: MaPlayer, self: MaPlayer?): Boolean
```

`selfId` = `MaLibrary.playerId`, exposed via a small `val selfPlayerId: String get() = playerId`
on MaLibrary (the UI needs it to mark "This device").

## UI (MusicBrowser.kt)

- **Speakers chip**: next to the existing Queue chip in the browser's top bar, same visual
  (label "Speakers"). Opens `speakersVisible` overlay; Queue and Speakers are mutually
  exclusive (opening one closes the other).
- **SpeakersPane** (overlay, same panel chrome as QueuePane): header "Speakers" + close.
  Poll `library.players()` every 5s while visible (`speakersVersion` bump = immediate
  refetch after any mutation, completion-ordered: bump inside `.onSuccess`).
- **Row** (one per `speakerRows(...)` entry):
  - Name (+ "· this device" suffix on self), dimmed when `!available`.
  - Status line, 12sp dimmed: "Playing — <nowPlayingText>" / "Grouped with <leader name>"
    (from `syncedTo` resolved against the list) / "Idle".
  - Volume slider (existing Slider styling, 0..100) → `setPlayerVolume` on
    `onValueChangeFinished` (same drag-guard pattern as the takeover volume).
    Hidden when `volumeLevel == null` or `!available`.
  - Action chips (small text chips, right side):
    - **Group** (`canOfferGroup(...)`) → `groupWithMe(player.playerId)`.
    - **Ungroup** (`inGroupWithSelf(...)` and not self) → `ungroupPlayer(player.playerId)`.
    - **Send queue** (not self, available) → `transferMyQueueTo(player.playerId)` —
      after success ALSO close the pane (the music left this device; the browser stays).
  - All mutations: `.onSuccess { speakersVersion++ }` / `.onFailure { showError(...) }`.
- Self row shows its slider (volume via MA keeps group logic consistent) and no action chips.

## Degradation

| Condition | Behavior |
|---|---|
| MA socket down | Speakers chip still renders; pane shows the standard error toast on fetch |
| Our player absent from players/all | One-shot includeProtocol retry; if still absent, list renders without the self pin (actions that need self hidden) |
| Unavailable player | Dimmed row, no slider/actions |
| canGroupWith excludes us | No Group chip |
| Transfer target refuses / fails | Error toast, pane stays open, refetch reconciles |
| Volume slider on a just-removed player | Command fails → toast; next poll drops the row |

## Out of scope (deliberate)

- Creating/destroying named MA group players (`set_members` bulk editing) — join/leave
  relative to THIS device only.
- Group-volume master slider (`players/cmd/group_volume`) — per-member sliders suffice v1.
- Power on/off, mute buttons, per-player queue peeking, transfer *from* another player
  *to* me (push model only: "send my queue there").

## Testing (plain-JVM JUnit4)

- `parsePlayers` pins: full payload (aliases `display_name`/`group_childs`/`state`
  exercised via fallback), minimal payload (nulls/defaults), unavailable player.
- MaLibraryTest (FakeTransport): `players()` sends `players/all` (no protocol arg);
  self-missing triggers the one-shot `return_protocol_players: true` retry;
  `groupWithMe` sends target_player = own playerId; `transferMyQueueTo` resolves the
  effective queue then sends transfer with target_queue_id = target player id;
  `setPlayerVolume` arg pin.
- `SpeakersModel` pins: ordering (self first, availability tiers, A-Z), `inGroupWithSelf`
  both leadership directions, `canOfferGroup` (self excluded, empty-canGroupWith
  permissive, explicit allow, explicit absence).

## Live-verify checklist

1. Open Speakers while MA music plays → all MA players listed, this device pinned first
   and labeled; note whether the includeProtocol fallback fired (logcat).
2. Slide another player's volume → that speaker's volume changes in MA.
3. Group a kitchen/other speaker with this device → music plays synced on both; Ungroup
   returns it; MA UI agrees throughout.
4. Send my queue to another player → playback continues there, this device goes idle,
   pane closes; queue intact on the target (check MA UI).
5. A player MA says can't group with us shows no Group chip.
6. Companion-source day-to-day screens unaffected (pane is MA-only surface).
