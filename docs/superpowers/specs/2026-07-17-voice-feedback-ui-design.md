# Voice Feedback UI — Thinking State, Failure Exits, Tap-to-Interrupt

**Date:** 2026-07-17
**Status:** Approved (transcript + pulsing dots; "No response" flash; tap-to-interrupt — per AskUserQuestion + chat)

## Problem

After the wake word, the bottom-center voice pill shows "Listening…" then the heard
transcript — and then just sits there while the LLM works. Worse, the overlay's ONLY
auto-dismiss is "4 s after TTS playback finishes" (`SatelliteSession.onPlaybackFinished` →
`dismissAtMs` → `onTick`). A pipeline that ends with no response (observed live 2026-07-17
18:29: qwen3 empty-response run — HA went processing→idle, sent `run-satellite`, no TTS) or
an `error` event strands the pill on screen indefinitely. There is also no way to shut the
assistant up mid-reply.

## Scope

All logic goes in the pure `SatelliteSession` state machine (plain-JVM tested, 500 ms tick
already exists); rendering in `VoiceOverlay.kt`; thin wiring in `App.kt`/`SatelliteServer`.
All three devices get it via shared code. No protocol changes — Wyoming has no
satellite-side pipeline abort, so "cancel" is local suppression.

## State machine (`VoiceOverlayState.kt`, `SatelliteSession.kt`)

**Phases:** `HIDDEN, LISTENING, TRANSCRIPT, THINKING, RESPONSE, FAILED` (add `THINKING`,
`FAILED`; `TRANSCRIPT` remains in the enum but is no longer emitted — kept so the enum
change is additive; UI renders it identically to THINKING minus dots if it ever appears).

**Transitions (changes only — everything not listed is unchanged):**
- `transcript` event → `THINKING` (carries the transcript text). Was: terminal `TRANSCRIPT`.
- `error` event → `FAILED` ("No response — try again") + `dismissAtMs = now + FAILED_MS`
  (3000 ms), in addition to its existing stop-streaming/re-arm behavior.
