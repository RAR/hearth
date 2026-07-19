# Queue Swipe-to-Remove — Design (SendSpin sweep, Batch B)

**Date:** 2026-07-19
**Status:** Approved scope (user chose "Swipe-to-remove only" — no reordering). API facts
from `.superpowers/sdd/ma-api-recon.md` §3; server MA 2.9.9.

## Goal

Swipe a row in the MusicBrowser queue pane left to remove that track from the queue —
mirroring the notification area's swipe-dismiss gesture. No reordering (deliberate).

## API surface (confirmed)

`player_queues/delete_item` `{queue_id, item_id_or_index}` — identical on 2.9.9/dev; accepts
the `queue_item_id` STRING (use it — indices shift under playback). Server silently ignores
(warn-logs) deletes of items already loaded into the playback buffer — so deleting the
current/near-current row is safe but may no-op.

## Data flow

- `MaCommandClient.deleteQueueItem(queueId: String, queueItemId: String): Result<Unit>` →
  `player_queues/delete_item` with `item_id_or_index = queueItemId`. Same try/log/Result
  shape as `clearQueue`.
- `MaLibrary.removeQueueItem(queueItemId: String): Result<Unit>` — `withQueue` (same
  effective-queue resolution as `jumpTo`/`clearQueue`).
- `QueueRow` takes `onRemove: suspend (MaQueueItem) -> Boolean` (not a fire-and-forget
  callback — removal is a server round trip that can fail, so the row needs the outcome to
  decide whether to stay off-screen or animate back). MusicBrowser implements it as
  `library.removeQueueItem(item.queueItemId)` → `.onSuccess { queueVersion++ }` (NOT a
  synchronous bump; matches `playItem`'s completion-ordered convention — final review of
  Batch A flagged the synchronous variant as dead intent) → `.onFailure { showError(...) }`
  → returns `result.isSuccess`.

## UI (MusicBrowser.kt QueuePane/QueueRow)

- `QueueRow` gains the notification-row swipe mechanics, copied from the proven
  `NotificationArea.NotificationRow` template (`Animatable` x-offset +
  `pointerInput(item.queueItemId) { detectHorizontalDragGestures(...) }`): left-drag only
  (`coerceAtMost(0f)`), threshold 0.30f of measured row width (`onSizeChanged`), animate
  fully off-screen `tween(200)` then fire the remove callback, snap back on `onDragCancel`
  and below-threshold `onDragEnd`.
- The existing tap-to-jump `clickable` stays; the horizontal detector and the pane's
  vertical scroll claim separate axes (same coexistence as the notification stack).
- The CURRENT item's row does NOT get the swipe (guard: `if (!item.isCurrentItem)`) — the
  server would silently ignore near-buffer deletes anyway (recon §3); offering a gesture
  that no-ops is worse than not offering it. Rows after removal: `QueueRow`'s `onDragEnd`
  animates fully off-screen first, then awaits `onRemove`'s Boolean result — a `true`
  (removed) leaves it off-screen for the following `queueVersion` refetch to naturally omit;
  a `false` (failed) animates the row back into view immediately, no refetch needed.
- No trash icon / affordance chrome — discoverability matches the notification rows
  (consistent gesture language on this dashboard).

## Degradation

| Condition | Behavior |
|---|---|
| Delete fails (socket drop, server error) | Row animates back on failure result; toast via existing `showError` |
| Swipe the current item | No swipe gesture offered |
| Server silently ignores (buffered/near-current item) | Command still reports success, so the row does not animate back on its own — it stays hidden until the queue panel is closed/reopened (same recovery path as the old pre-fix failure case); no error shown, since none occurred |
| Rapid multi-swipe | Each fires independently; each row's own animate/await/maybe-animate-back sequence is independent |

## Out of scope (deliberate)

- Reordering (user decision), undo/snackbar, swipe on the up-next takeover line,
  right-swipe actions.

## Testing (plain-JVM JUnit4)

- MaLibraryTest (FakeTransport seam): `removeQueueItem` resolves the effective queue and
  sends `player_queues/delete_item` with `queue_id` + `item_id_or_index` = the string id.
- No new pure fns — the gesture is Compose (untested by convention), the command is pinned
  via the wrapper test.

## Live-verify checklist

1. Queue open with ≥3 upcoming tracks → swipe a non-current row left past ~1/3 width →
   row slides off, MA queue loses the track, pane refetches consistent.
2. Below-threshold swipe → snaps back, no removal.
3. Vertical scroll in the pane still works; tap-to-jump still works.
4. Current row: cannot be swiped.
5. Kill the MA socket (stop MA) mid-pane → swipe shows the error toast and the row slides
   back into view immediately (no refetch needed).
