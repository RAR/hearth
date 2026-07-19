# Takeover Manual Dismiss (Home Button + Now-Playing Row) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user dismiss the now-playing takeover from a home button on the takeover itself — returning to the normal home dashboard without stopping playback — and give them a pinned now-playing row on the home screen that restores the takeover with one tap. Source of truth: `docs/superpowers/specs/2026-07-19-takeover-manual-dismiss-design.md`.

**Architecture:** A new App-level `manualDismissed` `remember` flag joins the existing `pausedTimedOut` flag; both feed a pure `takeoverVisibleOf(active, pausedTimedOut, manualDismissed)` fn that replaces the inlined `&&` chain. A takeover home button sets `manualDismissed`; session end (`active` -> false) clears it (dismiss is session-scoped, track changes never resurrect it). Re-entry is a pinned `NowPlayingRow` above the home notification stack, shown whenever music is active but the takeover isn't visible; its tap clears BOTH dismissal flags. Two new callbacks (`onTakeoverDismiss`, `onTakeoverRestore`) thread App -> DashboardShell -> HomeView (-> NowPlayingHome) as defaulted params, mirroring the existing `onBrowse` precedent.

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
- **NO new dependencies.** `material-icons-extended` (already a dependency) supplies `Icons.Outlined.Home` and `Icons.Outlined.MusicNote` (the latter is already imported and used in `NowPlayingHome.kt`).
- **Tests are plain-JVM JUnit4 only** — no Robolectric, no instrumentation, no Compose test harness. Match the existing idiom in `NotificationModelTest.kt` (`org.junit.Test`, `org.junit.Assert.*`).
- **Compose UI code is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`); the composables are verified only by the `:app:assembleDebug` compile inside the gate. UI-only tasks below explicitly have no unit test — do not invent fake Compose tests.
- **Do NOT push.** Commits stay local.
- **Current suite is 970 tests.** Task 1 adds 6 (final 976); Tasks 2 and 3 add none.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

## File Structure

- `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` — gains two pure fns: `takeoverVisibleOf` (App takeover-visibility gate) and `nowPlayingRowLabel` (pinned-row label). Both consumed by the home dashboard layer; homed together here (see Task 1's note).
- `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` — 6 new `@Test` methods pinning the two fns.
- `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` — new `onHome` param; the lone `TopEnd` browse chip becomes a two-chip `Row` (Browse + Home).
- `app/src/main/java/com/rar/echodash/App.kt` — `manualDismissed` flag + reset `LaunchedEffect`; `takeoverVisible` switches to `takeoverVisibleOf(...)`; `onTakeoverDismiss`/`onTakeoverRestore` callbacks passed to `DashboardShell`.
- `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — two new defaulted callback params, forwarded to `HomeView`.
- `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — two new defaulted callback params; `onHome = onTakeoverDismiss` on the `NowPlayingHome` call; the notification block gains the pinned `NowPlayingRow` in a width-capped `Column`.
- `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` — new public `NowPlayingRow` composable mirroring `NotificationRow`'s chrome.

---

## Task 1 — Pure model fns: `takeoverVisibleOf` + `nowPlayingRowLabel`

Two pure, plain-JVM helpers that back the takeover-visibility gate and the pinned-row label. Downstream tasks (App.kt gate, HomeView row) consume these, so they land first and fully tested.

**Home decision (flagged for review):** the spec routes `nowPlayingRowLabel` to `NotificationModel.kt`. `takeoverVisibleOf` is co-located there rather than in a new one-function file: both are tiny pure helpers for this single feature, both are consumed by the home dashboard layer, and `NotificationModel.kt` already hosts a mix of small home-overlay derivation helpers (severity mapping, timestamp labels, merge, auto-dismiss). This keeps the feature's model layer in one place with its existing test. (The alternative — a dedicated `ui/model/TakeoverModel.kt` — was considered; rejected to avoid a near-empty file and a second test file for one boolean fn.)

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` — append two top-level fns at the end of the file.
- Test: `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt` — append 6 `@Test` methods before the closing brace.

