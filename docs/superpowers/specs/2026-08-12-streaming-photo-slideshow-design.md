# Streaming Photo Slideshow — Design

**Date:** 2026-08-12
**Status:** approved

## Problem

The photo backdrop caches a bounded subset of the HA media folder and rotates it slowly. With a
`photoCacheCap` of 50 and `slideshowSeconds` of 300, the device loops the same 50 photos every
**4 h 10 m**, all day. `rotatingSubset` swaps ~20% of the cache per sync (10 photos) and syncs every
6 h, so the visible set drifts by only 40 photos/day. Against an archive of **several thousand**
photos that is ~75 days to cycle through everything, and the user sees heavy repetition daily.

Measured on the Show 5 (2026-08-12): 50 cached files, 5.6 MB total, ~113 KB each at 960×480.

Two goals, stated by the user:

1. **No repeats** at the 5-minute default.
2. **Small on-disk footprint** — not every device has free space to spare.

## Key insight

These are not in tension. If a photo is never shown twice, retaining it after display has no value.
Every displayed photo must be downloaded exactly once regardless of design, so the cache only needs
to be a **prefetch buffer**, not a library. The no-repeat requirement makes the footprint *smaller*,
not larger.

Raising `photoCacheCap` — the obvious move — is the wrong one: it buys a longer loop but still
repeats, costs proportional disk on the constrained devices, and because `ROTATION_FRACTION` is a
fraction *of the cache*, it scales recurring downloads linearly with the cap.

## Design

Replace "sync a bounded rotating subset every 6 h" with "keep N photos queued ahead, consume as
displayed."

### Seen-ledger

A persisted newline-delimited file of cache keys already displayed, stored beside the photo cache
dir. Selection draws uniformly at random from `listing − seen`, which is shuffle-without-replacement:
no photo repeats until the entire archive has been shown. When the unseen pool empties, the ledger
is cleared and a fresh epoch begins.

Size: ~40 bytes/key × 3,000 ≈ 120 KB. Each device keeps its own ledger; they drift independently,
which is fine and arguably desirable.

### Buffer, not cache

`photoCacheCap` (default 50, clamp 5–500) is replaced by `photoBufferDepth` (default **20**, clamp
5–100). On-disk residency becomes `bufferDepth + history + current` ≈ 24 files ≈ **2.7 MB** at Show 5
resolution — less than half of today's 5.6 MB, on every device.

### Store owns the cursor

Today `HomeView` shuffles a `List<File>` snapshot and walks an index. That model cannot survive a
buffer whose contents change under it: every refill changes the list identity, resetting
`remember(photos)` and jumping the index.

`PhotoStore` therefore owns the cursor:

- `current: StateFlow<File?>` — the photo to display.
- `advance()` — push current onto `history`, pop the next from `buffer`, mark the new current seen,
  trigger a refill. Called by the slideshow timer and by a forward swipe.
- `back()` — pop from `history` if non-empty; no-op otherwise.

`history` retains the last **3** shown photos so the existing back-swipe still works. A photo is
marked seen when it becomes current, and deleted from disk when it falls off the history tail.

This moves the ring logic out of Compose into a plain-JVM-testable place and removes the identity
trap entirely.

### Selection is pure

`rotatingSubset` retires. A new pure function replaces it:

```kotlin
fun nextBatch(
    listing: List<RemotePhoto>,
    seen: Set<String>,
    buffered: Set<String>,
    depth: Int,
    random: Random,
): PhotoBatch   // toDownload: List<RemotePhoto>, epochReset: Boolean
```

Keeping selection pure preserves the current testing posture — the interesting logic stays plain
JUnit4 with no Android or coroutine machinery.

### Triggers

The periodic timer no longer drives downloads; refill is driven by display consumption. The timer,
the CONNECTED transition, and config changes now only **re-browse the listing** to pick up newly
uploaded photos. Since the listing refresh is cheap and no longer coupled to churn, its interval
becomes user-settable: `photoSyncIntervalMinutes`, default **360**, clamp 15–1440, added to the
reactive trigger tuple in `PhotoStore.start` so a change restarts the timer instead of finishing a
stale 6-hour sleep.

### Offline behavior

If HA is unreachable the buffer drains but must not go blank. When the buffer is empty and a refill
cannot complete, the store stops deleting on advance and cycles the retained photos until downloads
resume. At depth 20 that is ~100 minutes of graceful degradation at the 5-minute default.

## Costs and constraints

**Bandwidth.** No-repeats has a hard floor: at 5 minutes a photo each device pulls **288 originals a
day**, and `AndroidPhotoDownloader` fetches the full-size original before resizing locally. At ~3 MB
an original that is ~**850 MB/day/device**; five devices ≈ 4 GB/day. Averaged that is ~10 KB/s per
device. Confirmed with the user that HA is reached over the LAN, so this is a non-issue.

**Config migration.** `ConfigJson` sets `ignoreUnknownKeys = true`, so existing stored and exported
configs carrying `photoCacheCap` parse cleanly and fall back to the new field's default. No
migration code needed.

**Web UI.** The Screens page swaps its "Photo cache cap" row for "Photo buffer depth" and gains
"Photo refresh (min)". `app.js` changes, so `node --check` joins the gate.

## Out of scope

- Server-side thumbnailing to cut the per-photo download. HA's media source exposes no resize
  endpoint; would require a separate integration-side helper.
- Cross-device ledger sharing.
- Changing `slideshowSeconds` behavior or the takeover pause semantics.

## Testing

Plain-JVM JUnit4 only, matching the existing suite:

- `nextBatch`: unseen-only selection, epoch reset when the pool empties, respects depth, excludes
  already-buffered keys, deterministic under an injected `Random`.
- Ledger: round-trips, tolerates a missing or corrupt file, bounded rewrite.
- `PhotoStore` cursor: advance marks seen and refills, history retains 3 and deletes past the tail,
  back-swipe within history, empty-buffer offline cycling does not delete.

Gate: `./gradlew testDebugUnitTest assembleDebug` with the return code checked, plus
`node --check app.js`.
