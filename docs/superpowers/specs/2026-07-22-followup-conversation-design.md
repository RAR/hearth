# Follow-up Conversations (Continue Conversation) — Design

**Date:** 2026-07-22
**Component:** Hearth voice satellite (Android app)
**Status:** Approved design, ready for planning

## Goal

Let the Hearth voice satellite reopen its microphone for a follow-up
utterance — without a fresh wake word — when the assistant's spoken reply is a
question (e.g. "Which room?"). This gives the natural multi-turn feel of
Home Assistant Voice PE ("Turn on the lights." / "Which ones?" / "Living room.")
on our devices.

## Background / why this is app-side

The Hearth app is a **Wyoming wake-streaming satellite** (`voice/SatelliteSession.kt`,
a pure state machine driven by `voice/SatelliteServer.kt`). In this model the
**satellite** initiates each pipeline run: after wake it sends
`run-pipeline (asr→tts, restart_on_end=false)` to HA.

Home Assistant already sets its `continue_conversation` intent automatically when
the LLM's reply is a follow-up question — and our Ollama prompt already tells the
model to "ask one short question instead of guessing." **But the Wyoming satellite
protocol does not deliver that flag to the satellite** (this is the documented
reason `rhasspy/wyoming-satellite` was deprecated in favour of the ESPHome protocol
used by Voice PE). So HA is "ready" but our satellite never hears about it, and
today it re-arms to wake-word mode after every response.

Because *our* satellite drives the pipeline, it can implement follow-up itself,
using the one signal it already has: the **response text**, delivered in the
existing `synthesize` handler. We reopen the mic when that text ends in `?`.
This mirrors HA's own historic continue-conversation heuristic, entirely inside
the app, with zero HA-side changes.

## Approved decisions

1. **Trigger:** the assistant's TTS response, trimmed, **ends with `?`**. No other signal.
2. **Enablement:** a **per-device config toggle**, default **OFF** (`voice.followUpEnabled`).
3. **Chaining:** **multi-round** — each follow-up answer that also ends in `?` reopens again, capped at `MAX_FOLLOW_UP_ROUNDS = 3` per wake, then dismisses normally.
4. **On silence:** **quiet dismiss** — no "No response" FAILED flash.
5. **Reopen earcon:** reuse the existing `WAKE` chirp as the "your turn" cue (approved default).
6. **Tap during a follow-up listen:** a tap while `followUpActive` **cancels quietly** and re-arms to wake mode (approved default).

## Architecture — SatelliteSession state machine changes

`SatelliteSession` stays pure and JVM-testable (no Android/coroutine imports).
All new behavior is added to the existing localWake path.

### New state fields

- `lastResponseText: String` — set from the `synthesize` event's text; reset on each fresh wake.
- `followUpActive: Boolean` — true while a follow-up listen window is open.
- `followUpRound: Int` — follow-ups opened since the last wake; reset to 0 on wake.
- `followUpDeadlineAtMs: Long?` — quiet-dismiss fallback timer for silence.
- Constructor gains `followUp: () -> Boolean` — a **live** config provider (read each
  time, like `name`), so toggling it never requires a satellite restart.

### New constants

- `MAX_FOLLOW_UP_ROUNDS = 3`
- `FOLLOW_UP_LISTEN_MS = 10_000L`

### Handler changes

