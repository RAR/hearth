# Queue Swipe-to-Remove Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user swipe a non-current row in the MusicBrowser queue overlay left to remove that track from the Music Assistant queue — mirroring the home notification area's swipe-dismiss gesture. Source of truth: `docs/superpowers/specs/2026-07-19-queue-swipe-remove-design.md`.

**Architecture:** A new `MaCommandClient.deleteQueueItem(queueId, queueItemId)` sends `player_queues/delete_item` with `item_id_or_index` set to the queue-item-id string (never an index — indices shift under playback). `MaLibrary.removeQueueItem(queueItemId)` wraps it via the existing `withQueue` effective-queue resolution, identical in shape to `jumpTo`/`clearQueue`. `QueueRow` in `MusicBrowser.kt` gains the exact swipe mechanics already proven in `NotificationArea.NotificationRow` (`Animatable` x-offset + `detectHorizontalDragGestures`, 0.30 width-fraction threshold, snap-back on cancel/below-threshold) — but only for non-current rows; the current item never receives the gesture. Unlike `NotificationRow`'s fire-and-forget dismiss, a completed swipe is a server round trip that can fail: `QueueRow` animates fully off-screen, then *awaits* a `suspend (MaQueueItem) -> Boolean` removal result before deciding what happens next — `true` leaves it off-screen (the following `queueVersion` refetch naturally omits the removed item), `false` animates the row straight back into view. `MusicBrowser` implements that callback as `library.removeQueueItem(id)` → `.onSuccess { queueVersion++ }` (never synchronously) → `.onFailure { showError(...) }` → returns `result.isSuccess`. This keeps the row's own animation state authoritative for both outcomes instead of depending on a later poll to reconcile a stuck offset.

**Tech Stack:** Kotlin + Jetpack Compose (native Android kiosk). Gradle (`:app`), JDK 21 (Amazon Corretto).

## Global Constraints

- **Gate before EVERY commit** — run both, each must show RC=0 before committing:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Check gradle's OWN exit code via `RC=$?` captured immediately after the gradle invocation. NEVER pipe gradle to `tail`/`head` (or any filter) — that masks gradle's exit code behind the filter's. Redirect all gradle output to the scratchpad log and inspect the log if RC is non-zero. `node --check` guards the config bundle; no task in this plan edits `app.js`, so it always passes here, but it remains part of the gate.
- **Commit trailer** — every commit message ends with exactly this trailer line:
  `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- **NO new dependencies.** Everything used here (`Animatable`, `detectHorizontalDragGestures`, coroutines, `kotlinx.serialization.json`) is already in the project via existing usages in `NotificationArea.kt` and `MaCommandClient.kt`.
- **Tests are plain-JVM JUnit4 only** — no Robolectric, no instrumentation, no Compose test harness. Match the existing idiom in `MaLibraryTest.kt` (`FakeTransport`/`Harness`, `org.junit.Test`, `org.junit.Assert.*`, `kotlinx.coroutines.test.runTest`).
- **Compose UI code is NOT unit-tested in this repo.** The gesture composable is verified only by the `:app:assembleDebug` compile inside the gate — do not invent fake Compose tests.
- **Do NOT push.** Commits stay local.
- **Current suite is 991 tests.** Task 1 adds 1 (final 992); Task 2 adds none.
- **The current item's row must NOT receive the swipe gesture** — guard: `if (!item.isCurrentItem)`.
- **`queueVersion` bumps on completion (`.onSuccess`), NEVER synchronously before the async op** — matches `playItem`'s and `jumpTo`/`clearQueue`'s existing convention in `MusicBrowser.kt`.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — gains `deleteQueueItem(queueId, queueItemId): Result<Unit>`, appended to the "Queue Management Commands" section (same try/log/Result shape as `clearQueue`), inserted immediately after `playQueueItem`.
- `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — gains `removeQueueItem(queueItemId): Result<Unit>`, a one-line `withQueue` wrapper, inserted immediately after `clearQueue()`.
- `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` — one new `@Test` pinning `removeQueueItem`'s command name + args, in the "---- ops ----" section.
- `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — `QueuePane` gains an `onRemove: suspend (MaQueueItem) -> Boolean` param threaded to `QueueRow`; `QueueRow` gains the swipe-to-remove gesture (copied mechanics from `NotificationArea.NotificationRow`, extended to await the removal result and animate back on failure), guarded off for the current item; the `QueuePane(...)` call site in `MusicBrowser` gains the `onRemove` lambda that calls `library.removeQueueItem(...)` and returns whether it succeeded.

---

## Task 1 — `MaCommandClient.deleteQueueItem` + `MaLibrary.removeQueueItem`

The command-layer plumbing: one MA API call (`player_queues/delete_item`) wrapped in the same `Result`-returning try/catch shape every other command in `MaCommandClient` uses, then exposed through `MaLibrary`'s effective-queue resolution. Pinned by one `MaLibraryTest` case that exercises the real `MaCommandClient` (via `FakeTransport`) end to end, matching how `playResolvesEffectiveQueueThenPlaysOnIt` and `playRadioResolvesQueueAndSendsRadioMode` already pin `play`/`playRadio`.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt` — add `deleteQueueItem` after `playQueueItem` (currently ends ~line 275, immediately before the `// ==== Library Commands ====` section comment).
- Modify: `app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt` — add `removeQueueItem` after `clearQueue()` (currently lines 150-151, immediately before the `// ---- Favorite / radio ops ----` comment).
- Test: `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt` — add one `@Test` in the "---- ops ----" section, after `playerUnavailableSurfacesAsFailureAndIsNotCached` (ends ~line 299) and before the `// ---- signIn ----` comment (~line 301).