**Interfaces:**
- Consumes: nothing new (pure Kotlin).
- Produces:
  - `fun takeoverVisibleOf(active: Boolean, pausedTimedOut: Boolean, manualDismissed: Boolean): Boolean` — true iff `active && !pausedTimedOut && !manualDismissed`. Consumed by App.kt (Task 2).
  - `fun nowPlayingRowLabel(title: String?, artist: String?): String` — `"Title — Artist"`; artist omitted when null/blank; null/blank title falls back to `"Now playing"` (artist without a title still yields just `"Now playing"`). Same `" — "` (em-dash) join as the takeover up-next line. Consumed by HomeView (Task 3).

### Steps

- [ ] **Step 1: Write the failing tests.** Append these 6 methods to `app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt`, inside the class, immediately before its final closing brace. The file already imports `assertEquals`, `assertNull`, `assertTrue`, and `Test`; add `import org.junit.Assert.assertFalse` at the top with the other `org.junit.Assert.*` imports.

  ```kotlin
      // ---- takeoverVisibleOf ----

      @Test
      fun takeoverHiddenWheneverInactive() {
          // active=false -> always hidden, regardless of the two flags (4 combinations).
          assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = false, manualDismissed = false))
          assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = true, manualDismissed = false))
          assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = false, manualDismissed = true))
          assertFalse(takeoverVisibleOf(active = false, pausedTimedOut = true, manualDismissed = true))
      }

      @Test
      fun takeoverVisibleOnlyWhenActiveAndNeitherFlagSet() {
          assertTrue(takeoverVisibleOf(active = true, pausedTimedOut = false, manualDismissed = false))
      }

      @Test
      fun takeoverHiddenByEitherFlagWhileActive() {
          assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = true, manualDismissed = false))
          assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = false, manualDismissed = true))
          assertFalse(takeoverVisibleOf(active = true, pausedTimedOut = true, manualDismissed = true))
      }

      // ---- nowPlayingRowLabel ----

      @Test
      fun nowPlayingRowLabelJoinsTitleAndArtist() {
          assertEquals("Song — Artist", nowPlayingRowLabel("Song", "Artist"))
      }

      @Test
      fun nowPlayingRowLabelTitleOnlyWhenArtistAbsent() {
          assertEquals("Song", nowPlayingRowLabel("Song", null))
          assertEquals("Song", nowPlayingRowLabel("Song", "   ")) // blank artist treated as absent
      }

      @Test
      fun nowPlayingRowLabelFallsBackWhenTitleBlank() {
          assertEquals("Now playing", nowPlayingRowLabel(null, null))
          assertEquals("Now playing", nowPlayingRowLabel("   ", "Artist")) // blank title, artist ignored
          assertEquals("Now playing", nowPlayingRowLabel(null, "Artist"))  // artist without title
      }
  ```

- [ ] **Step 2: Run tests to verify they fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.NotificationModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `unresolved reference: takeoverVisibleOf` / `nowPlayingRowLabel`. RC != 0.

- [ ] **Step 3: Write the minimal implementation.** Append these two fns to the END of `app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt` (after `autoDismissKeys`), matching the file's KDoc density:

  ```kotlin
  /**
   * Whether the home now-playing takeover should be showing: only while a session is [active] and
   * neither the paused-timeout dismissal ([pausedTimedOut]) nor the user's manual home-button
   * dismissal ([manualDismissed]) is in effect. Pure so App.kt can pin the gate instead of inlining
   * the `&&` chain.
   */
  fun takeoverVisibleOf(active: Boolean, pausedTimedOut: Boolean, manualDismissed: Boolean): Boolean =
      active && !pausedTimedOut && !manualDismissed

  /**
   * Label for the pinned now-playing row: "Title — Artist", with the artist (and its " — ") dropped
   * when null/blank. A null/blank title falls back to "Now playing" (pre-metadata streams), and an
   * artist with no title still yields just "Now playing". Same em-dash join as the takeover up-next
   * line.
   */
  fun nowPlayingRowLabel(title: String?, artist: String?): String {
      val t = title?.takeIf { it.isNotBlank() } ?: return "Now playing"
      val a = artist?.takeIf { it.isNotBlank() }
      return if (a != null) "$t — $a" else t
  }
  ```

