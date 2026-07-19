# Shuffle/Repeat Toggles + Up-Next Line Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add shuffle + repeat toggles (takeover now-playing group AND the MusicBrowser queue-pane header) and a single dimmed "Up next: Title — Artist" line on the takeover, for the SendSpin / Music Assistant source only. Both features degrade to *absent* for the companion `media_player` source. Source of truth: `docs/superpowers/specs/2026-07-19-shuffle-repeat-upnext-design.md`.

**Architecture:** Toggle state rides the existing SendSpin controller-state push — `SendspinEndpoint` collects `engine.controllerState` (`repeat`/`shuffle`/`supportedCommands`) and folds four new fields (`repeatMode`, `shuffle`, `canRepeat`, `canShuffle`) into `NowPlayingState` via the established `onSendspin(...)` merge, so the takeover reads them straight off the state it already collects. Toggle taps route through new `SendspinEndpoint.transportSetRepeat/transportSetShuffle` wrappers (engine `setRepeatMode`/`setShuffle`, no new protocol) wired as sendspin-branch-only App callbacks. The up-next line is owned separately by `DashboardShell`, which polls `MaLibrary.queue()` while the takeover is up and passes `upNext: MaQueueItem?` down as a parameter; tapping it bumps a signal counter that opens the queue pane after switching to the MEDIA view.

**Tech Stack:** Kotlin + Jetpack Compose (native Android kiosk). Pure model logic in `com.rar.echodash.ui.model` (plain-JVM JUnit4 tested). Vendored SendSpin engine + `MaLibrary` MA-API socket. Gradle (`:app`), JDK 21 (Amazon Corretto).

## Global Constraints

- **Gate before EVERY commit** — run both, each must show RC=0 before committing:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate.log 2>&1; RC=$?; echo "GATE RC=$RC"
  node --check app/src/main/assets/config/app.js; echo "NODE RC=$?"
  ```
  Check gradle's OWN exit code via `RC=$?` captured immediately after the gradle invocation. NEVER pipe gradle to `tail` (or any filter) — that masks gradle's exit code behind the filter's. Redirect all gradle output to the scratchpad log and inspect the log if RC is non-zero. `node --check` guards the config bundle; no task in this plan edits `app.js`, so it always passes here, but it remains part of the gate.
- **Commit trailer** — every commit message ends with exactly this trailer line:
  `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- **NO new dependencies.** `material-icons-extended` (already a dependency) supplies `Icons.Outlined.Shuffle`, `Icons.Outlined.Repeat`, `Icons.Outlined.RepeatOne`. Verified present in the resolved `material-icons-extended-android:1.7.6` artifact.
- **Tests are plain-JVM JUnit4 only** — no Robolectric, no instrumentation, no Compose test harness. Match the existing idiom in `NowPlayingSendspinTest.kt` / `QuickButtonsModelTest.kt` (`org.junit.Test`, `org.junit.Assert.*`).
- **Compose UI code is NOT unit-tested in this repo.** Testable logic lives in pure model functions (`ui/model/*.kt`); the Compose composables are verified only by the `:app:assembleDebug` compile inside the gate. UI-only tasks below explicitly have no unit test — do not invent fake Compose tests.
- **`NowPlayingState` hand-written `equals`/`hashCode` MUST include every new field.** `NowPlayingState` overrides both because of its `ByteArray` art field, and `StateFlow` dedups on equality — a field omitted from `equals`/`hashCode` means a repeat/shuffle change is silently swallowed and the toggle never lights (the exact bug class the `muted`-field comment warns about). Add all four new fields to both overrides.
- **Companion source shows neither toggles nor up-next.** Every toggle gates on `state.sendspin` AND its per-field non-null state AND its `can*` command gate; the up-next line gates on `state.sendspin` AND a known next item. The companion branch never sets these fields (they default to null/false), so the companion takeover is byte-unchanged.
- **Toggle visibility gates per spec:** shuffle visible iff `state.sendspin && state.shuffle != null && state.canShuffle`; repeat visible iff `state.sendspin && state.repeatMode != null && state.canRepeat`. Each gates independently.
- Scratchpad for logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/` (create with `mkdir -p` if absent).

---

## Task 1 — QueueModel.kt pure functions + QueueModelTest

Pure, plain-JVM UI-facing helpers for the repeat cycle and the up-next derivation. Downstream (App.kt cycle wiring, DashboardShell up-next poll) consumes these, so they land first and fully tested. (The toggle-visibility gate is NOT here — it lives in the `sendspin` package next to the endpoint that consumes it; see Task 3.)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt`
- Test: `app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt`

**Interfaces:**
- Consumes: `com.rar.echodash.sendspin.musicassistant.MaQueueItem` (fields `queueItemId: String`, `name: String`, `artist: String?`, `album: String?`, `imageUri: String?`, `duration: Long?`, `uri: String?`, `isCurrentItem: Boolean`); `com.rar.echodash.sendspin.musicassistant.MaQueueState` (fields `items: List<MaQueueItem>`, `currentIndex: Int`, `shuffleEnabled: Boolean`, `repeatMode: String`).
- Produces:
  - `fun nextRepeatMode(cur: String?): String` — `null`/`"off"`/unknown → `"all"`, `"all"` → `"one"`, `"one"` → `"off"`.
  - `fun upNextOf(q: MaQueueState): MaQueueItem?` — item after the one flagged `isCurrentItem`; `null` when no flag, current is last, or empty.

### Steps