**Interfaces:**
- Consumes: `MaCommandClient.sendCommand(command, args)` (existing internal method used by every command); `MaLibrary.withQueue` (existing private op plumbing, resolves the effective queue id and caches it).
- Produces:
  - `MaCommandClient.deleteQueueItem(queueId: String, queueItemId: String): Result<Unit>` — consumed by `MaLibrary.removeQueueItem` (this task) and pinned by the new test.
  - `MaLibrary.removeQueueItem(queueItemId: String): Result<Unit>` — consumed by `MusicBrowser.kt`'s `QueuePane` call site (Task 2).

### Steps

- [ ] **Step 1: Read the current insertion points.** Open `MaCommandClient.kt` and confirm `playQueueItem` (the `player_queues/play_index` command) is still immediately followed by the `// ========================================================================` / `// Library Commands` section header. Open `MaLibrary.kt` and confirm `clearQueue()` is still immediately followed by a blank line then `// ---- Favorite / radio ops ----`. If either anchor has moved, locate the equivalent spot (end of the queue-command group in `MaCommandClient.kt`; end of the `withQueue`-based queue ops in `MaLibrary.kt`) instead of relying on the line numbers below.

- [ ] **Step 2: Write the failing test.** Add this method to `app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt`, in the "---- ops ----" section, immediately after `playerUnavailableSurfacesAsFailureAndIsNotCached` (before the `// ---- signIn ----` comment):

  ```kotlin
      @Test
      fun removeQueueItemResolvesEffectiveQueueThenSendsDeleteItem() = runTest {
          val h = Harness(this)
          h.lib.configure(enabled = true, token = "tok")
          runCurrent()
          val res = h.lib.removeQueueItem("qi-42")
          assertTrue(res.isSuccess)
          assertEquals(
              listOf("player_queues/get_active_queue", "player_queues/delete_item"),
              h.commands.map { it.first },
          )
          val deleteArgs = h.commands[1].second
          assertEquals("q-77", deleteArgs["queue_id"])
          assertEquals("qi-42", deleteArgs["item_id_or_index"])
          h.lib.stop()
      }
  ```

  No new imports are needed — `assertEquals`/`assertTrue`/`Test`/`runTest`/`runCurrent` are already imported in this file, and `Harness` already resolves `player_queues/get_active_queue` to `"q-77"` by default (see `FakeTransport.respond`'s default branch, line ~41).

- [ ] **Step 3: Run the test to verify it fails.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t1.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `unresolved reference: removeQueueItem`. RC != 0.

- [ ] **Step 4: Implement `MaCommandClient.deleteQueueItem`.** Insert immediately after `playQueueItem`'s closing brace (after the line `Log.e(TAG, "Failed to play queue item: $queueItemId", e)` / `Result.failure(e)` / `}` / `}` block, currently ending around line 275), before the `// Library Commands` section header:

  ```kotlin
      /**
       * Remove a single item from the queue by its queue_item_id. Server-side: if the target is
       * already loaded into the player's playback buffer, the delete is silently ignored (with a
       * server warning log) rather than erroring — protects the currently-streaming item from
       * being pulled out from under playback. Callers should avoid offering this on the current
       * row (see MusicBrowser's QueueRow isCurrentItem guard) even though it's not load-bearing
       * for correctness here.
       */
      suspend fun deleteQueueItem(queueId: String, queueItemId: String): Result<Unit> {
          return try {
              sendCommand(
                  "player_queues/delete_item",
                  mapOf("queue_id" to queueId, "item_id_or_index" to queueItemId)
              )
              Log.i(TAG, "Removed queue item: $queueItemId")
              Result.success(Unit)
          } catch (e: Exception) {
              Log.e(TAG, "Failed to remove queue item: $queueItemId", e)
              Result.failure(e)
          }
      }
  ```

- [ ] **Step 5: Implement `MaLibrary.removeQueueItem`.** Insert immediately after `clearQueue()` (currently lines 150-151):

  ```kotlin
      suspend fun clearQueue(): Result<Unit> =
          withQueue { client, queueId -> client.clearQueue(queueId) }

      suspend fun removeQueueItem(queueItemId: String): Result<Unit> =
          withQueue { client, queueId -> client.deleteQueueItem(queueId, queueItemId) }
  ```
  (Only the new second function is an addition — `clearQueue()` is shown for anchoring.)

- [ ] **Step 6: Run the test to verify it passes.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.MaLibraryTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t1.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all existing + 1 new test green.

- [ ] **Step 7: Gate.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Expected: `GATE RC=0`, `NODE RC=0`, suite now 992.

- [ ] **Step 8: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/musicassistant/MaCommandClient.kt app/src/main/java/com/rar/echodash/sendspin/MaLibrary.kt app/src/test/java/com/rar/echodash/sendspin/MaLibraryTest.kt
  git commit -m "$(cat <<'EOF'
  feat(media): MaCommandClient.deleteQueueItem + MaLibrary.removeQueueItem

  Wraps player_queues/delete_item (queue_id + item_id_or_index = the stable
  queue_item_id string, never an index — indices shift under playback).
  MaLibrary.removeQueueItem resolves the effective queue via the existing
  withQueue plumbing, same shape as jumpTo/clearQueue. Pinned by one
  MaLibraryTest case (991 -> 992).

  Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
  EOF
  )"
  ```

---

## Task 2 — `QueueRow` swipe-to-remove gesture

Give `QueueRow` the notification-row swipe mechanics: an `Animatable` x-offset dragged left via `detectHorizontalDragGestures`, animated fully off-screen past a 0.30-width threshold. Removal is a server round trip that can fail, so — unlike `NotificationRow`'s fire-and-forget `onDismiss` — the swipe-complete path *awaits* a `suspend (MaQueueItem) -> Boolean` result before deciding what the row does next: `true` (removed) leaves it off-screen; `false` (failed) animates it straight back into view. `QueueRow` itself never touches `MaLibrary` — the actual removal (`library.removeQueueItem(...)` + `.onSuccess { queueVersion++ }` / `.onFailure { showError(...) }` + returning `result.isSuccess`) lives at the `QueuePane(...)` call site in `MusicBrowser`, alongside the existing `onJump`/`onClear` lambdas; `QueueRow`'s own `rememberCoroutineScope()` runs the whole animate → await-removal → maybe-animate-back sequence as one coroutine. **UI-only task: no unit test** — the command plumbing is covered by Task 1's test; `QueueRow`'s gesture and layout are verified only by the `:app:assembleDebug` compile, per this repo's Compose-untested convention.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — new imports; new `SWIPE_DISMISS_FRACTION` constant; `QueuePane` gains an `onRemove` param forwarded into the `items(...)` lambda; `QueueRow` is replaced with the swipe-enabled version; the `QueuePane(...)` call site (inside `MusicBrowser`) gains the `onRemove` lambda.

**Interfaces:**
- Consumes: `MaLibrary.removeQueueItem(queueItemId: String): Result<Unit>` (Task 1); `MusicBrowser`'s existing `library: MaLibrary`, `queueVersion` (`by remember { mutableIntStateOf(0) }`), and `showError: (String) -> Unit` — all already in scope at the `QueuePane(...)` call site (see the existing `onJump`/`onClear` lambdas there). Note `onRemove` does NOT need `MusicBrowser`'s own `rememberCoroutineScope()` wrapper the way `onJump`/`onClear` do — it's a suspend function value, already invoked from inside `QueueRow`'s own coroutine.
- Produces: `QueuePane`'s new `onRemove: suspend (MaQueueItem) -> Boolean` param and `QueueRow`'s new `onRemove: suspend (MaQueueItem) -> Boolean` param — both consumed only within this task (no downstream task).

### Steps

- [ ] **Step 1: Read the current code.** Re-open `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` and confirm: the `QueuePane(...)` call site inside `MusicBrowser` (currently lines 282-309, with `onJump` at 285-291 and `onClear` at 292-298); the `QueuePane` composable signature and its `items(queue.items, key = { it.queueItemId }) { qi -> QueueRow(qi, thumbs, onJump) }` body (currently lines 564-614); the `QueueRow` composable (currently lines 635-663); and the `private const val TAG = "MusicBrowser"` line (currently line 87). Also re-open `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` and confirm `NotificationRow`'s swipe block (currently lines 159-191: `Animatable(0f)`, `widthPx` via `onSizeChanged`, `offset { IntOffset(...) }`, `pointerInput(item.key) { detectHorizontalDragGestures(...) }`, `SWIPE_DISMISS_FRACTION` at line 56) — this is the template being copied. If any line numbers have drifted, locate the equivalent block by the code shown (not the numbers).

- [ ] **Step 2: Add the new imports.** `MusicBrowser.kt` needs 8 imports it doesn't currently have. Insert each at its alphabetically-correct position (matching this file's existing per-package ordering, which mirrors `NotificationArea.kt`'s import order for the same identifiers):

  1. Before the current first import (`import androidx.compose.foundation.ExperimentalFoundationApi`, line 3), insert:
  ```kotlin
  import androidx.compose.animation.core.Animatable
  import androidx.compose.animation.core.tween
  ```

  2. Between `import androidx.compose.foundation.combinedClickable` (line 7) and `import androidx.compose.foundation.layout.Arrangement` (line 8), insert:
  ```kotlin
  import androidx.compose.foundation.gestures.detectHorizontalDragGestures
  ```

  3. Between `import androidx.compose.foundation.layout.height` (line 15) and `import androidx.compose.foundation.layout.padding` (line 16), insert:
  ```kotlin
  import androidx.compose.foundation.layout.offset
  ```

  4. Between `import androidx.compose.ui.graphics.vector.ImageVector` (line 59) and `import androidx.compose.ui.layout.ContentScale` (line 60), insert:
  ```kotlin
  import androidx.compose.ui.input.pointer.pointerInput
  ```

  5. Immediately after `import androidx.compose.ui.layout.ContentScale` (line 60), insert:
  ```kotlin
  import androidx.compose.ui.layout.onSizeChanged
  ```

  6. Between `import androidx.compose.ui.unit.Dp` (line 63) and `import androidx.compose.ui.unit.dp` (line 64), insert:
  ```kotlin
  import androidx.compose.ui.unit.IntOffset
  ```

  7. Immediately before `import kotlinx.coroutines.delay` (line 82), insert:
  ```kotlin
  import kotlin.math.roundToInt
  ```

  (Exact placement is cosmetic — Kotlin doesn't enforce import order — but matching the file's existing convention keeps future diffs clean. If it's simpler to append all 8 as one block right after the last existing import, that compiles identically.)

- [ ] **Step 3: Add the `SWIPE_DISMISS_FRACTION` constant.** Immediately after `private const val TAG = "MusicBrowser"` (line 87):

  ```kotlin
  private const val TAG = "MusicBrowser"
  private const val SWIPE_DISMISS_FRACTION = 0.30f
  ```

- [ ] **Step 4: Add `onRemove` to `QueuePane` and thread it to `QueueRow`.** Change the `QueuePane` signature (currently lines 565-573) from:

  ```kotlin
  private fun QueuePane(
      queue: MaQueueState?,
      thumbs: MaThumbs,
      onJump: (String) -> Unit,
      onClear: () -> Unit,
      onToggleShuffle: () -> Unit,
      onCycleRepeat: () -> Unit,
      onToggleFavorite: () -> Unit = {},
  ) {
  ```
  to:
  ```kotlin
  private fun QueuePane(
      queue: MaQueueState?,
      thumbs: MaThumbs,
      onJump: (String) -> Unit,
      onClear: () -> Unit,
      onRemove: suspend (MaQueueItem) -> Boolean,
      onToggleShuffle: () -> Unit,
      onCycleRepeat: () -> Unit,
      onToggleFavorite: () -> Unit = {},
  ) {
  ```

  Then change the `items(...)` lambda (currently lines 608-610) from:
  ```kotlin
              items(queue.items, key = { it.queueItemId }) { qi ->
                  QueueRow(qi, thumbs, onJump)
              }
  ```
  to:
  ```kotlin
              items(queue.items, key = { it.queueItemId }) { qi ->
                  QueueRow(qi, thumbs, onJump, onRemove)
              }
  ```

- [ ] **Step 5: Replace `QueueRow` with the swipe-enabled version.** Replace the entire current `QueueRow` composable (currently lines 635-663):

  ```kotlin
  @Composable
  private fun QueueRow(item: MaQueueItem, thumbs: MaThumbs, onJump: (String) -> Unit) {
      Row(
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(if (item.isCurrentItem) Color(0xFF2A2F3C) else Color.Transparent)
              .clickable { onJump(item.queueItemId) }
              .padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
          Thumb(item.imageUri, thumbs, 36.dp, corner = 6.dp)
          Column(Modifier.weight(1f)) {
              Text(
                  item.name, color = Color.White, fontSize = 14.sp,
                  fontWeight = if (item.isCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
                  maxLines = 1, overflow = TextOverflow.Ellipsis,
              )
              val sub = listOfNotNull(item.artist, item.album).joinToString(" — ")
              if (sub.isNotBlank()) {
                  Text(
                      sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                      maxLines = 1, overflow = TextOverflow.Ellipsis,
                  )
              }
          }
      }
  }
  ```

  with:

  ```kotlin
  /**
   * A queue row: tap to jump (unchanged), swipe left to remove — mechanics copied from
   * [NotificationArea.NotificationRow] (Animatable x-offset + detectHorizontalDragGestures,
   * SWIPE_DISMISS_FRACTION threshold, snap back on cancel or a below-threshold release). The
   * horizontal drag detector and the QueuePane's vertical LazyColumn scroll claim separate
   * gesture axes, same coexistence as the notification stack.
   *
   * Unlike NotificationRow's fire-and-forget onDismiss, removal is a server round trip that can
   * fail — so a completed swipe animates fully off-screen, *awaits* [onRemove]'s suspending
   * Boolean result, and animates back into view if it returns false. This keeps the row's own
   * animation state authoritative for both outcomes instead of relying on a later queue refetch
   * to reconcile a stuck offset.
   *
   * The CURRENT item never receives the swipe (guard below) — the server silently ignores
   * deletes of a buffered item anyway, so offering a gesture that can no-op is worse than not
   * offering it (recon: player_queues/delete_item). The offset/onSizeChanged scaffolding stays
   * in place for the current row too (harmless — it never moves without a drag detector), which
   * keeps one row layout instead of two divergent ones.
   */
  @Composable
  private fun QueueRow(
      item: MaQueueItem,
      thumbs: MaThumbs,
      onJump: (String) -> Unit,
      onRemove: suspend (MaQueueItem) -> Boolean,
  ) {
      val offsetX = remember { Animatable(0f) }
      var widthPx by remember { mutableIntStateOf(0) }
      val scope = rememberCoroutineScope()

      Box(
          Modifier
              .fillMaxWidth()
              .onSizeChanged { widthPx = it.width }
              .offset { IntOffset(offsetX.value.roundToInt(), 0) }
              .then(
                  if (item.isCurrentItem) {
                      Modifier
                  } else {
                      Modifier.pointerInput(item.queueItemId) {
                          detectHorizontalDragGestures(
                              onDragEnd = {
                                  val threshold = widthPx * SWIPE_DISMISS_FRACTION
                                  if (widthPx > 0 && -offsetX.value >= threshold) {
                                      scope.launch {
                                          offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                          val removed = onRemove(item)
                                          // Failed (or the server otherwise didn't actually drop
                                          // it): bring the row back into view instead of leaving
                                          // a blank slot around until the panel is closed and
                                          // reopened — LazyColumn's key = { it.queueItemId }
                                          // would otherwise keep this exact offset pinned to
                                          // this row across every later recomposition.
                                          if (!removed) {
                                              offsetX.animateTo(0f, tween(200))
                                          }
                                      }
                                  } else {
                                      scope.launch { offsetX.animateTo(0f, tween(200)) }
                                  }
                              },
                              // A cancelled drag (ancestor claims the gesture, extra pointer)
                              // never reaches onDragEnd — snap back so the row can't be left
                              // stranded mid-swipe.
                              onDragCancel = {
                                  scope.launch { offsetX.animateTo(0f, tween(200)) }
                              },
                          ) { change, dragAmount ->
                              change.consume()
                              // Only left drags move the row; right drags clamp back to 0.
                              scope.launch {
                                  offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f))
                              }
                          }
                      }
                  }
              ),
      ) {
          Row(
              Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (item.isCurrentItem) Color(0xFF2A2F3C) else Color.Transparent)
                  .clickable { onJump(item.queueItemId) }
                  .padding(4.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Thumb(item.imageUri, thumbs, 36.dp, corner = 6.dp)
              Column(Modifier.weight(1f)) {
                  Text(
                      item.name, color = Color.White, fontSize = 14.sp,
                      fontWeight = if (item.isCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
                      maxLines = 1, overflow = TextOverflow.Ellipsis,
                  )
                  val sub = listOfNotNull(item.artist, item.album).joinToString(" — ")
                  if (sub.isNotBlank()) {
                      Text(
                          sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                          maxLines = 1, overflow = TextOverflow.Ellipsis,
                      )
                  }
              }
          }
      }
  }
  ```

  (`Row`'s inner content — `Thumb`, `Column`, both `Text`s — is byte-for-byte the original `QueueRow` body; only the wrapping `Box` + gesture scaffolding and the two new params are added.)

- [ ] **Step 6: Wire `onRemove` at the `QueuePane(...)` call site.** In `MusicBrowser`, the call site currently reads (lines 282-309):

  ```kotlin
              if (queueVisible) {
                  QueuePane(
                      queue = queueState,
                      thumbs = thumbs,
                      onJump = { id ->
                          scope.launch {
                              library.jumpTo(id)
                                  .onSuccess { queueVersion++ }
                                  .onFailure { showError(it.message ?: "Couldn't jump to item") }
                          }
                      },
                      onClear = {
                          scope.launch {
                              library.clearQueue()
                                  .onSuccess { queueVersion++ }
                                  .onFailure { showError(it.message ?: "Couldn't clear the queue") }
                          }
                      },
                      // Computed off the queue's OWN state (not local now-playing), so a tap
                      // always toggles what the chip is currently displaying — no cross-source
                      // divergence between what's shown and what gets flipped.
                      onToggleShuffle = { queueState?.let { q -> onSetShuffle(!q.shuffleEnabled) }; queueVersion++ },
                      onCycleRepeat = { queueState?.let { q -> onSetRepeat(nextRepeatMode(q.repeatMode)) }; queueVersion++ },
                      // Favorite the queue's current item (the App callback decides add vs remove).
                      onToggleFavorite = {
                          queueState?.let { onFavoriteToggle(currentItemOf(it)) }
                          queueVersion++
                      },
                  )
              } else when (content) {
  ```

  Add the `onRemove` lambda between `onClear` and `onToggleShuffle`:

  ```kotlin
              if (queueVisible) {
                  QueuePane(
                      queue = queueState,
                      thumbs = thumbs,
                      onJump = { id ->
                          scope.launch {
                              library.jumpTo(id)
                                  .onSuccess { queueVersion++ }
                                  .onFailure { showError(it.message ?: "Couldn't jump to item") }
                          }
                      },
                      onClear = {
                          scope.launch {
                              library.clearQueue()
                                  .onSuccess { queueVersion++ }
                                  .onFailure { showError(it.message ?: "Couldn't clear the queue") }
                          }
                      },
                      // Suspend, Boolean-returning: QueueRow already animated the row fully
                      // off-screen before calling this, then awaits the result to decide whether
                      // to animate it back (see QueueRow's onDragEnd) — so this runs inside
                      // QueueRow's own coroutine, not scope.launch'd here like onJump/onClear.
                      // queueVersion bumps only on success, never synchronously.
                      onRemove = { item ->
                          val result = library.removeQueueItem(item.queueItemId)
                          result.onSuccess { queueVersion++ }
                          result.onFailure { showError(it.message ?: "Couldn't remove item") }
                          result.isSuccess
                      },
                      // Computed off the queue's OWN state (not local now-playing), so a tap
                      // always toggles what the chip is currently displaying — no cross-source
                      // divergence between what's shown and what gets flipped.
                      onToggleShuffle = { queueState?.let { q -> onSetShuffle(!q.shuffleEnabled) }; queueVersion++ },
                      onCycleRepeat = { queueState?.let { q -> onSetRepeat(nextRepeatMode(q.repeatMode)) }; queueVersion++ },
                      // Favorite the queue's current item (the App callback decides add vs remove).
                      onToggleFavorite = {
                          queueState?.let { onFavoriteToggle(currentItemOf(it)) }
                          queueVersion++
                      },
                  )
              } else when (content) {
  ```

- [ ] **Step 7: Gate.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Expected: `GATE RC=0` (compile of the new imports, constant, and gesture code), `NODE RC=0`, suite stays at 992 (this task adds no tests).

- [ ] **Step 8: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt
  git commit -m "$(cat <<'EOF'
  feat(media): swipe-left to remove a queue row

  QueueRow copies NotificationRow's swipe-dismiss mechanics (Animatable
  x-offset + detectHorizontalDragGestures, 0.30 width threshold, snap back on
  cancel/below-threshold) but awaits a suspend (MaQueueItem) -> Boolean
  removal result before deciding what happens next: true leaves the row
  off-screen (the following queueVersion refetch naturally omits it), false
  animates it straight back into view. MusicBrowser's new QueuePane onRemove
  callback calls library.removeQueueItem, bumps queueVersion only on success
  (never synchronously), shows an error toast on failure, and returns
  result.isSuccess. The current item never gets the gesture (server silently
  no-ops deletes of buffered items). UI-only (compile-gated, no reordering —
  deliberate per spec).

  Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
  EOF
  )"
  ```

---

## Self-Review

**1. Spec coverage:**
- `MaCommandClient.deleteQueueItem` sending `player_queues/delete_item` with `queue_id` + `item_id_or_index` = the queue-item-id string (never an index) — Task 1. ✓
- `MaLibrary.removeQueueItem` via `withQueue` (same effective-queue resolution as `jumpTo`/`clearQueue`) — Task 1. ✓
- Swipe completion awaits `onRemove(item)`; `MusicBrowser`'s implementation calls `library.removeQueueItem(item.queueItemId)`, `.onSuccess { queueVersion++ }` (never synchronous), `.onFailure { showError(...) }`, returns `result.isSuccess` — Task 2, Step 6. ✓
- `QueueRow` swipe mechanics copied from `NotificationRow` (Animatable, `detectHorizontalDragGestures`, 0.30 threshold via `onSizeChanged`, left-only via `coerceAtMost(0f)`, snap back on cancel/below-threshold), extended to await the removal result and animate back on `false` — Task 2, Step 5. ✓
- Existing tap-to-jump `clickable` preserved; horizontal drag detector and the pane's vertical scroll on separate axes — Task 2, Step 5 (inner `Row` keeps its original `clickable`; `LazyColumn`'s vertical scroll is untouched). ✓
- Current item's row does NOT get the swipe (`if (!item.isCurrentItem)` guard) — Task 2, Step 5. ✓
- No trash icon / affordance chrome — Task 2, Step 5 (visual content is byte-for-byte the original `QueueRow` body; nothing added). ✓
- Degradation table: swipe on current item → no gesture offered ✓; rapid multi-swipe → each `pointerInput(item.queueItemId)` instance is independent, each runs its own animate→await→maybe-animate-back coroutine ✓. Delete FAILS (socket drop, server error) → `onRemove` returns `false`, `QueueRow`'s `onDragEnd` coroutine animates `offsetX` back to 0 immediately (no dependency on the next queue poll), and the error toast fires via `showError` — Task 2, Step 5 (`if (!removed) { offsetX.animateTo(0f, tween(200)) }`) + Step 6. ✓ Residual (not in scope of the lead's fix, noted here for completeness): a server silent no-op on a buffered/near-current item still returns `Result.success` (the command itself succeeded; MA just chose not to apply it), so `onRemove` returns `true` and the row does NOT animate back even though the item is technically still queued — recoverable the same way as the pre-fix failure case (close/reopen the queue panel). This is narrower than the original gap (only near-buffer items, never the literal current item, which is already guarded off) and matches the updated spec's Degradation table.
- Testing: `MaLibraryTest` pin for `removeQueueItem` (command name + `queue_id` + `item_id_or_index` args) — Task 1. No new pure fns; gesture is Compose/untested by convention — Task 2 explicitly has no test step. ✓
- Out of scope (reordering, undo/snackbar, up-next takeover swipe, right-swipe) — untouched by both tasks. ✓
- Live-verify checklist — reproduced verbatim below. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code, including the full replaced `QueueRow` body and the full `QueuePane(...)` call site diff. ✓

**3. Type consistency:** `MaCommandClient.deleteQueueItem(queueId: String, queueItemId: String): Result<Unit>` (Task 1, Step 4) is called by `MaLibrary.removeQueueItem(queueItemId: String): Result<Unit>` (Task 1, Step 5) with the same param order (`queueId, queueItemId`) as `withQueue`'s `(client, queueId) ->` lambda supplies. `MaLibrary.removeQueueItem` is called from `MusicBrowser.kt`'s `QueuePane(...)` call site (Task 2, Step 6) inside a `suspend (MaQueueItem) -> Boolean` lambda that reads `item.queueItemId` and returns `result.isSuccess: Boolean`, matching `QueuePane`'s `onRemove: suspend (MaQueueItem) -> Boolean` (Task 2, Step 4) and `QueueRow`'s `onRemove: suspend (MaQueueItem) -> Boolean` (Task 2, Step 5), which invokes it with `item: MaQueueItem` and reads its `Boolean` result as `removed` — same signature end to end. ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Queue open with ≥3 upcoming tracks → swipe a non-current row left past ~1/3 width → row slides off, MA queue loses the track, pane refetches consistent.
2. Below-threshold swipe → snaps back, no removal.
3. Vertical scroll in the pane still works; tap-to-jump still works.
4. Current row: cannot be swiped.
5. Kill the MA socket (stop MA) mid-pane → swipe shows the error toast and the row slides
   back into view immediately (no refetch needed).