- [ ] **Step 4: Run tests to verify they pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.NotificationModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all existing + 6 new tests green (suite now 976).

- [ ] **Step 5: Gate.** Run the full gate from Global Constraints (both `GATE RC=0` and `NODE RC=0`).

- [ ] **Step 6: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/NotificationModel.kt app/src/test/java/com/rar/echodash/ui/model/NotificationModelTest.kt
  git commit -m "feat(media): takeoverVisibleOf + nowPlayingRowLabel pure fns

takeoverVisibleOf gates the home takeover on active + the paused-timeout and
new manual-dismiss flags; nowPlayingRowLabel formats the pinned now-playing
row (Title — Artist, 'Now playing' fallback). Pure + JVM-tested (6 tests).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 2 — Takeover home button + manual-dismiss flag (NowPlayingHome + App + DashboardShell + HomeView threading)

Add the `manualDismissed` session flag and its reset, switch `takeoverVisible` to `takeoverVisibleOf(...)`, add the takeover home button (a second `TopEnd` chip), and thread the two new callbacks App -> DashboardShell -> HomeView -> NowPlayingHome. **UI/wiring task: no unit test** — the gate logic is covered by Task 1's `takeoverVisibleOf` tests; the composables and callback plumbing are verified only by the `:app:assembleDebug` compile.

