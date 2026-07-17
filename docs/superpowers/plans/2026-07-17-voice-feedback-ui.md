# Voice Feedback UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the bottom-center voice pill a THINKING state (transcript + pulsing dots), auto-recovering FAILED exits (stalled pipeline, `error`, mid-run `run-satellite`), and tap-to-interrupt (abort playback / cancel a thinking run) — so the pill never strands on screen and the user can shut the assistant up.

**Architecture:** All new logic lives in the pure `SatelliteSession` state machine (plain-JVM tested via the 500 ms `onTick`); `SatelliteServer` gains one thin bridge; `AnnouncePlayer`/`AndroidPcmSink` gain a public abort primitive (the sealed action + `Out` method already flow through the existing `dispatch`); `VoiceOverlay` renders the two new phases and forwards taps; `App.kt` wires the callbacks. No protocol changes — Wyoming has no satellite-side pipeline-abort, so "cancel" is local suppression.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, kotlinx-coroutines 1.9.0, JUnit4 (plain JVM). Wyoming TCP satellite. AudioTrack (`MODE_STREAM`, `USAGE_ASSISTANT`).

## Global Constraints

- Work directly on `master`; commit per task; every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Gate before EVERY commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` — expected `BUILD SUCCESSFUL`, all tests green.
- NO new dependencies. Plain-JVM JUnit4 tests only — no Robolectric, no Compose UI tests. Compose rendering changes are verified live on device, not in unit tests.
- Pure logic (phases, watchdog, suppression, tap semantics) lives in `SatelliteSession` so it stays plain-JVM testable; Compose/Android code stays thin.
- Follow existing code style: comment density matches surrounding code, no narrating comments.
- Exact constants (copied verbatim from the spec): `WATCHDOG_MS = 30_000L`, `FAILED_MS = 3_000L`, failed pill text `"No response — try again"`, thinking-dot color `Color(0xFF4FC3F7)` (voice blue), failed text color `Color(0xFFB0B4BE)` (muted), dot pulse ~900 ms cycle / 150 ms stagger per dot.
- Exact transition rules (from the spec's transition table): `synthesize` re-arms the watchdog; `audio-start` clears it; `run-satellite` fails the run *only* when phase is `LISTENING` or `THINKING` (at phase `HIDDEN` it arms as today); suppression swallows `synthesize`/`audio-start`/`audio-chunk` but suppressed `audio-stop` still emits `played`; `onPlayed` is NOT invoked on abort.

---

## File Structure

- `app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt` — add `THINKING`, `FAILED` to the phase enum (additive; `TRANSCRIPT` stays). Task 1.
- `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt` — the state machine: watchdog, failure exits, tap-to-interrupt, suppression. Tasks 1, 2, 4.
- `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` — render THINKING (dots) + FAILED (muted) + make the pill clickable. Compile-keeping enum stub in Task 1; full UI in Task 5.
- `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt` — `SatelliteAction.PlaybackAbort` dispatch case + `Out.onPlaybackAbort()` (Task 3); `onOverlayTapped()` bridge (Task 4).
- `app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt` — public `abort()` entry (Task 3). (Sealed `Cmd.Abort` + no-`onPlayed` semantics already exist.)
- `app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt` — `abort()` adds `stop()` for immediate silence (Task 3). (`PcmSink.abort()` interface already exists.)
- `app/src/main/java/com/rar/echodash/App.kt` — wire `Out.onPlaybackAbort` (Task 3) and the `VoiceOverlay` `onTap` (Task 5).
- `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt` — session test cases (Tasks 1, 2, 4).
- `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt` — `RecordingOut` gains the new `Out` override so it compiles (Task 3).
- `app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt` — pin the public `abort()` (Task 3).

**Why this task order:** Tasks 1–2 are pure-session and add no sealed/enum members that other files must handle beyond the Task-1 UI stub. Task 3 introduces `SatelliteAction.PlaybackAbort` and `Out.onPlaybackAbort()`; because `SatelliteServer.dispatch()` is an exhaustive `when` over the sealed `SatelliteAction` (Kotlin 2.1.0 = compile error if non-exhaustive) and both `Out` implementers (`App.kt`, test `RecordingOut`) must implement every method, the action type, its dispatch case, the `Out` method, both overrides, and the `AnnouncePlayer.abort()` they call must all land together. Task 4 (tap) then *emits* `PlaybackAbort` through that existing plumbing. Task 5 is pure Compose/wiring.

---

## Task 1: Phases, transcript→THINKING, and the watchdog

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt`
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt` (compile-keeping stub only)
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (later tasks rely on these):
  - `enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, THINKING, RESPONSE, FAILED }`
  - `SatelliteSession` companion consts `WATCHDOG_MS = 30_000L`, `FAILED_MS = 3_000L`, `FAILED_TEXT = "No response — try again"`
  - `private fun SatelliteSession.failActions(nowMs: Long): List<SatelliteAction>` — sets `dismissAtMs = nowMs + FAILED_MS`, clears `watchdogAtMs`, mirrors the localWake error cleanup (`streaming-stopped` + `ResetDetector`, `wakeState = DETECTING`) or a no-op cleanup otherwise, then appends the `FAILED` overlay. Used by Tasks 2 and (via `onTick`) here.
  - `private var watchdogAtMs: Long?` armed on entering `LISTENING`/`THINKING` and by `synthesize`, cleared by `audio-start`/`reset()`, fired in `onTick`.

- [ ] **Step 1: Write the failing tests** — in `SatelliteSessionTest.kt`, first UPDATE the existing overlay-flow test to expect `THINKING` instead of the terminal `TRANSCRIPT` (spec test 1). Change the middle assertion of `detectionTranscriptSynthesizeDriveOverlay` (currently lines ~84-85):

Replace:
```kotlin
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, "turn on the light"),
            (s.onEvent(event("transcript", """{"text":"turn on the light"}""")).last() as SatelliteAction.Overlay).state)
