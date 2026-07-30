# Kitchen voice wedge — field evidence brief (2026-07-29)

Read this before investigating. Everything below is OBSERVED, not inferred. Do not re-derive it.

## Symptom

The Kitchen Echo Show 5 (`com.rar.hearth` 0.2.539+0724e4d, localWake mode) wakes normally but the
voice pipeline never returns a result. It stays that way **permanently until the app process is
restarted**. Force-stopping and relaunching the app clears it every time.

## The break, from the device log (`GET /api/log`, tag `SatelliteServer`)

Every voice session on 2026-07-29, in order. "VAD" = HA sent `recv voice-started`.

| session start | trigger   | duration | VAD | transcript |
|---------------|-----------|---------:|-----|------------|
| 11:46:04.537  | WAKE      |   3.1 s  | yes | yes |
| 11:46:20.180  | FOLLOW-UP |   7.0 s  | yes | yes |
| 12:31:06.677  | WAKE      |   5.3 s  | yes | yes |
| 12:36:12.183  | WAKE      |   7.1 s  | yes | yes |
| 12:36:33.636  | FOLLOW-UP |   3.6 s  | yes | yes |
| 13:20:13.113  | WAKE      |  15.3 s  | **NO**  | yes |
| 13:20:37.998  | FOLLOW-UP |   4.1 s  | yes | yes |
| 13:20:48.147  | FOLLOW-UP |  10.1 s  | **NO**  | **NO** |
| 14:04:46.053  | WAKE      |  30.5 s  | **NO**  | **NO** |
| 14:27:58.479  | WAKE      |  30.3 s  | **NO**  | **NO** |
| 14:44:40.993  | WAKE      |  30.1 s  | **NO**  | **NO** |
| 14:54:29.076  | WAKE      |  30.1 s  | **NO**  | **NO** |
| 17:57:50.965  | WAKE      |  30.4 s  | **NO**  | **NO** |
| 18:11:49.947  | WAKE      |  30.2 s  | **NO**  | **NO** |
| 19:45:07.293  | WAKE      |  30.1 s  | **NO**  | **NO** |
| 19:45:47.675  | WAKE      |  30.3 s  | **NO**  | **NO** |

Note 13:20:13: VAD never fired yet a transcript still came back after 15.3 s — a partial/early
warning sign ~35 s before the permanent wedge.

The last-good and first-bad sessions are adjacent follow-ups in the same conversation chain
(13:20:13 WAKE -> 13:20:37 FOLLOW-UP -> 13:20:48 FOLLOW-UP). `voice.followUpEnabled` is **true**
on the Kitchen. It is **false** on freshy (10.75.0.13), which has never exhibited this.

## A failing session, verbatim

```
14:04:46.049 I/SatelliteServer: wake 'ok_ember' score=0.79
14:04:46.050 D/SatelliteServer: send detection {"name":"ok_ember","timestamp":null}
14:04:46.051 D/SatelliteServer: send run-pipeline {"start_stage":"asr","end_stage":"tts","restart_on_end":false}
14:04:46.053 D/SatelliteServer: send streaming-started {}
14:04:46.068 W/AudioTrack: Use of stream types is deprecated ...      <- WAKE earcon starts
14:04:46.101 D/SatelliteServer: recv transcribe {"language":"en"}     <- HA is alive & replies
14:04:46.704 D/AudioTrack: stop(151): called with 10142 frames delivered  <- earcon done
   ... 30 seconds of nothing ...
14:05:16.531 D/SatelliteServer: send streaming-stopped {}             <- WATCHDOG_MS
```

A working session differs ONLY by the presence of, a few seconds in:
```
recv voice-started {"timestamp":3100}
recv voice-stopped {"timestamp":3800}
recv transcript {"text":" Thank you."}
```

## Ruled out with direct evidence — do NOT re-investigate these

- **Mic hardware / gain.** Detector heartbeat stays healthy throughout the wedged period:
  `wake max score=... rms=... chunks=168 dropped=0 (5s)`. rms ranged 88–1222 (1222 = loud audio
  present during the wedge). MICPGA is fine.
- **Wake detector.** Still fires during the wedge, scores 0.79–1.00.
- **Whisper / STT.** Found at `10.75.0.65:10300` (Wyoming, external, not an add-on). Fed it one of
  the device's own 16 kHz capture WAVs directly over the wire: transcript returned in **0.34 s**.
  Healthy.
- **HA connection.** `recv transcribe` arrives from HA at the start of every failed session, so the
  socket is bidirectionally alive at that moment. `recv describe` / `send info` also continue
  throughout (NB: describe probes may arrive on separate short-lived connections — see commit
  6476d78, zeroconf describe-probes vs newest-wins).
- **The assist pipeline config.** stt=`stt.faster_whisper` (owned by a loaded config entry),
  conversation=`conversation.ollama_conversation`, tts=HA Cloud. Unchanged; worked at 13:20.
- **A device config change made that morning** (`captureOnWake` toggled off ~08:30). Sessions
  succeeded at 11:46, 12:31, 12:36 and 13:20 — hours AFTER it. Not causal.
- **The `dismissAtMs` stale-deadline race** (fixed in 0724e4d, which this build HAS). Different
  signature: that one blanked the overlay and stranded the watchdog on its `else` branch. Here the
  watchdog fires correctly every time, at a clean 30 s.

## The open question

`SatelliteSession.onMicChunk` in localWake mode forwards on `wakeState == STREAMING` only, and
`onWakeDetected` resets `micTimestampMs = 0L` and `wakeState = STREAMING` on EVERY wake. So the
pure state machine looks like it should self-heal on the next wake — and the detector heartbeat
confirms `wakeState` really does return to `DETECTING` between sessions (otherwise the detector
would be starved, and it isn't). Yet it never recovers.

So: audio should be flowing to HA, and HA is not hearing speech in it.

**Nobody has determined whether the audio-chunk events stop being SENT, or are sent but contain
silence.** There is no instrumentation on the streaming path — the heartbeat's rms is only
accumulated while DETECTING, never while STREAMING. That is the central unknown.

## Scope / constraints

- `minSdk` 27, `targetSdk` 34. No new dependencies.
- App tests are plain-JVM JUnit4 only — no instrumented tests, no Robolectric. `SatelliteSession`
  is a pure module and is unit-testable; `SatelliteServer`/`MicStreamer` touch Android APIs.
- Gate: `./gradlew testDebugUnitTest assembleDebug`.
- NEVER run `dumpsys media.audio_flinger` — it crashes this device's audio HAL.
- Relevant files: `app/src/main/java/com/rar/hearth/voice/` — `SatelliteSession.kt` (677 lines,
  pure state machine), `SatelliteServer.kt` (375, threading/sockets/mic ownership),
  `MicStreamer.kt` (95, AudioRecord), `EarconPlayer.kt` (71, AudioTrack), `WakeDetector.kt`,
  `WakeAudioRing.kt`, `MixerGuard.kt`.
- Known HAL quirk: prime AudioTrack BEFORE play(); pad one-shots >= 300 ms. The WAKE earcon plays
  via AudioTrack at the exact moment streaming starts, on a device whose mic and speaker share a
  HAL that has bitten this project before.