- `run-satellite` while phase is `LISTENING` or `THINKING` → same `FAILED` treatment (HA
  abandoned the pipeline mid-run — tonight's incident). At session start (phase `HIDDEN`)
  `run-satellite` behaves exactly as today.
- **Watchdog:** entering `LISTENING` (detection/onWakeDetected) or `THINKING` (transcript)
  sets `watchdogAtMs = now + WATCHDOG_MS` (30 000 ms). `synthesize` re-arms it (covers
  TTS-never-plays); `audio-start` clears it (post-playback dismiss takes over); `reset()`/
  hide clears it. In `onTick`, when `watchdogAtMs` expires:
  - phase `LISTENING`/`THINKING` → `FAILED` + 3 s dismiss + the error-path cleanup
    (stop streaming, re-arm local detector — mirror the existing `error` handling).
  - phase `RESPONSE` (answer text shown but playback never started) → `HIDDEN` quietly.
- `FAILED` auto-hides via the existing `dismissAtMs` mechanism (3 s, vs 4 s post-TTS).

**Tap-to-interrupt:** new entry point `SatelliteSession.onOverlayTapped(nowMs): List<SatelliteAction>`
(bridged like `onPlaybackFinished` — `SatelliteServer.onOverlayTapped()` synchronizes and
dispatches; `App.kt` passes it to the composable).
- Phase `RESPONSE` → emit new `SatelliteAction.PlaybackAbort` + overlay `HIDDEN`; clear
  `ttsActive` (mic un-gates) and `dismissAtMs`/`watchdogAtMs`.
- Phase `THINKING` → overlay `HIDDEN` + set `suppressRun = true`. While suppressed: the
  run's late `synthesize` produces no overlay change; `audio-start`/`audio-chunk` are
  swallowed (no playback actions, `ttsActive` stays false); `audio-stop` immediately emits
  the `played` event so HA's pipeline completes cleanly. Suppression resets on the next
  wake/detection/`run-pipeline` and on `reset()`.
- Phase `FAILED` → `HIDDEN` immediately. `LISTENING`/`HIDDEN` → no-op (empty list).

## Playback abort (`AnnouncePlayer`, `AndroidPcmSink`, `App.kt`)

`SatelliteAction.PlaybackAbort` → `Out.onPlaybackAbort()` → `AnnouncePlayer` gains an abort
command that, unlike `Cmd.Stop` (which drains), calls a new `AndroidPcmSink.abort()`:
`AudioTrack.pause(); flush(); stop()` so silence is immediate. `onPlayed` is NOT invoked on
abort (the session already cleaned its own state; invoking it would just set a stray
`dismissAtMs` on a hidden overlay — harmless but noisy). Echo HAL note: pause+flush is a
standard path; the ≥300 ms trailing-silence quirk applies to short one-shot *starts*, not
aborts — no special handling.

## UI (`VoiceOverlay.kt`, `App.kt`)

- `THINKING`: pill shows the transcript text with a row of three dots beneath, staggered
  alpha pulse (reuse the `WakeGlow` `rememberInfiniteTransition`/`infiniteRepeatable`
  pattern: ~900 ms cycle, 150 ms stagger per dot, voice blue `Color(0xFF4FC3F7)`).
- `FAILED`: pill shows "No response — try again" in a muted tone (`Color(0xFFB0B4BE)` text
  on the existing dark pill surface). No earcon (user chose message-only).
- The pill `Surface` becomes `clickable` (ripple fine), invoking the tap callback; the rest
  of the screen still passes touches to the dashboard. `WakeGlow` stays LISTENING-only.
- `App.kt`: `VoiceOverlay(voiceOverlayState, onTap = { satellite.onOverlayTapped() })`.
  Night-mode suppression and screen-wake already key on `phase != HIDDEN` — the new phases
  ride along with no change.

## Tests (plain-JVM JUnit4, extend `SatelliteSessionTest.kt`)

1. transcript → `THINKING` with text (replaces the TRANSCRIPT expectation in the existing
   overlay-flow test).
2. Watchdog: wake at t, no transcript → at t+30 000 `FAILED` + streaming stopped + detector
   re-armed; at +3 000 more → `HIDDEN`.
3. Watchdog in THINKING: transcript at t re-arms; fires at t+30 000 → `FAILED`.
4. `error` during THINKING → immediate `FAILED` + 3 s auto-hide.
5. Mid-run `run-satellite` (phase THINKING) → `FAILED`; at session start (HIDDEN) → no
   overlay change (pin existing behavior).
6. `RESPONSE` with no `audio-start` → quiet `HIDDEN` at watchdog expiry (no FAILED text).
7. Tap during RESPONSE+playback → `PlaybackAbort` + `HIDDEN` + mic un-gated.
8. Tap during THINKING → `HIDDEN`; late `synthesize`/`audio-start`/chunks produce no
   overlay/playback actions; `audio-stop` → `played` emitted; next wake clears suppression
   and behaves normally.
9. Existing tests updated only where they pin the old TRANSCRIPT terminal state; the 4 s
   post-playback dismiss test stays green untouched.

## Out of scope

- Voice barge-in (wake word over TTS) — mic is gated during playback because the Echos
  have no AEC; the wake engine would hear the device's own speaker.
- Aborting the HA pipeline server-side (no such Wyoming event from the satellite).
- Timer chips / timer-finished overlay (separate UI, untouched).

## Verification

- Gate per commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew
  :app:testDebugUnitTest :app:assembleDebug`.
- Live on the desk Echo (10.75.1.98): normal question → transcript + dots → spoken answer;
  tap mid-answer → immediate silence + pill gone; repro tonight's failure shape (if it
  recurs) → "No response — try again" then auto-hide. M9 caveat: night-clock overlay eats
  scripted taps — wake-tap first.
