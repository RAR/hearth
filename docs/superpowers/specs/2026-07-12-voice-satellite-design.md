# HA Voice Satellite (Wyoming) — Design

**Date:** 2026-07-12
**Status:** Approved by user (brainstorming session)

## Goal

Hands-free voice control of Home Assistant from the Echo Dashboard: say the wake word
from across the room, speak a command, hear the response — with a small on-screen
overlay showing what was heard and answered. The Echo becomes a first-class HA Assist
satellite.

## User's environment (verified)

- Echo Show 5 (MT8163, LineageOS 18.1 "checkers"), mic hardware present
  (`feature:android.hardware.microphone`, TLV320AIC3101 mic-array ADC) and confirmed
  working by the user under this build. Physical mic-mute switch exists.
- HA has a full working Assist pipeline (STT + conversation agent + TTS) **and** the
  openWakeWord add-on already serving other satellites.
- The app already ships: `WyomingCodec` (complete Wyoming 1.7.1 event framing:
  JSONL header + data block + binary payload, hardened against malformed input),
  `VacaServer` (coroutine TCP server pattern on port 10700), `NsdAdvertiser`
  (mDNS service registration), and `AndroidPcmSink` (AudioTrack PCM playback used by
  VACA announcements).

## Approach (decision: Wyoming satellite server, always-streaming mode)

The app runs a second Wyoming TCP server on fixed port **10600**, advertised as
`_wyoming._tcp.` so HA auto-discovers it. HA's Wyoming integration connects **inbound**
(same direction as VACA). The satellite runs in *always-streaming* mode: it performs no
audio processing itself — HA does VAD, wake word (openWakeWord), STT, intent, and TTS.

Rejected alternatives:

- **`assist_pipeline/run` over the existing HA WebSocket** — zero HA-side setup, but
  the WS wake-word stage is built for bounded runs with inactivity timeouts; the app
  would own a restart-forever loop on a path not battle-tested for continuous
  listening, sharing failure modes with the entity subscription.
- **On-device wake word (openWakeWord TFLite)** — best privacy, but adds a TFLite
  runtime + DSP on a weak CPU, and the user's openWakeWord server makes it redundant.

## Protocol flow (verify against sources before implementation)

Expected event sequence (satellite ⇄ HA), per the wyoming-satellite reference
implementation's `AlwaysStreamingSatellite`:

1. HA connects, sends `describe`; satellite replies `info` including a `satellite`
   section (name "Echo Dashboard", no local wake/asr/tts services).
2. HA sends `run-satellite`; satellite replies `run-pipeline` (start stage wake,
   end stage TTS) and starts streaming mic audio as `audio-chunk` events
   (16 kHz / 16-bit / mono), preceded by the appropriate audio-format metadata.
3. HA sends back, per interaction: `detection` (wake word heard) →
   `transcript` (recognized text) → `synthesize` (response text) →
   TTS PCM as `audio-start` / `audio-chunk` / `audio-stop`; satellite plays it via
   `AndroidPcmSink` and replies `played`. Streaming of mic audio continues throughout
   (HA manages barge-in/ignore server-side).
4. `pause-satellite` stops mic streaming until the next `run-satellite`.
   Disconnect stops the mic; the server keeps listening and HA's integration retries.
5. `ping` events are answered with `pong` (keepalive).

**Binding plan-phase requirement:** the plan MUST verify exact event names, data
fields, audio-format metadata, and ordering against the `rhasspy/wyoming-satellite`
and HA `wyoming` integration sources before coding; the sequence above is design
intent, not gospel. Deviations found there govern.

## Components

New `voice/` package:

- **`SatelliteServer`** — TCP accept loop on 10600 (VacaServer pattern: newest
  connection wins, coroutine per connection, `WyomingCodec` framing). Owns no policy;
  routes events to/from the session.
- **`SatelliteSession`** (plain logic, JVM-testable) — the protocol/state machine.
  Input: decoded `WyomingEvent`s + lifecycle signals (connected, disconnected,
  mic-chunk available, playback finished, clock ticks). Output: events to send,
  mic start/stop commands, playback commands, and `VoiceOverlayState` updates.
  All decisions live here; the server and UI just obey.
- **`MicStreamer`** (Android-only) — `AudioRecord`, source `VOICE_RECOGNITION`,
  16 kHz / 16-bit / mono, ~30 ms chunks pushed to the session. Runs **only** while
  voice is enabled in config AND a satellite connection is active with streaming
  requested. Released otherwise. (~32 KB/s upstream while streaming.)
- **`VoiceOverlay`** (composable) — bottom-center pill over the active view, far
  lighter than the doorbell popup. States: hidden → "Listening…" (on `detection`,
  wakes screen) → transcript text (on `transcript`) → response text (on `synthesize`,
  while TTS plays) → fades ~4 s after playback ends. Appearing counts as user
  activity (same kiosk `onUserInteraction()` + idle-timer wiring as the doorbell
  popup). Renders above panels, below the doorbell popup.
