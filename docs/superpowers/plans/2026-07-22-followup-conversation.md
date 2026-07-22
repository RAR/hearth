# Follow-up Conversations (Continue Conversation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reopen the satellite mic for a follow-up utterance — no fresh wake word — when the assistant's TTS reply ends in `?`, chained up to 3 rounds, quiet-dismissing on silence.

**Architecture:** All behavior lives in the pure `SatelliteSession` state machine (localWake path only): `onPlaybackFinished` decides reopen-vs-dismiss from `lastResponseText` captured in the `synthesize` handler; error/tick/tap paths quiet-dismiss. A live `() -> Boolean` config provider is threaded App → SatelliteServer → SatelliteSession so the toggle applies without a satellite restart.

**Tech Stack:** Kotlin/Jetpack Compose app; plain-JVM JUnit4 tests; vanilla-JS web-config page.

**Spec (source of truth):** `docs/superpowers/specs/2026-07-22-followup-conversation-design.md`

## Global Constraints

- Gate before EVERY commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` — redirect output to a scratchpad log file and capture `RC=$?`; NEVER pipe gradle to tail/head (grep the log instead). Plus `node --check app/src/main/assets/config/app.js` before any commit touching app.js.
- Scratchpad for gate logs: `/tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad`
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL`
- NO new dependencies. Tests are plain-JVM JUnit4 only. `SatelliteSession` has no Android/coroutine imports — keep it that way. Compose/HTML/JS are NOT unit-tested beyond `node --check`.
- `followUpEnabled` must apply LIVE via a `() -> Boolean` provider threaded through SatelliteServer into SatelliteSession — it must NOT be added to the satellite-restart trigger set in App.kt (restarting drops device-local timers).
- Keep the non-localWake (HA-runs-wake) fallback path unchanged; follow-up is localWake-only.
- New constants (spec-fixed): `MAX_FOLLOW_UP_ROUNDS = 3`, `FOLLOW_UP_LISTEN_MS = 10_000L`. Not configurable.
- Repo root: `/home/rar/android_simpla_ha_dash`, branch `master`. All commands run from the repo root.

**Gate command template** (substitute the task number for `N`):

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-taskN.log 2>&1
RC=$?; echo "RC=$RC"
```

Expected: `RC=0`. On failure, inspect with `grep -n -A5 "FAILED\|error:" <logfile>` — never pipe gradle itself.

---

### Task 1: `VoiceSettings.followUpEnabled` config field

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/config/DashConfig.kt:142-172` (`VoiceSettings`)
- Test: `app/src/test/java/com/rar/hearth/config/DashConfigTest.kt` (add after `wakeThresholdDefaultsAndClamps`, ~:452)

**Interfaces:**
- Consumes: nothing new.
- Produces: `VoiceSettings.followUpEnabled: Boolean` (default `false`), read later by App.kt (Task 4) and web-config (Task 5) as `config.voice.followUpEnabled`.

- [ ] **Step 1: Write the failing test**

Add to `DashConfigTest.kt`, after `wakeThresholdDefaultsAndClamps` (the file already has `decodeConfig` and the `VoiceSettings` import):

```kotlin
    @Test
    fun followUpEnabledDefaultsFalseAndSurvivesClamp() {
        assertEquals(false, DashConfig().voice.followUpEnabled)
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals(false, cfg.voice.followUpEnabled)             // old config -> default
        // Plain boolean: clamped() must pass it through unchanged in both states.
        assertEquals(true, DashConfig(voice = VoiceSettings(followUpEnabled = true)).clamped().voice.followUpEnabled)
        assertEquals(false, DashConfig(voice = VoiceSettings(followUpEnabled = false)).clamped().voice.followUpEnabled)
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.hearth.config.DashConfigTest" \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-task1-fail.log 2>&1
RC=$?; echo "RC=$RC"
```

Expected: `RC=1`, log contains a compile error — `unresolved reference: followUpEnabled` (compile failure IS the failing state for a new field).

- [ ] **Step 3: Add the field**

In `DashConfig.kt`, change the `VoiceSettings` parameter list (`clamped()` needs no change — a plain boolean has nothing to normalize):