**Ordering note:** this task threads `onTakeoverRestore` all the way to a `HomeView` param, but that param is not consumed until Task 3 (the `NowPlayingRow` tap). An unused *public function parameter* is not flagged by the Kotlin compiler (unlike an unused local), so the gate stays green. `onTakeoverDismiss` IS consumed here (the home button). This keeps all App/DashboardShell threading in one task; Task 3 only adds the row.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` — add `import androidx.compose.material.icons.outlined.Home`; add `onHome` param (~after `onBrowse`, line 81); replace the lone `TopEnd` browse `Box` (lines 98-106) with a two-chip `Row`.
- Modify: `app/src/main/java/com/rar/echodash/App.kt` — add `import com.rar.echodash.ui.model.takeoverVisibleOf`; add the `manualDismissed` flag + reset `LaunchedEffect` and switch `takeoverVisible` (lines 751-760); add `onTakeoverDismiss`/`onTakeoverRestore` to the `DashboardShell(...)` call (after `onBrowse`, ~line 939).
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — add two defaulted params (after `onBrowse`, line 102); forward them in the `HomeView(...)` call (after `onBrowse = onBrowse,`, ~line 303).
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — add two defaulted params (after `onBrowse`, line 203); add `onHome = onTakeoverDismiss` to the `NowPlayingHome(...)` call (~line 259).

**Interfaces:**
- Consumes: `com.rar.echodash.ui.model.takeoverVisibleOf` (Task 1).
- Produces (`NowPlayingHome` new param): `onHome: () -> Unit = {}`.
- Produces (`DashboardShell` + `HomeView` new params, forwarded down): `onTakeoverDismiss: () -> Unit = {}`, `onTakeoverRestore: () -> Unit = {}`. (`onTakeoverRestore` is consumed by Task 3's `NowPlayingRow` in HomeView.)

### Steps

- [ ] **Step 1: Read** the current `NowPlayingHome.kt` `TopEnd` block (lines 98-106) and param list (lines 72-86), the `App.kt` `pausedTimedOut` block (lines 751-760) and `DashboardShell(...)` call (the `onBrowse` block ~936-939), the `DashboardShell` param list (~lines 78-118) and its `HomeView(...)` call (~271-311), and the `HomeView` param list (~173-211) and its `NowPlayingHome(...)` call (~250-264). Confirm the anchors below still match.

- [ ] **Step 2: Implement `NowPlayingHome.kt`.** Three edits.

  1. Add the `Home` icon import next to the other `androidx.compose.material.icons.outlined.*` imports (they sit alphabetically around lines 26-33: `MusicNote`, `Pause`, `PlayArrow`, `Repeat`, ...). Insert:
  ```kotlin
  import androidx.compose.material.icons.outlined.Home
  ```

  2. Add the `onHome` param to `NowPlayingHome`, immediately after `onBrowse: () -> Unit = {},` (line 81):
  ```kotlin
      onBrowse: () -> Unit = {},
      onHome: () -> Unit = {},
  ```

  3. Replace the lone browse `Box` (lines 98-106):
  ```kotlin
          // Browse the MA library (jumps to the MEDIA view). TopEnd: TopStart holds the compact
          // clock HomeView draws above this layer. 48 dp (not the transport 64) so it clears the
          // art card's top edge on the smallest canvas (787×394, art at its height-limited 360dp);
          // taller screens only add clearance.
          Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp)) {
              NpTransportButton(Icons.AutoMirrored.Outlined.QueueMusic, size = 48.dp, iconSize = 24.dp) {
                  onBrowse()
              }
          }
  ```
  with a two-chip `Row` (Browse first/left, Home in the outermost corner):
  ```kotlin
          // Top-right chips over the takeover: Browse (jumps to the MA library / MEDIA view) and
          // Home (dismisses the takeover for the session — music keeps playing — returning to the
          // dashboard). TopStart holds the compact clock HomeView draws above this layer. Home sits
          // in the outermost corner (exit lives in the corner); Browse is to its left. Both 48 dp
          // (not the transport 64) so they clear the art card's top edge on the smallest canvas
          // (787×394, art at its height-limited 360dp); the row grows leftward along the empty top edge.
          Row(
              Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
              NpTransportButton(Icons.AutoMirrored.Outlined.QueueMusic, size = 48.dp, iconSize = 24.dp) {
                  onBrowse()
              }
              NpTransportButton(Icons.Outlined.Home, size = 48.dp, iconSize = 24.dp) {
                  onHome()
              }
          }
  ```
  (`Row`, `Arrangement`, `Alignment`, `Modifier`, `padding`, `dp` are all already imported in this file.)

- [ ] **Step 3: Implement `App.kt`.** Three edits.

  1. Add the import alphabetically among the `com.rar.echodash.ui.model.*` imports (after `quickButtonService`, line 62):
  ```kotlin
  import com.rar.echodash.ui.model.takeoverVisibleOf
  ```

  2. Replace the `takeoverVisible` derivation. The current block ends (line 760):
  ```kotlin
                      val takeoverVisible = nowPlayingState.active && !pausedTimedOut
  ```
  Insert the `manualDismissed` flag + reset effect just above it and switch to the pure fn, so lines 751-760 read:
  ```kotlin
                      var pausedTimedOut by remember { mutableStateOf(false) }
                      LaunchedEffect(nowPlayingState.active, nowPlayingState.playing, config.media.pausedDismissSeconds) {
                          if (nowPlayingState.active && !nowPlayingState.playing) {
                              delay(config.media.pausedDismissSeconds * 1000L)
                              pausedTimedOut = true
                          } else {
                              pausedTimedOut = false
                          }
                      }
                      // The user can dismiss the takeover from its home button; the dismissal sticks
                      // for the whole listening session (track changes never resurrect it). Session
                      // end (active -> false) clears it so the NEXT session takes over again.
                      var manualDismissed by remember { mutableStateOf(false) }
                      LaunchedEffect(nowPlayingState.active) {
                          if (!nowPlayingState.active) manualDismissed = false
                      }
                      val takeoverVisible = takeoverVisibleOf(nowPlayingState.active, pausedTimedOut, manualDismissed)
  ```

  3. Add the two callbacks to the `DashboardShell(...)` call, immediately after the `onBrowse = { ... }` block (which currently ends at line 939 with `},`):
  ```kotlin
                          onBrowse = {
                              deps.currentView.value = DashView.MEDIA
                              deps.kiosk.onUserInteraction()
                          },
                          // Takeover home button hides the takeover for the rest of the session
                          // (music keeps playing); the pinned now-playing row's tap restores it.
                          // Restore also clears the paused-timeout dismissal, so the row is the way
                          // back from that path too (which otherwise has no on-device re-entry).
                          onTakeoverDismiss = { manualDismissed = true },
                          onTakeoverRestore = { manualDismissed = false; pausedTimedOut = false },
  ```
  (`manualDismissed` and `pausedTimedOut` are `by remember` vars in this same composable scope, declared above at lines 751/762 — assigning to them from these lambdas triggers recomposition.)

- [ ] **Step 4: Implement `DashboardShell.kt`.** Two edits.

  1. Add the two params after `onBrowse: () -> Unit,` (line 102):
  ```kotlin
      onBrowse: () -> Unit,
      onTakeoverDismiss: () -> Unit = {},
      onTakeoverRestore: () -> Unit = {},
  ```

  2. Forward them in the `HomeView(...)` call, immediately after `onBrowse = onBrowse,` (line 303):
  ```kotlin
                        onBrowse = onBrowse,
                        onTakeoverDismiss = onTakeoverDismiss,
                        onTakeoverRestore = onTakeoverRestore,
  ```

- [ ] **Step 5: Implement `HomeView.kt`.** Two edits.

  1. Add the two params after `onBrowse: () -> Unit = {},` (line 203):
  ```kotlin
      onBrowse: () -> Unit = {},
      onTakeoverDismiss: () -> Unit = {},
      onTakeoverRestore: () -> Unit = {},
  ```

  2. Add `onHome = onTakeoverDismiss,` to the `NowPlayingHome(...)` call inside the `Crossfade`, immediately after `onBrowse = onBrowse,` (line 259):
  ```kotlin
                      onBrowse = onBrowse,
                      onHome = onTakeoverDismiss,
  ```
  (`onTakeoverRestore` is now an accepted HomeView param but is not consumed until Task 3 — see the Ordering note. No compiler warning results from an unused public param.)

- [ ] **Step 6: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of all four files + the takeover-button wiring) and the 976-test suite green.

- [ ] **Step 7: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt app/src/main/java/com/rar/echodash/App.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt app/src/main/java/com/rar/echodash/ui/HomeView.kt
  git commit -m "feat(media): takeover home button + session-scoped manual dismiss

NowPlayingHome's lone browse chip becomes a Browse+Home two-chip row; Home
sets an App-level manualDismissed flag (session end clears it) so the takeover
hides while playback continues. takeoverVisible now routes through the pure
takeoverVisibleOf. onTakeoverDismiss/onTakeoverRestore thread App -> Dashboard
Shell -> HomeView (restore wired in the next task's now-playing row). UI/wiring
(compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 3 — Pinned `NowPlayingRow` (NotificationArea) + HomeView integration

Add the public `NowPlayingRow` composable and place it above the home notification stack, shown whenever music is active but the takeover isn't visible; its tap fires `onTakeoverRestore`. **UI-only task: no unit test** — the label is covered by Task 1's `nowPlayingRowLabel` tests; the composable and its placement are verified only by the `:app:assembleDebug` compile.

**Art type (flagged for review):** the spec's signature reads `NowPlayingRow(label: String, artThumb: Bitmap?, onTap)`, but `ArtBitmaps.sharp`/`.blurred` are `androidx.compose.ui.graphics.ImageBitmap`, not `android.graphics.Bitmap` (see `media/ArtFetcher.kt:18`). So `artThumb` is typed `ImageBitmap?` and rendered via the `Image(bitmap: ImageBitmap, ...)` overload — exactly what `NowPlayingHome.kt` already does with `art.sharp`. HomeView passes `art?.sharp` with zero new decoding.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/NotificationArea.kt` — add imports; add the public `NowPlayingRow` composable (place after `NotificationArea`, before the private `NotificationRow`, ~line 89).
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — add `import com.rar.echodash.ui.model.nowPlayingRowLabel`; replace the notification `AnimatedVisibility` block (lines 385-402) with the widened gate + width-capped `Column` holding `NowPlayingRow` above `NotificationArea`.