**`synthesize` (`:204`)** — when not `suppressRun`, additionally store
`lastResponseText = textOf(event)` before showing the RESPONSE overlay. (Unchanged
otherwise; `suppressRun` runs store nothing, so they can't trigger follow-up.)

**`onPlaybackFinished` (`:321`)** — this is the core hook. After the existing
`ttsActive = false` and the `played` event, decide follow-up vs normal dismiss:

- Open a follow-up **iff** `followUp()` **and not** `suppressRun` **and**
  `lastResponseText.trim().endsWith("?")` **and** `followUpRound < MAX_FOLLOW_UP_ROUNDS`.
- **If opening:** set `wakeState = STREAMING`, `micTimestampMs = 0`, `followUpActive = true`,
  `followUpRound++`, `followUpDeadlineAtMs = now + FOLLOW_UP_LISTEN_MS`; do **not** set
  `dismissAtMs`. Emit, in wire order:
  `Send("played")`, `Send(runPipelineLocalEvent())`, `Send("streaming-started")`,
  `Earcon(WAKE)`, `Overlay(LISTENING)`.
  (Mic stays on through TTS — gated by `ttsActive` — so streaming resumes with no StartMic.)
- **If not opening:** unchanged — `Send("played")` and set `dismissAtMs = now + DISMISS_MS`.

**`transcript` (`:160`)** — the user answered: clear `followUpActive = false` (the existing
re-arm to `DETECTING` and THINKING overlay are unchanged). `lastResponseText` is left to be
overwritten by the next `synthesize`. The next `onPlaybackFinished` re-evaluates for the
following round; `followUpRound` keeps counting up to the cap.

**`error` (`:187`)** — if `followUpActive` (STT VAD timed out on silence): quiet-dismiss —
clear `followUpActive`, `followUpDeadlineAtMs`, `watchdogAtMs`; re-arm the detector
(`wakeState = DETECTING`, `streaming-stopped` + `ResetDetector`); hide the overlay. **No**
FAILED flash. Reuses the existing alarm-silenced quiet-cleanup shape. Non-follow-up errors
are unchanged (`failActions`). The existing `alarmSilencedThisRun` branch takes precedence.

**`onTick` (`:369`)** — belt-and-suspenders: if `followUpDeadlineAtMs` is set and passed and
still `followUpActive`, run the same quiet-dismiss (covers the case HA sends no `error`).
Evaluated before/independent of the 30s `watchdogAtMs` so a follow-up silence never reaches
the loud LISTENING watchdog.

**`onOverlayTapped` (`:332`)** — add: if `followUpActive` (overlay is LISTENING), quiet-dismiss
and re-arm (kills the hot mic on demand). Non-follow-up LISTENING taps stay a no-op.

**`onWakeDetected` (`:262`) and `reset()` (`:412`)** — zero `followUpRound`, `followUpActive`,
`followUpDeadlineAtMs`, and `lastResponseText`, so every fresh wake starts a clean chain.

### Reopen is ASR-stage, not wake-stage

Reuse the existing `runPipelineLocalEvent()` (`start_stage=asr`, `end_stage=tts`,
`restart_on_end=false`) — identical to a post-wake run, minus the wake stage. No new
Wyoming event types.

## Config & wiring

- **`config/DashConfig.kt`** — `VoiceSettings` gains `val followUpEnabled: Boolean = false`.
  It is a plain boolean; `clamped()` needs no change (no normalization required).
- **`voice/SatelliteServer.kt`** — constructor gains `followUp: () -> Boolean`; both
  `SatelliteSession(...)` construction sites (`:81`, `:97`) pass it through. Because the
  provider is read live, toggling `followUpEnabled` does **not** need to be in the
  satellite-restart trigger set.
- **`App.kt`** — at the `SatelliteServer(...)` construction (`:458`), pass
  `followUp = { configStore.config.value.voice.followUpEnabled }`. **Do not** add
  `followUpEnabled` to the reactive restart trigger (`:519` collects
  `voice.enabled`/`wakeWord`/`wakeThreshold`) — it applies live, no restart, no dropped timers.
- **Web-config voice page** — `assets/config/app.js` `renderVoice()` (`:961`): add a toggle
  row ("Continue conversation") after the wake settings, defaulting `v.followUpEnabled` to
  `false` when absent, wired like the existing `enabled` toggle. `node --check app.js` is the
  only JS gate.

## Testing (pure JVM, JUnit4 — `SatelliteSessionTest.kt`)

- Enabled + response ends in `?` → reopen: STREAMING, emits `played` + asr `run-pipeline` +
  `streaming-started` + WAKE earcon + LISTENING overlay, **no** `dismissAtMs`.
- Disabled → normal dismiss (no reopen).
- Enabled but response has no trailing `?` → normal dismiss.
- `suppressRun` run → no reopen (and `synthesize` stored no text).
- Chaining: rounds 1..3 reopen; round 4 (cap reached) dismisses normally.
- Silence via `error` while `followUpActive` → quiet dismiss (overlay hidden, detector re-armed,
  **no** FAILED overlay).
- `onTick` past `followUpDeadlineAtMs` while `followUpActive` → quiet dismiss.
- Tap while `followUpActive` → quiet dismiss + re-arm.
- `onWakeDetected` resets `followUpRound`/`lastResponseText` (a new wake after a maxed chain
  can follow up again).
- `VoiceSettings` default `followUpEnabled == false`; survives `clamped()`.

## Out of scope

- No HA-side changes (automations, integration, prompt). HA already sets its own intent.
- No new Wyoming event types or protocol capability flags.
- No configurable trigger phrases, no configurable window/cap (constants for now).
- No change to the non-localWake (HA-runs-wake) fallback path — follow-up is localWake only.

## Gate

`JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
(redirect to a log, capture `RC=$?`, never pipe to tail/head), plus
`node --check app/src/main/assets/config/app.js` before any commit touching `app.js`.
Every commit ends with the `Claude-Session:` trailer.