- [ ] **Write the failing test.** Create `app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt`:
  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaQueueItem
  import com.rar.echodash.sendspin.musicassistant.MaQueueState
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test

  class QueueModelTest {

      /** A queue item with just the fields these helpers read; the rest are inert placeholders. */
      private fun item(name: String, current: Boolean, artist: String? = null): MaQueueItem =
          MaQueueItem(
              queueItemId = name, name = name, artist = artist, album = null,
              imageUri = null, duration = null, uri = null, isCurrentItem = current,
          )

      private fun queue(vararg items: MaQueueItem): MaQueueState =
          MaQueueState(items = items.toList(), currentIndex = 0, shuffleEnabled = false, repeatMode = "off")

      // ---- nextRepeatMode ----

      @Test
      fun nextRepeatModeCyclesOffAllOneOff() {
          assertEquals("all", nextRepeatMode(null))
          assertEquals("all", nextRepeatMode("off"))
          assertEquals("one", nextRepeatMode("all"))
          assertEquals("off", nextRepeatMode("one"))
          assertEquals("all", nextRepeatMode("garbage")) // unrecognized restarts the cycle
      }

      // ---- upNextOf ----

      @Test
      fun upNextReturnsItemAfterCurrent() {
          val q = queue(item("A", current = false), item("B", current = true), item("C", current = false))
          assertEquals("C", upNextOf(q)?.name)
      }

      @Test
      fun upNextNullWhenCurrentIsLast() {
          val q = queue(item("A", current = false), item("B", current = true))
          assertNull(upNextOf(q))
      }

      @Test
      fun upNextNullWhenNoCurrentFlag() {
          val q = queue(item("A", current = false), item("B", current = false))
          assertNull(upNextOf(q))
      }

      @Test
      fun upNextNullWhenEmpty() {
          assertNull(upNextOf(queue()))
      }
  }
  ```

- [ ] **Run to see it fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.QueueModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `unresolved reference: nextRepeatMode` / `upNextOf` (QueueModel.kt does not exist yet). RC != 0.

- [ ] **Minimal implementation.** Read the neighbor `QuickButtonsModel.kt` first to match the comment density and style (KDoc on each public fn, terse rationale). Create `app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt`:
  ```kotlin
  package com.rar.echodash.ui.model

  import com.rar.echodash.sendspin.musicassistant.MaQueueItem
  import com.rar.echodash.sendspin.musicassistant.MaQueueState

  /**
   * Next repeat mode in the takeover's cycle: off -> all -> one -> off. A null or unrecognized
   * current value restarts at "all" (the engine's optimistic default), so a first tap always turns
   * repeat on rather than no-opping.
   */
  fun nextRepeatMode(cur: String?): String = when (cur) {
      "all" -> "one"
      "one" -> "off"
      else -> "all" // null, "off", or any unknown value
  }

  /**
   * The queue item that plays after the current one, or null when the queue can't answer
   * "what's next": no item flagged [MaQueueItem.isCurrentItem], the current item is last, or the
   * list is empty. (With a 200-item page a current index past the page end also yields null --
   * acceptable, matching the queue pane's existing page behavior.)
   */
  fun upNextOf(q: MaQueueState): MaQueueItem? {
      val idx = q.items.indexOfFirst { it.isCurrentItem }
      if (idx < 0 || idx >= q.items.lastIndex) return null
      return q.items[idx + 1]
  }
  ```

- [ ] **Run to see it pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.ui.model.QueueModelTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all 5 tests green.

- [ ] **Gate.** Run the full gate from Global Constraints (both `GATE RC=0` and `NODE RC=0`).

- [ ] **Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/model/QueueModel.kt app/src/test/java/com/rar/echodash/ui/model/QueueModelTest.kt
  git commit -m "feat(media): QueueModel pure fns for repeat cycle + up-next

nextRepeatMode/upNextOf back the takeover repeat cycle and the up-next line.
Pure + JVM-tested (5 tests). (The toggle-visibility gate lives in the sendspin
package next to its consumer -- see the SendspinEndpoint task.)

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 2 — NowPlayingState new fields + onSendspin params + store tests

Extend the read-side state with the four toggle fields, wire them through `onSendspin(...)` and `recompute()`, and — critically — add them to the hand-written `equals`/`hashCode`. Depends on nothing from Task 1 (store is independent) but is ordered here so the endpoint (Task 3) has the fields to publish into.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt` — `NowPlayingState` data class (~lines 13-35), `equals` (~51-60), `hashCode` (~62-79), backing fields (~118-127), `onSendspin(...)` signature (~152-171), `recompute()` SendSpin branch (~175-189).
- Test: `app/src/test/java/com/rar/echodash/media/NowPlayingSendspinTest.kt` — append new `@Test` methods.

**Interfaces:**
- Consumes: nothing new (pure Kotlin/`kotlinx`).
- Produces (new `NowPlayingState` fields, all defaulting to "unknown"): `val repeatMode: String? = null`, `val shuffle: Boolean? = null`, `val canRepeat: Boolean = false`, `val canShuffle: Boolean = false`.
- Produces (extended `onSendspin` signature, four new trailing params with defaults so every existing `onSendspin(false, ...)` deactivation call stays valid): `..., repeatMode: String? = null, shuffle: Boolean? = null, canRepeat: Boolean = false, canShuffle: Boolean = false`.

### Steps

- [ ] **Write the failing test.** Append to `app/src/test/java/com/rar/echodash/media/NowPlayingSendspinTest.kt` (inside the existing class, before the closing brace). Note the file already imports `assertEquals`/`assertFalse`/`assertTrue`/`Test`; add `import org.junit.Assert.assertNotEquals` and `import org.junit.Assert.assertNull` at the top:
  ```kotlin
      @Test fun sendspinRepeatShuffleGatesFlowIntoState() {
          val store = NowPlayingStore()
          // Omitted new params default to unknown/false (companion + legacy deactivation calls).
          store.onSendspin(true, true, "Song", "Artist", "Album", null, 55)
          store.state.value.let {
              assertNull(it.repeatMode); assertNull(it.shuffle)
              assertFalse(it.canRepeat); assertFalse(it.canShuffle)
          }
          store.onSendspin(true, true, "Song", "Artist", "Album", null, 55,
              repeatMode = "one", shuffle = true, canRepeat = true, canShuffle = true)
          store.state.value.let {
              assertEquals("one", it.repeatMode); assertEquals(true, it.shuffle)
              assertTrue(it.canRepeat); assertTrue(it.canShuffle)
          }
      }

      @Test fun deactivationDefaultsClearRepeatShuffleGates() {
          val store = NowPlayingStore()
          store.onSendspin(true, true, "Song", "Artist", "Album", null, 55,
              repeatMode = "all", shuffle = true, canRepeat = true, canShuffle = true)
          // The existing deactivation call passes none of the new params -> defaults clear them.
          store.onSendspin(false, false, null, null, null, null, 55)
          store.state.value.let {
              assertFalse(it.active)
              assertNull(it.repeatMode); assertNull(it.shuffle)
              assertFalse(it.canRepeat); assertFalse(it.canShuffle)
          }
      }

      @Test fun equalsAndHashCodeDependOnRepeatMode() {
          val base = NowPlayingState(sendspin = true, repeatMode = "off")
          val diff = base.copy(repeatMode = "all")
          assertNotEquals(base, diff)
          assertNotEquals(base.hashCode(), diff.hashCode())
      }

      @Test fun equalsAndHashCodeDependOnShuffle() {
          val base = NowPlayingState(sendspin = true, shuffle = false)
          val diff = base.copy(shuffle = true)
          assertNotEquals(base, diff)
          assertNotEquals(base.hashCode(), diff.hashCode())
      }

      @Test fun equalsAndHashCodeDependOnCanRepeat() {
          val base = NowPlayingState(sendspin = true, canRepeat = false)
          val diff = base.copy(canRepeat = true)
          assertNotEquals(base, diff)
          assertNotEquals(base.hashCode(), diff.hashCode())
      }

      @Test fun equalsAndHashCodeDependOnCanShuffle() {
          val base = NowPlayingState(sendspin = true, canShuffle = false)
          val diff = base.copy(canShuffle = true)
          assertNotEquals(base, diff)
          assertNotEquals(base.hashCode(), diff.hashCode())
      }
  ```

