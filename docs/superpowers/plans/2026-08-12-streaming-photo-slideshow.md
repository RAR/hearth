# Streaming Photo Slideshow — Implementation Plan

Spec: `docs/superpowers/specs/2026-08-12-streaming-photo-slideshow-design.md`

**Gate after every task:** `./gradlew testDebugUnitTest assembleDebug` with the return code checked
(never piped to `tail` alone), plus `node --check app/src/main/assets/config/app.js` once `app.js`
changes. Commit per task. Plain-JVM JUnit4 only; no new dependencies.

## Global constraints

- `minSdk` 27, `targetSdk` 34, `applicationId com.rar.hearth` — untouched.
- The ledger lives in `filesDir`, **not** `cacheDir`: the cache dir is subject to Android's
  storage-pressure eviction and to `stalePhotoCacheDirs` wipes on a resolution change, and
  `cacheDir.listFiles()` is the buffer inventory, so a stray file there would be read as a photo.
- Existing configs carry `photoCacheCap`; `ConfigJson` has `ignoreUnknownKeys = true`, so the field
  is simply dropped with no migration code.

---

## Task 1: Pure selection + ledger modules

**Files:**
- Create `app/src/main/java/com/rar/hearth/photos/PhotoLedger.kt`
- Create `app/src/main/java/com/rar/hearth/photos/NextBatch.kt`
- Delete `app/src/main/java/com/rar/hearth/photos/RotatingSubset.kt`
- Delete `app/src/test/java/com/rar/hearth/photos/RotatingSubsetTest.kt`
- Create `app/src/test/java/com/rar/hearth/photos/PhotoLedgerTest.kt`
- Create `app/src/test/java/com/rar/hearth/photos/NextBatchTest.kt`

`PhotoLedger(file: File)`: `read(): Set<String>`, `add(keys)`, `clear()`. Newline-delimited, written
via temp-file rename so an interrupted write can't corrupt it. A missing or unreadable file reads as
empty — never throws.

`nextBatch(listing, seen, buffered, depth, random): PhotoBatch(toDownload, epochReset)`. Draws
`depth - buffered.size` items uniformly from `listing − seen − buffered`. When that pool is empty
and `buffered.size < depth`, sets `epochReset = true` and re-draws against `listing − buffered`.

**Tests:** unseen-only selection; excludes already-buffered; respects depth; epoch reset when the
pool empties; deterministic under injected `Random`; ledger round-trip, missing file, corrupt file.

---

## Task 2: Config fields

**Files:** `app/src/main/java/com/rar/hearth/config/DashConfig.kt`,
`app/src/test/java/com/rar/hearth/config/` (existing config test)

Replace `photoCacheCap: Int = 50` with `photoBufferDepth: Int = 20` (clamp 5–100) and add
`photoSyncIntervalMinutes: Int = 360` (clamp 15–1440) in `HomeSettings` and the validation block
near `DashConfig.kt:469`.

**Tests:** both clamps at each bound; an old JSON body containing `photoCacheCap` parses and yields
the new defaults.

---

## Task 3: PhotoStore rework

**Files:** `app/src/main/java/com/rar/hearth/photos/PhotoStore.kt`,
`app/src/test/java/com/rar/hearth/photos/PhotoStoreTest.kt` (rewrite)

- Constructor gains `ledger: PhotoLedger`; `syncIntervalMs` param is dropped in favor of the config
  field.
- State: `buffer: ArrayDeque<File>` (unshown), `history: ArrayDeque<File>` (last 3 shown),
  `current: StateFlow<File?>`. Retire the `photos: StateFlow<List<File>>` surface.
- `advance()`: push current → history, trim history past 3 (deleting the evicted file), pop next
  from buffer into current, mark it seen in the ledger, launch a refill. If the buffer is empty,
  cycle `history` instead and **do not delete** — the offline path.
- `back()`: pop from history into current; no-op when empty.
- `refresh()` (was `sync()`): re-browse the listing, run `nextBatch`, download into the buffer,
  clear the ledger on `epochReset`. Still mutex-serialized.
- Triggers: CONNECTED transition, config change (folder / bufferDepth / slideshowEnabled /
  **syncIntervalMinutes**), and a timer loop that re-reads the interval each iteration so a config
  change restarts it rather than finishing a stale sleep.
- On construction, adopt any files already in `cacheDir` as the initial buffer.

**Tests:** advance marks seen and refills; history retains 3 and deletes past the tail; back within
history; empty-buffer cycling deletes nothing; epoch reset clears the ledger; interval change
restarts the timer.

---

## Task 4: UI + wiring

**Files:** `app/src/main/java/com/rar/hearth/ui/HomeView.kt`,
`app/src/main/java/com/rar/hearth/ui/DashboardShell.kt`, `app/src/main/java/com/rar/hearth/App.kt`,
`app/src/main/java/com/rar/hearth/AppDeps.kt`, `app/src/main/assets/config/app.js`

- `HomeView`: replace `photos: List<File>` with `photo: File?`, `onAdvance: () -> Unit`,
  `onBack: () -> Unit`. Delete the local `order`/`photoIndex`/`shuffled()` block; the timer calls
  `onAdvance`, the swipe calls `onAdvance`/`onBack`. Keep the `takeoverVisible` pause and the
  re-arm-on-change behavior.
- `AppDeps`: `PhotoStore(..., ledger = PhotoLedger(File(appContext.filesDir, "photo-seen.txt")))`.
- `app.js` (~line 1066): replace the "Photo cache cap" row with "Photo buffer depth" and add
  "Photo refresh (min)".

---

## Verification

Unit gate per task. Then flash one device, confirm the buffer settles at ~24 files
(`adb shell ls .../cache/photos-* | wc -l`), the ledger grows in `files/photo-seen.txt`, and photos
advance without repeating. Stage the rest of the fleet only after that device looks right.