```kotlin
@Serializable
data class VoiceSettings(
    val enabled: Boolean = false,
    val timerTone: String = "argon",
    val timerVolume: Int = 80,
    val wakeSoundVolume: Int = 80,
    val wakeWord: String = "okay_nabu",
    val wakeThreshold: Int = 50,
    val followUpEnabled: Boolean = false,
) {
```

- [ ] **Step 4: Run test to verify it passes**

Same command as Step 2 (log to `gate-task1-pass.log`). Expected: `RC=0`.

- [ ] **Step 5: Full gate, then commit**

Run the Global Constraints gate template with `N=1`. Expected `RC=0`. Then:

```bash
cd /home/rar/android_simpla_ha_dash
git add app/src/main/java/com/rar/hearth/config/DashConfig.kt app/src/test/java/com/rar/hearth/config/DashConfigTest.kt
git commit -m "feat(voice): add VoiceSettings.followUpEnabled config field (default off)

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 2: Thread the `followUp` provider through SatelliteServer → SatelliteSession

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/voice/SatelliteSession.kt:61-65` (constructor)
- Modify: `app/src/main/java/com/rar/hearth/voice/SatelliteServer.kt:33-39` (constructor), `:81` and `:97` (the two `SatelliteSession(...)` construction sites)

**Interfaces:**
- Consumes: nothing from Task 1 yet (the provider lambda's source is wired in Task 4).
- Produces:
  - `SatelliteSession(appVersion: String, name: () -> String, localWake: Boolean = false, followUp: () -> Boolean = { false })` — Task 3's tests construct sessions with the `followUp` named argument.
  - `SatelliteServer(scope, port = PORT, appVersion, name, followUp: () -> Boolean = { false }, out)` — Task 4 passes `followUp = { ... }` at the App.kt construction site.
- Defaulting both new parameters to `{ false }` keeps every existing call site compiling unchanged, so this commit is green on its own. (Verified: App.kt:458 and all three `SatelliteServerTest.kt` sites (:54, :161, :191) construct with NAMED arguments — e.g. `SatelliteServer(scope, port = 0, appVersion = "0.3", name = { "Test Sat" }, out = out)` — so inserting a defaulted param before `out` breaks nothing.)

This is pure plumbing with no observable behavior yet, so there is no new unit test; the deliverable is verified by the full gate (compile + all 1043+ existing tests still green). A reviewer accepts/rejects this task on signature correctness alone.

- [ ] **Step 1: Add the parameter to SatelliteSession**

In `SatelliteSession.kt`, change the class header:

```kotlin
class SatelliteSession(
    private val appVersion: String,
    private val name: () -> String,
    private val localWake: Boolean = false,
    private val followUp: () -> Boolean = { false },
) {
```

- [ ] **Step 2: Add the parameter to SatelliteServer and pass it through both construction sites**

In `SatelliteServer.kt`, change the class header (new param between `name` and `out`, defaulted so App.kt still compiles until Task 4):

```kotlin
class SatelliteServer(
    private val scope: CoroutineScope,
    private val port: Int = PORT,
    private val appVersion: String,
    private val name: () -> String,
    private val followUp: () -> Boolean = { false },
    private val out: Out,
) {
```

Change the field initializer at ~:81:

```kotlin
    @Volatile private var session = SatelliteSession(appVersion, name, followUp = followUp)
```

Change the `start()` construction at ~:97:

```kotlin
        session = SatelliteSession(appVersion, name, localWake, followUp = followUp)
```

- [ ] **Step 3: Full gate**

Run the Global Constraints gate template with `N=2`. Expected `RC=0` (everything compiles, all existing tests pass — behavior is unchanged because `{ false }` defaults make `followUp()` never true).

- [ ] **Step 4: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add app/src/main/java/com/rar/hearth/voice/SatelliteSession.kt app/src/main/java/com/rar/hearth/voice/SatelliteServer.kt
git commit -m "feat(voice): thread live followUp provider through SatelliteServer into SatelliteSession

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 3: SatelliteSession follow-up state machine (the core)

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/voice/SatelliteSession.kt` (handlers: `synthesize` ~:204, `error` ~:187, `transcript` ~:160, `onPlaybackFinished` ~:321, `onOverlayTapped` ~:332, `onWakeDetected` ~:262, `onTick` ~:369, `reset()` ~:412, companion constants ~:550)
- Test: `app/src/test/java/com/rar/hearth/voice/SatelliteSessionTest.kt` (append after `tapDuringThinkingSuppressesRunButCompletesPipeline`)

**Interfaces:**
- Consumes: `SatelliteSession` constructor `followUp: () -> Boolean = { false }` from Task 2; existing `runPipelineLocalEvent()`, `overlayAction`, `textOf`, `WakeState`, `EarconKind.WAKE`.
- Produces: companion constants `MAX_FOLLOW_UP_ROUNDS: Int = 3` and `FOLLOW_UP_LISTEN_MS: Long = 10_000L` (public, referenced by tests); no new public methods — all behavior rides existing entry points.

**Design notes carried from the spec (do not deviate):**
- Reopen iff `localWake && followUp() && !suppressRun && lastResponseText.trim().endsWith("?") && followUpRound < MAX_FOLLOW_UP_ROUNDS`, additionally guarded on `wakeState == WakeState.DETECTING` (the normal post-transcript state at playback end; excludes PAUSED — mic is off — and IDLE, mirroring `onWakeDetected`'s guard).
- Reopen wire order: `played`, asr `run-pipeline`, `streaming-started`, WAKE earcon, LISTENING overlay. No `dismissAtMs`. Mic stays on through TTS (gated by `ttsActive`), so no `StartMic`.
- Quiet dismiss (shared by error/tick/tap): clear follow-up state + `watchdogAtMs` + `dismissAtMs`, `wakeState = DETECTING`, emit `streaming-stopped` + `ResetDetector` + hidden overlay. No FAILED flash. Same shape as the existing `alarmSilencedThisRun` error branch, which keeps precedence.
- `onTick`'s follow-up deadline check runs BEFORE the watchdog block and clears `watchdogAtMs`, so a follow-up silence can never reach the loud LISTENING watchdog.

- [ ] **Step 1: Write the failing tests**

Append to `SatelliteSessionTest.kt`, inside the class, after `tapDuringThinkingSuppressesRunButCompletesPipeline` (reuses the file's existing `event`, `sends`, `timers` helpers):

```kotlin
    // ---- follow-up conversations ----

    private fun followUpSession(enabled: () -> Boolean = { true }) =
        SatelliteSession(appVersion = "9.9", name = { "Test Sat" }, localWake = true, followUp = enabled)

    /** Drive wake -> transcript -> synthesize(text) so onPlaybackFinished can decide. */
    private fun driveToResponse(s: SatelliteSession, text: String, nowMs: Long) {
        s.onEvent(event("transcript", """{"text":"turn on the lights"}"""), nowMs = nowMs)
        s.onEvent(event("synthesize", """{"text":"$text"}"""), nowMs = nowMs)
        s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}"""), nowMs = nowMs)
        s.onEvent(event("audio-stop"), nowMs = nowMs)
    }

    @Test
    fun followUpReopensWhenResponseEndsInQuestionMark() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        val a = s.onPlaybackFinished(nowMs = 4_000)
        val ev = sends(a)
        assertEquals("played", ev[0].type)
        assertEquals("run-pipeline", ev[1].type)
        assertEquals("asr", ev[1].data["start_stage"]!!.jsonPrimitive.content)
        assertEquals("tts", ev[1].data["end_stage"]!!.jsonPrimitive.content)
        assertEquals(false, ev[1].data["restart_on_end"]!!.jsonPrimitive.boolean)
        assertEquals("streaming-started", ev[2].type)
        assertTrue(a.contains(SatelliteAction.Earcon(EarconKind.WAKE)))
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (a.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertFalse(a.contains(SatelliteAction.StartMic))              // mic never went off
        // Mic chunks stream straight to HA again — no new wake word.
        assertEquals("audio-chunk", sends(s.onMicChunk(ByteArray(960) { 3 })).single().type)
        // No dismissAtMs was set: before the 10 s listen deadline nothing hides the overlay.
        assertTrue(s.onTick(nowMs = 13_999).none { it is SatelliteAction.Overlay })
        assertEquals(VoiceOverlayPhase.LISTENING, s.overlay.phase)
    }

    @Test
    fun followUpDisabledDismissesNormally() {
        val s = followUpSession(enabled = { false })
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        val a = s.onPlaybackFinished(nowMs = 4_000)
        assertEquals(listOf("played"), sends(a).map { it.type })       // no run-pipeline
        assertTrue(a.none { it is SatelliteAction.Earcon })
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),      // normal +4 s auto-dismiss
            (s.onTick(nowMs = 8_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun followUpNotOpenedWithoutTrailingQuestionMark() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Okay, lights on.", nowMs = 1_000)
        val a = s.onPlaybackFinished(nowMs = 4_000)
        assertEquals(listOf("played"), sends(a).map { it.type })
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (s.onTick(nowMs = 8_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun suppressedRunNeverReopensAndStoresNoText() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 0) // THINKING
        s.onOverlayTapped(nowMs = 100)                                 // tap -> suppressRun
        assertTrue(s.onEvent(event("synthesize", """{"text":"Which room?"}""")).isEmpty()) // swallowed
        val a = s.onPlaybackFinished(nowMs = 1_000)
        assertEquals(listOf("played"), sends(a).map { it.type })       // no reopen
    }

    @Test
    fun followUpChainsUpToCapThenDismisses() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        var now = 1_000L
        repeat(3) { round ->
            driveToResponse(s, "And then?", nowMs = now)
            val a = s.onPlaybackFinished(nowMs = now)
            assertTrue("round $round should reopen",
                sends(a).map { it.type }.contains("run-pipeline"))
            now += 1_000
        }
        // Round 4: cap reached -> normal dismiss.
        driveToResponse(s, "And then?", nowMs = now)
        val capped = s.onPlaybackFinished(nowMs = now)
        assertEquals(listOf("played"), sends(capped).map { it.type })
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (s.onTick(nowMs = now + 4_000).last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
    }

    @Test
    fun silenceDuringFollowUpDismissesQuietlyViaError() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        s.onPlaybackFinished(nowMs = 2_000)                            // reopened
        val a = s.onEvent(event("error", """{"text":"stt timeout"}"""), nowMs = 9_000)
        assertEquals(VoiceOverlayState(), s.overlay)                   // NO FAILED flash
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        // Re-armed: the next mic chunk feeds the detector again.
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())
        // A later, unrelated error still fails loudly.
        s.onWakeDetected("alexa", nowMs = 10_000)
        s.onEvent(event("error"), nowMs = 11_000)
        assertEquals(VoiceOverlayPhase.FAILED, s.overlay.phase)
    }

    @Test
    fun followUpDeadlineTickDismissesQuietly() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        s.onPlaybackFinished(nowMs = 2_000)                            // deadline @ 12_000
        assertTrue(s.onTick(nowMs = 11_999).isEmpty())                 // before deadline: nothing
        val a = s.onTick(nowMs = 12_000)
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.HIDDEN),
            (a.last { it is SatelliteAction.Overlay } as SatelliteAction.Overlay).state)
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        // The quiet dismiss also cleared the watchdog: no loud FAILED ever fires later.
        assertTrue(s.onTick(nowMs = 60_000).none { it is SatelliteAction.Overlay })
    }

    @Test
    fun tapDuringFollowUpCancelsQuietlyAndReArms() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        s.onPlaybackFinished(nowMs = 2_000)                            // reopened, LISTENING
        val a = s.onOverlayTapped(nowMs = 3_000)
        assertEquals(VoiceOverlayState(), s.overlay)                   // hidden, no FAILED
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())                // detector re-armed
        // A NON-follow-up LISTENING tap stays a no-op (existing behavior preserved).
        s.onWakeDetected("alexa", nowMs = 4_000)
        assertTrue(s.onOverlayTapped(nowMs = 4_500).isEmpty())
    }

    @Test
    fun freshWakeResetsFollowUpChain() {
        val s = followUpSession()
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        var now = 1_000L
        repeat(3) {                                                    // exhaust the cap
            driveToResponse(s, "And then?", nowMs = now)
            s.onPlaybackFinished(nowMs = now)
            now += 1_000
        }
        driveToResponse(s, "And then?", nowMs = now)
        s.onPlaybackFinished(nowMs = now)                              // capped -> dismissed
        s.onTick(nowMs = now + 4_000)                                  // overlay hidden
        // New wake: followUpRound/lastResponseText reset, so follow-up works again.
        s.onWakeDetected("alexa", nowMs = now + 10_000)
        driveToResponse(s, "Which room?", nowMs = now + 11_000)
        assertTrue(sends(s.onPlaybackFinished(nowMs = now + 12_000))
            .map { it.type }.contains("run-pipeline"))
    }

    @Test
    fun followUpProviderIsReadLive() {
        var on = false
        val s = followUpSession(enabled = { on })
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onWakeDetected("alexa", nowMs = 0)
        driveToResponse(s, "Which room?", nowMs = 1_000)
        assertEquals(listOf("played"), sends(s.onPlaybackFinished(nowMs = 2_000)).map { it.type })
        s.onTick(nowMs = 6_000)                                        // dismissed
        on = true                                                      // toggle flips LIVE, no restart
        s.onWakeDetected("alexa", nowMs = 10_000)
        driveToResponse(s, "Which room?", nowMs = 11_000)
        assertTrue(sends(s.onPlaybackFinished(nowMs = 12_000)).map { it.type }.contains("run-pipeline"))
    }

    @Test
    fun legacyModeNeverReopens() {
        // Non-localWake (HA runs wake): fallback path stays byte-for-byte unchanged.
        val s = SatelliteSession(appVersion = "9.9", name = { "Test Sat" }, followUp = { true })
        s.onEvent(event("run-satellite"), nowMs = 0)
        s.onEvent(event("detection", """{"name":"x"}"""), nowMs = 0)
        s.onEvent(event("transcript", """{"text":"hi"}"""), nowMs = 1_000)
        s.onEvent(event("synthesize", """{"text":"Which room?"}"""), nowMs = 1_000)
        val a = s.onPlaybackFinished(nowMs = 2_000)
        assertEquals(listOf("played"), sends(a).map { it.type })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/rar/android_simpla_ha_dash
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.hearth.voice.SatelliteSessionTest" \
  > /tmp/claude-1000/-home-rar-android-simpla-ha-dash/a1c1e7db-06f6-486a-bea7-780876de1218/scratchpad/gate-task3-fail.log 2>&1
RC=$?; echo "RC=$RC"
```

Expected: `RC=1`; grep the log for the new test names — reopen tests fail on `expected "run-pipeline"` / list-size assertions (constructor already accepts `followUp` from Task 2, so these compile and fail at runtime).

- [ ] **Step 3: Implement follow-up in SatelliteSession.kt**

Six edits, all inside `SatelliteSession`:

**(a) New state fields** — after the existing `private var suppressRun = false` (~:75):

```kotlin
    private var suppressRun = false
    // Follow-up conversation state (localWake only): reopen the mic without a wake word when
    // the assistant's reply is a question. See onPlaybackFinished.
    private var lastResponseText = ""
    private var followUpActive = false
    private var followUpRound = 0
    private var followUpDeadlineAtMs: Long? = null
```

**(b) `synthesize` handler** — capture the response text (suppressed runs store nothing, so they can never trigger a reopen):

```kotlin
        "synthesize" -> if (suppressRun) emptyList() else {
            watchdogAtMs = nowMs + WATCHDOG_MS
            lastResponseText = textOf(event)
            listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        }
```

**(c) `transcript` handler** — the user answered a follow-up: close the listen window. Add two lines at the top of the existing `"transcript" ->` branch (before `val silencedRun = alarmSilencedThisRun`):

```kotlin
        "transcript" -> {
            followUpActive = false
            followUpDeadlineAtMs = null
            val silencedRun = alarmSilencedThisRun
```

(Everything else in the branch is unchanged: the existing localWake re-arm to DETECTING and the THINKING overlay already do the right thing; `lastResponseText` is left to be overwritten by the next `synthesize`, and `followUpRound` keeps counting toward the cap.)

**(d) `error` handler** — insert a quiet-dismiss branch between the existing `alarmSilencedThisRun` branch (which keeps precedence) and `failActions`:

```kotlin
        "error" -> if (alarmSilencedThisRun) {
            // "OK Ember" (to hush the alarm), then nothing: HA's STT times out with an error.
            // The wake already silenced the ring, so dismiss quietly — a FAILED flash would
            // punish a fully successful interaction.
            alarmSilencedThisRun = false
            watchdogAtMs = null
            dismissAtMs = null
            val cleanup = if (localWake) {
                wakeState = WakeState.DETECTING
                listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
            } else {
                emptyList()
            }
            cleanup + overlayAction(VoiceOverlayState())
        } else if (followUpActive) {
            // STT VAD timed out on silence during a follow-up listen: the user chose not to
            // answer. Quiet dismiss — no FAILED flash — and re-arm the wake detector.
            followUpQuietDismiss()
        } else {
            failActions(nowMs)
        }
```

**(e) `onWakeDetected`** — every fresh wake starts a clean chain. Add after the existing `suppressRun = false` line:

```kotlin
        wakeState = WakeState.STREAMING
        micTimestampMs = 0L
        suppressRun = false
        followUpActive = false
        followUpRound = 0
        followUpDeadlineAtMs = null
        lastResponseText = ""
        watchdogAtMs = nowMs + WATCHDOG_MS
```

**(f) `onPlaybackFinished`** — the core decision. Replace the whole method:

```kotlin
    fun onPlaybackFinished(nowMs: Long): List<SatelliteAction> {
        ttsActive = false
        // Follow-up: the assistant asked a question — reopen the mic without a new wake word.
        // DETECTING is the normal post-transcript state at playback end; it also excludes
        // PAUSED (mic is off) and IDLE, mirroring onWakeDetected's guard.
        val reopen = localWake && followUp() && !suppressRun &&
            wakeState == WakeState.DETECTING &&
            lastResponseText.trim().endsWith("?") &&
            followUpRound < MAX_FOLLOW_UP_ROUNDS
        if (reopen) {
            wakeState = WakeState.STREAMING
            micTimestampMs = 0L
            followUpActive = true
            followUpRound += 1
            followUpDeadlineAtMs = nowMs + FOLLOW_UP_LISTEN_MS
            // Mic stayed on through TTS (gated by ttsActive), so streaming resumes with no StartMic.
            return listOf(
                SatelliteAction.Send(WyomingEvent("played")),
                SatelliteAction.Send(runPipelineLocalEvent()),
                SatelliteAction.Send(WyomingEvent("streaming-started")),
                SatelliteAction.Earcon(EarconKind.WAKE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
            )
        }
        dismissAtMs = nowMs + DISMISS_MS
        return listOf(SatelliteAction.Send(WyomingEvent("played")))
    }
```

**(g) `onOverlayTapped`** — a tap during a follow-up listen kills the hot mic. Wrap the existing `when` (whose branches are unchanged):

```kotlin
    fun onOverlayTapped(nowMs: Long): List<SatelliteAction> = if (followUpActive) {
        // Tap while the follow-up mic is hot (overlay is LISTENING): cancel quietly and
        // re-arm wake detection. Non-follow-up LISTENING taps below stay a no-op.
        followUpQuietDismiss()
    } else when (overlay.phase) {
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

**(h) `onTick`** — belt-and-suspenders deadline (covers HA sending no `error`). Insert at the TOP of the method, before the watchdog block — the quiet dismiss clears `watchdogAtMs`, so a follow-up silence never reaches the loud LISTENING watchdog:

```kotlin
    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Follow-up listen deadline: no error arrived and the user said nothing — quiet dismiss.
        // Checked before the watchdog (and it clears watchdogAtMs) so a follow-up silence can
        // never surface the loud LISTENING FAILED flash.
        followUpDeadlineAtMs?.let {
            if (nowMs >= it && followUpActive) actions += followUpQuietDismiss()
        }
        // Watchdog: a stalled pipeline (no transcript, or answer text but no playback) must not
```

(the rest of the method is unchanged).

**(i) Shared quiet-dismiss helper + `reset()` + constants** — add the helper next to `failActions` (~:429), extend `reset()`, and add the two companion constants after `FAILED_TEXT`:

```kotlin
    /**
     * Quietly close a follow-up listen window (silence, deadline, or tap): hide the overlay with
     * no FAILED flash, stop streaming, and re-arm the on-device detector. Only reachable while
     * followUpActive, which is only ever set in localWake mode.
     */
    private fun followUpQuietDismiss(): List<SatelliteAction> {
        followUpActive = false
        followUpDeadlineAtMs = null
        watchdogAtMs = null
        dismissAtMs = null
        wakeState = WakeState.DETECTING
        return listOf(
            SatelliteAction.Send(WyomingEvent("streaming-stopped")),
            SatelliteAction.ResetDetector,
            overlayAction(VoiceOverlayState()),
        )
    }
```

```kotlin
    private fun reset() {
        streaming = false
        wakeState = WakeState.IDLE
        ttsActive = false
        micTimestampMs = 0L
        dismissAtMs = null
        watchdogAtMs = null
        suppressRun = false
        alarmSilencedThisRun = false
        lastResponseText = ""
        followUpActive = false
        followUpRound = 0
        followUpDeadlineAtMs = null
        overlay = VoiceOverlayState()
    }
```

```kotlin
        const val FAILED_MS = 3_000L
        const val FAILED_TEXT = "No response — try again"
        const val MAX_FOLLOW_UP_ROUNDS = 3
        const val FOLLOW_UP_LISTEN_MS = 10_000L
```

- [ ] **Step 4: Run tests to verify they pass**

Same command as Step 2, logging to `gate-task3-pass.log`. Expected: `RC=0` — all new tests AND every pre-existing SatelliteSessionTest test green (the fallback/alarm/watchdog suites must be untouched).

- [ ] **Step 5: Full gate**

Run the Global Constraints gate template with `N=3`. Expected `RC=0`.

- [ ] **Step 6: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add app/src/main/java/com/rar/hearth/voice/SatelliteSession.kt app/src/test/java/com/rar/hearth/voice/SatelliteSessionTest.kt
git commit -m "feat(voice): follow-up conversations — reopen mic when the reply is a question

Response text ending in '?' reopens an asr-stage listen (WAKE chirp, LISTENING
overlay) without a new wake word; chains up to 3 rounds per wake; silence,
deadline (10 s), or a tap dismiss quietly with no FAILED flash. localWake only.

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 4: App.kt live wiring

**Files:**
- Modify: `app/src/main/java/com/rar/hearth/App.kt:458-461` (the `SatelliteServer(...)` construction)

**Interfaces:**
- Consumes: `SatelliteServer` constructor param `followUp: () -> Boolean` (Task 2); `VoiceSettings.followUpEnabled` (Task 1); existing `configStore.config: StateFlow<DashConfig>`.
- Produces: the running app reads the toggle live on every playback finish.

**CRITICAL:** Do NOT touch `startVoice()`'s reactive trigger (~:519, the `Triple(it.voice.enabled, it.voice.wakeWord, it.voice.wakeThreshold)` map). Adding `followUpEnabled` there would restart the satellite on toggle and drop device-local timers. The lambda below is read live; no restart is needed or wanted.

No unit test: App.kt has no plain-JVM test surface (Android imports); the deliverable is compile-verified by the gate and behaviorally covered by Task 3's `followUpProviderIsReadLive`.

- [ ] **Step 1: Pass the provider**

In `App.kt`, change the `SatelliteServer` construction (~:458) — add one named argument after `name`:

```kotlin
    val satellite: SatelliteServer = SatelliteServer(
        scope = scope,
        appVersion = BuildConfig.VERSION_NAME,
        name = { deviceName() },
        // Read LIVE on each playback finish — deliberately NOT in startVoice()'s restart
        // trigger set: restarting the satellite would drop device-local timers.
        followUp = { configStore.config.value.voice.followUpEnabled },
        out = object : SatelliteServer.Out {
```

(the `out = object : ...` body is unchanged).

- [ ] **Step 2: Full gate**

Run the Global Constraints gate template with `N=4`. Expected `RC=0`.

- [ ] **Step 3: Verify the restart trigger was not touched**

```bash
cd /home/rar/android_simpla_ha_dash
grep -n "followUpEnabled" app/src/main/java/com/rar/hearth/App.kt
```

Expected: exactly ONE hit — the `followUp = { ... }` lambda. It must NOT appear anywhere near the `voiceSettings`/`Triple(...)` flow in `startVoice()`.

- [ ] **Step 4: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add app/src/main/java/com/rar/hearth/App.kt
git commit -m "feat(voice): wire live followUpEnabled provider into the satellite (no restart)

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

### Task 5: Web-config "Continue conversation" toggle

**Files:**
- Modify: `app/src/main/assets/config/app.js:961-1037` (`renderVoice()`)

**Interfaces:**
- Consumes: `config.voice.followUpEnabled` (round-trips through the existing `/api/config` save; Kotlin side decodes it via Task 1's field).
- Produces: user-facing toggle. Verified by `node --check` only (project rule: no JS unit tests).

- [ ] **Step 1: Add the toggle row**

In `renderVoice()`:

**(a)** Add a defensive default after the existing block of `if (v.xxx == null)` defaults (after `if (v.wakeThreshold == null) v.wakeThreshold = 50;`):

```js
  if (v.followUpEnabled == null) v.followUpEnabled = false;
```

**(b)** Add the row after the wake settings — insert immediately after `host.appendChild(labeledRow("Wake sensitivity", sens));`:

```js
  const followUp = el("input"); followUp.type = "checkbox"; followUp.checked = !!v.followUpEnabled;
  followUp.setAttribute("aria-label", "Continue conversation enabled");
  followUp.addEventListener("change", () => v.followUpEnabled = followUp.checked);
  host.appendChild(labeledRow("Continue conversation", followUp));
```

**(c)** Extend the trailing muted help text: in the final `host.appendChild(el("div", "muted", ...))` string, add this sentence after the "Wake sound: ..." sentence (keep the surrounding `" + "` string-concat style):

```js
    "Continue conversation: when the assistant's reply is a question, the mic reopens for your answer without a new wake word (up to 3 rounds; applies immediately, no restart). " +
```

- [ ] **Step 2: JS syntax gate**

```bash
cd /home/rar/android_simpla_ha_dash
node --check app/src/main/assets/config/app.js && echo JS_OK
```

Expected: `JS_OK`.

- [ ] **Step 3: Full gate**

Run the Global Constraints gate template with `N=5`. Expected `RC=0` (app.js ships as an asset; the gradle gate still guards the Kotlin tree).

- [ ] **Step 4: Commit**

```bash
cd /home/rar/android_simpla_ha_dash
git add app/src/main/assets/config/app.js
git commit -m "feat(web-config): Continue conversation toggle on the voice page

Claude-Session: https://claude.ai/code/session_01TWMLgkTCLRx4DEE1zHvDfL"
```

---

## Spec coverage map (self-review)

| Spec item | Task |
| --- | --- |
| `voice.followUpEnabled`, default off, `clamped()` no-op | 1 |
| Live `() -> Boolean` provider, both server construction sites | 2 (+4) |
| `lastResponseText` in `synthesize`; suppressRun stores nothing | 3 (b) |
| Reopen on `?` in `onPlaybackFinished`, exact wire order, no dismissAtMs, no StartMic | 3 (f) |
| Chaining + `MAX_FOLLOW_UP_ROUNDS = 3` cap | 3 (f), chaining test |
| `transcript` closes the window, round keeps counting | 3 (c) |
| `error` quiet dismiss (alarm branch precedence kept) | 3 (d) |
| `onTick` deadline quiet dismiss before watchdog | 3 (h) |
| Tap during follow-up cancels quietly; other LISTENING taps stay no-ops | 3 (g) |
| Wake + `reset()` clear all follow-up state | 3 (e, i) |
| `FOLLOW_UP_LISTEN_MS = 10_000L` | 3 (i) |
| App wiring, NOT in restart trigger | 4 |
| Web-config toggle + `node --check` | 5 |
| Non-localWake path unchanged | 3 guard `localWake &&` + `legacyModeNeverReopens` test |
| Every test in the spec's Testing section | Tasks 1 & 3 test bodies |
