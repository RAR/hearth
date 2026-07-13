# Wake-Word Acknowledgment: Edge Glow + Earcons (2026-07-13)

User request: "make it more obvious when a wake word has been heard - both graphically and a sound." Design approved in conversation (visual = full screen-edge glow after confirming the hardware can carry it; earcon = fixed chirp with volume setting; sounds on wake AND end-of-listening).

## Signals (already present — no protocol work)

HA's pipeline sends Wyoming `detection` when the wake word is heard and `transcript` when speech capture ends. `SatelliteSession` already handles both (overlay LISTENING / TRANSCRIPT).

## 1. Session — new `Earcon` action

In SatelliteSession.kt, next to `SatelliteAction`:

```kotlin
enum class EarconKind { WAKE, DONE }
```

`SatelliteAction` gains `data class Earcon(val kind: EarconKind) : SatelliteAction`.

- `"detection"` → `listOf(SatelliteAction.Earcon(EarconKind.WAKE), overlayAction(LISTENING))`
- `"transcript"` → `listOf(SatelliteAction.Earcon(EarconKind.DONE), overlayAction(TRANSCRIPT, text))`

The earcon precedes the overlay action so existing `.last() as Overlay` test assertions keep working. `synthesize`/other events unchanged; DONE is emitted on every transcript (detection always precedes it — run-pipeline starts at the wake stage).

## 2. Tones — `ToneGenerator.earcon()`

New pure function alongside `render()`:

```kotlin
fun earcon(kind: String, volume: Int, rate: Int): ShortArray
```

- Same amplitude law as `render` ((volume/100) · 0.6 · Short.MAX_VALUE; volume 0 = silence).
- `"wake"`: rising two-note chirp — 660 Hz for 130 ms then 880 Hz for 150 ms, each note with an 8 ms linear attack/release ramp (no clicks). ~280 ms total, NO trailing gap (one-shot, not a looping alarm cycle).
- `"done"`: falling — 880 Hz for 100 ms then 660 Hz for 120 ms, same ramps. ~220 ms.
- `"preview"`: wake + 150 ms silence + done, concatenated (used by the config-page Preview button).
- Unknown kind falls back to `"wake"`.

## 3. Playback — `EarconPlayer`

New small class in `voice/` mirroring `TimerChime.playOnce`'s known-good HAL recipe (prime the full buffer BEFORE `play()`, then wait for the playback head before release), with two differences: it renders via `ToneGenerator.earcon`, and it plays on **STREAM_MUSIC** (tracks media volume like the TTS responses; the timer alarm stays on STREAM_ALARM). `play(kind: String, volume: Int)` no-ops when `volume <= 0`. Best-effort, never throws, own daemon thread per call.

Mic-pickup caveat (accepted): the chirp plays while the mic streams to STT; no AEC on this device. ~280 ms is the commercial-assistant norm; shorten/quiet if mis-transcriptions appear.

## 4. Wiring

- `SatelliteServer.Out` gains `fun onEarcon(kind: EarconKind)`; `dispatch()` gains the case.
- App.kt: `val earconPlayer = EarconPlayer()` beside `timerChime`; Out impl plays `"wake"`/`"done"` at `configStore.config.value.voice.wakeSoundVolume`.

## 5. Visual — `WakeGlow`

New composable in ui/VoiceOverlay.kt: four gradient strips hugging the screen edges (28dp deep, cyan `0xFF4FC3F7`, fading inward to transparent; corners overlap → slightly brighter, acceptable glow look). Alpha pulses 0.45→0.9 on a 600 ms reversing tween (1.2 s full cycle, FastOutSlowInEasing) via `rememberInfiniteTransition` — animation only runs while composed. Wrapped in `AnimatedVisibility(visible, fadeIn(tween(250)), fadeOut(tween(400)))`.

Rendered in App.kt immediately before `VoiceOverlay(voiceOverlayState)` with `visible = voiceOverlayState.phase == VoiceOverlayPhase.LISTENING` — same layer as the pill, so it appears over night mode and the music takeover exactly like the pill. No config; free when voice is off (phase stays HIDDEN).

## 6. Config — `voice.wakeSoundVolume`

`VoiceSettings` gains `val wakeSoundVolume: Int = 80`; `clamped()` coerces 0..100 (0 = silent). Old configs default to 80.

ConfigServer: new constructor param `previewEarcon: (Int) -> Unit`; PIN-gated `POST /api/voice/preview-wake` mirroring `handlePreviewChime` (body `{"volume": n}`, fallback to saved value, normalize via `VoiceSettings(...).clamped()`), calls `previewEarcon(norm.wakeSoundVolume)`. App passes `previewEarcon = { volume -> earconPlayer.play("preview", volume) }`.

app.js Voice card: after the Alarm volume row + Preview button, add a "Wake sound volume" number input (0–100, default 80, same pattern as Alarm volume) and its own Preview button posting `/api/voice/preview-wake` with `{ volume: v.wakeSoundVolume }`. Muted hint gains: "Wake sound: chirps when the wake word is heard and when it stops listening; volume 0 disables it."

## Tests (plain JVM)

- SatelliteSessionTest: `detection` emits `Earcon(WAKE)` before the LISTENING overlay; `transcript` emits `Earcon(DONE)`; existing `.last()` overlay assertions untouched.
- ToneGeneratorTest: wake/done/preview earcon lengths (rate 16000: wake = 0.13·rate + 0.15·rate samples; done = 0.10·rate + 0.12·rate; preview = wake + 0.15·rate + done); non-silent at volume 80; all-zero at volume 0; unknown kind = wake length.
- DashConfigTest: `wakeSoundVolume` clamps (e.g. 150→100, -5→0) and defaults to 80 when absent from saved JSON.
- SatelliteServerTest: RecordingOut gains onEarcon; existing tests compile/pass.

## Constraints

Kotlin 2.1.0; compileSdk 34 NEVER bump; NO new dependencies; plain-JVM JUnit4 only; gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0; commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`. Config back-compat required. NEVER `dumpsys media.audio_flinger`.