- [ ] **Run to see it fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.media.NowPlayingSendspinTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `no value passed for parameter` / `unresolved reference: repeatMode` (fields and params don't exist yet). RC != 0.

- [ ] **Minimal implementation.** Read the current `NowPlayingStore.kt` overrides first to preserve exact comment style. Make four edits:

  1. Add the four fields to the `NowPlayingState` data class after `canSeek` (~line 34), matching the existing per-field KDoc density:
  ```kotlin
      /** Seek is offered (companion media_player with the SEEK feature + a known duration only). */
      val canSeek: Boolean = false,
      /** Group repeat mode from SendSpin controller state: "off"|"one"|"all"; null = unknown. */
      val repeatMode: String? = null,
      /** Group shuffle from SendSpin controller state; null = unknown. */
      val shuffle: Boolean? = null,
      /** Repeat/shuffle commands the server advertises (gate the toggles' visibility). */
      val canRepeat: Boolean = false,
      val canShuffle: Boolean = false,
  ```

  2. Extend `equals` — add the four comparisons to the returned boolean chain (place before the `localArt` content compare so the ByteArray compare stays last):
  ```kotlin
              positionAtMs == other.positionAtMs && canSeek == other.canSeek &&
              repeatMode == other.repeatMode && shuffle == other.shuffle &&
              canRepeat == other.canRepeat && canShuffle == other.canShuffle &&
              (localArt?.contentEquals(other.localArt ?: ByteArray(0)) ?: (other.localArt == null))
  ```

  3. Extend `hashCode` — add four accumulate lines after the `canSeek` line, before `return r`:
  ```kotlin
          r = 31 * r + canSeek.hashCode()
          r = 31 * r + (repeatMode?.hashCode() ?: 0)
          r = 31 * r + (shuffle?.hashCode() ?: 0)
          r = 31 * r + canRepeat.hashCode()
          r = 31 * r + canShuffle.hashCode()
          return r
  ```

  4. Add backing fields next to the other `sendspin*` vars (~line 127), extend `onSendspin` params, set them, and populate them in `recompute()`:
  ```kotlin
      private var sendspinDurationMs = 0L
      private var sendspinPositionMs = 0L
      private var sendspinPositionAtMs = 0L
      private var sendspinRepeatMode: String? = null
      private var sendspinShuffle: Boolean? = null
      private var sendspinCanRepeat = false
      private var sendspinCanShuffle = false
  ```
  `onSendspin` signature — add the four params after `positionAtMs` (defaults keep every deactivation call site valid):
  ```kotlin
      @Synchronized
      fun onSendspin(active: Boolean, playing: Boolean, title: String?, artist: String?,
                     album: String?, artworkData: ByteArray?, volume: Int,
                     muted: Boolean = false,
                     durationMs: Long = 0, positionMs: Long = 0, positionAtMs: Long = 0,
                     // Toggle state from the SendSpin controller push. Defaults keep every existing
                     // deactivation call (onSendspin(false, ...)) clearing them to unknown/hidden.
                     repeatMode: String? = null, shuffle: Boolean? = null,
                     canRepeat: Boolean = false, canShuffle: Boolean = false) {
  ```
  In the body, after `sendspinPositionAtMs = positionAtMs`:
  ```kotlin
          sendspinRepeatMode = repeatMode
          sendspinShuffle = shuffle
          sendspinCanRepeat = canRepeat
          sendspinCanShuffle = canShuffle
  ```
  In `recompute()`'s SendSpin branch, add the four to the `NowPlayingState(...)` construction (after `canSeek = false,`):
  ```kotlin
                  canSeek = false,
                  repeatMode = sendspinRepeatMode, shuffle = sendspinShuffle,
                  canRepeat = sendspinCanRepeat, canShuffle = sendspinCanShuffle,
  ```

- [ ] **Run to see it pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.media.NowPlayingSendspinTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all existing + 6 new tests green.

- [ ] **Gate.** Run the full gate from Global Constraints.

- [ ] **Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/media/NowPlayingStore.kt app/src/test/java/com/rar/echodash/media/NowPlayingSendspinTest.kt
  git commit -m "feat(media): NowPlayingState carries repeat/shuffle + gates

Four new SendSpin-only fields (repeatMode/shuffle/canRepeat/canShuffle) with
matching equals/hashCode extensions so StateFlow dedup can't swallow toggle
updates. onSendspin gains defaulted params (6 store tests).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 3 — RepeatShuffleGates pure fn + SendspinEndpoint controller-state collection, publish, resets, transport wrappers

Two parts. First (TDD, plain-JVM tested): the `repeatShuffleGates` pure fn + `RepeatShuffleGates` data class, homed in the `sendspin` package next to the endpoint that consumes it — matching the existing pure-fn-with-pinning-test precedent `app/src/main/java/com/rar/echodash/sendspin/PlaybackIntent.kt` / `PlaybackIntentTest.kt`. Second (compile-gated): collect `engine.controllerState`, map it (through `repeatShuffleGates`) into the `onSendspin(...)` publish path, reset the four fields on deactivate/stop, and add the two transport wrappers.

`RepeatShuffleGates.kt` sits in `package com.rar.echodash.sendspin`, so `SendspinEndpoint` (same package) uses it with **no import** — no `ui.model` dependency crosses into the endpoint. The endpoint wiring itself has no plain-JVM test seam (it needs an Android `Context` and constructs `SendSpin` directly; `SendspinStatusTest.kt` documents this — "the only JVM-testable pure unit in the endpoint; the … pipeline is device-bound and verified only by the compile gate"), so it is covered by the `repeatShuffleGates` tests here (the gate logic), Task 2's store tests (the `onSendspin` -> state mapping), and the `:app:assembleDebug` compile; its behavior is exercised on-device via the end-of-plan live-verify checklist.

**Files:**
- Create: `app/src/main/java/com/rar/echodash/sendspin/RepeatShuffleGates.kt`
- Test: `app/src/test/java/com/rar/echodash/sendspin/RepeatShuffleGatesTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/sendspin/SendspinEndpoint.kt` — transport wrappers next to `transportNext`/`transportPrev` (~184-185); new `@Volatile` fields next to the `np*` metadata block (~209-220); `publishNowPlaying()` (~238-251); a new controller collector job field (~101) + start-up collector in `start()` (~442-478); resets in the connectionState Failed/Idle branch (~456-465) and in `stop()` (~544-549); a `controllerCollectorJob` cancel in `stop()` (~513-514).

**Interfaces:**
- Consumes: `SendSpin.controllerState: StateFlow<ControllerState?>` (~line 188); `ControllerState(supportedCommands: List<String>?, volume: Int?, muted: Boolean?, repeat: String?, shuffle: Boolean?)` (~247); `SendSpin.setRepeatMode(mode: String)` (~737, validates `"off"`/`"one"`/`"all"`, warns+drops anything else); `SendSpin.setShuffle(enabled: Boolean)` (~747); `NowPlayingStore.onSendspin(...)` extended signature (Task 2).
- Produces:
  - `data class RepeatShuffleGates(val canRepeat: Boolean, val canShuffle: Boolean)` and `fun repeatShuffleGates(supported: List<String>?): RepeatShuffleGates` in `package com.rar.echodash.sendspin` — `null` → both true; else `canRepeat` = any of `repeat_off`/`repeat_one`/`repeat_all` present, `canShuffle` = `shuffle` or `unshuffle` present. Consumed by `SendspinEndpoint` (same package, no import).
  - `fun transportSetRepeat(mode: String)` and `fun transportSetShuffle(enabled: Boolean)` on `SendspinEndpoint` (no-op when the engine is absent), consumed by App.kt (Task 5).

### Steps

**Part A — `repeatShuffleGates` pure fn (TDD cycle):**

- [ ] **Write the failing test.** Read the precedent `PlaybackIntentTest.kt` first (same package, same plain-JVM idiom). Create `app/src/test/java/com/rar/echodash/sendspin/RepeatShuffleGatesTest.kt`:
  ```kotlin
  package com.rar.echodash.sendspin

  import org.junit.Assert.assertEquals
  import org.junit.Test

  /**
   * Pins the pure toggle-visibility gate derived from the SendSpin controller's advertised
   * `supported_commands`. Lives beside [SendspinEndpoint] (its only consumer), like PlaybackIntent.
   */
  class RepeatShuffleGatesTest {

      @Test
      fun nullSetIsOptimisticBothTrue() {
          val g = repeatShuffleGates(null)
          assertEquals(true, g.canRepeat)
          assertEquals(true, g.canShuffle)
      }

      @Test
      fun partialRepeatOnlySet() {
          // Only a subset of repeat modes advertised, no shuffle command.
          val g = repeatShuffleGates(listOf("play", "pause", "repeat_all"))
          assertEquals(true, g.canRepeat)
          assertEquals(false, g.canShuffle)
      }

      @Test
      fun shuffleUnshuffleSet() {
          // "unshuffle" alone still enables the shuffle toggle; no repeat command present.
          val g = repeatShuffleGates(listOf("play", "unshuffle"))
          assertEquals(false, g.canRepeat)
          assertEquals(true, g.canShuffle)
      }

      @Test
      fun unrelatedCommandsNeither() {
          val g = repeatShuffleGates(listOf("play", "pause", "next", "previous", "volume"))
          assertEquals(false, g.canRepeat)
          assertEquals(false, g.canShuffle)
      }
  }
  ```

- [ ] **Run to see it fail.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.RepeatShuffleGatesTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: compilation failure — `unresolved reference: repeatShuffleGates` (RepeatShuffleGates.kt does not exist yet). RC != 0.

- [ ] **Minimal implementation.** Read the precedent `PlaybackIntent.kt` first to match its KDoc density. Create `app/src/main/java/com/rar/echodash/sendspin/RepeatShuffleGates.kt`:
  ```kotlin
  package com.rar.echodash.sendspin

  /** Which of the repeat/shuffle toggles the server's advertised command set allows. */
  data class RepeatShuffleGates(val canRepeat: Boolean, val canShuffle: Boolean)

  /**
   * Derive per-toggle visibility from the SendSpin controller's advertised `supported_commands`.
   * A null set (server never sent one) is optimistic -- both true, matching the engine-side drop
   * guard, which also passes unknown sets through. Otherwise canRepeat requires any repeat_*
   * command and canShuffle requires shuffle or unshuffle. Kept next to [SendspinEndpoint], its only
   * consumer, as a pure unit-testable fn (mirrors PlaybackIntent).
   */
  fun repeatShuffleGates(supported: List<String>?): RepeatShuffleGates {
      if (supported == null) return RepeatShuffleGates(canRepeat = true, canShuffle = true)
      val canRepeat = supported.any { it == "repeat_off" || it == "repeat_one" || it == "repeat_all" }
      val canShuffle = supported.any { it == "shuffle" || it == "unshuffle" }
      return RepeatShuffleGates(canRepeat, canShuffle)
  }
  ```

- [ ] **Run to see it pass.**
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests 'com.rar.echodash.sendspin.RepeatShuffleGatesTest' \
    > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/t.log 2>&1; echo RC=$?
  ```
  Expected: RC=0, all 4 tests green.

**Part B — SendspinEndpoint wiring (compile-gated):**

- [ ] **Read the surrounding sections** of `SendspinEndpoint.kt` (the `transportNext`/`transportPrev` wrappers ~177-193, the `np*` field block ~205-235, `publishNowPlaying` ~237-251, the `stateCollectorJob` setup in `start()` ~442-478, the Failed/Idle reset ~456-465, and `stop()` ~506-550) to match the file's comment density and threading idiom (`@Volatile` fields, `mainScope.launch` for UI-facing writes). Note: `SendspinEndpoint` is `package com.rar.echodash.sendspin`, the same package as `RepeatShuffleGates.kt`, so `repeatShuffleGates` needs **no import**.

- [ ] **Implement.** Five edits:

  1. Add the two transport wrappers right after `transportPrev()` (~line 185), matching the one-line `transportNext`/`transportPrev` idiom:
  ```kotlin
      fun transportNext() { sendSpin?.next() }
      fun transportPrev() { sendSpin?.previous() }
      /** Cycle the group repeat mode via the engine (no-op while disconnected). */
      fun transportSetRepeat(mode: String) { sendSpin?.setRepeatMode(mode) }
      /** Set group shuffle via the engine (no-op while disconnected). */
      fun transportSetShuffle(enabled: Boolean) { sendSpin?.setShuffle(enabled) }
  ```

  2. Add four `@Volatile` fields to the now-playing metadata block, after `npPositionAtMs` (~line 220):
  ```kotlin
      // Toggle state merged from the engine's controllerState push (repeat/shuffle) and its
      // supported_commands (the gates). Written on the controllerState collector (Default
      // dispatcher), read in publishNowPlaying -- @Volatile for cross-thread visibility, matching
      // the np* fields around them. Null repeat/shuffle = the server never sent controller state.
      @Volatile private var npRepeatMode: String? = null
      @Volatile private var npShuffle: Boolean? = null
      @Volatile private var npCanRepeat: Boolean = false
      @Volatile private var npCanShuffle: Boolean = false
  ```

  3. Extend `publishNowPlaying()` — add the four to the `onSendspin(...)` call:
  ```kotlin
          mainScope.launch {
              nowPlaying.onSendspin(
                  active = active, playing = playing,
                  title = npTitle, artist = npArtist, album = npAlbum,
                  artworkData = npArtwork, volume = npVolume, muted = mutedNow,
                  durationMs = npDurationMs, positionMs = npPositionMs, positionAtMs = npPositionAtMs,
                  repeatMode = npRepeatMode, shuffle = npShuffle,
                  canRepeat = npCanRepeat, canShuffle = npCanShuffle,
              )
          }
  ```

  4. Add a collector-job field next to `stateCollectorJob` (~line 101):
  ```kotlin
      private var stateCollectorJob: Job? = null
      private var controllerCollectorJob: Job? = null
  ```
  Launch the controller collector in `start()`, right after the `stateCollectorJob = scope.launch { ss.connectionState.collect { ... } }` block closes (~line 478, before the `address` handling):
  ```kotlin
          // Push repeat/shuffle + the toggle gates into the now-playing state as the server's
          // controller object arrives (delta-merged in ControllerState.mergedWith). Null cs (no
          // controller object yet) leaves repeat/shuffle null -> the takeover hides both toggles.
          controllerCollectorJob = scope.launch {
              ss.controllerState.collect { cs ->
                  npRepeatMode = cs?.repeat
                  npShuffle = cs?.shuffle
                  val gates = repeatShuffleGates(cs?.supportedCommands)
                  npCanRepeat = gates.canRepeat
                  npCanShuffle = gates.canShuffle
                  publishNowPlaying()
              }
          }
  ```

  5. Reset the four fields alongside the existing progress reset in BOTH deactivation sites, and cancel the new job in `stop()`. In the connectionState Failed/Idle branch (~line 461, after `npDurationMs = 0; npPositionMs = 0; npPositionAtMs = 0`):
  ```kotlin
                      npDurationMs = 0; npPositionMs = 0; npPositionAtMs = 0
                      npRepeatMode = null; npShuffle = null; npCanRepeat = false; npCanShuffle = false
  ```
  In `stop()`, cancel the collector next to the `stateCollectorJob` cancel (~line 513):
  ```kotlin
          stateCollectorJob?.cancel()
          stateCollectorJob = null
          controllerCollectorJob?.cancel()
          controllerCollectorJob = null
  ```
  And in `stop()` after the progress reset (~line 548, before the final `mainScope.launch { nowPlaying.onSendspin(false, ...) }`):
  ```kotlin
          npDurationMs = 0; npPositionMs = 0; npPositionAtMs = 0
          npRepeatMode = null; npShuffle = null; npCanRepeat = false; npCanShuffle = false
  ```
  The two `onSendspin(false, false, null, null, null, null, npVolume, muted = false)` deactivation calls need NO change: the new `onSendspin` params default to `null`/`null`/`false`/`false`, which is exactly the reset state. Resetting the `@Volatile` fields above just prevents a later reactivation (e.g. `onStreamStart`'s `publishNowPlaying()`) from flashing a stale toggle before the next controller push lands.

- [ ] **Gate.** Run the full gate from Global Constraints. Verifies the `:app:assembleDebug` compile of the endpoint wiring plus the unit suite (the 4 new `RepeatShuffleGatesTest` cases + Tasks 1-2 tests all green).

- [ ] **Commit.** (One commit covers Part A's pure fn + test and Part B's endpoint wiring — the fn's only consumer is the endpoint, so they ship together.)
  ```bash
  git add app/src/main/java/com/rar/echodash/sendspin/RepeatShuffleGates.kt app/src/test/java/com/rar/echodash/sendspin/RepeatShuffleGatesTest.kt app/src/main/java/com/rar/echodash/sendspin/SendspinEndpoint.kt
  git commit -m "feat(media): SendspinEndpoint publishes repeat/shuffle + adds set wrappers

repeatShuffleGates pure fn (in the sendspin package, JVM-tested, 4 tests) gates
toggle visibility. SendspinEndpoint collects engine.controllerState -> maps
repeat/shuffle + gates into the onSendspin publish path; resets on deactivate/
stop; transportSetRepeat/transportSetShuffle wrappers delegate to the engine
(no-op when absent). The endpoint wiring has no plain-JVM seam (Context + direct
SendSpin construction); covered by the gate tests, the store tests, and compile.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 4 — Takeover UI: toggle row + up-next line (NowPlayingHome) + HomeView threading

Add the two toggle circles (below the volume row, inside the `width(224.dp)` group) and the up-next line (under the progress row), plus the new params, and thread them from `HomeView`. **UI-only task: no unit test** — the toggle glyph/lit rules and gate booleans are already covered by Task 1 (`nextRepeatMode`), Task 3 (`repeatShuffleGates`), and Task 2 (state fields); the composables are verified only by the `:app:assembleDebug` compile in the gate.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt` — new params on `NowPlayingHome` (~68-78); up-next line in the outer metadata `Column` after the progress row (~144); toggle `Row` inside the inner `width(224.dp)` `Column` after the volume row (~181); new private `NpToggleButton` composable near `NpTransportButton` (~187).
- Modify: `app/src/main/java/com/rar/echodash/ui/HomeView.kt` — new params on `HomeView` (~193-205); pass-through in the `NowPlayingHome(...)` call (~245-255).

**Interfaces:**
- Consumes: `NowPlayingState.sendspin`/`.repeatMode`/`.shuffle`/`.canRepeat`/`.canShuffle` (Task 2); `com.rar.echodash.sendspin.musicassistant.MaQueueItem` (fields `name`, `artist`); `Icons.Outlined.Shuffle`/`.Repeat`/`.RepeatOne`.
- Produces (`NowPlayingHome` new params): `onCycleRepeat: () -> Unit = {}`, `onToggleShuffle: () -> Unit = {}`, `upNext: MaQueueItem? = null`, `onUpNextTap: () -> Unit = {}`.
- Produces (`HomeView` new params, forwarded down): `onMediaCycleRepeat: () -> Unit = {}`, `onMediaToggleShuffle: () -> Unit = {}`, `upNext: MaQueueItem? = null`, `onUpNextTap: () -> Unit = {}`.

### Steps

- [ ] **Read** `NowPlayingHome.kt` in full (already reviewed: metadata `Column` with `spacedBy(14.dp)`, the inner `width(224.dp)` transport+volume `Column`, `NpTransportButton`'s `size`/`iconSize` shape, the `#2A2F3C` chip color, the muted-row idiom) and the `HomeView.kt` `NowPlayingHome(...)` call site.

- [ ] **Implement NowPlayingHome.kt.** Four edits:

  1. Add imports (with the existing icon/type imports):
  ```kotlin
  import androidx.compose.material.icons.outlined.Repeat
  import androidx.compose.material.icons.outlined.RepeatOne
  import androidx.compose.material.icons.outlined.Shuffle
  import com.rar.echodash.sendspin.musicassistant.MaQueueItem
  ```

  2. Extend the `NowPlayingHome` param list (after `onBrowse`):
  ```kotlin
      onBrowse: () -> Unit = {},
      onCycleRepeat: () -> Unit = {},
      onToggleShuffle: () -> Unit = {},
      upNext: MaQueueItem? = null,
      onUpNextTap: () -> Unit = {},
  ```

  3. Add the up-next line in the OUTER metadata `Column`, immediately after the progress-row block (the `if (state.durationMs > 0) { TrackProgressRow(...) }`), before the inner `width(224.dp)` `Column`:
  ```kotlin
              // Progress + optional seek. Only when a track length is known (radio/ICY has none).
              if (state.durationMs > 0) {
                  TrackProgressRow(state = state, onSeek = onSeek)
              }
              // Up next (SendSpin only): a single dimmed line, glanceable, tap -> queue overlay.
              if (state.sendspin && upNext != null) {
                  val upNextLabel = "Up next: ${upNext.name}" + (upNext.artist?.let { " — $it" } ?: "")
                  Text(
                      upNextLabel,
                      color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp,
                      maxLines = 1, overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.clickable { onUpNextTap() },
                  )
              }
  ```

  4. Add the toggle `Row` inside the inner `width(224.dp)` `Column`, after the muted/volume `Row` (the last child, before the inner `Column` closes). Compute the gates and render only when at least one toggle is visible so the parent `spacedBy(14.dp)` never adds an empty gap:
  ```kotlin
                  // (muted/volume Row above)
                  val showShuffle = state.sendspin && state.shuffle != null && state.canShuffle
                  val showRepeat = state.sendspin && state.repeatMode != null && state.canRepeat
                  if (showShuffle || showRepeat) {
                      Row(
                          horizontalArrangement = Arrangement.spacedBy(16.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                          if (showShuffle) {
                              NpToggleButton(Icons.Outlined.Shuffle, on = state.shuffle == true) { onToggleShuffle() }
                          }
                          if (showRepeat) {
                              val repeatIcon = if (state.repeatMode == "one") Icons.Outlined.RepeatOne else Icons.Outlined.Repeat
                              val repeatOn = state.repeatMode == "all" || state.repeatMode == "one"
                              NpToggleButton(repeatIcon, on = repeatOn) { onCycleRepeat() }
                          }
                      }
                  }
  ```

  5. Add the `NpToggleButton` private composable next to `NpTransportButton` (~line 205), following its structure (40 dp circle, `#2A2F3C` chip, 20 dp icon), differing only in the tint rule:
  ```kotlin
  /** A 40 dp round toggle chip (20 dp icon): accent tint when [on], dimmed white when off. */
  @Composable
  private fun NpToggleButton(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
      Box(
          Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(Color(0xFF2A2F3C))
              .clickable { onClick() },
          contentAlignment = Alignment.Center,
      ) {
          Icon(
              icon, contentDescription = null,
              tint = if (on) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.45f),
              modifier = Modifier.size(20.dp),
          )
      }
  }
  ```

- [ ] **Implement HomeView.kt.** Two edits:

  1. Add the four params to `HomeView` (after `onBrowse`):
  ```kotlin
      onBrowse: () -> Unit = {},
      onMediaCycleRepeat: () -> Unit = {},
      onMediaToggleShuffle: () -> Unit = {},
      upNext: com.rar.echodash.sendspin.musicassistant.MaQueueItem? = null,
      onUpNextTap: () -> Unit = {},
  ```
  (Use the fully-qualified type or add an `import com.rar.echodash.sendspin.musicassistant.MaQueueItem` — prefer the import for consistency with the file's other imports.)

  2. Forward them in the `NowPlayingHome(...)` call inside the `Crossfade` (after `onBrowse = onBrowse`):
  ```kotlin
                  NowPlayingHome(
                      state = nowPlaying,
                      art = art,
                      onPlay = onMediaPlay,
                      onPause = onMediaPause,
                      onNext = onMediaNext,
                      onPrev = onMediaPrev,
                      onVolume = onMediaVolume,
                      onSeek = onMediaSeek,
                      onBrowse = onBrowse,
                      onCycleRepeat = onMediaCycleRepeat,
                      onToggleShuffle = onMediaToggleShuffle,
                      upNext = upNext,
                      onUpNextTap = onUpNextTap,
                  )
  ```

- [ ] **Gate.** Run the full gate from Global Constraints (compile-verifies the composables; unit suite from Tasks 1-3 stays green).

- [ ] **Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/NowPlayingHome.kt app/src/main/java/com/rar/echodash/ui/HomeView.kt
  git commit -m "feat(media): takeover shuffle/repeat toggles + up-next line

NowPlayingHome gains two 40dp toggle circles (accent-lit) below the volume row
and a dimmed tappable up-next line under the progress row, both SendSpin-gated.
HomeView threads the callbacks + upNext through. UI-only (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 5 — DashboardShell up-next poll + open-queue signal; App.kt callbacks

Wire the App-level cycle/toggle callbacks (using Task 1's `nextRepeatMode`), and own the up-next poll + the open-queue signal in `DashboardShell`. **UI/wiring task: no unit test** — the pure cycle logic is covered by Task 1; the poll effect and callback plumbing are compile-gated. The App callbacks call `deps.sendspin.transportSetRepeat/transportSetShuffle` (Task 3) with `nextRepeatMode(...)` (Task 1).

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/App.kt` — add `onMediaCycleRepeat`/`onMediaToggleShuffle` to the `DashboardShell(...)` call (media-callback block ~872-913); import `com.rar.echodash.ui.model.nextRepeatMode`.
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — new params (~85-108); up-next poll `LaunchedEffect` + `upNext`/`openQueueSignal` local state + MA-connected read (in the composable body, ~110-115); forward to `HomeView` (~228-261) and `MediaPanel` (~275-279); imports.

**Interfaces:**
- Consumes: `SendspinEndpoint.transportSetRepeat(mode)`/`transportSetShuffle(enabled)` (Task 3); `com.rar.echodash.ui.model.nextRepeatMode` (Task 1); `com.rar.echodash.ui.model.upNextOf` (Task 1); `MaLibrary.queue(): Result<MaQueueState>`; `MaLibrary.state: StateFlow<MaLibraryState>` with `MaLibraryState.Connected`; `nowPlayingState.sendspin`/`.repeatMode`/`.shuffle`/`.title`; `takeoverVisible`.
- Produces (`DashboardShell` new params): `onMediaCycleRepeat: () -> Unit = {}`, `onMediaToggleShuffle: () -> Unit = {}`.
- Produces (internal to DashboardShell, consumed by Task 6's `MediaPanel` call): `openQueueSignal: Int` local counter passed to `MediaPanel(..., openQueueSignal = openQueueSignal)`.

### Steps

- [ ] **Read** the `App.kt` media-callback block (~872-913, the `if (nowPlayingState.sendspin) deps.sendspin.transportX() else <companion>` idiom), the `DashboardShell(...)` call site (~837+), and the `DashboardShell` composable body (params ~85-108, the `HomeView(...)` and `MediaPanel(...)` calls ~228-279). Confirm `MediaPanel` currently takes no `openQueueSignal` (Task 6 adds it).

- [ ] **Implement App.kt.** Two edits:

  1. Import `nextRepeatMode` (with the other `com.rar.echodash.ui.model.*` imports ~57-61):
  ```kotlin
  import com.rar.echodash.ui.model.nextRepeatMode
  ```

  2. Add the two callbacks to the `DashboardShell(...)` call, right after `onMediaSeek = { ... }` (~913), matching the sendspin-branch idiom (companion branch = no-op):
  ```kotlin
                          // SendSpin-only: cycle group repeat / toggle group shuffle. Companion
                          // media_player has no queue concept here, so the else branch is a no-op.
                          onMediaCycleRepeat = {
                              if (nowPlayingState.sendspin)
                                  deps.sendspin.transportSetRepeat(nextRepeatMode(nowPlayingState.repeatMode))
                          },
                          onMediaToggleShuffle = {
                              if (nowPlayingState.sendspin)
                                  deps.sendspin.transportSetShuffle(!(nowPlayingState.shuffle ?: false))
                          },
  ```

- [ ] **Implement DashboardShell.kt.** Four edits:

  1. Add imports (with existing ones):
  ```kotlin
  import androidx.compose.runtime.mutableIntStateOf
  import androidx.lifecycle.compose.collectAsStateWithLifecycle
  import com.rar.echodash.sendspin.MaLibraryState
  import com.rar.echodash.sendspin.musicassistant.MaQueueItem
  import com.rar.echodash.ui.model.upNextOf
  ```

  2. Add the two new params to `DashboardShell` (after `onBrowse: () -> Unit,` ~97):
  ```kotlin
      onBrowse: () -> Unit,
      onMediaCycleRepeat: () -> Unit = {},
      onMediaToggleShuffle: () -> Unit = {},
  ```

  3. In the composable body (after `val connected = ...` ~110), add the up-next state, the MA-connected read, the open-queue counter, and the poll effect:
  ```kotlin
      // Up-next line state, owned here (not in NowPlayingState): it comes from the MA API poll, a
      // different producer than SendspinEndpoint. library is a process-lifetime dependency, so its
      // null-ness is fixed for this composition -- the guarded collect below is stable across
      // recompositions.
      var upNext by remember { mutableStateOf<MaQueueItem?>(null) }
      // Bumped when the takeover's up-next line is tapped; threaded to MusicBrowser to open the
      // queue overlay on first composition in the MEDIA view. Starts at 0 (never-requested).
      var openQueueSignal by remember { mutableIntStateOf(0) }
      // deps.maLibrary is a process-lifetime constant (App.kt:295) -- this branch never flips, so the
      // collectAsStateWithLifecycle call stays structurally stable across recompositions (it is kept
      // out of a conditional expression -- the composable is called only inside the stable branch).
      val maConnected = if (library != null) {
          val maState by library.state.collectAsStateWithLifecycle()
          maState is MaLibraryState.Connected
      } else false
      // Poll the queue while the takeover is up on a SendSpin source with a live MA socket. Keyed on
      // nowPlaying.title so a track advance restarts the poll and refreshes the line immediately;
      // any fetch failure (or a null next item) sets null -- the takeover is glanceable, not a
      // diagnostics surface. The gate going false clears the line.
      LaunchedEffect(takeoverVisible, nowPlaying.sendspin, nowPlaying.title, maConnected) {
          if (!(takeoverVisible && nowPlaying.sendspin && library != null && maConnected)) {
              upNext = null
              return@LaunchedEffect
          }
          while (true) {
              upNext = library.queue().getOrNull()?.let { upNextOf(it) }
              delay(10_000)
          }
      }
  ```

  4. Forward to `HomeView(...)` (add after `onBrowse = onBrowse,` ~260). Do NOT modify the `MediaPanel(...)` call here — its `openQueueSignal`/`onCycleRepeat`/`onToggleShuffle` params are added (defaulted) in Task 6, and Task 6 owns the call-site wiring so THIS commit gates green on its own:
  ```kotlin
                      onBrowse = onBrowse,
                      onMediaCycleRepeat = onMediaCycleRepeat,
                      onMediaToggleShuffle = onMediaToggleShuffle,
                      upNext = upNext,
                      onUpNextTap = {
                          openQueueSignal++
                          onSelect(DashView.MEDIA)
                      },
  ```
  > **Ordering note:** after this task `openQueueSignal` is written by `onUpNextTap` but not yet consumed (Task 6 threads it into `MediaPanel` → `MusicBrowser`). That is a compile-clean intermediate state — a `var` that is incremented compiles without error (no unresolved reference, no unused-var error). The up-next tap therefore switches to the MEDIA view but doesn't yet open the queue overlay until Task 6 lands; the toggles and the up-next line itself are fully live. The `MediaPanel` call stays on its current signature (new params defaulted), so the gate is green.

- [ ] **Gate.** Run the full gate from Global Constraints. Expect `GATE RC=0` (the `MediaPanel` call is unchanged and Task 6's future params are defaulted, so nothing is unresolved) and the Tasks 1-3 unit suite green.

- [ ] **Commit** (App.kt + DashboardShell):
  ```bash
  git add app/src/main/java/com/rar/echodash/App.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
  git commit -m "feat(media): App cycle/shuffle callbacks + DashboardShell up-next poll

App wires sendspin-only onMediaCycleRepeat/onMediaToggleShuffle (nextRepeatMode
cycle). DashboardShell owns the 10s MaLibrary.queue() up-next poll (takeover +
SendSpin + MA-connected gated) and an openQueueSignal counter bumped on up-next
tap, switching to MEDIA (queue-open wiring lands in Task 6). UI/wiring
(compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Task 6 — MusicBrowser queue-pane chips + openQueueSignal; MediaPanel threading

Add the open-queue signal handling and the two toggle chips in the `QueuePane` header (28 dp circles, 16 dp icon), wired to the same App-level callbacks and bumping `queueVersion`, then close the wiring by threading `openQueueSignal`/`onCycleRepeat`/`onToggleShuffle` from the `DashboardShell` → `MediaPanel` call. **UI-only task: no unit test** — the chip glyph/lit rules mirror Task 4 and read straight off `MaQueueState`; compile-gated. All new `MediaPanel`/`MusicBrowser` params are defaulted, so Task 5 gated green without them; this task fills in the call-site wiring last.

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt` — `MediaPanel` params (~50-60); pass-through to `MusicBrowser` (~75).
- Modify: `app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt` — `MusicBrowser` params (~128); `openQueueSignal` `LaunchedEffect` (near the queue poll ~199-209); `QueuePane` call — pass the two toggle callbacks that call up + bump `queueVersion` (~244-261); `QueuePane` signature + header chips (~509-540); new private chip composable; imports.
- Modify: `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt` — the `MediaPanel(...)` call in the `DashView.MEDIA` branch (~275-279): pass `openQueueSignal`, `onCycleRepeat`, `onToggleShuffle` (the local `openQueueSignal` and the `onMediaCycleRepeat`/`onMediaToggleShuffle` params both already exist from Task 5).

**Interfaces:**
- Consumes: `MaQueueState.shuffleEnabled: Boolean`, `MaQueueState.repeatMode: String` (chip lit state); the App-level `onCycleRepeat`/`onToggleShuffle` callbacks threaded via `MediaPanel` from `DashboardShell` (Task 5); `Icons.Outlined.Shuffle`/`.Repeat`/`.RepeatOne`.
- Produces (`MediaPanel` new params): `openQueueSignal: Int = 0`, `onCycleRepeat: () -> Unit = {}`, `onToggleShuffle: () -> Unit = {}`.
- Produces (`MusicBrowser` new params): `openQueueSignal: Int = 0`, `onCycleRepeat: () -> Unit = {}`, `onToggleShuffle: () -> Unit = {}`.

### Steps

- [ ] **Read** `MusicBrowser.kt` in full (already reviewed: the `queueVersion`/`queueVisible` state ~134-136, the queue poll `LaunchedEffect(queueVisible, queueVersion)` ~201-209, the `QueuePane(...)` call with `onJump`/`onClear` that `.onSuccess { queueVersion++ }` ~244-261, and `QueuePane`'s header `Row` with the "Queue" label + "Clear" chip ~515-529) and `MediaPanel.kt`'s `MusicBrowser(library, thumbs, ...)` call (~75).

- [ ] **Implement MediaPanel.kt.** Two edits:

  1. Add three params to `MediaPanel` (after `thumbs: MaThumbs? = null,` ~60):
  ```kotlin
      library: MaLibrary? = null,
      thumbs: MaThumbs? = null,
      openQueueSignal: Int = 0,
      onCycleRepeat: () -> Unit = {},
      onToggleShuffle: () -> Unit = {},
  ```

  2. Forward to the `MusicBrowser(...)` call (~75):
  ```kotlin
              MusicBrowser(
                  library, thumbs, Modifier.weight(1f).fillMaxWidth(),
                  openQueueSignal = openQueueSignal,
                  onCycleRepeat = onCycleRepeat,
                  onToggleShuffle = onToggleShuffle,
              )
  ```

- [ ] **Implement MusicBrowser.kt.** Five edits:

  1. Add imports (with the existing icon imports):
  ```kotlin
  import androidx.compose.material.icons.outlined.Repeat
  import androidx.compose.material.icons.outlined.RepeatOne
  import androidx.compose.material.icons.outlined.Shuffle
  ```

  2. Extend the `MusicBrowser` signature (~128):
  ```kotlin
  @Composable
  fun MusicBrowser(
      library: MaLibrary,
      thumbs: MaThumbs,
      modifier: Modifier = Modifier,
      openQueueSignal: Int = 0,
      onCycleRepeat: () -> Unit = {},
      onToggleShuffle: () -> Unit = {},
  ) {
  ```
  (The existing single-line `fun MusicBrowser(library: MaLibrary, thumbs: MaThumbs, modifier: Modifier = Modifier)` becomes this multi-line form.)

  3. Add the open-queue effect next to the queue poll effect (~after line 209). `0` is the never-requested sentinel, so a fresh composition with signal 0 stays on the browser:
  ```kotlin
      // Open the queue overlay when DashboardShell bumps the signal (up-next line tapped). 0 is the
      // never-requested value; a nonzero value opens the queue on (re)composition.
      LaunchedEffect(openQueueSignal) {
          if (openQueueSignal > 0) queueVisible = true
      }
  ```

  4. Pass the two toggle callbacks into the `QueuePane(...)` call (~244), each calling up then bumping `queueVersion` for an immediate refetch (matching the `onJump`/`onClear` idiom; the 5 s poll self-corrects any race with the server applying the command):
  ```kotlin
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
                      onToggleShuffle = { onToggleShuffle(); queueVersion++ },
                      onCycleRepeat = { onCycleRepeat(); queueVersion++ },
                  )
  ```

  5. Extend `QueuePane` (~509) — add the two callback params and render the chips in the header `Row` between the "Queue" label and "Clear". Chips render whenever the queue is loaded (the queue pane exists only for the MA/SendSpin path, so no source gate). Add a private 28 dp chip composable:
  ```kotlin
  @Composable
  private fun QueuePane(
      queue: MaQueueState?,
      thumbs: MaThumbs,
      onJump: (String) -> Unit,
      onClear: () -> Unit,
      onToggleShuffle: () -> Unit,
      onCycleRepeat: () -> Unit,
  ) {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
              Text(
                  "Queue", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                  modifier = Modifier.weight(1f),
              )
              // Toggle chips mirror the takeover: lit off the queue's own shuffle/repeat state.
              if (queue != null) {
                  QueueToggleChip(Icons.Outlined.Shuffle, on = queue.shuffleEnabled) { onToggleShuffle() }
                  val repeatIcon = if (queue.repeatMode == "one") Icons.Outlined.RepeatOne else Icons.Outlined.Repeat
                  val repeatOn = queue.repeatMode == "all" || queue.repeatMode == "one"
                  QueueToggleChip(repeatIcon, on = repeatOn) { onCycleRepeat() }
              }
              Text(
                  "Clear", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                  modifier = Modifier
                      .clip(RoundedCornerShape(12.dp))
                      .background(Color(0xFF2A2F3C))
                      .clickable { onClear() }
                      .padding(horizontal = 12.dp, vertical = 4.dp),
              )
          }
          when {
              queue == null -> EmptyHint("Loading…")
              queue.items.isEmpty() -> EmptyHint("Queue is empty")
              else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  items(queue.items, key = { it.queueItemId }) { qi ->
                      QueueRow(qi, thumbs, onJump)
                  }
              }
          }
      }
  }

  /** A 28 dp round toggle chip (16 dp icon) for the queue header: accent tint when [on]. */
  @Composable
  private fun QueueToggleChip(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
      Box(
          Modifier
              .size(28.dp)
              .clip(CircleShape)
              .background(Color(0xFF2A2F3C))
              .clickable { onClick() },
          contentAlignment = Alignment.Center,
      ) {
          Icon(
              icon, contentDescription = null,
              tint = if (on) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.45f),
              modifier = Modifier.size(16.dp),
          )
      }
  }
  ```
  This new composable references `ImageVector` — add `import androidx.compose.ui.graphics.vector.ImageVector` if not already present in `MusicBrowser.kt`.

- [ ] **Implement DashboardShell.kt call-site wiring.** Update the `MediaPanel(...)` call in the `DashView.MEDIA` branch (~275-279) to pass the three now-defined `MediaPanel` params (`openQueueSignal` local + `onMediaCycleRepeat`/`onMediaToggleShuffle` params all exist from Task 5):
  ```kotlin
                  DashView.MEDIA -> MediaPanel(
                      nowPlaying, art, onMediaPlay, onMediaPause, onMediaStop,
                      onMediaNext, onMediaPrev, onMediaVolume,
                      library = library, thumbs = thumbs,
                      openQueueSignal = openQueueSignal,
                      onCycleRepeat = onMediaCycleRepeat,
                      onToggleShuffle = onMediaToggleShuffle,
                  )
  ```

- [ ] **Gate.** Run the full gate from Global Constraints. This is where the full feature graph is live — expect `GATE RC=0` and the unit suite (Tasks 1-3, 15 new tests) green.

- [ ] **Commit.**
  ```bash
  git add app/src/main/java/com/rar/echodash/ui/panels/MediaPanel.kt app/src/main/java/com/rar/echodash/ui/panels/MusicBrowser.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
  git commit -m "feat(media): queue-pane shuffle/repeat chips + open-queue signal

QueuePane header gains two 28dp toggle chips (lit off queue state) between the
Queue label and Clear, wired to the App callbacks + a queueVersion bump for an
immediate refetch. openQueueSignal opens the overlay when the takeover up-next
line is tapped. MediaPanel + the DashboardShell MEDIA call thread it all
through. UI-only (compile-gated).

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
  ```

---

## Live-verify checklist (implementation end — not a task; run on-device)

From the design spec's "Live-verify checklist". Run against a real device playing Music Assistant music; the SendSpin socket path is the primary and only-built command path (the MA-API fallback in the spec is documented-only — build it **only** if step 1 shows the commands are not advertised):

1. Play MA music on a device → confirm `supported_commands` includes `repeat_*`/`shuffle`/`unshuffle` (logcat). **If absent, build the documented MA-API fallback** (spec "Documented fallback" section — two `MaCommandClient` commands `player_queues/shuffle` + `player_queues/repeat` exposed as `MaLibrary.setShuffle`/`setRepeat` via `withQueue`; all UI/state/gates stay identical).
2. Toggle shuffle on the takeover → MA UI reflects it; toggle shuffle in MA → the takeover chip follows (push-updated).
3. Repeat cycle off → all → one → off on the takeover; the glyph switches to `RepeatOne` on "one".
4. Up-next line is correct; it advances on track change; tapping it switches to the MEDIA view with the queue overlay open.
5. Queue-pane chips mirror the takeover state; queue order visibly reshuffles on toggle.
6. Companion source (desk Echo with a companion `media_player` entity): the takeover is unchanged — no toggles, no up-next line.