- **Second `NsdAdvertiser` instance** for `_wyoming._tcp.` port 10600 (parameterize
  the existing class if its service type is hardcoded).

## Timers (local countdown on the device)

"Start a timer for 5 minutes" must produce an on-screen countdown on the Echo and a
local chime when it fires. HA Assist targets timer intents at the satellite that heard
them and delegates the timer to the device via Wyoming timer events:

- `timer-started` (id, total seconds, name if given) → add a countdown chip on screen.
- `timer-updated` (pause/resume/add/remove time) → adjust the chip.
- `timer-cancelled` → remove the chip.
- `timer-finished` → chime locally (generated tone via the existing audio path — no
  bundled asset, no new dependency), show a full-attention "Timer done" overlay that
  wakes the screen; tap to dismiss, auto-silence after 60 s.

Multiple concurrent timers are supported (HA allows several); chips stack. The
satellite's `info` block declares timer support so HA routes timer intents to the
device. Timer state lives in `SatelliteSession` (JVM-testable: event in → timer list /
overlay state out; countdown display math tested against a fake clock). Exact timer
event names/fields are covered by the same binding plan-phase source verification as
the rest of the protocol. Timers survive HA disconnects (they run on-device); they do
not survive an app restart (accepted).

## Permissions

- Add `RECORD_AUDIO` to the manifest. On launch, if voice is enabled in config and
  the permission is missing, `MainActivity` requests it (one-time tap on-device);
  at flash time it is pre-granted via `adb shell pm grant` so the dialog never
  appears in practice. If denied/missing while voice is enabled, the satellite
  stays connectable but reports a mic error and streams nothing.

## Audio interplay

- TTS replies play through `AndroidPcmSink` — the same path and mixing behavior as
  VACA announcements (plays over whatever else is playing).
- Voice reply coinciding with a doorbell popup or media audio is an accepted rare
  collision: both play.
- Mic capture never conflicts with playback (capture vs. render paths).

## Config model (`DashConfig`)

- New top-level `voice: VoiceSettings = VoiceSettings()` with
  `@Serializable data class VoiceSettings(val enabled: Boolean = false)`.
  Nothing to clamp. Backward/forward compatible via existing
  `ignoreUnknownKeys` + `encodeDefaults`.
- The server starts/stops reactively on config changes (collect the config
  StateFlow — no app restart needed to enable/disable).
- Port fixed at 10600; satellite name fixed at "Echo Dashboard"; pipeline and wake
  word are chosen in the HA UI where they belong. No other knobs (YAGNI).

## Web config page (`app.js`)

- **Voice** card: single toggle "Voice satellite (Wyoming)". Hint text: "Home
  Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol
  integration at <this-device-ip>:10600. Pick the pipeline and wake word in HA's
  Assist satellite settings."

## Error handling summary

| Failure | Behavior |
|---|---|
| HA disconnects / integration reloads | Mic stops; server keeps listening; HA retries |
| Mic fails to open / permission missing | Connection stays up; satellite reports error event; HA shows satellite unavailable; no crash |
| Malformed Wyoming frame | Drop that connection, accept the next (codec already throws IOException) |
| Physical mic-mute switch on | HA hears silence; wake word never fires; nothing breaks |
| Voice disabled in config | Server not running; nothing advertised |
| Second connection arrives | Newest connection wins (VacaServer pattern) |

## Testing (plain-JVM JUnit4 only, per repo rule)

- `SatelliteSessionTest`: describe→info reply; run-satellite starts mic + emits
  run-pipeline; detection/transcript/synthesize drive `VoiceOverlayState`
  transitions; audio-start/stop routes playback and emits `played` after
  playback-finished; pause-satellite and disconnect stop the mic; ping→pong;
  overlay auto-dismiss timing; mic-error path; timer lifecycle (started/updated/
  cancelled/finished → chip list and finished-overlay transitions, countdown math
  against a fake clock, multiple concurrent timers, timers unaffected by disconnect).
- `DashConfigTest` additions: `voice` round-trip + default-off.
- `WyomingCodec` is already covered; `AudioRecord`/`AudioTrack`/composables are
  Android-only — verified on-device.

## Out of scope (YAGNI)

- On-device wake word or VAD.
- Alarms (clock-time alarms, as opposed to countdown timers), continued conversation,
  barge-in handling beyond what HA does server-side, multiple wake words, area-aware
  targeting config on-device.
- Timer persistence across app restarts.
- Exposing mic mute as an HA entity.

## Build/toolchain constraints (unchanged, binding)

- Kotlin 2.1.0, compileSdk 34 (never bump), media3 pinned 1.4.1, NanoHTTPD 2.3.1.
  No new dependencies expected for this feature.
- Build gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
