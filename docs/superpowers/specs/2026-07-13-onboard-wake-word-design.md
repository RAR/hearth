# Onboard Wake Word Detection (2026-07-13)

Run openWakeWord locally on the Echo so mic audio only streams to Home Assistant after the wake word is heard on-device (privacy, ~32 KB/s off the network, survives HA restarts). **Replaces** the always-streaming mode (user's explicit choice — no dual mode; git is the rollback), with one internal exception: a silent fallback to the old behavior if the models fail to load.

User approvals: TFLite dependency exception granted; bundle okay_nabu + hey_jarvis + alexa with a picker; replace streaming mode entirely; design approved 2026-07-13.

Verified references (committed): `docs/superpowers/research/research-openwakeword.md` (models, exact streaming algorithm, Android gotchas) and `docs/superpowers/research/research-wyoming-localwake.md` (protocol flow, quoted from wyoming-satellite + HA core source). Implementers MUST follow those docs for constants and event flow; this spec summarizes.

## 1. Dependency + model assets

- `implementation("org.tensorflow:tensorflow-lite:2.14.0")` — the app's second external dependency ever (approved). minSdk 28 / compileSdk 34 compatible. Standard artifact only — the melspectrogram graph was binary-verified to use builtin conv ops, NO select-tf-ops.
- Five float32 models under `app/src/main/assets/wake/`, all downloaded from `https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/models/<name>.tflite` (byte-identical to openWakeWord v0.5.1 release assets where both exist; okay_nabu exists ONLY here). Verify sizes on download:
  - `melspectrogram.tflite` 1,092,516 B; `embedding_model.tflite` 1,330,312 B; `okay_nabu.tflite` 206,380 B; `hey_jarvis.tflite` 1,278,912 B; `alexa.tflite` 855,312 B (~4.7 MB total).
- License note (docs only, no code impact): model weights are CC BY-NC-SA 4.0 (non-commercial) — fine for this personal device; the app must never be distributed commercially with these assets.

## 2. `WakeDetector` — pure-JVM inference pipeline (the tested core)

New `voice/WakeDetector.kt`, NO android imports. The three interpreters hide behind:

```kotlin
fun interface TfGraph { fun run(input: FloatArray): FloatArray }
```

Constructor: `WakeDetector(melspec: TfGraph, embedding: TfGraph, head: TfGraph, thresholdPct: Int, nowMs: () -> Long)`.

Algorithm (port of wyoming-openwakeword/pyopen-wakeword; all constants from the research doc's summary table):
- Accumulate incoming 16 kHz 16-bit mono PCM into **1280-sample (80 ms) chunks** (carry remainders).
- Per chunk: audio window = last **1760** samples (480 left-context + 1280 new; ring shift `arraycopy(1280→0, len 480)` then append). Feed melspec (in: 1760 floats of raw sample values; out: 8×32) → apply **`x/10 + 2` exactly once** → shift the 76×32 mel ring left by 8, append the 8 new frames.
- Feed embedding (in: 76×32; out: 96 floats) → shift the 16×96 embedding ring left by 1, append.
- Feed head (in: 16×96; out: 1 score). Detection when `score > thresholdPct/100f`, trigger_level 1 (first qualifying frame), then a **2000 ms wall-clock refractory** (injected clock).
- Warm-up: all rings zero-initialized; suppress detections for the first **16 chunks (~1.3 s)** after construction or `reset()` (both reference implementations refuse to trust early frames).
- API: `process(pcm: ByteArray): Boolean` (true = wake detected this call; little-endian 16-bit like the mic path), `reset()`.

`TfGraph` fakes make every buffer shift, the normalization, chunk accumulation, threshold, refractory, and warm-up testable in plain JVM.

## 3. Android glue — `TfliteWakeGraphs`

New `voice/TfliteWakeGraphs.kt` (thin, untested): loads the three interpreters from assets given a wake-word id. CRITICAL (the one real reported Android crash): melspectrogram's input is dynamic — `resizeInput(0, intArrayOf(1, 1760))` **then** `allocateTensors()`, once at load. Embedding and head have fixed shapes, no resize. Exposes the three `TfGraph`s or returns null on ANY failure (missing asset, interpreter exception) — the caller logs and falls back (§6). Detector inference runs on one dedicated daemon thread fed via a small queue from the mic callback; if the queue backs up past ~8 chunks, drop oldest (never block the mic).

## 4. Protocol — `SatelliteSession` becomes a wake-streaming satellite

Session gains `localWake: Boolean` (constructor). `localWake=false` preserves today's behavior verbatim (the fallback). With `localWake=true`, per the reference `WakeStreamingSatellite` contract (research doc §1):

- **`run-satellite`** → StartMic, but NO `run-pipeline` and NO streaming; state = DETECTING. (Send no `streaming-started` yet.)
- **Mic routing**: new action `SatelliteAction.FeedDetector(pcm)`. In DETECTING, `onMicChunk` emits `FeedDetector` (server runs the detector); while STREAMING it emits `audio-chunk` Sends exactly as today. While a TTS response is playing (between `audio-start` and `onPlaybackFinished`), DETECTING-state mic chunks are **dropped** (no FeedDetector) — our anti-self-trigger improvement over the reference, which relies only on its refractory timer.
- **`onWakeDetected(name, nowMs)`** (new entry point, called by the server when the detector fires) → emits in order: `Send(detection {name, timestamp: null})`, `Send(run-pipeline {start_stage:"asr", end_stage:"tts", restart_on_end:false})`, `Send(streaming-started)`, `Earcon(WAKE)`, `Overlay(LISTENING)`; state = STREAMING. (Detection BEFORE run-pipeline — HA reads them in order to resolve the wake phrase. HA skips its own wake stage purely because start_stage=asr.)
- **Streaming stops** (state → DETECTING, emit `Send(streaming-stopped)` + `SatelliteAction.ResetDetector`) on exactly: `transcript`, `error`, `pause-satellite` (state → paused, mic off), `run-satellite` (re-arm). `transcript`/`synthesize` overlay + DONE-earcon behavior unchanged.
- **`info` event**: when localWake, populate `wake` with one program whose `models` list the three bundled wake words (`name` = model id, `phrase` = "Okay Nabu"/"Hey Jarvis"/"Alexa", installed=true) — HA uses it only to display a friendly phrase. `active_wake_words`/`max_active_wake_words`/`has_vad` stay as-is (HA never reads them).
- No local VAD: HA's STT stage ends the utterance; we stream until told.
- The HA `detection` event case (line "detection" → earcon+overlay) only fires in legacy mode now; keep it for the fallback path.

`SatelliteServer` executes `FeedDetector`/`ResetDetector` against a `WakeDetector?` provided at start; a detector hit calls back into the session under the same lock (`onWakeDetected`).

## 5. Config + web page

- `VoiceSettings` gains `wakeWord: String = "okay_nabu"` (valid set: okay_nabu, hey_jarvis, alexa; clamped like timerTone) and `wakeThreshold: Int = 50` (clamp 10..95; score threshold = /100).
- Voice card: "Wake word" select (Okay Nabu / Hey Jarvis / Alexa) + "Wake sensitivity" number input (10–95, higher = stricter). Muted hint: wake word now detected on-device; the pipeline's "streaming wake word" setting in HA is no longer used; mic audio only reaches HA after the wake word.
- Changing wakeWord/wakeThreshold (or voice.enabled) live: App recreates graphs + detector and restarts the satellite server path, same reactive pattern as the existing voice.enabled watcher.

## 6. Failure fallback (not user-facing)

At satellite start, App attempts `TfliteWakeGraphs.load(assets, wakeWord)`. Null → log one warning and start the session with `localWake=false` — exact current behavior (`run-pipeline start_stage:"wake" restart_on_end:true` + always-stream, HA-side wake). This covers the residual risk that the melspectrogram graph needs ops our TFLite build lacks (evidence says it doesn't).

## 7. Observability

Log each detection with its score at INFO (`WakeDetector` returns score internally; server logs "wake '<name>' score=0.87"). DEBUG-log the max score per ~5 s window while detecting, so threshold tuning is a logcat away.

## 8. Tests (plain JVM)

- `WakeDetectorTest` (fake TfGraphs that record inputs and return scripted outputs): chunk accumulation (exact 1280 / split across calls / remainder carry); audio ring passes 1760 with 480-sample context; x/10+2 applied to melspec output before the mel ring; mel ring shifts by 8, embedding ring by 1; head sees last 16 embeddings; detection at score>threshold; refractory suppresses a second hit within 2 s (fake clock) and allows after; warm-up suppresses first 16 chunks; reset() re-arms warm-up.
- `SatelliteSessionTest`: localWake=true — run-satellite → StartMic + no run-pipeline; mic → FeedDetector; onWakeDetected → exact action sequence/order above; mic → audio-chunk while streaming; transcript → streaming-stopped + ResetDetector + subsequent mic → FeedDetector; error/pause behaviors; TTS window drops FeedDetector then resumes after onPlaybackFinished; localWake=false → byte-for-byte today's behavior (existing tests keep passing unchanged where they construct the session with localWake=false or default).
- `DashConfigTest`: wakeWord/wakeThreshold defaults + clamps.
- On-device verification plan: flash, watch `adb logcat | grep WakeDetector` while saying the wake word; confirm HA pipeline runs STT→TTS end-to-end; confirm timers + TTS unchanged.

## Constraints

Kotlin 2.1.0; compileSdk 34 NEVER bump; minSdk 28; **new dependency allowed: org.tensorflow:tensorflow-lite:2.14.0 ONLY** (no select-tf-ops, no support libs); plain-JVM JUnit4 only (WakeDetector/session tests must not import android.*); gate `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` exit 0; commit trailer `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`. Config back-compat. NEVER `dumpsys media.audio_flinger`.