**Interfaces:**
- Consumes: `com.rar.echodash.ui.model.nowPlayingRowLabel` (Task 1); `HomeView`'s existing `nowPlaying: NowPlayingState`, `art: ArtBitmaps?` (`art?.sharp: ImageBitmap?`), `takeoverVisible: Boolean`, and `onTakeoverRestore` (Task 2) params.
- Produces (`NotificationArea.kt` new public composable): `fun NowPlayingRow(label: String, artThumb: ImageBitmap?, onTap: () -> Unit)`.

### Steps

- [ ] **Step 1: Read** `NotificationArea.kt` in full (already reviewed: `NotificationRow`'s `RoundedCornerShape(14.dp)` on `Color.Black.copy(alpha = 0.35f)`, 18sp white `FontWeight.Medium` title) and the `HomeView.kt` notification `AnimatedVisibility` block (lines 385-402), the `caps` values (`caps.notifMaxWidthDp` / `caps.notifMaxHeightDp`, line 246), and confirm `nowPlaying`/`art`/`takeoverVisible`/`onTakeoverRestore` are all in scope there.

- [ ] **Step 2: Implement `NotificationArea.kt`.** Two edits.

  1. Add these imports alongside the existing ones (the file already imports `background`, `clickable`, `Arrangement`, `Box`, `Row`, `fillMaxWidth`, `padding`, `RoundedCornerShape`, `Text`, `clip`, `Color`, `Alignment`, `FontWeight`, `TextOverflow`, `dp`, `sp`):
  ```kotlin
  import androidx.compose.foundation.Image
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.foundation.layout.size
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.outlined.MusicNote
  import androidx.compose.material3.Icon
  import androidx.compose.ui.graphics.ImageBitmap
  import androidx.compose.ui.layout.ContentScale
  ```

  2. Add the public `NowPlayingRow` composable immediately after the `NotificationArea` composable's closing brace (line 88), before the private `NotificationRow`:
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

- [ ] **Step 3: Implement `HomeView.kt`.** Two edits.

  1. Add the import alongside the other `com.rar.echodash.ui.model.*` imports (after `nextEventCard`, line 107):
  ```kotlin
  import com.rar.echodash.ui.model.nowPlayingRowLabel
  ```

  2. Replace the entire notification `AnimatedVisibility` block (lines 385-402):
  ```kotlin
              AnimatedVisibility(
                  visible = notifications.isNotEmpty(),
                  enter = fadeIn(tween(600)),
                  exit = fadeOut(tween(600)),
                  modifier = Modifier
                      .align(Alignment.TopStart)
                      .padding(start = 28.dp, top = 70.dp),
              ) {
                  NotificationArea(
                      notifications = notifications,
                      nowMs = now,
                      onDismiss = onDismiss,
                      modifier = Modifier
                          .widthIn(max = caps.notifMaxWidthDp.dp)
                          .heightIn(max = caps.notifMaxHeightDp.dp)
                          .clipToBounds(),
                  )
              }
  ```
  with the widened gate + width-capped `Column`:
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
  (`Column`, `Arrangement`, `widthIn`, `heightIn`, `clipToBounds`, `Alignment`, `Modifier`, `padding`, `dp` are all already imported in `HomeView.kt`.)

- [ ] **Step 4: Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (compile of `NowPlayingRow` + the HomeView Column) and the 976-test suite green.

- [ ] **Step 5: Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/NotificationArea.kt app/src/main/java/com/rar/echodash/ui/HomeView.kt
  git commit -m "feat(media): pinned now-playing row restores dismissed takeover

NowPlayingRow mirrors the notification-row chrome (34dp art thumb / MusicNote
fallback + one tappable line) and pins above the notification stack whenever
music is active but the takeover is hidden — covering both the manual home
button and the existing paused-timeout dismissal (previously no on-device
re-entry). Tap clears both dismissal flags. UI-only (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Self-Review

**1. Spec coverage:**
- Session-scoped dismiss that keeps playback + resets on session end — Task 2 (`manualDismissed` flag + reset `LaunchedEffect`, `takeoverVisibleOf`). ✓
- Takeover home button, `Icons.Outlined.Home`, 48dp, outermost corner, Browse left, `spacedBy(12.dp)`, both 48dp `NpTransportButton` — Task 2. ✓
- `onTakeoverRestore` clears `manualDismissed` AND `pausedTimedOut` (row is the way back from the paused-timeout path too) — Task 2 App callback. ✓
- Pinned `NowPlayingRow` above the notification stack, `nowPlaying.active && !takeoverVisible`, covers both dismissal paths — Task 3. ✓
- `NowPlayingRow` visual language (14dp corners, Black-0.35, 34dp art thumb w/ 8dp clip + Crop, MusicNote fallback at white-0.5, one 18sp white medium line, ellipsis, whole-row clickable, no swipe/timestamp/accent) — Task 3. ✓
- HomeView gate widens to `notifications.isNotEmpty() || showNowPlayingRow`; width cap on Column, height cap + clipToBounds on inner `NotificationArea` — Task 3. ✓
- Clock protection (adaptive-sizing golden rule): the row adds 62dp above the stack, so the stack's height cap shrinks by 62 while the row shows — the combined block still ends `NOTIF_CLOCK_GAP` above the clock (lead-review addition; the spec's "the notif height cap already protects the clock" claim was only true without the row). ✓
- `takeoverVisibleOf` (8 combinations pinned) + `nowPlayingRowLabel` (join / title-only / blank-artist / blank-title fallback) — Task 1. ✓
- Degradation (source-agnostic, no-art MusicNote, no-title "Now playing", session-end reset, paused-timeout re-entry, real notifications above/non-scrolling) — covered by the fns + Task 3 layout. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step shows complete code. ✓

**3. Type consistency:** `onHome`/`onTakeoverDismiss`/`onTakeoverRestore` are `() -> Unit` everywhere; `takeoverVisibleOf(active, pausedTimedOut, manualDismissed): Boolean` and `nowPlayingRowLabel(title, artist): String` match between Task 1 (definition), Task 2 (App call), and Task 3 (HomeView call); `NowPlayingRow(label: String, artThumb: ImageBitmap?, onTap: () -> Unit)` matches between Task 3's definition and its HomeView call (`art?.sharp` is `ImageBitmap?`). ✓

---

## Live-verify checklist (implementation end — not a task; run on-device)

Reproduced verbatim from the design spec:

1. Music playing → tap home button → dashboard returns, audio continues.
2. Track advances while dismissed → takeover stays away; row label updates.
3. Tap row → takeover returns with correct state.
4. Stop playback (or let queue end) while dismissed, start new music → takeover asserts.
5. Pause → wait pausedDismissSeconds → row appears → tap → takeover returns paused.
6. Companion source: same button/row behavior.
