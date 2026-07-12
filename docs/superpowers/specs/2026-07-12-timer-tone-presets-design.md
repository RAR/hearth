# Timer Alarm Tone Presets + Volume — Design

**Date:** 2026-07-12
**Status:** Approved by user (brainstorming session)

## Goal

Let the user choose what the timer-finished alarm sounds like and how loud it is,
from the web config page, with a preview button. Today `TimerChime` plays one
hardcoded synthesized two-tone loop at fixed amplitude.

## Config model (`DashConfig`)

`VoiceSettings` gains:

- `timerTone: String = "twotone"` — one of `twotone`, `beeps`, `chime`, `trill`.
  `clamped()` normalizes: trim; unknown/blank value → `"twotone"`.
- `timerVolume: Int = 80` — clamped 0–100 in `clamped()`.

Backward compatible via existing `ignoreUnknownKeys`/`encodeDefaults`: old configs
load with defaults that reproduce today's exact behavior (two-tone at the current
amplitude — see Volume mapping).

## Tones (all synthesized, no assets, no new dependencies)

Synthesis moves to a pure JVM-testable generator; playback stays in `TimerChime`
(AudioTrack, STREAM_ALARM, loop until dismiss / 60 s auto-silence — unchanged):

- `twotone` — current sound, unchanged: 200 ms 880 Hz + 200 ms 1320 Hz, ~1 s gap.
- `beeps` — classic digital alarm: three 120 ms 1000 Hz beeps with 80 ms gaps,
  then ~1 s pause.
- `chime` — gentle: 350 ms E6 (1318.5 Hz) then 350 ms C6 (1046.5 Hz), each with a
  linear decay envelope to 0, ~1.6 s gap.
- `trill` — urgent: 1 s of fast alternation between 1400 Hz and 1800 Hz in 60 ms
  segments, ~0.6 s gap.

**Structure:** `object ToneGenerator` (new file `voice/ToneGenerator.kt`, pure JVM):
`fun render(tone: String, volume: Int, rate: Int): ShortArray` returns one full
cycle (sound + trailing silence gap) as 16-bit mono PCM at `rate` Hz. Unknown tone
falls back to `twotone`. `TimerChime.start(tone: String, volume: Int)` renders once
and loops the buffer as today (write tone, write gap — the gap is now baked into
the rendered cycle, simplifying the loop to a single buffer write per iteration).

**Volume mapping:** amplitude = `(volume / 100.0) * 0.6 * Short.MAX_VALUE` — i.e.
volume 100 equals today's fixed 0.6 headroom amplitude and the default 80 is
slightly quieter than current behavior. Chosen for headroom safety on the Echo
speaker; the default change from today's loudness is deliberate and acceptable.
Volume 0 renders silence (visual-only alarm) — allowed, not special-cased.

## Wiring (App.kt)

The alert `LaunchedEffect` reads the live config and calls
`deps.timerChime.start(config.voice.timerTone, config.voice.timerVolume)`.
A config change while an alert is ringing does not restart the chime (next alarm
uses the new settings) — acceptable.

## Preview (config server + web UI)

- New PIN-gated endpoint `POST /api/voice/preview-chime` with JSON body
  `{"tone": "...", "volume": N}`; either field may be omitted and defaults to the
  currently saved config value. Values are clamped/normalized the same way as
  `clamped()`, then it plays ONE cycle (not looping) through `TimerChime` (a `playOnce(tone, volume)` method that
  renders one cycle and stops — reuses the same AudioTrack path, idempotent,
  cannot leave a loop running).
- Web config Voice card gains: "Timer alarm" `<select>` with the four presets,
  "Alarm volume" number input (0–100), and a "Preview" button that POSTs the
  currently selected (unsaved) tone+volume to the endpoint — audition without
  saving or setting a real timer.

## Error handling

| Case | Behavior |
|---|---|
| Unknown tone string in config/preview | falls back to `twotone` |
| Volume out of range | clamped 0–100 |
| Preview while a real alarm is ringing | one-shot mixes/interrupts on the same AudioTrack path — playOnce is best-effort; never throws |
| Preview endpoint without valid session | 401, same as every other config API route |

## Testing (plain-JVM JUnit4)

- `ToneGeneratorTest`: each preset renders non-empty PCM of the expected cycle
  length (±1 frame); amplitude scales with volume (volume 0 → all zeros, 100 →
  peak ≈ 0.6 * Short.MAX_VALUE); unknown tone falls back to twotone (identical
  output); rate parameter respected.
- `DashConfigTest`: round-trip of the new fields; clamp rules (unknown tone →
  twotone, volume clamped, defaults).
- ConfigServer route tested in the same style as existing route tests (auth
  required; clamping applied) if the existing test seam allows; AudioTrack
  playback itself is on-device.

## Out of scope

Custom audio files, per-timer tones, TTS spoken announcements, changing the
voice-response volume (this is timer-alarm only).

## Constraints (unchanged, binding)

Kotlin 2.1.0, compileSdk 34, media3 1.4.1, NanoHTTPD 2.3.1, no new dependencies.
Gate: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`.