```
with:
```kotlin
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.THINKING, "turn on the light"),
            (s.onEvent(event("transcript", """{"text":"turn on the light"}""")).last() as SatelliteAction.Overlay).state)
```

Then add these three new tests (spec tests 2, 3, 6) at the end of the class, before the final `}`:
```kotlin
    // ---- voice watchdog / thinking / failed ----

    @Test
    fun watchdogFiresFromListeningToFailedThenHides() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 1_000)                       // LISTENING, watchdog @ 31_000
        assertTrue(s.onTick(nowMs = 30_999).none { it is SatelliteAction.Overlay }) // before deadline
        val fired = s.onTick(nowMs = 31_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (fired.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(sends(fired).map { it.type }.contains("streaming-stopped"))       // streaming stopped
        assertTrue(fired.contains(SatelliteAction.ResetDetector))                    // detector re-armed
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),                     // +3 s -> HIDDEN
            (s.onTick(nowMs = 34_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun watchdogInThinkingReArmedByTranscriptFires() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 5_000)   // LISTENING, watchdog @ 35_000
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 10_000) // THINKING, re-armed @ 40_000
        assertTrue(s.onTick(nowMs = 35_000).none { it is SatelliteAction.Overlay }) // old deadline passed harmlessly
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (s.onTick(nowMs = 40_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun responseWithoutPlaybackHidesQuietlyAtWatchdog() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)
        s.onEvent(event("synthesize", """{"text":"Answer"}"""), nowMs = 2_000) // RESPONSE, watchdog @ 32_000
        assertEquals(VoiceOverlayPhase.RESPONSE, s.overlay.phase)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),                 // quiet hide, no FAILED text
            (s.onTick(nowMs = 32_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertEquals(VoiceOverlayPhase.HIDDEN, s.overlay.phase)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest"`
Expected: FAIL — Kotlin compilation error `unresolved reference: THINKING` / `unresolved reference: FAILED` (the enum values and watchdog logic don't exist yet).

- [ ] **Step 3: Add the two enum values** — in `VoiceOverlayState.kt`, replace the current enum line:
```kotlin
enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, RESPONSE }
```
with (additive — `TRANSCRIPT` stays):
```kotlin
enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, THINKING, RESPONSE, FAILED }
```

- [ ] **Step 4: Add the watchdog field, constants, `failActions`, and the transition/tick edits** — in `SatelliteSession.kt`:

  Add the field beside `dismissAtMs` (after `private var dismissAtMs: Long? = null`):
```kotlin
    private var watchdogAtMs: Long? = null
```

  Change the `detection` handler (currently lines ~130-134) to arm the watchdog:
```kotlin
        "detection" -> {
            // Legacy/fallback: HA reports the wake word. In localWake HA never sends this.
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(
                SatelliteAction.Earcon(EarconKind.WAKE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
            )
        }
```

  Change the `transcript` handler (currently lines ~135-146) to go to THINKING and arm the watchdog:
```kotlin
        "transcript" -> {
            watchdogAtMs = nowMs + WATCHDOG_MS
            val base = listOf(
                SatelliteAction.Earcon(EarconKind.DONE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.THINKING, textOf(event))),
            )
            if (localWake) {
                wakeState = WakeState.DETECTING
                base + listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
            } else {
                base
            }
        }
```

  Change the `synthesize` handler (currently line ~153) to re-arm the watchdog (TTS-never-plays coverage):
```kotlin
        "synthesize" -> {
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        }
```

  Change the `audio-start` handler (currently lines ~154-163) to clear the watchdog (playback dismiss takes over):
```kotlin
        "audio-start" -> {
            ttsActive = true
            watchdogAtMs = null
            listOf(
                SatelliteAction.PlaybackStart(
                    rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                    width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                    channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
                ),
            )
        }
```

  Add the watchdog arm to `onWakeDetected` (after `micTimestampMs = 0L`, currently line ~210):
```kotlin
        wakeState = WakeState.STREAMING
        micTimestampMs = 0L
        watchdogAtMs = nowMs + WATCHDOG_MS
```

  Add the watchdog block at the TOP of `onTick`, before the existing dismiss line. Replace the start of `onTick`:
```kotlin
    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Voice overlay auto-dismiss (~4 s after playback).
        dismissAtMs?.let { if (nowMs >= it) { dismissAtMs = null; actions += overlayAction(VoiceOverlayState()) } }
```
with:
```kotlin
    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Watchdog: a stalled pipeline (no transcript, or answer text but no playback) must not
        // strand the pill. LISTENING/THINKING fail loudly; RESPONSE hides quietly.
        watchdogAtMs?.let {
            if (nowMs >= it) {
                watchdogAtMs = null
                when (overlay.phase) {
                    VoiceOverlayPhase.LISTENING, VoiceOverlayPhase.THINKING -> actions += failActions(nowMs)
                    VoiceOverlayPhase.RESPONSE -> actions += overlayAction(VoiceOverlayState())
                    else -> {}
                }
            }
        }
        // Voice overlay auto-dismiss (~4 s after playback, 3 s after a FAILED flash).
        dismissAtMs?.let { if (nowMs >= it) { dismissAtMs = null; actions += overlayAction(VoiceOverlayState()) } }
```

  Add `watchdogAtMs = null` to `reset()` (after `dismissAtMs = null`):
```kotlin
        dismissAtMs = null
        watchdogAtMs = null
```

  Add the `failActions` helper directly above the existing `overlayAction` private function:
```kotlin
    /**
     * Fail the current run: show the "no response" pill for [FAILED_MS], then let the existing
     * dismiss path hide it. Mirrors the error cleanup (stop streaming, re-arm the local detector)
     * in localWake mode; a no-op cleanup otherwise. Clears the watchdog it was called from.
     */
    private fun failActions(nowMs: Long): List<SatelliteAction> {
        dismissAtMs = nowMs + FAILED_MS
        watchdogAtMs = null
        val cleanup = if (localWake) {
            wakeState = WakeState.DETECTING
            listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
        } else {
            emptyList()
        }
        return cleanup + overlayAction(VoiceOverlayState(VoiceOverlayPhase.FAILED, FAILED_TEXT))
    }
```

  Add the constants to the `companion object` (after `const val DISMISS_MS = 4000L`):
```kotlin
        const val WATCHDOG_MS = 30_000L
        const val FAILED_MS = 3_000L
        const val FAILED_TEXT = "No response — try again"
```

- [ ] **Step 5: Keep `VoiceOverlay.kt` compiling** — the `val label = when (state.phase)` expression is now non-exhaustive. In `VoiceOverlay.kt`, replace the `when` block (currently lines ~87-92):
```kotlin
    val label = when (state.phase) {
        VoiceOverlayPhase.LISTENING -> "Listening…"
        VoiceOverlayPhase.TRANSCRIPT -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.RESPONSE -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.HIDDEN -> ""
    }
```
with (dots + clickable land in Task 5; this is the minimal exhaustiveness fix):
```kotlin
    val label = when (state.phase) {
        VoiceOverlayPhase.LISTENING -> "Listening…"
        VoiceOverlayPhase.TRANSCRIPT -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.THINKING -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.RESPONSE -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.FAILED -> "No response — try again"
        VoiceOverlayPhase.HIDDEN -> ""
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest"`
Expected: PASS — including the updated `detectionTranscriptSynthesizeDriveOverlay`, the three new watchdog tests, AND the untouched `overlayAutoDismissesFourSecondsAfterPlayback` (the 4 s post-TTS dismiss stays green: it never arms `audio-start`, so its watchdog @ 30 000 never fires before the 14 000 dismiss hides the pill).

- [ ] **Step 7: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt
git commit -m "feat(voice): THINKING phase + 30s pipeline watchdog

transcript now drives THINKING (was terminal TRANSCRIPT); entering
LISTENING/THINKING and synthesize arm a 30s watchdog, audio-start clears
it. On expiry: LISTENING/THINKING flash FAILED (3s) with error-style
cleanup; RESPONSE-without-playback hides quietly.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 2: Failure exits — `error` and mid-run `run-satellite`

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt`
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`

**Interfaces:**
- Consumes: `failActions(nowMs)`, `VoiceOverlayPhase.FAILED`, `WATCHDOG_MS`/`FAILED_MS`/`FAILED_TEXT` (Task 1).
- Produces: `error` → immediate FAILED; `run-satellite` → FAILED when phase is `LISTENING`/`THINKING`, unchanged arming otherwise.

- [ ] **Step 1: Write the failing tests** — add to `SatelliteSessionTest.kt` before the final `}`:
```kotlin
    @Test
    fun errorDuringThinkingShowsFailedThenAutoHides() {
        val s = session()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)  // THINKING
        val err = s.onEvent(event("error", """{"text":"boom"}"""), nowMs = 2_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (err.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(s.onTick(nowMs = 4_999).none { it is SatelliteAction.Overlay })  // before +3 s
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (s.onTick(nowMs = 5_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun midRunRunSatelliteFailsButAtStartArmsNormally() {
        val s = session()
        // Session start (phase HIDDEN): run-satellite arms as today, no FAILED overlay.
        val start = s.onEvent(event("run-satellite"), nowMs = 0)
        assertTrue(start.none { it is SatelliteAction.Overlay })
        assertTrue(sends(start).map { it.type }.contains("run-pipeline"))
        // Mid-run (THINKING): a fresh run-satellite means HA abandoned the pipeline -> FAILED.
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)  // THINKING
        val mid = s.onEvent(event("run-satellite"), nowMs = 2_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.FAILED, "No response — try again"),
            (mid.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest.errorDuringThinkingShowsFailedThenAutoHides" --tests "com.rar.echodash.voice.SatelliteSessionTest.midRunRunSatelliteFailsButAtStartArmsNormally"`
Expected: FAIL — `error` still returns `emptyList()`/no overlay; `run-satellite` still arms unconditionally (no FAILED overlay in `mid`).

- [ ] **Step 3: Route `error` and mid-run `run-satellite` through `failActions`** — in `SatelliteSession.kt`:

  Replace the `run-satellite` handler (currently lines ~101-118) with the phase-guarded version:
```kotlin
        "run-satellite" -> if (overlay.phase == VoiceOverlayPhase.LISTENING || overlay.phase == VoiceOverlayPhase.THINKING) {
            // HA abandoned the pipeline mid-run (e.g. empty LLM response). Fail rather than re-arm silently.
            failActions(nowMs)
        } else if (localWake) {
            // Arm on-device detection: mic on, but no pipeline and no streaming yet.
            wakeState = WakeState.DETECTING
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(WyomingEvent("streaming-stopped")),
                SatelliteAction.ResetDetector,
                SatelliteAction.StartMic,
            )
        } else {
            streaming = true
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(runPipelineEvent()),
                SatelliteAction.Send(WyomingEvent("streaming-started")),
                SatelliteAction.StartMic,
            )
        }
```

  Replace the `error` handler (currently lines ~147-152) with:
```kotlin
        "error" -> failActions(nowMs)
```
Note: `failActions` still performs the localWake `streaming-stopped` + `ResetDetector` + `wakeState = DETECTING` cleanup, so the existing `localWakeErrorStopsStreamingAndReArms` test stays green; it now also flashes the FAILED pill.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest"`
Expected: PASS — the two new tests plus every prior SatelliteSession test (notably `localWakeErrorStopsStreamingAndReArms`, `localWakeRunSatelliteArmsMicWithoutRunPipeline`, `runSatelliteStartsMicAndSendsRunPipeline`).

- [ ] **Step 5: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt
git commit -m "feat(voice): FAILED exits on error + mid-run run-satellite

error always flashes the 'No response' pill (keeping localWake cleanup);
run-satellite while LISTENING/THINKING treats the pipeline as abandoned
and fails, while run-satellite at session start arms exactly as before.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 3: Playback abort plumbing

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt`
- Modify: `app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt`
- Modify: `app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`
- Test: `app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt`
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt` (compile-keeping override)

**Interfaces:**
- Consumes: nothing from the session (this is inert plumbing; Task 4 emits the action).
- Produces (Task 4 relies on these):
  - `data object SatelliteAction.PlaybackAbort : SatelliteAction`
  - `SatelliteServer.Out.onPlaybackAbort()` and its `dispatch` case `SatelliteAction.PlaybackAbort -> out.onPlaybackAbort()`
  - `AnnouncePlayer.abort()` — enqueues the existing `Cmd.Abort` (drops buffered audio, does NOT fire `onPlayed`).
  - `AndroidPcmSink.abort()` — now `pause(); flush(); stop()` then `release()` for immediate silence.

- [ ] **Step 1: Write the failing test** — add to `AnnouncePlayerTest.kt` before the final `}` (mirrors `disconnectAbortsWithoutPlayed`, but via the new public `abort()`):
```kotlin
    @Test
    fun abortStopsPlaybackWithoutPlayed() = runTest {
        val h = Harness(this)
        h.player.onAudioStart(22050, 2, 1)
        h.player.onAudioChunk(ByteArray(10))
        h.player.abort()
        h.player.shutdown()
        advanceUntilIdle()
        assertEquals(0, h.playedCount)                       // no onPlayed on abort
        assertEquals(listOf(true, false), h.ducks)           // ducked then un-ducked
        assertEquals("abort", h.sink.calls.last())           // sink aborted (dropped buffer)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.AnnouncePlayerTest.abortStopsPlaybackWithoutPlayed"`
Expected: FAIL — Kotlin compilation error `unresolved reference: abort` (no public `AnnouncePlayer.abort()` yet).

- [ ] **Step 3: Add the public `abort()` to `AnnouncePlayer`** — in `AnnouncePlayer.kt`, add directly after `onDisconnected()` (both enqueue `Cmd.Abort`; keep them separate so the call sites read clearly):
```kotlin
    /** Stop playback immediately, dropping any buffered audio. Does not fire onPlayed. */
    fun abort() {
        queue.trySend(Cmd.Abort)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.vaca.AnnouncePlayerTest"`
Expected: PASS — `abortStopsPlaybackWithoutPlayed` plus the existing AnnouncePlayer tests.

- [ ] **Step 5: Add the sealed action, the `Out` method, and the dispatch case** — in `SatelliteServer.kt`:

  In the `SatelliteAction` sealed interface (in `SatelliteSession.kt`, after `data object PlaybackStop : SatelliteAction`), add:
```kotlin
    data object PlaybackAbort : SatelliteAction
```

  In `SatelliteServer.kt`, add to the `Out` interface (after `fun onPlaybackStop()`):
```kotlin
        fun onPlaybackAbort()
```

  In `SatelliteServer.dispatch()`, add the case (after `SatelliteAction.PlaybackStop -> out.onPlaybackStop()`):
```kotlin
            SatelliteAction.PlaybackAbort -> out.onPlaybackAbort()
```

- [ ] **Step 6: Make `AndroidPcmSink.abort()` silence immediately** — in `AndroidPcmSink.kt`, replace `abort()` (currently lines ~67-74):
```kotlin
    override fun abort() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
        framesWritten = 0
    }
```
with (adds `stop()` per the spec so the track halts, not just pauses):
```kotlin
    override fun abort() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        framesWritten = 0
    }
```
Note: this is Android `AudioTrack` code (no plain-JVM unit test — the test `FakeSink` only records `"abort"`); it is verified by the gate compile and live on device. Echo HAL note from the spec: `pause`+`flush` is a standard path and the ≥300 ms trailing-silence quirk applies to short one-shot *starts*, not aborts.

- [ ] **Step 7: Wire the real `Out` and satisfy the test `Out`** — implement the new method in both `SatelliteServer.Out` implementers:

  In `App.kt`, add to the anonymous `object : SatelliteServer.Out` (after the `onPlaybackStop` override, ~line 417):
```kotlin
            override fun onPlaybackAbort() = voicePlayer.abort()
```

  In `SatelliteServerTest.kt`, add to `RecordingOut` (after the `onPlaybackStop` override, ~line 31):
```kotlin
        override fun onPlaybackAbort() { calls.put("pb-abort") }
```

- [ ] **Step 8: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests green. (`SatelliteServer.dispatch` compiles exhaustively; both `Out` implementers compile; the new action is defined but not yet emitted.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt \
        app/src/main/java/com/rar/echodash/vaca/AnnouncePlayer.kt \
        app/src/main/java/com/rar/echodash/vaca/AndroidPcmSink.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/test/java/com/rar/echodash/vaca/AnnouncePlayerTest.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt
git commit -m "feat(voice): playback-abort primitive (action + Out + sink stop)

SatelliteAction.PlaybackAbort -> Out.onPlaybackAbort -> AnnouncePlayer.abort()
(enqueues the existing non-onPlayed Cmd.Abort). AndroidPcmSink.abort() now
pause/flush/stop for immediate silence. Inert until the tap wiring emits it.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 4: Tap-to-interrupt and suppression

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt`
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt`
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`

**Interfaces:**
- Consumes: `SatelliteAction.PlaybackAbort` (Task 3), `WATCHDOG_MS` (Task 1), all phases.
- Produces (Task 5 relies on these):
  - `SatelliteSession.onOverlayTapped(nowMs: Long): List<SatelliteAction>`
  - `SatelliteServer.onOverlayTapped()` — synchronizes on `lock` and dispatches against the active session.
  - `private var suppressRun: Boolean` — set by a THINKING tap; swallows the run's late `synthesize`/`audio-start`/`audio-chunk` and turns a suppressed `audio-stop` into an immediate `played`; reset on the next wake/detection/run-satellite and on `reset()`.

- [ ] **Step 1: Write the failing tests** — add to `SatelliteSessionTest.kt` before the final `}`:
```kotlin
    @Test
    fun tapDuringResponseAbortsPlaybackHidesAndUngatesMic() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 0)        // -> DETECTING, THINKING
        s.onEvent(event("synthesize", """{"text":"Answer"}"""), nowMs = 0)    // RESPONSE
        s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}"""), nowMs = 0) // ttsActive
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())              // mic gated during playback
        val tap = s.onOverlayTapped(nowMs = 100)
        assertTrue(tap.contains(SatelliteAction.PlaybackAbort))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (tap.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isNotEmpty())           // mic un-gated after abort
    }

    @Test
    fun tapDuringThinkingSuppressesRunButCompletesPipeline() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 0)        // THINKING (wakeState DETECTING)
        val tap = s.onOverlayTapped(nowMs = 0)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (tap.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertFalse(tap.contains(SatelliteAction.PlaybackAbort))              // nothing playing yet
        // The run's late events are swallowed:
        assertTrue(s.onEvent(event("synthesize", """{"text":"late"}""")).isEmpty())
        assertTrue(s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}""")).isEmpty())
        assertTrue(s.onEvent(event("audio-chunk", null, ByteArray(8) { 1 })).isEmpty())
        // ...but audio-stop still completes HA's pipeline:
        assertEquals("played", sends(s.onEvent(event("audio-stop"))).single().type)
        // Next wake clears suppression and behaves normally:
        val wake = s.onWakeDetected("alexa", nowMs = 0)
        assertTrue(sends(wake).map { it.type }.contains("run-pipeline"))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (wake.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest.tapDuringResponseAbortsPlaybackHidesAndUngatesMic" --tests "com.rar.echodash.voice.SatelliteSessionTest.tapDuringThinkingSuppressesRunButCompletesPipeline"`
Expected: FAIL — Kotlin compilation error `unresolved reference: onOverlayTapped` (the entry point and suppression don't exist yet).

- [ ] **Step 3: Add `suppressRun`, the tap entry point, and suppression guards** — in `SatelliteSession.kt`:

  Add the field beside `watchdogAtMs` (after `private var watchdogAtMs: Long? = null`):
```kotlin
    private var suppressRun = false
```

  Reset `suppressRun` on the three "new interaction" entry points. In the `run-satellite` handler, add `suppressRun = false` as the first line of BOTH non-fail branches. The handler becomes:
```kotlin
        "run-satellite" -> if (overlay.phase == VoiceOverlayPhase.LISTENING || overlay.phase == VoiceOverlayPhase.THINKING) {
            // HA abandoned the pipeline mid-run (e.g. empty LLM response). Fail rather than re-arm silently.
            failActions(nowMs)
        } else if (localWake) {
            suppressRun = false
            wakeState = WakeState.DETECTING
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(WyomingEvent("streaming-stopped")),
                SatelliteAction.ResetDetector,
                SatelliteAction.StartMic,
            )
        } else {
            suppressRun = false
            streaming = true
            micTimestampMs = 0L
            listOf(
                SatelliteAction.Send(runPipelineEvent()),
                SatelliteAction.Send(WyomingEvent("streaming-started")),
                SatelliteAction.StartMic,
            )
        }
```

  In the `detection` handler, add `suppressRun = false`:
```kotlin
        "detection" -> {
            // Legacy/fallback: HA reports the wake word. In localWake HA never sends this.
            suppressRun = false
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(
                SatelliteAction.Earcon(EarconKind.WAKE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
            )
        }
```

  In `onWakeDetected`, add `suppressRun = false` (after `micTimestampMs = 0L`):
```kotlin
        wakeState = WakeState.STREAMING
        micTimestampMs = 0L
        suppressRun = false
        watchdogAtMs = nowMs + WATCHDOG_MS
```

  Wrap `synthesize`, `audio-start`, `audio-chunk`, and `audio-stop` in the suppression guard:
```kotlin
        "synthesize" -> if (suppressRun) emptyList() else {
            watchdogAtMs = nowMs + WATCHDOG_MS
            listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        }
        "audio-start" -> if (suppressRun) emptyList() else {
            ttsActive = true
            watchdogAtMs = null
            listOf(
                SatelliteAction.PlaybackStart(
                    rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                    width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                    channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
                ),
            )
        }
        "audio-chunk" -> if (suppressRun) emptyList() else listOf(SatelliteAction.PlaybackChunk(event.payload))
        "audio-stop" -> if (suppressRun) listOf(SatelliteAction.Send(WyomingEvent("played"))) else listOf(SatelliteAction.PlaybackStop)
```

  Add `suppressRun = false` to `reset()` (after `watchdogAtMs = null`):
```kotlin
        watchdogAtMs = null
        suppressRun = false
```

  Add the tap entry point directly after `onPlaybackFinished` (nowMs is part of the required signature and kept for parity with the other bridged entry points even though the immediate-hide paths don't read it):
```kotlin
    /**
     * The user tapped the voice pill. RESPONSE aborts playback and hides (mic un-gates via
     * ttsActive); THINKING hides and suppresses the rest of the in-flight run so HA's pipeline
     * still completes; FAILED hides immediately. LISTENING/HIDDEN/TRANSCRIPT are no-ops.
     */
    fun onOverlayTapped(nowMs: Long): List<SatelliteAction> = when (overlay.phase) {
        VoiceOverlayPhase.RESPONSE -> {
            ttsActive = false
            dismissAtMs = null
            watchdogAtMs = null
            listOf(SatelliteAction.PlaybackAbort, overlayAction(VoiceOverlayState()))
        }
        VoiceOverlayPhase.THINKING -> {
            suppressRun = true
            dismissAtMs = null
            watchdogAtMs = null
            listOf(overlayAction(VoiceOverlayState()))
        }
        VoiceOverlayPhase.FAILED -> {
            dismissAtMs = null
            watchdogAtMs = null
            listOf(overlayAction(VoiceOverlayState()))
        }
        else -> emptyList()
    }
```

- [ ] **Step 4: Add the server bridge** — in `SatelliteServer.kt`, add after `onPlaybackFinished()` (mirrors `dismissTimerAlert`: `onOverlayTapped` emits only `out`-side actions, never `Send`, so a null `active` is safe):
```kotlin
    /** Tap on the voice pill: abort playback / cancel a thinking run (may run with no connection). */
    fun onOverlayTapped() {
        synchronized(lock) { dispatch(active, session.onOverlayTapped(System.currentTimeMillis())) }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.SatelliteSessionTest"`
Expected: PASS — the two new tap tests plus every prior SatelliteSession test (`ttsAudioRoutesToPlaybackAndPlayedAfterFinish`, `localWakeDropsDetectorFeedDuringTtsWindow`, etc. are unaffected: `suppressRun` is false on every normal path).

- [ ] **Step 6: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt
git commit -m "feat(voice): tap-to-interrupt (abort playback / cancel thinking)

RESPONSE tap emits PlaybackAbort + hides + un-gates mic; THINKING tap hides
and suppresses the in-flight run (late synthesize/audio-* swallowed,
audio-stop still emits played so HA completes cleanly); FAILED tap hides.
Suppression resets on the next wake/detection/run-satellite.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 5: UI — thinking dots, failed style, clickable pill, App wiring

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt`

No unit tests: the state machine is already fully covered (Tasks 1–4); Compose rendering is verified by the gate build (compile) and live on device. This is the only task without a red/green test cycle.

**Interfaces:**
- Consumes: `VoiceOverlayPhase.THINKING`/`FAILED` (Task 1); `SatelliteServer.onOverlayTapped()` (Task 4).
- Produces: `VoiceOverlay(state: VoiceOverlayState, onTap: () -> Unit, modifier: Modifier = Modifier)` — a breaking signature change to the single existing call site (updated in this task).

- [ ] **Step 1: Add the imports** — in `VoiceOverlay.kt`, add these imports (alphabetically among the existing ones; `Column`, `Box`, `background`, `padding`, `getValue` are already imported):
```kotlin
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
```
(If any of these are already present in the file, skip the duplicates; `TextAlign` is needed by the new `textAlign = TextAlign.Center`.)

- [ ] **Step 2: Rewrite `VoiceOverlay` and add `ThinkingDots`** — replace the whole `VoiceOverlay` composable (currently lines ~84-104, the version with the Task-1 stub `when`) with:
```kotlin
@Composable
fun VoiceOverlay(state: VoiceOverlayState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    if (state.phase == VoiceOverlayPhase.HIDDEN) return
    val label = when (state.phase) {
        VoiceOverlayPhase.LISTENING -> "Listening…"
        VoiceOverlayPhase.TRANSCRIPT -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.THINKING -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.RESPONSE -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.FAILED -> "No response — try again"
        VoiceOverlayPhase.HIDDEN -> ""
    }
    val textColor = if (state.phase == VoiceOverlayPhase.FAILED) Color(0xFFB0B4BE) else Color.White
    Box(modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xE6101218),
            modifier = Modifier.clickable { onTap() },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(label, color = textColor, fontSize = 18.sp, textAlign = TextAlign.Center)
                if (state.phase == VoiceOverlayPhase.THINKING) {
                    ThinkingDots(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Three voice-blue dots with a staggered alpha pulse (~900 ms cycle, 150 ms per-dot stagger). */
@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "thinkingDots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by pulse.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(450, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 150),
                ),
                label = "thinkingDot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .background(Color(0xFF4FC3F7).copy(alpha = alpha), CircleShape),
            )
        }
    }
}
```
Note: a 450 ms `tween` with `RepeatMode.Reverse` is a ~900 ms full cycle; `StartOffset(i * 150)` gives the 150 ms per-dot stagger. `WakeGlow` is untouched and stays LISTENING-only.

- [ ] **Step 3: Wire the tap at the call site** — in `App.kt`, replace the `VoiceOverlay` call (currently line ~962):
```kotlin
                    VoiceOverlay(voiceOverlayState)
```
with:
```kotlin
                    VoiceOverlay(voiceOverlayState, onTap = { deps.satellite.onOverlayTapped() })
```
Note: night-mode suppression (`voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN`, ~line 930) and screen-wake (~line 937) already key on `phase != HIDDEN`, so the new THINKING/FAILED phases ride along unchanged — no edits there.

- [ ] **Step 4: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all tests green. (The `VoiceOverlay` signature change compiles at its one call site; `ThinkingDots` and the new imports resolve.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt \
        app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(voice): thinking dots, failed pill style, clickable pill

THINKING shows the transcript with three staggered voice-blue pulsing dots;
FAILED shows 'No response — try again' in a muted tone; the pill Surface is
now clickable and forwards taps to satellite.onOverlayTapped().

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

- [ ] **Step 6: Live verification (manual, after flashing)** — per the spec, on the desk Echo (10.75.1.98): normal question → transcript + pulsing dots → spoken answer; tap mid-answer → immediate silence + pill gone; if tonight's empty-response failure recurs → "No response — try again" then auto-hide after 3 s. M9 caveat: the night-clock overlay eats scripted taps — wake-tap first.

---

## Self-Review

**1. Spec coverage** — every spec section maps to a task:
- State machine: `THINKING` (T1), `FAILED` (T1/T2), `TRANSCRIPT` kept in enum + rendered THINKING-like (T1 stub / T5). ✔
- Transitions: transcript→THINKING (T1); error→FAILED (T2); mid-run run-satellite→FAILED, HIDDEN unchanged (T2); watchdog arm/re-arm/clear/fire incl. RESPONSE quiet-hide (T1); FAILED auto-hide via `dismissAtMs` (T1). ✔
- Tap-to-interrupt: RESPONSE→PlaybackAbort+HIDDEN+ttsActive clear (T4); THINKING→HIDDEN+suppressRun with swallow + audio-stop→played + reset triggers (T4); FAILED→HIDDEN, LISTENING/HIDDEN no-op (T4); server bridge `onOverlayTapped` (T4). ✔
- Playback abort: `PlaybackAbort` action + `Out.onPlaybackAbort` (T3); `AnnouncePlayer.abort()` reusing the non-`onPlayed` `Cmd.Abort` (T3); `AndroidPcmSink.abort()` pause/flush/stop (T3); App wiring (T3). ✔
- UI: THINKING dots (color/timing), FAILED muted color, clickable pill, `WakeGlow` LISTENING-only, App `onTap` (T5). ✔
- Tests 1–9: T1 covers 1,2,3,6 and keeps the 4 s dismiss test green (9); T2 covers 4,5; T4 covers 7,8. ✔
- Out of scope (barge-in, server-side abort, timer overlay) — untouched. ✔

**2. Placeholder scan** — no TBD/TODO/"similar to Task N"; every code step shows complete code; every command has expected output. Note: the Task-1 `VoiceOverlayState.kt` "replace" block shows the intentionally-wrong current-vs-target lines only to anchor the edit; the target line is complete. ✔

**3. Type consistency** — names are stable across tasks: `watchdogAtMs`, `suppressRun`, `failActions(nowMs)`, `WATCHDOG_MS`/`FAILED_MS`/`FAILED_TEXT`, `VoiceOverlayPhase.THINKING`/`FAILED`, `SatelliteAction.PlaybackAbort`, `Out.onPlaybackAbort()`, `AnnouncePlayer.abort()`, `SatelliteSession.onOverlayTapped(nowMs)`, `SatelliteServer.onOverlayTapped()`, `VoiceOverlay(state, onTap, modifier)`. `failActions` is defined in T1 and only consumed by T2/onTick. `PlaybackAbort` is defined in T3 and only emitted in T4. ✔

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-17-voice-feedback-ui.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
