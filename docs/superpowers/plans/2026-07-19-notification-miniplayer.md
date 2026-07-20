# Notification Mini-Player Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pinned `NowPlayingRow` (a single line that never actually disappeared because SendSpin/MA reports `stopped` for both pause and real stop) with a taller `MiniPlayerCard` — 56dp art, title/artist lines, and a back/play-pause/next/stop transport row — and give it a lifecycle that actually hides: visible while playing, then a 5-minute post-pause grace window measured from the real pause instant, or an instant hide on Stop/swipe. Source of truth: `docs/superpowers/specs/2026-07-19-notification-miniplayer-design.md`.

**Architecture:** A new pure `miniPlayerVisible(active, playing, takeoverVisible, dismissed, pausedSinceMs, nowMs)` fn (paired with the `MINIPLAYER_GRACE_MS` constant) replaces the inlined `nowPlaying.active && !takeoverVisible` check that currently gates the row. HomeView tracks two new pieces of session state — `miniDismissed` (swipe) and `pausedSinceMs` (stamped at the instant `playing` goes false, reset on resume or session end) — declared in HomeView's own unconditional scope so they survive the takeover's mount/unmount cycle (the takeover's ~60s paused-timeout hides it well before the mini-player's 5-minute budget closes; the card must pick up the *remaining* time of the *same* pause, not restart a fresh timer when it (re)mounts). `NowPlayingRow` in `NotificationArea.kt` is replaced by `MiniPlayerCard` (plus a private `MiniTransportButton` clone of the takeover's transport chip and the same swipe-to-dismiss mechanics `NotificationRow` already uses). `onMediaStop` — already a `DashboardShell` param used by `MediaPanel` — is threaded one hop further, to `HomeView`, for the card's Stop chip.

**Tech Stack:** Kotlin + Jetpack Compose (native Android kiosk). Pure model logic in `com.rar.echodash.ui.model` (plain-JVM JUnit4 tested). Gradle (`:app`), JDK 21 (Amazon Corretto).

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
- **NO new dependencies.** `material-icons-extended` (already a dependency) supplies every icon this feature needs — `Icons.Outlined.SkipPrevious`/`Pause`/`PlayArrow`/`SkipNext`/`Stop` are already used elsewhere in the app (`NowPlayingHome.kt`, `panels/MediaPanel.kt`).
- **Tests are plain-JVM JUnit4 only** — no Robolectric, no instrumentation, no Compose test harness. Match the existing idiom in `NotificationModelTest.kt` (`org.junit.Test`, `org.junit.Assert.*`).
- **Compose UI code is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`); the composables are verified only by the `:app:assembleDebug` compile inside the gate. The UI task below explicitly has no unit test — do not invent fake Compose tests.
- **Do NOT push.** Commits stay local.
- **Current suite is 1035 tests.** Task 1 adds 8 (final 1043); Task 2 adds none.
- **Clock-cap shrink becomes 130 (was 62)**, with the derivation spelled out at the constant/comment site; the `coerceAtLeast(60)` floor stays.
- **`nowPlayingRowLabel` is NOT deleted** — it stays in `NotificationModel.kt` with its existing tests standing; the mini-player card renders title/artist as two separate lines instead of calling it (see Task 2's signature note).
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` — gains `MINIPLAYER_GRACE_MS` + the pure `miniPlayerVisible` fn, appended after the existing `nowPlayingRowLabel` (which is untouched).
- `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` — 8 new `@Test` methods pinning `miniPlayerVisible` + the grace constant.
- `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` — the public `NowPlayingRow` composable is removed; a public `MiniPlayerCard` (+ a private `MiniTransportButton` clone of `NowPlayingHome`'s transport chip) replaces it, reusing the file's existing swipe mechanics (`Animatable` offset, `SWIPE_DISMISS_FRACTION`, drag/cancel handling).
- `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — new `onMediaStop: () -> Unit = {}` param; new `miniDismissed`/`pausedSinceMs` state + their `LaunchedEffect`s; the notification block's `showNowPlayingRow`/`NowPlayingRow`/`- 62` become `showMiniPlayer`/`MiniPlayerCard`/`- 130`.
- `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — forwards `onMediaStop` (already its own param, used today only by `MediaPanel`) to the `HomeView(...)` call.

---

## Task 1 — Pure model fn: `miniPlayerVisible` + `MINIPLAYER_GRACE_MS`

The visibility gate for the mini-player card: active session, neither the takeover nor a swipe claiming it, and — once paused — a bounded grace window measured from the real pause instant. Lands first, fully tested, so Task 2's UI wiring is a pure plug-in.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` — append the constant + fn at the end of the file (after `nowPlayingRowLabel`).
- Test: `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` — append 8 `@Test` methods before the closing brace.

**Interfaces:**
- Consumes: nothing new (pure Kotlin).
- Produces:
  - `const val MINIPLAYER_GRACE_MS: Long` = `5 * 60_000L` (300,000 ms). Consumed by `miniPlayerVisible` itself; referenced directly by two of this task's own tests.
  - `fun miniPlayerVisible(active: Boolean, playing: Boolean, takeoverVisible: Boolean, dismissed: Boolean, pausedSinceMs: Long, nowMs: Long): Boolean`. Consumed by HomeView (Task 2), which supplies `pausedSinceMs` from its own tracked state and `nowMs` from its existing minute ticker.

### Steps

- [ ] **Step 1: Write the failing tests.** Append these 8 methods to `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt`, inside the class, immediately before its final closing brace (after the existing `nowPlayingRowLabel` tests). The file already imports `assertEquals`, `assertFalse`, `assertNull`, `assertTrue`, and `Test` — no new imports needed.

  ```kotlin
      // ---- miniPlayerVisible ----

      @Test
      fun miniPlayerGraceConstantIsFiveMinutes() {
          assertEquals(5 * 60_000L, MINIPLAYER_GRACE_MS)
      }

      @Test
      fun miniPlayerVisibleWhilePlayingAndAllGatesOpen() {
          assertTrue(
              miniPlayerVisible(
                  active = true, playing = true, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = 0L, nowMs = 1_000L,
              ),
          )
      }

      @Test
      fun miniPlayerHiddenByAnyGateWhilePlaying() {
          // Each gate individually hides the card even while actively playing.
          assertFalse(
              miniPlayerVisible(
                  active = false, playing = true, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = 0L, nowMs = 1_000L,
              ),
          )
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = true, takeoverVisible = true, dismissed = false,
                  pausedSinceMs = 0L, nowMs = 1_000L,
              ),
          )
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = true, takeoverVisible = false, dismissed = true,
                  pausedSinceMs = 0L, nowMs = 1_000L,
              ),
          )
      }

      @Test
      fun miniPlayerVisibleWithinGraceAfterPause() {
          // Paused 1 minute ago, well inside the 5-minute grace window.
          val pausedAt = 100_000L
          assertTrue(
              miniPlayerVisible(
                  active = true, playing = false, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = pausedAt, nowMs = pausedAt + 60_000L,
              ),
          )
      }

      @Test
      fun miniPlayerHiddenAtOrAfterGraceExpiry() {
          val pausedAt = 100_000L
          // Exactly at the boundary (comparison is strict "<") and past it both hide the card.
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = false, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = pausedAt, nowMs = pausedAt + MINIPLAYER_GRACE_MS,
              ),
          )
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = false, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = pausedAt, nowMs = pausedAt + MINIPLAYER_GRACE_MS + 1,
              ),
          )
      }

      @Test
      fun miniPlayerHiddenWhenNeverPlayingThisSession() {
          // pausedSinceMs=0 means "never observed paused/playing" -- nothing to resume, so hidden
          // regardless of how much time has passed.
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = false, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = 0L, nowMs = 999_999L,
              ),
          )
      }

      @Test
      fun miniPlayerHiddenWhenDismissedRegardlessOfPauseState() {
          val pausedAt = 100_000L
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = false, takeoverVisible = false, dismissed = true,
                  pausedSinceMs = pausedAt, nowMs = pausedAt + 60_000L,
              ),
          )
          assertFalse(
              miniPlayerVisible(
                  active = true, playing = true, takeoverVisible = false, dismissed = true,
                  pausedSinceMs = 0L, nowMs = pausedAt,
              ),
          )
      }

      @Test
      fun miniPlayerHiddenWheneverInactiveRegardlessOfOtherState() {
          val pausedAt = 100_000L
          assertFalse(
              miniPlayerVisible(
                  active = false, playing = false, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = pausedAt, nowMs = pausedAt + 60_000L,
              ),
          )
          assertFalse(
              miniPlayerVisible(
                  active = false, playing = true, takeoverVisible = false, dismissed = false,
                  pausedSinceMs = 0L, nowMs = pausedAt,
              ),
          )
      }
  ```

- [ ] **Step 2: Run tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.NotificationModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `unresolved reference: miniPlayerVisible` / `MINIPLAYER_GRACE_MS`. RC != 0.

- [ ] **Step 3: Write the minimal implementation.** Append this constant + fn to the END of `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` (after `nowPlayingRowLabel`):

  ```kotlin
  /**
   * Grace window the mini-player card lingers after playback pauses/stops (indistinguishable on the
   * SendSpin/MA wire -- both report `stopped`) before auto-hiding: 5 minutes, measured from the
   * pause/stop moment itself -- NOT from whenever the card happens to become visible (see
   * [miniPlayerVisible]'s `pausedSinceMs` contract). The takeover's own ~60s paused-timeout hides the
   * takeover well inside this window, handing off to the card for the remainder of the same pause.
   */
  const val MINIPLAYER_GRACE_MS = 5 * 60_000L

  /**
   * Mini-player card visibility. [active] and neither [takeoverVisible] nor a user's swipe
   * ([dismissed]) gate it off entirely. While [playing] it always shows; once paused it lingers until
   * [MINIPLAYER_GRACE_MS] has elapsed since [pausedSinceMs] (the device-clock instant `playing` last
   * went false this session -- 0 means "never observed paused", which hides the card since there is
   * nothing to resume). Callers must stamp [pausedSinceMs] at the actual pause instant, not at
   * whenever this composable happens to (re)mount, so the grace budget survives e.g. the takeover's
   * own hand-off.
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
      if (pausedSinceMs <= 0) return false
      return nowMs - pausedSinceMs < MINIPLAYER_GRACE_MS
  }
  ```

- [ ] **Step 4: Run tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.NotificationModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all existing + 8 new tests green (suite now 1043).

- [ ] **Step 5: Gate.** Run the full gate from Global Constraints (both `GATE RC=0` and `NODE RC=0`).

- [ ] **Step 6: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt
  git commit -m "$(cat <<'EOF'
  feat(media): miniPlayerVisible pure fn + MINIPLAYER_GRACE_MS

  Mini-player replaces the always-pinned NowPlayingRow, whose lifecycle never
  actually hid it (SendSpin/MA reports "stopped" for both pause and real
  stop). miniPlayerVisible gates on active/takeover/dismissed plus a 5-minute
  post-pause grace measured from the real pause instant, so a stop or
  speaker-switch now disappears within 5 min instead of never. Pure +
  JVM-tested (8 tests).

  Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
  EOF
  )"
  ```

---

## Task 2 — `MiniPlayerCard` (NotificationArea) + HomeView wiring + `onMediaStop` threading

Replace `NowPlayingRow` with the taller `MiniPlayerCard`, switch HomeView's gate from the inlined `nowPlaying.active && !takeoverVisible` to `miniPlayerVisible(...)`, add the `miniDismissed`/`pausedSinceMs` state that feeds it, widen the notification stack's height-cap shrink to 130, and thread `onMediaStop` from `DashboardShell` (which already owns it) down to `HomeView` for the card's Stop chip. **UI/wiring task: no unit test** — the gate logic is covered by Task 1's `miniPlayerVisible` tests; the composables and callback plumbing are verified only by the `:app:assembleDebug` compile.

**Signature decision (flagged for review):** the design spec's `MiniPlayerCard` signature comment reads `label: String, // nowPlayingRowLabel as today`, but the spec's own prose two paragraphs later says to render title and artist as **separate** lines and pass `nowPlaying.title ?: "Now playing"` for the title line specifically (its "Label change note" section is explicit: "no new model fn needed... `nowPlayingRowLabel` tests stand" — i.e. the card does NOT call it). That first-param doc comment is stale, carried over from the old single-line `NowPlayingRow`. This plan resolves the contradiction by naming the param `title: String` (not `label`) so the signature matches its actual, unambiguous use; `nowPlayingRowLabel` is left completely untouched per the spec's own note.

**State-placement decision (flagged for review):** `miniDismissed` and `pausedSinceMs` are declared at HomeView's own top-level (unconditional) scope — immediately after the existing `val now by rememberMinuteTicker()` — rather than inside the `if (takeoverVisible) {...} else {...}` block lower down that actually renders the card. The spec doesn't spell out where in HomeView these live. Placing them inside that `else` branch would be a real bug: that branch unmounts whenever `takeoverVisible` flips true (its `remember` state resets on remount), so `pausedSinceMs` would get re-stamped to "now" the moment the takeover's own ~60s paused-timeout hides it and hands off to the card — resetting the 5-minute grace budget instead of continuing it from the real pause instant, exactly the bug this feature exists to fix for Stop/speaker-switch. The top-level `BoxWithConstraints` (and everything before it) is unconditionally composed on every HomeView recomposition regardless of `takeoverVisible`, so state declared there survives the inner branch swap.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` — imports; remove the public `NowPlayingRow` composable (lines 98-149 today); add public `MiniPlayerCard` + private `MiniTransportButton` in its place.
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — imports; new `onMediaStop` param; new state block; replace the notification `AnimatedVisibility` block.
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — forward `onMediaStop` (already a `DashboardShell` param) to the `HomeView(...)` call.

**Interfaces:**
- Consumes: `com.rar.echodash.ui.model.miniPlayerVisible` (Task 1); HomeView's existing `nowPlaying: NowPlayingState`, `art: ArtBitmaps?` (`art?.sharp: ImageBitmap?`), `takeoverVisible: Boolean`, `onTakeoverRestore`, `onMediaPlay`/`onMediaPause`/`onMediaNext`/`onMediaPrev` params.
- Produces (`NotificationArea.kt` new public composable):
  ```kotlin
  fun MiniPlayerCard(
      title: String,
      artist: String?,
      artThumb: ImageBitmap?,
      playing: Boolean,
      onPrev: () -> Unit,
      onPlayPause: () -> Unit,
      onNext: () -> Unit,
      onStop: () -> Unit,
      onTap: () -> Unit,
      onDismiss: () -> Unit,
  )
  ```
- Produces (`HomeView` new param, forwarded from `DashboardShell`): `onMediaStop: () -> Unit = {}`.

### Steps

- [ ] **Step 1: Read** the current `NotificationArea.kt` in full (already reviewed: `NotificationRow`'s swipe mechanics at lines 151-245 — `Animatable` offset, `SWIPE_DISMISS_FRACTION = 0.30f`, `onDragEnd`/`onDragCancel` — and the `NowPlayingRow` composable at lines 98-149 being replaced), the `HomeView.kt` param list (lines 174-217), its top-of-function state (lines 218-231), and its notification `AnimatedVisibility` block (lines 391-441), and `DashboardShell.kt`'s `HomeView(...)` call (lines 282-329, specifically the `onMediaPlay`/`onMediaPause` lines at 308-309). Confirm the anchors below still match; adjust the diffs to whatever the file's current line numbers are if they've drifted.

- [ ] **Step 2: Implement `NotificationArea.kt`.** Two edits.

  1. Add these imports alongside the existing ones (the file already imports `Animatable`, `tween`, `Image`, `background`, `clickable`, `detectHorizontalDragGestures`, `Arrangement`, `Box`, `Column`, `Row`, `fillMaxSize`, `fillMaxWidth`, `padding`, `size`, `RoundedCornerShape`, `Icons`, `MusicNote`, `Icon`, `Text`, `mutableIntStateOf`, `remember`, `rememberCoroutineScope`, `Alignment`, `Modifier`, `clip`, `Color`, `ImageBitmap`, `pointerInput`, `ContentScale`, `onSizeChanged`, `FontWeight`, `TextOverflow`, `IntOffset`, `dp`, `sp`, `roundToInt`, `launch`):
     - `import androidx.compose.foundation.shape.CircleShape` — insert immediately before the existing `import androidx.compose.foundation.shape.RoundedCornerShape` line.
     - Insert these four lines immediately after the existing `import androidx.compose.material.icons.outlined.MusicNote` line:
       ```kotlin
       import androidx.compose.material.icons.outlined.Pause
       import androidx.compose.material.icons.outlined.PlayArrow
       import androidx.compose.material.icons.outlined.SkipNext
       import androidx.compose.material.icons.outlined.SkipPrevious
       import androidx.compose.material.icons.outlined.Stop
       ```
     - `import androidx.compose.ui.graphics.vector.ImageVector` — insert immediately after the existing `import androidx.compose.ui.graphics.ImageBitmap` line.

  2. Replace the entire `NowPlayingRow` composable (today's lines 98-149 — from its KDoc comment through its closing brace):
     ```kotlin
     /**
      * Pinned now-playing row for the home notification stack — shown while music is active but the
      * takeover is dismissed (manual home-button OR the paused-timeout path). Mirrors [NotificationRow]'s
      * chrome (RoundedCornerShape(14) on Black-0.35, 18sp white medium text) but is a single tappable
      * line: a 34dp art thumbnail ([artThumb] = the already-decoded art.sharp) or a MusicNote fallback,
      * then [label]. Tapping restores the takeover. No swipe-dismiss, no timestamp, no severity accent
      * bar — it isn't a notification.
      */
     @Composable
     fun NowPlayingRow(label: String, artThumb: ImageBitmap?, onTap: () -> Unit) {
         Row(
             Modifier
                 .fillMaxWidth()
                 .clip(RoundedCornerShape(14.dp))
                 .background(Color.Black.copy(alpha = 0.35f))
                 .clickable { onTap() }
                 .padding(horizontal = 12.dp, vertical = 10.dp),
             verticalAlignment = Alignment.CenterVertically,
             horizontalArrangement = Arrangement.spacedBy(12.dp),
         ) {
             Box(
                 Modifier
                     .size(34.dp)
                     .clip(RoundedCornerShape(8.dp))
                     .background(Color.Black.copy(alpha = 0.25f)),
                 contentAlignment = Alignment.Center,
             ) {
                 if (artThumb != null) {
                     Image(
                         artThumb, contentDescription = null,
                         modifier = Modifier.fillMaxSize(),
                         contentScale = ContentScale.Crop,
                     )
                 } else {
                     Icon(
                         Icons.Outlined.MusicNote, contentDescription = null,
                         tint = Color.White.copy(alpha = 0.5f),
                         modifier = Modifier.size(20.dp),
                     )
                 }
             }
             Text(
                 label,
                 color = Color.White,
                 fontSize = 18.sp,
                 fontWeight = FontWeight.Medium,
                 maxLines = 1,
                 overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.weight(1f),
             )
         }
     }
     ```
     with:
     ```kotlin
     /**
      * Mini-player card for the home notification stack — shown while a session is active, playing
      * (or within the post-pause grace window, see [miniPlayerVisible]), and neither the takeover nor
      * a swipe has claimed it. Chrome matches [NotificationRow] (RoundedCornerShape(14) on Black-0.35).
      * Row 1 (tap = [onTap], restores the takeover): a 56dp art thumbnail ([artThumb] = art.sharp, or a
      * MusicNote fallback) + a title/artist two-line column ([artist] omitted when null/blank). Row 2:
      * four 36dp transport chips (back/play-pause/next/stop). The whole card is wrapped in the exact
      * swipe-to-dismiss mechanics [NotificationRow] uses below — fire-and-forget, local state only, no
      * server op and so no failure path (unlike the MA queue rows' suspend variant elsewhere).
      */
     @Composable
     fun MiniPlayerCard(
         title: String,
         artist: String?,
         artThumb: ImageBitmap?,
         playing: Boolean,
         onPrev: () -> Unit,
         onPlayPause: () -> Unit,
         onNext: () -> Unit,
         onStop: () -> Unit,
         onTap: () -> Unit,
         onDismiss: () -> Unit,
     ) {
         val offsetX = remember { Animatable(0f) }
         var widthPx by remember { mutableIntStateOf(0) }
         val scope = rememberCoroutineScope()

         Box(
             Modifier
                 .fillMaxWidth()
                 .onSizeChanged { widthPx = it.width }
                 .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                 .pointerInput(Unit) {
                     detectHorizontalDragGestures(
                         onDragEnd = {
                             val threshold = widthPx * SWIPE_DISMISS_FRACTION
                             if (widthPx > 0 && -offsetX.value >= threshold) {
                                 scope.launch {
                                     offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                     onDismiss()
                                 }
                             } else {
                                 scope.launch { offsetX.animateTo(0f, tween(200)) }
                             }
                         },
                         // A cancelled drag (ancestor claims the gesture, extra pointer) never reaches
                         // onDragEnd — snap back so the card can't be left stranded mid-swipe.
                         onDragCancel = {
                             scope.launch { offsetX.animateTo(0f, tween(200)) }
                         },
                     ) { change, dragAmount ->
                         change.consume()
                         // Only left drags move the card; right drags clamp back to 0.
                         scope.launch { offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f)) }
                     }
                 },
         ) {
             Column(
                 Modifier
                     .fillMaxWidth()
                     .clip(RoundedCornerShape(14.dp))
                     .background(Color.Black.copy(alpha = 0.35f))
                     .padding(horizontal = 12.dp, vertical = 10.dp),
                 verticalArrangement = Arrangement.spacedBy(8.dp),
             ) {
                 // Body tap restores the takeover. The transport row below sits inside this same swipe
                 // area but consumes its own taps -- normal Compose click handling already wins over
                 // the drag detector for a tap that doesn't cross the swipe threshold.
                 Row(
                     Modifier.fillMaxWidth().clickable { onTap() },
                     verticalAlignment = Alignment.CenterVertically,
                     horizontalArrangement = Arrangement.spacedBy(12.dp),
                 ) {
                     Box(
                         Modifier
                             .size(56.dp)
                             .clip(RoundedCornerShape(8.dp))
                             .background(Color.Black.copy(alpha = 0.25f)),
                         contentAlignment = Alignment.Center,
                     ) {
                         if (artThumb != null) {
                             Image(
                                 artThumb, contentDescription = null,
                                 modifier = Modifier.fillMaxSize(),
                                 contentScale = ContentScale.Crop,
                             )
                         } else {
                             Icon(
                                 Icons.Outlined.MusicNote, contentDescription = null,
                                 tint = Color.White.copy(alpha = 0.5f),
                                 modifier = Modifier.size(28.dp),
                             )
                         }
                     }
                     Column(Modifier.weight(1f)) {
                         Text(
                             title,
                             color = Color.White,
                             fontSize = 16.sp,
                             fontWeight = FontWeight.Medium,
                             maxLines = 1,
                             overflow = TextOverflow.Ellipsis,
                         )
                         if (!artist.isNullOrBlank()) {
                             Text(
                                 artist,
                                 color = Color.White.copy(alpha = 0.6f),
                                 fontSize = 13.sp,
                                 maxLines = 1,
                                 overflow = TextOverflow.Ellipsis,
                             )
                         }
                     }
                 }
                 Row(
                     Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                     verticalAlignment = Alignment.CenterVertically,
                 ) {
                     MiniTransportButton(Icons.Outlined.SkipPrevious, onPrev)
                     MiniTransportButton(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, onPlayPause)
                     MiniTransportButton(Icons.Outlined.SkipNext, onNext)
                     MiniTransportButton(Icons.Outlined.Stop, onStop)
                 }
             }
         }
     }

     /** 36dp round transport chip (18dp icon), same #2A2F3C background as the takeover's transport
      *  button — a smaller clone local to this file (that one, `NpTransportButton`, is private to
      *  `NowPlayingHome.kt`). */
     @Composable
     private fun MiniTransportButton(icon: ImageVector, onClick: () -> Unit) {
         Box(
             Modifier
                 .size(36.dp)
                 .clip(CircleShape)
                 .background(Color(0xFF2A2F3C))
                 .clickable { onClick() },
             contentAlignment = Alignment.Center,
         ) {
             Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
         }
     }
     ```
     (`SWIPE_DISMISS_FRACTION` is the file's existing `private const val` at the top, already used by `NotificationRow` — reused here unchanged, per the design spec's "exact `NotificationRow` mechanics" instruction.)

- [ ] **Step 3: Implement `HomeView.kt`.** Four edits.

  1. Import edit: delete the existing `import com.rar.echodash.ui.model.nowPlayingRowLabel` line and insert `import com.rar.echodash.ui.model.miniPlayerVisible` immediately before the existing `import com.rar.echodash.ui.model.nextEventCard` line (keeps the block's alphabetical order: `eventTimeLabel`, `homeCardWidthDp`, `homeOverlayCaps`, `miniPlayerVisible`, `nextEventCard`, `solarFlowCard`, `solarStatsCompact`, `weatherPillText`).

  2. Add the `onMediaStop` param to `HomeView`'s signature, immediately after `onMediaPause: () -> Unit,`:
     ```kotlin
         onMediaPlay: () -> Unit,
         onMediaPause: () -> Unit,
         onMediaStop: () -> Unit = {},
         onMediaNext: () -> Unit,
         onMediaPrev: () -> Unit,
     ```

  3. Add the mini-player state block immediately after `val now by rememberMinuteTicker()` (before `val order = remember(photos) { photos.shuffled() }`), so it lives in HomeView's own unconditional scope — see the "State-placement decision" note above for why this specific spot matters:
     ```kotlin
         val context = LocalContext.current
         var menuOpen by remember { mutableStateOf(false) }
         val now by rememberMinuteTicker()

         // Mini-player dismiss + pause-grace state. Declared here — NOT inside the
         // `if (takeoverVisible) {...} else {...}` split further down — because that branch unmounts
         // while the takeover is up; pausedSinceMs must survive that hand-off holding the ACTUAL pause
         // instant (the takeover's own ~60s paused-timeout hides it well before the mini-player's own
         // 5-minute grace closes, and the card needs to know the pause started ~60s ago, not "now", to
         // honor the shared budget). BoxWithConstraints below is composed unconditionally every
         // recomposition, so state declared here (its ancestor) is unaffected by that inner branch swap.
         var miniDismissed by remember { mutableStateOf(false) }
         LaunchedEffect(nowPlaying.active) {
             if (!nowPlaying.active) miniDismissed = false
         }
         var pausedSinceMs by remember { mutableStateOf(0L) }
         LaunchedEffect(nowPlaying.playing, nowPlaying.active) {
             pausedSinceMs = if (nowPlaying.active && !nowPlaying.playing) System.currentTimeMillis() else 0L
         }

         val order = remember(photos) { photos.shuffled() }
     ```

  4. Replace the entire notification `AnimatedVisibility` block (today's lines 391-441 — from the `// Notification stack + pinned now-playing row` comment through its closing brace):
     ```kotlin
                 // Notification stack + pinned now-playing row: just below the weather/AQI pill row
                 // (top = 70dp). The row shows whenever music is active but the takeover is dismissed
                 // (manual home-button OR the paused-timeout); tapping it restores the takeover. The
                 // width cap moves onto the Column so the row and notifications share one edge; the
                 // height cap + clipToBounds stay on NotificationArea so real notifications scroll
                 // under their cap while the pinned row never clips. `!takeoverVisible` is always true
                 // in this else-branch — kept defensively per the design spec (which covers BOTH
                 // dismissal paths through this one formula).
                 val showNowPlayingRow = nowPlaying.active && !takeoverVisible
                 // homeOverlayCaps sizes notifMaxHeightDp so the stack ends NOTIF_CLOCK_GAP above the
                 // clock block; the pinned row adds 62dp above the stack (34dp thumb + 2×10dp pad +
                 // 8dp Column gap), so shrink the stack's cap by the same amount to keep that contract
                 // (Show 5: 200 → 138, still ~3 scrollable rows). Floor of 60 keeps one row usable if
                 // a future tiny screen ever bottoms out the geometry floor.
                 val notifHeightCap =
                     if (showNowPlayingRow) (caps.notifMaxHeightDp - 62).coerceAtLeast(60)
                     else caps.notifMaxHeightDp
                 AnimatedVisibility(
                     visible = notifications.isNotEmpty() || showNowPlayingRow,
                     enter = fadeIn(tween(600)),
                     exit = fadeOut(tween(600)),
                     modifier = Modifier
                         .align(Alignment.TopStart)
                         .padding(start = 28.dp, top = 70.dp),
                 ) {
                     Column(
                         Modifier.widthIn(max = caps.notifMaxWidthDp.dp),
                         verticalArrangement = Arrangement.spacedBy(8.dp),
                     ) {
                         if (showNowPlayingRow) {
                             NowPlayingRow(
                                 label = nowPlayingRowLabel(nowPlaying.title, nowPlaying.artist),
                                 artThumb = art?.sharp,
                                 onTap = onTakeoverRestore,
                             )
                         }
                         // Guarded so NotificationArea's "empty lists should not be rendered by the
                         // caller" contract holds when only the row is showing (no scroll container spun
                         // up for zero rows).
                         if (notifications.isNotEmpty()) {
                             NotificationArea(
                                 notifications = notifications,
                                 nowMs = now,
                                 onDismiss = onDismiss,
                                 modifier = Modifier
                                     .heightIn(max = notifHeightCap.dp)
                                     .clipToBounds(),
                             )
                         }
                     }
                 }
     ```
     with:
     ```kotlin
                 // Notification stack + mini-player card: just below the weather/AQI pill row
                 // (top = 70dp). The card shows while music is active, playing (or within the
                 // post-pause grace window), and neither the takeover nor a swipe has claimed it --
                 // miniPlayerVisible owns that whole decision (see model file). The width cap moves
                 // onto the Column so the card and notifications share one edge; the height cap +
                 // clipToBounds stay on NotificationArea so real notifications scroll under their cap
                 // while the card never clips.
                 val showMiniPlayer = miniPlayerVisible(
                     active = nowPlaying.active,
                     playing = nowPlaying.playing,
                     takeoverVisible = takeoverVisible,
                     dismissed = miniDismissed,
                     pausedSinceMs = pausedSinceMs,
                     nowMs = now,
                 )
                 // homeOverlayCaps sizes notifMaxHeightDp so the stack ends NOTIF_CLOCK_GAP above the
                 // clock block; the mini-player card is taller than the old single-line row -- 12+56+
                 // 8+36+10 ≈ 122dp of card content (12 top pad + 56 art/title row + 8 Column gap + 36
                 // transport row + 10 bottom pad) plus this Column's own 8dp spacedBy gap above the
                 // stack ⇒ 130 -- so shrink the stack's cap by that amount to keep the clock-clearance
                 // contract. Same coerceAtLeast(60) floor as before (keeps one notification row usable
                 // if a future tiny screen ever bottoms out the geometry floor).
                 val notifHeightCap =
                     if (showMiniPlayer) (caps.notifMaxHeightDp - 130).coerceAtLeast(60)
                     else caps.notifMaxHeightDp
                 AnimatedVisibility(
                     visible = notifications.isNotEmpty() || showMiniPlayer,
                     enter = fadeIn(tween(600)),
                     exit = fadeOut(tween(600)),
                     modifier = Modifier
                         .align(Alignment.TopStart)
                         .padding(start = 28.dp, top = 70.dp),
                 ) {
                     Column(
                         Modifier.widthIn(max = caps.notifMaxWidthDp.dp),
                         verticalArrangement = Arrangement.spacedBy(8.dp),
                     ) {
                         if (showMiniPlayer) {
                             MiniPlayerCard(
                                 title = nowPlaying.title?.takeIf { it.isNotBlank() } ?: "Now playing",
                                 artist = nowPlaying.artist,
                                 artThumb = art?.sharp,
                                 playing = nowPlaying.playing,
                                 onPrev = onMediaPrev,
                                 onPlayPause = { if (nowPlaying.playing) onMediaPause() else onMediaPlay() },
                                 onNext = onMediaNext,
                                 onStop = onMediaStop,
                                 onTap = onTakeoverRestore,
                                 onDismiss = { miniDismissed = true },
                             )
                         }
                         // Guarded so NotificationArea's "empty lists should not be rendered by the
                         // caller" contract holds when only the card is showing (no scroll container
                         // spun up for zero rows).
                         if (notifications.isNotEmpty()) {
                             NotificationArea(
                                 notifications = notifications,
                                 nowMs = now,
                                 onDismiss = onDismiss,
                                 modifier = Modifier
                                     .heightIn(max = notifHeightCap.dp)
                                     .clipToBounds(),
                             )
                         }
                     }
                 }
     ```
     (`Column`, `Arrangement`, `widthIn`, `heightIn`, `clipToBounds`, `Alignment`, `Modifier`, `padding`, `dp` are all already imported in `HomeView.kt`.)

- [ ] **Step 4: Implement `DashboardShell.kt`.** One edit: forward the shell's existing `onMediaStop` param to the `HomeView(...)` call in the `DashView.HOME` branch, immediately after `onMediaPause = onMediaPause,`:
  ```kotlin
                          onMediaPlay = onMediaPlay,
                          onMediaPause = onMediaPause,
                          onMediaStop = onMediaStop,
                          onMediaNext = onMediaNext,
                          onMediaPrev = onMediaPrev,
  ```
  (`onMediaStop: () -> Unit` is already a `DashboardShell` param — today it's consumed only by the `MediaPanel(...)` call in the `DashView.MEDIA` branch. No signature change needed here, just the extra forwarding line.)

- [ ] **Step 5: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of all three files + the new composables/state/threading) and the 1043-test suite green (Task 1's 8 new tests, none added here).

- [ ] **Step 6: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/NotificationArea.kt app/src/main/java/com/rar/echodash/ui/HomeView.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
  git commit -m "$(cat <<'EOF'
  feat(media): mini-player card replaces pinned now-playing row

  MiniPlayerCard (56dp art, title/artist two lines, back/play-pause/next/stop
  transport row, swipe-to-dismiss) replaces NowPlayingRow in the home
  notification stack. HomeView now tracks miniDismissed + pausedSinceMs
  (declared outside the takeover's if/else branch so the pause instant
  survives the takeover's own hand-off) and gates the card through
  miniPlayerVisible. The notification stack's height-cap shrink grows from 62
  to 130 for the taller card. onMediaStop threads DashboardShell -> HomeView
  for the card's Stop chip. UI-only (compile-gated).

  Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL
  EOF
  )"
  ```

---

## Self-Review

**1. Spec coverage:**
- Taller card, 56dp art, title + artist lines, back/play-pause/next/stop transport row — Task 2 `MiniPlayerCard`. ✓
- Visibility rules (`active && !takeoverVisible && !dismissed && (playing || within grace)`) — Task 1 `miniPlayerVisible`, consumed by Task 2. ✓
- `MINIPLAYER_GRACE_MS = 5 min` measured from the pause/stop moment, takeover's ~60s timeout hands off to the card for minutes 61-300 — Task 1 constant + docstring; Task 2's state-placement note explicitly protects this contract (`pausedSinceMs` stamped once, outside the branch that would otherwise reset it). ✓
- Swipe-to-dismiss exactly like a notification row, fire-and-forget, `dismissed` resets when `active` goes false — Task 2 `MiniPlayerCard`'s swipe wrapper (cloned from `NotificationRow`) + `miniDismissed`'s reset `LaunchedEffect`. ✓
- Stop button ends playback for real, drops `active`, both flags reset — Task 2 wires `onStop = onMediaStop` (the existing real stop transport); `active → false` already resets both `miniDismissed` (Task 2 Step 3.3) and `pausedSinceMs` (same effect) via their `LaunchedEffect`s. ✓
- `MiniPlayerCard` signature, chrome, layout (Row1 art+two lines/tap-to-restore, Row2 four 36dp chips, `MiniTransportButton` clone, swipe wrapper, chips consume their own taps) — Task 2, with the signature deviation (`title` not `label`) flagged and justified against the spec's own contradicting prose. ✓
- Label-change note (`nowPlayingRowLabel` stays, not deleted, not called by the card) — Task 2 leaves it untouched; Task 1 doesn't touch it either. ✓
- Threading: `onMediaStop` on HomeView, wired `onPrev`/`onNext`/`onPlayPause`/`onStop`/`onTap`/`onDismiss` — Task 2 Steps 3-4. ✓
- Clock cap: shrink becomes 130 (derivation comment), same `coerceAtLeast(60)` floor — Task 2 Step 3.4. ✓
- Degradation table (companion-source parity via source-agnostic state fields, no-art fallback, no-artist single line, real notifications pinned below + cap shrink) — covered by `miniPlayerVisible`'s pure gate (source-agnostic by construction — it only looks at `NowPlayingState` fields) + Task 2's layout/cap logic. ✓
- Out-of-scope items (volume/progress/seek on the card, distinguishing pause from stop, a config knob for the grace window) — none implemented, as intended; no task adds them. ✓
- Testing list (`miniPlayerVisible`: playing-true-per-flag, paused-within-grace, paused-at/after-grace, `pausedSinceMs=0 && !playing`, dismissed-regardless, inactive-regardless) — Task 1's 8 tests cover every bullet. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code, including the full "old" blocks being replaced so an out-of-order reader isn't left guessing what changes. ✓

**3. Type consistency:** `miniPlayerVisible(active, playing, takeoverVisible, dismissed, pausedSinceMs, nowMs): Boolean` and `MINIPLAYER_GRACE_MS: Long` match between Task 1 (definition + tests) and Task 2 (HomeView call). `MiniPlayerCard(title: String, artist: String?, artThumb: ImageBitmap?, playing: Boolean, onPrev/onPlayPause/onNext/onStop/onTap/onDismiss: () -> Unit)` matches between Task 2's `NotificationArea.kt` definition and its `HomeView.kt` call site. `onMediaStop: () -> Unit` matches across `DashboardShell` (pre-existing), the new `HomeView` param, and the forwarding call. ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Music playing, tap takeover Home → card (art, two lines, 4 chips) instead of the old row; body tap restores takeover.
2. Play/pause chip toggles playback and its own glyph; back/next skip; Stop ends the session and the card disappears.
3. Pause playback → takeover self-dismisses at ~60s → card shows; wait past 5 min → card auto-hides.
4. Stop from MA/another room ("switch speakers") → card gone within 5 min without touching the panel.
5. Swipe the card left → gone; start a new session → card eligibility returns.
6. Notification stack under the card scrolls within its shrunken cap; clock untouched.
