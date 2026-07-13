# Onboard Wake Word Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run openWakeWord locally on the Echo so mic audio only streams to Home Assistant after the wake word is heard on-device (privacy, ~32 KB/s off the network, survives HA restarts). Replaces the always-streaming mode, with a silent fallback to the old behavior if the models fail to load.

**Architecture:** A new pure-JVM `WakeDetector` ports the openWakeWord streaming pipeline (melspec → embedding → head) behind a `fun interface TfGraph`. A thin, untested `TfliteWakeGraphs` loads three TFLite interpreters from bundled assets and adapts them to `TfGraph`. `SatelliteSession` gains a `localWake` mode: it arms detection on `run-satellite`, feeds mic chunks to the detector, and (via a new `onWakeDetected`) streams to HA only after a local hit — mirroring the reference `WakeStreamingSatellite` protocol. `SatelliteServer` runs the detector on a dedicated daemon thread fed by a bounded drop-oldest queue and re-enters the session under its lock on detection. `App` builds the graphs + detector at satellite start, falling back to `localWake=false` on load failure, and restarts on `enabled`/`wakeWord`/`wakeThreshold` config changes. Two new config knobs: `voice.wakeWord`, `voice.wakeThreshold`.

**Tech Stack:** Kotlin 2.1.0, TensorFlow Lite 2.14.0 (new), Jetpack Compose, kotlinx.serialization, NanoHTTPD config server, vanilla JS config page, JUnit4 plain JVM.

**Spec:** docs/superpowers/specs/2026-07-13-onboard-wake-word-design.md

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 NEVER bump; minSdk 28.
- The ONLY new dependency allowed is `org.tensorflow:tensorflow-lite:2.14.0` (no select-tf-ops, no support libs).
- Plain-JVM JUnit4 tests only — no `android.*` imports in tests (WakeDetector/session/config tests must run on the JVM).
- Build gate (repo root): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` must exit 0.
- Every commit message ends with the trailer line: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Config back-compat: old saved configs (no `wakeWord`/`wakeThreshold`) must load with defaults `okay_nabu` / `50`.
- `localWake=false` (default) must be byte-for-byte identical to today's behavior — the existing SatelliteSession/SatelliteServer tests must pass UNCHANGED.
- NEVER `dumpsys media.audio_flinger` (crashes the audio HAL on this device).

All numeric constants below come from `docs/superpowers/research/research-openwakeword.md` (Summary table, §2, §6). The protocol event flow comes from `docs/superpowers/research/research-wyoming-localwake.md` (Practical implications §1–7).

---

### Task 1: `WakeDetector` inference pipeline + config knobs (pure JVM)

**Files:**
- Create: `app/src/main/java/com/rar/echodash/voice/WakeDetector.kt`
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt` (VoiceSettings ~line 101, its `clamped()` and companion)
- Test: `app/src/test/java/com/rar/echodash/voice/WakeDetectorTest.kt` (create)
- Test: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (extend)

**Interfaces:**
- Produces: `WakeDetector.TfGraph` (`fun interface TfGraph { fun run(input: FloatArray): FloatArray }`); `class WakeDetector(melspec: TfGraph, embedding: TfGraph, head: TfGraph, thresholdPct: Int, nowMs: () -> Long)` with `fun process(pcm: ByteArray): Boolean`, `val lastScore: Float`, `fun reset()`. Task 2 (server) and Task 3 (App, TfliteWakeGraphs) consume all of these.
- Produces: `VoiceSettings.wakeWord: String = "okay_nabu"`, `VoiceSettings.wakeThreshold: Int = 50`, `VoiceSettings.WAKE_WORDS`. Task 3 (App + web page) consumes these.

**Constants (all from the research Summary table):** chunk 1280 samples (80 ms); melspec input 1760 floats = 1280 new + 480 left-context; melspec output 8×32; transform `x/10 + 2` once; mel ring 76×32 shift-by-8; embedding input 76×32, output 96; embedding ring 16×96 shift-by-1; head input 16×96, output 1 score; threshold `thresholdPct/100`; trigger_level 1 (fire on first qualifying frame); 2000 ms wall-clock refractory; 16-chunk warm-up suppression. PCM is little-endian 16-bit converted to raw int16-valued floats (NOT normalized to ±1).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rar/echodash/voice/WakeDetectorTest.kt`:

```kotlin
package com.rar.echodash.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDetectorTest {

    /** Fake TfGraph that records every input (copied) and returns a scripted output. */
    private class RecordingGraph(val transform: (FloatArray) -> FloatArray) : WakeDetector.TfGraph {
        val inputs = mutableListOf<FloatArray>()
        override fun run(input: FloatArray): FloatArray {
            inputs.add(input.copyOf())
            return transform(input)
        }
    }

    /** n samples all equal to [value], little-endian 16-bit. */
    private fun pcm(n: Int, value: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            b[2 * i] = (value and 0xFF).toByte()
            b[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun zeros256() = RecordingGraph { FloatArray(256) }
    private fun zeros96() = RecordingGraph { FloatArray(96) }
    private fun score(v: Float) = RecordingGraph { floatArrayOf(v) }

    @Test
    fun melspecRunsOncePerCompleteChunkCarryingRemainder() {
        val mel = zeros256()
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(640, 1))            // half a chunk -> nothing yet
        assertEquals(0, mel.inputs.size)
        d.process(pcm(640, 1))            // completes 1280 -> one melspec call
        assertEquals(1, mel.inputs.size)
        d.process(pcm(1280, 1))           // exactly one more chunk
        assertEquals(2, mel.inputs.size)
    }

    @Test
    fun audioWindowIs1760With480Context() {
        val mel = zeros256()
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(1280, 100))
        d.process(pcm(1280, 200))
        assertEquals(1760, mel.inputs[0].size)
        // First chunk: 480 zero context, then 1280 of value 100.
        for (i in 0 until 480) assertEquals(0f, mel.inputs[0][i], 1e-6f)
        for (i in 480 until 1760) assertEquals(100f, mel.inputs[0][i], 1e-6f)
        // Second chunk: context = trailing 480 of the previous chunk (100), then 1280 of 200.
        for (i in 0 until 480) assertEquals(100f, mel.inputs[1][i], 1e-6f)
        for (i in 480 until 1760) assertEquals(200f, mel.inputs[1][i], 1e-6f)
    }

    @Test
    fun pcmDecodedAsSignedLittleEndian() {
        val mel = zeros256()
        val d = WakeDetector(mel, zeros96(), score(0f), 50) { 0L }
        d.process(pcm(1280, -5))
        for (i in 480 until 1760) assertEquals(-5f, mel.inputs[0][i], 1e-6f)
    }

    @Test
    fun melTransformXOver10Plus2AppliedOnce() {
        val mel = RecordingGraph { FloatArray(256) { 30f } }
        val emb = zeros96()
        val d = WakeDetector(mel, emb, score(0f), 50) { 0L }
        d.process(pcm(1280, 1))
        val melRing = emb.inputs[0]          // 76*32 = 2432, last 8 frames are the new ones
        assertEquals(2432, melRing.size)
        assertEquals(0f, melRing[0], 1e-6f)              // oldest frame still zero
        assertEquals(5f, melRing[68 * 32], 1e-6f)        // 30/10 + 2 = 5, first new frame
        assertEquals(5f, melRing[2431], 1e-6f)           // last element
    }

    @Test
    fun melRingShiftsByEight() {
        var call = 0
        val mel = RecordingGraph { call += 1; val v = if (call == 1) 30f else 70f; FloatArray(256) { v } }
        val emb = zeros96()
        val d = WakeDetector(mel, emb, score(0f), 50) { 0L }
        d.process(pcm(2560, 1))              // two chunks in one call
        val ring2 = emb.inputs[1]            // mel ring at the second chunk
        assertEquals(5f, ring2[60 * 32], 1e-6f)   // chunk1 frames (30->5) shifted left by 8 to 60..67
        assertEquals(9f, ring2[68 * 32], 1e-6f)   // chunk2 frames (70->9) are the newest 8
        assertEquals(0f, ring2[59 * 32], 1e-6f)   // still zero before the shifted block
    }

    @Test
    fun embeddingRingShiftsByOneAndHeadSeesLast16() {
        var c = 0
        val emb = RecordingGraph { c += 1; FloatArray(96) { c.toFloat() } }  // call1->1, call2->2, ...
        val head = score(0f)
        val d = WakeDetector(zeros256(), emb, head, 50) { 0L }
        d.process(pcm(1280 * 3, 1))          // three chunks -> three embeddings
        val hin = head.inputs[2]             // head input at the third chunk
        assertEquals(1536, hin.size)         // 16 * 96
        assertEquals(3f, hin[15 * 96], 1e-6f)   // newest embedding at row 15
        assertEquals(2f, hin[14 * 96], 1e-6f)
        assertEquals(1f, hin[13 * 96], 1e-6f)
        assertEquals(0f, hin[12 * 96], 1e-6f)   // rows before that still zero
    }

    @Test
    fun warmupSuppressesFirst16ChunksThenFires() {
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertFalse(d.process(pcm(1280 * 16, 1)))   // first 16 chunks suppressed
        assertTrue(d.process(pcm(1280, 1)))         // 17th chunk fires
        assertEquals(0.9f, d.lastScore, 1e-6f)
    }

    @Test
    fun detectionIsStrictlyAboveThreshold() {
        val d = WakeDetector(zeros256(), zeros96(), score(0.5f), 50) { 0L }
        assertFalse(d.process(pcm(1280 * 17, 1)))   // 0.5 is not > 0.5
    }

    @Test
    fun refractorySuppressesWithinTwoSecondsThenReleases() {
        var clock = 0L
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { clock }
        d.process(pcm(1280 * 16, 1))         // warm up (clock 0)
        clock = 1000
        assertTrue(d.process(pcm(1280, 1)))  // chunk 17 fires at t=1000
        clock = 2000                          // 1000 ms later, < 2000 refractory
        assertFalse(d.process(pcm(1280, 1)))
        clock = 3001                          // 2001 ms after the fire, past refractory
        assertTrue(d.process(pcm(1280, 1)))
    }

    @Test
    fun resetReArmsWarmupAndClearsRefractory() {
        val d = WakeDetector(zeros256(), zeros96(), score(0.9f), 50) { 0L }
        assertTrue(d.process(pcm(1280 * 17, 1)))    // fires once
        d.reset()
        assertFalse(d.process(pcm(1280 * 16, 1)))   // warm-up suppresses 16 again
        assertTrue(d.process(pcm(1280, 1)))         // 17th fires (no refractory carried over)
    }
}
```

Extend `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` — add these tests inside the class (imports `assertEquals`/`assertTrue` are already present):

```kotlin
    @Test
    fun wakeWordDefaultsAndClamps() {
        assertEquals("okay_nabu", DashConfig().voice.wakeWord)
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("okay_nabu", cfg.voice.wakeWord)               // old config -> default
        assertEquals("hey_jarvis", DashConfig(voice = VoiceSettings(wakeWord = "hey_jarvis")).clamped().voice.wakeWord)
        assertEquals("alexa", DashConfig(voice = VoiceSettings(wakeWord = "  alexa  ")).clamped().voice.wakeWord) // trimmed
        assertEquals("okay_nabu", DashConfig(voice = VoiceSettings(wakeWord = "bogus")).clamped().voice.wakeWord) // unknown -> default
    }

    @Test
    fun wakeThresholdDefaultsAndClamps() {
        assertEquals(50, DashConfig().voice.wakeThreshold)
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals(50, cfg.voice.wakeThreshold)                  // old config -> default
        assertEquals(95, DashConfig(voice = VoiceSettings(wakeThreshold = 200)).clamped().voice.wakeThreshold) // ceil 95
        assertEquals(10, DashConfig(voice = VoiceSettings(wakeThreshold = 1)).clamped().voice.wakeThreshold)   // floor 10
        assertEquals(70, DashConfig(voice = VoiceSettings(wakeThreshold = 70)).clamped().voice.wakeThreshold)
    }

    @Test
    fun wakeSettingsRoundTrip() {
        val cfg = DashConfig(voice = VoiceSettings(enabled = true, wakeWord = "alexa", wakeThreshold = 65))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("alexa", decodeConfig(text).voice.wakeWord)
        assertEquals(65, decodeConfig(text).voice.wakeThreshold)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.voice.WakeDetectorTest' --tests 'com.rar.echodash.config.DashConfigTest'`
Expected: compilation FAILS (no `WakeDetector` class / no `wakeWord`/`wakeThreshold` on `VoiceSettings`).

- [ ] **Step 3: Implement WakeDetector**

Create `app/src/main/java/com/rar/echodash/voice/WakeDetector.kt`:

```kotlin
package com.rar.echodash.voice

/**
 * Pure-JVM streaming openWakeWord inference: melspectrogram -> embedding -> wake-word head.
 * A port of wyoming-openwakeword / pyopen-wakeword (constants from research-openwakeword.md).
 * The three TFLite graphs hide behind [TfGraph] so the whole pipeline — buffer shifts, the
 * x/10+2 transform, chunk accumulation, threshold, refractory, and warm-up — is testable with
 * fake graphs and no Android/TFLite imports. All threading lives in SatelliteServer; this class
 * is not thread-safe and must be driven from a single thread.
 *
 * PCM in is little-endian 16-bit mono @ 16 kHz, converted to RAW int16-valued floats (the
 * melspec model was trained on un-normalized sample values, not ±1).
 */
class WakeDetector(
    private val melspec: TfGraph,
    private val embedding: TfGraph,
    private val head: TfGraph,
    private val thresholdPct: Int,
    private val nowMs: () -> Long,
) {
    /** One TFLite graph: flat float input -> flat float output. */
    fun interface TfGraph {
        fun run(input: FloatArray): FloatArray
    }

    // Ring buffers, all zero-initialized (warm-up state).
    private val audioRing = FloatArray(MEL_SAMPLES)                 // 1760 = 480 context + 1280 new
    private val melRing = FloatArray(EMB_FEATURES * NUM_MELS)       // 76 x 32
    private val embRing = FloatArray(EMB_WINDOW * WW_FEATURES)      // 16 x 96
    private var pending = FloatArray(0)                             // leftover samples (< 1280)
    private var chunksProcessed = 0
    private var lastDetectMs: Long? = null

    /** Score of the most recently processed chunk (for logging/tuning). */
    var lastScore: Float = 0f
        private set

    /** Feed a mic PCM chunk. Returns true iff a wake detection fired during this call. */
    fun process(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        val n = pcm.size / 2
        val incoming = FloatArray(n) { i ->
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()          // sign-extends -> correct signed 16-bit
            ((hi shl 8) or lo).toFloat()
        }
        val combined = if (pending.isEmpty()) incoming else pending + incoming
        var offset = 0
        var fired = false
        while (combined.size - offset >= SAMPLES_PER_CHUNK) {
            if (processChunk(combined, offset)) fired = true
            offset += SAMPLES_PER_CHUNK
        }
        pending = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else FloatArray(0)
        return fired
    }

    /** Re-arm: clear all buffers and the refractory timer, restart the 16-chunk warm-up. */
    fun reset() {
        audioRing.fill(0f)
        melRing.fill(0f)
        embRing.fill(0f)
        pending = FloatArray(0)
        chunksProcessed = 0
        lastDetectMs = null
        lastScore = 0f
    }

    private fun processChunk(src: FloatArray, offset: Int): Boolean {
        // Audio window: keep the trailing 480 samples of the previous window as left-context,
        // then append the new 1280.
        System.arraycopy(audioRing, SAMPLES_PER_CHUNK, audioRing, 0, CONTEXT)
        System.arraycopy(src, offset, audioRing, CONTEXT, SAMPLES_PER_CHUNK)

        // Melspec: 1760 samples -> 8 x 32 frames; apply x/10 + 2 exactly once, then shift the
        // mel ring left by 8 frames and append.
        val mel = melspec.run(audioRing)                 // 8 * 32 = 256
        val shift = MEL_FRAMES_PER_CHUNK * NUM_MELS       // 256
        System.arraycopy(melRing, shift, melRing, 0, melRing.size - shift)
        val base = melRing.size - shift                  // (76 - 8) * 32 = 2176
        for (i in 0 until shift) melRing[base + i] = mel[i] / 10f + 2f

        // Embedding: 76 x 32 -> 96; shift the embedding ring left by 1 and append.
        val emb = embedding.run(melRing)                 // 96
        System.arraycopy(embRing, WW_FEATURES, embRing, 0, embRing.size - WW_FEATURES)
        System.arraycopy(emb, 0, embRing, embRing.size - WW_FEATURES, WW_FEATURES)

        // Head: 16 x 96 -> 1 score.
        val out = head.run(embRing)
        lastScore = out[0]
        chunksProcessed++

        // Warm-up: never trust the first 16 chunks (~1.3 s) after construction/reset.
        if (chunksProcessed <= WARMUP_CHUNKS) return false
        // Refractory: 2 s wall-clock cooldown after any detection.
        val now = nowMs()
        val last = lastDetectMs
        if (last != null && now - last < REFRACTORY_MS) return false
        // trigger_level 1: fire on the first frame strictly above threshold.
        if (lastScore > thresholdPct / 100f) {
            lastDetectMs = now
            return true
        }
        return false
    }

    private companion object {
        const val SAMPLES_PER_CHUNK = 1280      // 80 ms @ 16 kHz
        const val CONTEXT = 480                 // 3 STFT hops of left-context
        const val MEL_SAMPLES = 1760            // CONTEXT + SAMPLES_PER_CHUNK
        const val NUM_MELS = 32
        const val MEL_FRAMES_PER_CHUNK = 8      // ceil(1760/160 - 3)
        const val EMB_FEATURES = 76             // mel-frame window into the embedding model
        const val WW_FEATURES = 96              // embedding dimension
        const val EMB_WINDOW = 16               // embeddings the head sees
        const val WARMUP_CHUNKS = 16
        const val REFRACTORY_MS = 2000L
    }
}
```

Now edit `app/src/main/java/com/rar/echodash/config/DashConfig.kt`. Replace the `VoiceSettings` data class (lines 100–119) with:

```kotlin
@Serializable
data class VoiceSettings(
    val enabled: Boolean = false,
    val timerTone: String = "twotone",
    val timerVolume: Int = 80,
    val wakeSoundVolume: Int = 80,
    val wakeWord: String = "okay_nabu",
    val wakeThreshold: Int = 50,
) {
    /** Normalize the timer-alarm fields: trim + unknown/blank tone falls to "twotone",
     *  volumes coerced into 0..100. Wake word clamps to the bundled set (unknown -> okay_nabu);
     *  wake threshold (score * 100) coerced into 10..95. Shared by DashConfig.clamped and the
     *  preview endpoint. */
    fun clamped(): VoiceSettings = copy(
        timerTone = timerTone.trim().let { if (it in TONES) it else "twotone" },
        timerVolume = timerVolume.coerceIn(0, 100),
        wakeSoundVolume = wakeSoundVolume.coerceIn(0, 100),
        wakeWord = wakeWord.trim().let { if (it in WAKE_WORDS) it else "okay_nabu" },
        wakeThreshold = wakeThreshold.coerceIn(10, 95),
    )

    companion object {
        /** The four recognized preset ids. */
        val TONES: Set<String> = setOf("twotone", "beeps", "chime", "trill")

        /** The three bundled on-device wake-word model ids. */
        val WAKE_WORDS: Set<String> = setOf("okay_nabu", "hey_jarvis", "alexa")
    }
}
```

- [ ] **Step 4: Run the full gate** — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`, expect exit 0.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/WakeDetector.kt \
        app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/test/java/com/rar/echodash/voice/WakeDetectorTest.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "WakeDetector: pure-JVM openWakeWord streaming pipeline + wake config knobs

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 2: `SatelliteSession` wake-streaming mode + `SatelliteServer` detector thread

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt` (full-file replacement below)
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt` (full-file replacement below)
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt` (extend)
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt` (extend)

**Interfaces:**
- Consumes (Task 1): `WakeDetector` (`process`, `lastScore`, `reset`), `WakeDetector.TfGraph`.
- Produces: `SatelliteSession(appVersion: String, localWake: Boolean = false)`; `SatelliteSession.onWakeDetected(name: String, nowMs: Long): List<SatelliteAction>`; `SatelliteAction.FeedDetector(pcm)`, `SatelliteAction.ResetDetector`; `SatelliteServer.start(localWake: Boolean = false, detector: WakeDetector? = null, wakeWord: String = "okay_nabu")`. Task 3 (App) consumes the session ctor default and `SatelliteServer.start(...)`.
- The `SatelliteServer.Out` interface is NOT widened — `FeedDetector`/`ResetDetector` are handled internally.

- [ ] **Step 1: Write the failing tests**

Extend `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`. Add the import `import org.junit.Assert.assertFalse` at the top, then add these tests inside the class (the existing `session()`, `event(...)`, `sends(...)`, and `jsonArray()` helpers are reused):

```kotlin
    private fun wakeSession() = SatelliteSession(appVersion = "9.9", localWake = true)

    @Test
    fun localWakeRunSatelliteArmsMicWithoutRunPipeline() {
        val a = wakeSession().onEvent(event("run-satellite"))
        assertTrue(a.contains(SatelliteAction.StartMic))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        val types = sends(a).map { it.type }
        assertFalse(types.contains("run-pipeline"))
        assertFalse(types.contains("streaming-started"))
        assertTrue(types.contains("streaming-stopped"))
    }

    @Test
    fun localWakeMicChunkFeedsDetectorWhileDetecting() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onMicChunk(ByteArray(960) { 5 })
        assertEquals(1, a.size)
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 5 }), a.first())
    }

    @Test
    fun onWakeDetectedEmitsDetectionThenRunPipelineThenStreamingStarted() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onWakeDetected("alexa", nowMs = 0)
        val ev = sends(a)
        assertEquals("detection", ev[0].type)
        assertEquals("alexa", ev[0].data["name"]!!.jsonPrimitive.content)
        assertEquals("run-pipeline", ev[1].type)
        assertEquals("asr", ev[1].data["start_stage"]!!.jsonPrimitive.content)
        assertEquals("tts", ev[1].data["end_stage"]!!.jsonPrimitive.content)
        assertEquals(false, ev[1].data["restart_on_end"]!!.jsonPrimitive.boolean)
        assertEquals("streaming-started", ev[2].type)
        val earconIdx = a.indexOfFirst { it is SatelliteAction.Earcon }
        val overlayIdx = a.indexOfFirst { it is SatelliteAction.Overlay }
        assertTrue(earconIdx in 0 until overlayIdx)                       // earcon before overlay
        assertEquals(SatelliteAction.Earcon(EarconKind.WAKE), a[earconIdx])
        assertEquals(VoiceOverlayState(VoiceOverlayPhase.LISTENING),
            (a[overlayIdx] as SatelliteAction.Overlay).state)
    }

    @Test
    fun localWakeMicChunkStreamsAudioAfterDetection() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val e = sends(s.onMicChunk(ByteArray(960) { 7 })).single()
        assertEquals("audio-chunk", e.type)
        assertArrayEquals(ByteArray(960) { 7 }, e.payload)
    }

    @Test
    fun localWakeTranscriptStopsStreamingResetsDetectorAndReArms() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val a = s.onEvent(event("transcript", """{"text":"hi"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.DONE), a.first())
        assertTrue(a.any { it is SatelliteAction.Overlay })
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        // Back to detecting: the next mic chunk feeds the detector again.
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())
    }

    @Test
    fun localWakeErrorStopsStreamingAndReArms() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onWakeDetected("alexa", 0)
        val a = s.onEvent(event("error", """{"text":"boom"}"""))
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())
    }

    @Test
    fun localWakePauseStopsMicAndDropsMicChunks() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        val a = s.onEvent(event("pause-satellite"))
        assertTrue(a.contains(SatelliteAction.StopMic))
        assertTrue(a.contains(SatelliteAction.ResetDetector))
        assertTrue(sends(a).map { it.type }.contains("streaming-stopped"))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // paused -> dropped
    }

    @Test
    fun localWakeDropsDetectorFeedDuringTtsWindow() {
        val s = wakeSession()
        s.onEvent(event("run-satellite"))
        s.onEvent(event("audio-start", """{"rate":22050,"width":2,"channels":1}"""))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // dropped: TTS playing
        s.onEvent(event("audio-stop"))
        assertTrue(s.onMicChunk(ByteArray(960) { 1 }).isEmpty())        // still within the window
        s.onPlaybackFinished(1000)
        assertEquals(SatelliteAction.FeedDetector(ByteArray(960) { 1 }),
            s.onMicChunk(ByteArray(960) { 1 }).first())                  // resumes after playback
    }

    @Test
    fun localWakeInfoAdvertisesThreeWakeModels() {
        val info = sends(wakeSession().onEvent(event("describe"))).single()
        val wake = info.data["wake"]!!.jsonArray()
        assertEquals(1, wake.size)
        val models = wake.first().jsonObject["models"]!!.jsonArray()
        assertEquals(3, models.size)
        val phrases = models.map { it.jsonObject["phrase"]!!.jsonPrimitive.content }
        assertTrue(phrases.contains("Okay Nabu"))
        assertTrue(phrases.contains("Hey Jarvis"))
        assertTrue(phrases.contains("Alexa"))
    }

    @Test
    fun legacyModeInfoWakeStaysEmpty() {
        val info = sends(SatelliteSession("9.9").onEvent(event("describe"))).single()
        assertTrue(info.data["wake"]!!.jsonArray().isEmpty())
    }
```

Note: these use `jsonObject` (already imported) and `.jsonArray()` (the file's existing private extension). `sends(...)` returns the `Send` events in order, so `ev[0]/ev[1]/ev[2]` assert wire order.

Extend `app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt`. Add `import kotlinx.serialization.json.jsonPrimitive` at the top, then add this test inside the class (it stops the `@Before`-started legacy server and starts a localWake one):

```kotlin
    private fun awaitBind() {
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("server did not bind", server.boundPort > 0)
    }

    @Test fun localWakeDetectionStreamsToServer() {
        server.stop()
        // Always-fire head so the 17th chunk (past the 16-chunk warm-up) triggers.
        val det = WakeDetector(
            melspec = WakeDetector.TfGraph { FloatArray(256) },
            embedding = WakeDetector.TfGraph { FloatArray(96) },
            head = WakeDetector.TfGraph { floatArrayOf(0.9f) },
            thresholdPct = 50,
            nowMs = { 0L },
        )
        server = SatelliteServer(scope, port = 0, appVersion = "0.3", out = out)
        server.start(localWake = true, detector = det, wakeWord = "alexa")
        awaitBind()
        TestClient(server.boundPort).use { c ->
            c.send(WyomingEvent("run-satellite"))
            assertEquals("streaming-stopped", c.read()!!.type)   // localWake run-satellite re-arms
            assertEquals("start-mic", out.next())
            // Feed 17 whole chunks in one submission; the detector accumulates and fires.
            server.submitMicChunk(ByteArray(17 * 1280 * 2) { 1 })
            val detection = c.read()!!
            assertEquals("detection", detection.type)
            assertEquals("alexa", detection.data["name"]!!.jsonPrimitive.content)
            assertEquals("run-pipeline", c.read()!!.type)
            assertEquals("streaming-started", c.read()!!.type)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.voice.SatelliteSessionTest' --tests 'com.rar.echodash.voice.SatelliteServerTest'`
Expected: compilation FAILS (no `localWake` ctor param, no `onWakeDetected`, no `FeedDetector`/`ResetDetector`, no `start(localWake=...)`).

- [ ] **Step 3: Implement — replace the whole of `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt` with:**

```kotlin
package com.rar.echodash.voice

import com.rar.echodash.vaca.WyomingEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Which acknowledgment chirp to play. */
enum class EarconKind { WAKE, DONE }

/** Actions the pure session asks the outside world to perform. */
sealed interface SatelliteAction {
    data class Send(val event: WyomingEvent) : SatelliteAction
    data object StartMic : SatelliteAction
    data object StopMic : SatelliteAction
    data class PlaybackStart(val rate: Int, val width: Int, val channels: Int) : SatelliteAction
    data class PlaybackChunk(val pcm: ByteArray) : SatelliteAction {
        override fun equals(other: Any?) = other is PlaybackChunk && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }
    data object PlaybackStop : SatelliteAction
    data class Overlay(val state: VoiceOverlayState) : SatelliteAction
    data class Timers(val state: TimersUiState) : SatelliteAction
    data class Earcon(val kind: EarconKind) : SatelliteAction

    /** Feed a raw mic PCM chunk to the on-device wake detector (localWake only). */
    data class FeedDetector(val pcm: ByteArray) : SatelliteAction {
        override fun equals(other: Any?) = other is FeedDetector && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }

    /** Reset the on-device wake detector back to warm-up (localWake only). */
    data object ResetDetector : SatelliteAction
}

/**
 * Pure protocol/state machine for the Wyoming voice satellite.
 *
 * [localWake] = false preserves the original always-streaming behavior (HA runs the wake stage);
 * this is the silent fallback and is byte-for-byte identical to the pre-wake-word implementation.
 *
 * [localWake] = true turns this into a wake-streaming satellite (per wyoming-satellite's
 * WakeStreamingSatellite): run-satellite arms an on-device detector (StartMic, no run-pipeline);
 * mic chunks flow to the detector as FeedDetector actions; [onWakeDetected] (called by the server
 * when the detector fires) emits detection -> run-pipeline(asr..tts) -> streaming-started and
 * begins streaming; transcript/error/run-satellite stop streaming and re-arm, pause-satellite
 * stops streaming and turns the mic off. While a TTS response plays (audio-start .. onPlaybackFinished)
 * detecting-state mic chunks are dropped entirely (anti-self-trigger).
 *
 * No Android or coroutine imports so it runs in plain-JVM tests. All threading lives in the server.
 */
class SatelliteSession(
    private val appVersion: String,
    private val localWake: Boolean = false,
) {

    private enum class WakeState { IDLE, DETECTING, STREAMING, PAUSED }
    private var wakeState = WakeState.IDLE

    private var streaming = false
    private var ttsActive = false
    private var micTimestampMs = 0L
    private var dismissAtMs: Long? = null
    var overlay: VoiceOverlayState = VoiceOverlayState()
        private set

    // Timer state persists across connect/disconnect (device-local); reset() never touches it.
    private class TimerRec(
        val id: String,
        val name: String,
        var anchorRemainingSec: Long,
        var anchorMs: Long,
        var active: Boolean,
    )
    private val timers = LinkedHashMap<String, TimerRec>()
    private var alert: TimerAlert? = null
    private var alertSilenceAtMs: Long? = null

    fun onConnected(): List<SatelliteAction> {
        reset()
        return emptyList()
    }

    fun onDisconnected(): List<SatelliteAction> {
        reset()
        return listOf(SatelliteAction.StopMic, overlayAction(VoiceOverlayState()))
    }

    fun onEvent(event: WyomingEvent, nowMs: Long = 0L): List<SatelliteAction> = when (event.type) {
        "describe" -> listOf(SatelliteAction.Send(infoEvent()))
        "ping" -> listOf(SatelliteAction.Send(pongEvent((event.data["text"] as? JsonPrimitive)?.contentOrNull)))
        "run-satellite" -> if (localWake) {
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
        "pause-satellite" -> if (localWake) {
            wakeState = WakeState.PAUSED
            listOf(
                SatelliteAction.Send(WyomingEvent("streaming-stopped")),
                SatelliteAction.ResetDetector,
                SatelliteAction.StopMic,
            )
        } else {
            streaming = false
            listOf(SatelliteAction.StopMic, SatelliteAction.Send(WyomingEvent("streaming-stopped")))
        }
        "detection" -> listOf(
            // Legacy/fallback: HA reports the wake word. In localWake HA never sends this.
            SatelliteAction.Earcon(EarconKind.WAKE),
            overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
        )
        "transcript" -> {
            val base = listOf(
                SatelliteAction.Earcon(EarconKind.DONE),
                overlayAction(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, textOf(event))),
            )
            if (localWake) {
                wakeState = WakeState.DETECTING
                base + listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
            } else {
                base
            }
        }
        "error" -> if (localWake) {
            wakeState = WakeState.DETECTING
            listOf(SatelliteAction.Send(WyomingEvent("streaming-stopped")), SatelliteAction.ResetDetector)
        } else {
            emptyList()
        }
        "synthesize" -> listOf(overlayAction(VoiceOverlayState(VoiceOverlayPhase.RESPONSE, textOf(event))))
        "audio-start" -> {
            ttsActive = true
            listOf(
                SatelliteAction.PlaybackStart(
                    rate = event.data["rate"]?.jsonPrimitive?.int ?: 22050,
                    width = event.data["width"]?.jsonPrimitive?.int ?: 2,
                    channels = event.data["channels"]?.jsonPrimitive?.int ?: 1,
                ),
            )
        }
        "audio-chunk" -> listOf(SatelliteAction.PlaybackChunk(event.payload))
        "audio-stop" -> listOf(SatelliteAction.PlaybackStop)
        "timer-started" -> {
            val id = strOf(event, "id")
            timers[id] = TimerRec(
                id = id,
                name = strOf(event, "name"),
                anchorRemainingSec = longOf(event, "total_seconds"),
                anchorMs = nowMs,
                active = true,
            )
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-updated" -> {
            timers[strOf(event, "id")]?.let { rec ->
                rec.anchorRemainingSec = longOf(event, "total_seconds")
                rec.anchorMs = nowMs
                rec.active = boolOf(event, "is_active", true)
            }
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-cancelled" -> {
            timers.remove(strOf(event, "id"))
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        "timer-finished" -> {
            val rec = timers.remove(strOf(event, "id"))
            alert = TimerAlert(label = rec?.name?.ifBlank { "Timer" } ?: "Timer")
            alertSilenceAtMs = nowMs + ALERT_SILENCE_MS
            listOf(SatelliteAction.Timers(timersState(nowMs)))
        }
        else -> emptyList()
    }

    /**
     * The on-device detector fired for wake word [name]. Emits, in wire order:
     * detection -> run-pipeline(asr..tts, restart_on_end=false) -> streaming-started,
     * then the local WAKE earcon and LISTENING overlay; begins streaming mic audio.
     */
    fun onWakeDetected(name: String, nowMs: Long): List<SatelliteAction> {
        wakeState = WakeState.STREAMING
        micTimestampMs = 0L
        return listOf(
            SatelliteAction.Send(detectionEvent(name)),
            SatelliteAction.Send(runPipelineLocalEvent()),
            SatelliteAction.Send(WyomingEvent("streaming-started")),
            SatelliteAction.Earcon(EarconKind.WAKE),
            overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
        )
    }

    fun onMicChunk(pcm: ByteArray): List<SatelliteAction> {
        if (pcm.isEmpty()) return emptyList()
        if (localWake) {
            return when (wakeState) {
                WakeState.DETECTING -> if (ttsActive) emptyList() else listOf(SatelliteAction.FeedDetector(pcm))
                WakeState.STREAMING -> listOf(audioChunkAction(pcm))
                WakeState.IDLE, WakeState.PAUSED -> emptyList()
            }
        }
        if (!streaming) return emptyList()
        return listOf(audioChunkAction(pcm))
    }

    private fun audioChunkAction(pcm: ByteArray): SatelliteAction {
        val ts = micTimestampMs
        micTimestampMs += pcm.size.toLong() * 1000L / (AUDIO_WIDTH.toLong() * AUDIO_CHANNELS * AUDIO_RATE)
        return SatelliteAction.Send(audioChunkEvent(pcm, ts))
    }

    fun onMicError(): List<SatelliteAction> = listOf(
        SatelliteAction.Send(
            WyomingEvent(
                "error",
                buildJsonObject {
                    put("text", "microphone unavailable")
                    put("code", "mic_unavailable")
                },
            ),
        ),
    )

    fun onPlaybackFinished(nowMs: Long): List<SatelliteAction> {
        ttsActive = false
        dismissAtMs = nowMs + DISMISS_MS
        return listOf(SatelliteAction.Send(WyomingEvent("played")))
    }

    fun onTimerAlertDismissed(nowMs: Long): List<SatelliteAction> {
        alert = null
        alertSilenceAtMs = null
        return listOf(SatelliteAction.Timers(timersState(nowMs)))
    }

    fun onTick(nowMs: Long): List<SatelliteAction> {
        val actions = mutableListOf<SatelliteAction>()
        // Voice overlay auto-dismiss (~4 s after playback).
        dismissAtMs?.let { if (nowMs >= it) { dismissAtMs = null; actions += overlayAction(VoiceOverlayState()) } }
        // Timer alert auto-silence after 60 s.
        var timersChanged = false
        alertSilenceAtMs?.let { if (nowMs >= it) { alert = null; alertSilenceAtMs = null; timersChanged = true } }
        // Re-emit live timer state while any timer or alert is present (StateFlow dedups no-ops).
        if (timers.isNotEmpty() || alert != null || timersChanged) {
            actions += SatelliteAction.Timers(timersState(nowMs))
        }
        return actions
    }

    private fun timersState(nowMs: Long) = TimersUiState(
        chips = timers.values.map { TimerChip(it.id, it.name, it.remainingSec(nowMs), it.active) },
        alert = alert,
    )

    private fun TimerRec.remainingSec(nowMs: Long): Long =
        if (active) (anchorRemainingSec - (nowMs - anchorMs) / 1000L).coerceAtLeast(0L) else anchorRemainingSec

    private fun strOf(event: WyomingEvent, key: String): String =
        (event.data[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun longOf(event: WyomingEvent, key: String): Long =
        (event.data[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun boolOf(event: WyomingEvent, key: String, default: Boolean): Boolean =
        (event.data[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun reset() {
        streaming = false
        wakeState = WakeState.IDLE
        ttsActive = false
        micTimestampMs = 0L
        dismissAtMs = null
        overlay = VoiceOverlayState()
    }

    private fun overlayAction(state: VoiceOverlayState): SatelliteAction.Overlay {
        overlay = state
        return SatelliteAction.Overlay(state)
    }

    private fun textOf(event: WyomingEvent): String =
        (event.data["text"] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun audioChunkEvent(pcm: ByteArray, timestampMs: Long) = WyomingEvent(
        "audio-chunk",
        buildJsonObject {
            put("rate", AUDIO_RATE)
            put("width", AUDIO_WIDTH)
            put("channels", AUDIO_CHANNELS)
            put("timestamp", timestampMs)
        },
        pcm,
    )

    /** Legacy/fallback pipeline: HA runs the wake stage and restarts on end. */
    private fun runPipelineEvent() = WyomingEvent(
        "run-pipeline",
        buildJsonObject {
            put("start_stage", "wake")
            put("end_stage", "tts")
            put("restart_on_end", true)
        },
    )

    /** Local-wake pipeline: HA skips its wake stage (start at asr) and does not restart. */
    private fun runPipelineLocalEvent() = WyomingEvent(
        "run-pipeline",
        buildJsonObject {
            put("start_stage", "asr")
            put("end_stage", "tts")
            put("restart_on_end", false)
        },
    )

    private fun detectionEvent(name: String) = WyomingEvent(
        "detection",
        buildJsonObject {
            put("name", name)
            put("timestamp", JsonNull)
        },
    )

    private fun pongEvent(text: String?) = WyomingEvent(
        "pong",
        buildJsonObject { if (text != null) put("text", text) else put("text", JsonNull) },
    )

    private fun infoEvent(): WyomingEvent {
        val data = buildJsonObject {
            for (key in listOf("asr", "tts", "handle", "intent", "mic", "snd")) putJsonArray(key) {}
            putJsonArray("wake") {
                if (localWake) {
                    addJsonObject {
                        put("name", "openWakeWord")
                        putJsonObject("attribution") {
                            put("name", SATELLITE_NAME)
                            put("url", "https://github.com/rar/echo-dashboard")
                        }
                        put("installed", true)
                        put("description", "On-device openWakeWord")
                        put("version", JsonNull)
                        putJsonArray("models") {
                            for ((id, phrase) in WAKE_MODELS) {
                                addJsonObject {
                                    put("name", id)
                                    put("phrase", phrase)
                                    put("installed", true)
                                    putJsonArray("languages") {}
                                    put("version", JsonNull)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("satellite") {
                put("name", SATELLITE_NAME)
                putJsonObject("attribution") {
                    put("name", SATELLITE_NAME)
                    put("url", "https://github.com/rar/echo-dashboard")
                }
                put("installed", true)
                put("description", "Home Assistant voice satellite")
                put("version", appVersion)
                put("area", JsonNull)
                put("has_vad", false)
                putJsonArray("active_wake_words") {}
                put("max_active_wake_words", 0)
                put("supports_trigger", false)
            }
        }
        return WyomingEvent("info", data)
    }

    companion object {
        const val SATELLITE_NAME = "Echo Dashboard"
        const val AUDIO_RATE = 16000
        const val AUDIO_WIDTH = 2
        const val AUDIO_CHANNELS = 1
        const val DISMISS_MS = 4000L
        const val ALERT_SILENCE_MS = 60000L

        /** The three bundled wake-word model ids and their friendly phrases (HA display only). */
        val WAKE_MODELS = listOf(
            "okay_nabu" to "Okay Nabu",
            "hey_jarvis" to "Hey Jarvis",
            "alexa" to "Alexa",
        )
    }
}
```

- [ ] **Step 4: Implement — replace the whole of `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt` with:**

```kotlin
package com.rar.echodash.voice

import android.util.Log
import com.rar.echodash.vaca.WyomingCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * Wyoming TCP server for the voice satellite (port 10600). HA connects inbound.
 * Newest connection wins. Reader runs off the lock; the active [SatelliteSession]
 * and all socket writes are serialized on [lock], so pongs are never starved by
 * blocking playback (playback is offloaded to an AnnouncePlayer via [out]). Note:
 * outbound mic-chunk writes share the same socket and can still stall pongs under
 * TCP back-pressure (e.g. a stalled HA); this is bounded and self-heals via disconnect.
 *
 * When started with localWake, an on-device [WakeDetector] runs on a dedicated daemon
 * thread fed by a bounded, drop-oldest queue (FeedDetector/ResetDetector actions from the
 * session). A detector hit re-enters the session under [lock] via onWakeDetected.
 */
class SatelliteServer(
    private val scope: CoroutineScope,
    private val port: Int = PORT,
    private val appVersion: String,
    private val out: Out,
) {
    interface Out {
        fun onStartMic()
        fun onStopMic()
        fun onPlaybackStart(rate: Int, width: Int, channels: Int)
        fun onPlaybackChunk(pcm: ByteArray)
        fun onPlaybackStop()
        fun onOverlay(state: VoiceOverlayState)
        fun onTimers(state: TimersUiState)
        fun onEarcon(kind: EarconKind)
    }

    companion object {
        const val PORT = 10600
        private const val TAG = "SatelliteServer"
        private const val BIND_RETRY_MS = 5_000L
        private const val TICK_MS = 500L
        private const val DETECTOR_QUEUE_MAX = 8
        private val RESET_MARKER = Any()
    }

    private class Connection(val socket: Socket, val out: OutputStream)

    @Volatile var boundPort: Int = -1
        private set

    // Recreated on each start() with the current localWake flag; device-local timers persist
    // across HA reconnects (same session instance) but are dropped on a start()/stop() cycle
    // (voice enable/disable or a wake-word/threshold change), which is rare and acceptable.
    @Volatile private var session = SatelliteSession(appVersion)
    private val lock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    private var active: Connection? = null
    private var acceptJob: Job? = null
    private var tickJob: Job? = null

    // On-device wake detection (localWake only).
    @Volatile private var detector: WakeDetector? = null
    @Volatile private var wakeWord: String = "okay_nabu"
    private val detectorQueue = LinkedBlockingDeque<Any>()
    @Volatile private var detectorThread: Thread? = null

    fun start(localWake: Boolean = false, detector: WakeDetector? = null, wakeWord: String = "okay_nabu") {
        if (acceptJob?.isActive == true) return
        session = SatelliteSession(appVersion, localWake)
        this.detector = if (localWake) detector else null
        this.wakeWord = wakeWord
        startDetectorThread()
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val server = try {
                    ServerSocket(port)
                } catch (e: IOException) {
                    Log.w(TAG, "bind failed, retrying", e)
                    delay(BIND_RETRY_MS)
                    continue
                }
                serverSocket = server
                boundPort = server.localPort
                try {
                    while (isActive) {
                        val socket = server.accept()
                        launch { handle(socket) }
                    }
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "accept loop ended", e)
                } finally {
                    runCatching { server.close() }
                    serverSocket = null
                    boundPort = -1
                }
            }
        }
        // Runs regardless of connection state so timers keep counting down while HA is away.
        tickJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TICK_MS)
                synchronized(lock) { dispatch(active, session.onTick(System.currentTimeMillis())) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel(); acceptJob = null
        tickJob?.cancel(); tickJob = null
        detectorThread?.interrupt(); detectorThread = null
        detectorQueue.clear()
        detector = null
        runCatching { serverSocket?.close() }
        synchronized(lock) {
            active?.let { runCatching { it.socket.close() } }
            active = null
        }
    }

    /** Feed a mic chunk; resulting audio-chunk/FeedDetector actions run against the active session. */
    fun submitMicChunk(pcm: ByteArray) {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onMicChunk(pcm))
        }
    }

    fun reportMicError() {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onMicError())
        }
    }

    fun onPlaybackFinished() {
        synchronized(lock) {
            val conn = active ?: return
            dispatch(conn, session.onPlaybackFinished(System.currentTimeMillis()))
        }
    }

    /** Tap on the "Timer done" overlay: clear the alert (may run with no active connection). */
    fun dismissTimerAlert() {
        synchronized(lock) { dispatch(active, session.onTimerAlertDismissed(System.currentTimeMillis())) }
    }

    private fun handle(socket: Socket) {
        val conn = try {
            Connection(socket, socket.getOutputStream().buffered())
        } catch (e: IOException) {
            runCatching { socket.close() }
            return
        }
        synchronized(lock) {
            active?.let { runCatching { it.socket.close() } }  // newest wins
            active = conn
            dispatch(conn, session.onConnected())
        }
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val event = WyomingCodec.read(input) ?: break
                synchronized(lock) {
                    if (active !== conn) return           // superseded
                    dispatch(conn, session.onEvent(event, System.currentTimeMillis()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
        } finally {
            synchronized(lock) {
                if (active === conn) {
                    dispatch(conn, session.onDisconnected())
                    active = null
                }
            }
            runCatching { socket.close() }
        }
    }

    /**
     * Must be called while holding [lock]. Writes are small Wyoming frames. [conn] may be null
     * (e.g. a timer tick while HA is disconnected): Send actions are then dropped — timer/overlay
     * actions never produce Sends, so nothing is lost. FeedDetector/ResetDetector are handled
     * internally against the detector thread (never surfaced on [out]).
     */
    private fun dispatch(conn: Connection?, actions: List<SatelliteAction>) {
        for (a in actions) when (a) {
            is SatelliteAction.Send ->
                if (conn != null) {
                    try { WyomingCodec.write(a.event, conn.out) } catch (e: Exception) { Log.w(TAG, "write failed", e) }
                }
            SatelliteAction.StartMic -> out.onStartMic()
            SatelliteAction.StopMic -> out.onStopMic()
            is SatelliteAction.PlaybackStart -> out.onPlaybackStart(a.rate, a.width, a.channels)
            is SatelliteAction.PlaybackChunk -> out.onPlaybackChunk(a.pcm)
            SatelliteAction.PlaybackStop -> out.onPlaybackStop()
            is SatelliteAction.Overlay -> out.onOverlay(a.state)
            is SatelliteAction.Timers -> out.onTimers(a.state)
            is SatelliteAction.Earcon -> out.onEarcon(a.kind)
            is SatelliteAction.FeedDetector -> enqueueDetector(a.pcm)
            SatelliteAction.ResetDetector -> {
                detectorQueue.clear()
                detectorQueue.offer(RESET_MARKER)
            }
        }
    }

    private fun enqueueDetector(pcm: ByteArray) {
        // Drop oldest so the mic path never blocks on slow inference.
        while (detectorQueue.size >= DETECTOR_QUEUE_MAX) detectorQueue.pollFirst()
        detectorQueue.offer(pcm)
    }

    private fun startDetectorThread() {
        val det = detector ?: return
        detectorQueue.clear()
        detectorThread = thread(name = "WakeDetector", isDaemon = true) {
            var windowMax = 0f
            var windowStart = System.currentTimeMillis()
            try {
                while (true) {
                    val item = detectorQueue.take()
                    if (item === RESET_MARKER) {
                        det.reset()
                        continue
                    }
                    if (item !is ByteArray) continue
                    val fired = det.process(item)
                    val score = det.lastScore
                    if (score > windowMax) windowMax = score
                    val nowW = System.currentTimeMillis()
                    if (nowW - windowStart >= 5_000L) {
                        Log.d(TAG, "wake max score=%.2f (5s)".format(windowMax))
                        windowMax = 0f
                        windowStart = nowW
                    }
                    if (fired) {
                        synchronized(lock) {
                            val conn = active
                            if (conn != null) {
                                Log.i(TAG, "wake '$wakeWord' score=%.2f".format(score))
                                dispatch(conn, session.onWakeDetected(wakeWord, System.currentTimeMillis()))
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // stop() interrupted us; exit cleanly.
            } catch (e: Exception) {
                Log.w(TAG, "wake detector thread died", e)
            }
        }
    }
}
```

- [ ] **Step 5: Run the full gate** — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`, expect exit 0. (App.kt still compiles: it calls `satellite.start()` today with no args, which resolves to the new defaults `localWake=false, detector=null, wakeWord="okay_nabu"` — App is rewired in Task 3.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt
git commit -m "Satellite: local wake-streaming mode + on-device detector thread

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 3: TFLite dependency, model assets, `TfliteWakeGraphs`, App wiring, config page

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block ~line 30)
- Create (download): `app/src/main/assets/wake/{melspectrogram,embedding_model,okay_nabu,hey_jarvis,alexa}.tflite`
- Create: `app/src/main/java/com/rar/echodash/voice/TfliteWakeGraphs.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (imports; `startVoice()` ~lines 285–305)
- Modify: `app/src/main/assets/config/app.js` (`WAKE_WORD_OPTIONS` const ~line 22; `renderVoice()` ~line 538)

**Interfaces:**
- Consumes (Task 1): `WakeDetector(...)`, `WakeDetector.TfGraph`, `VoiceSettings.wakeWord`, `VoiceSettings.wakeThreshold`.
- Consumes (Task 2): `SatelliteServer.start(localWake, detector, wakeWord)`.
- Produces: `TfliteWakeGraphs.load(assets, wakeWord): Triple<TfGraph, TfGraph, TfGraph>?`.

**Test coverage note:** no new unit tests here — this is Android/TFLite/HTTP/JS glue. Coverage is: the streaming inference math is in `WakeDetectorTest` (Task 1); the session protocol + detector-thread wiring is in `SatelliteSessionTest`/`SatelliteServerTest` (Task 2); config clamps/round-trip are in `DashConfigTest` (Task 1); this task is verified by the build gate (compile) and the on-device verification plan (spec §8).

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, add to the `dependencies { ... }` block (after the nanohttpd line ~47):

```kotlin
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
```

- [ ] **Step 2: Download and verify the five model assets**

```bash
mkdir -p app/src/main/assets/wake
base="https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/models"
for f in melspectrogram embedding_model okay_nabu hey_jarvis alexa; do
  curl -fSL "$base/$f.tflite" -o "app/src/main/assets/wake/$f.tflite"
done
```

Verify exact byte sizes (must match precisely — abort if any differ):

```bash
cd app/src/main/assets/wake
declare -A want=( [melspectrogram.tflite]=1092516 [embedding_model.tflite]=1330312 \
  [okay_nabu.tflite]=206380 [hey_jarvis.tflite]=1278912 [alexa.tflite]=855312 )
ok=1
for f in "${!want[@]}"; do
  got=$(stat -c%s "$f")
  if [ "$got" != "${want[$f]}" ]; then echo "SIZE MISMATCH $f: got $got want ${want[$f]}"; ok=0; else echo "OK $f $got"; fi
done
[ "$ok" = 1 ] || { echo "ABORT: model size verification failed"; exit 1; }
```

Expected output: five `OK` lines. (~4.7 MB total. Models are CC BY-NC-SA 4.0 — fine for this personal device; never distribute commercially.)

- [ ] **Step 3: Create `app/src/main/java/com/rar/echodash/voice/TfliteWakeGraphs.kt`:**

```kotlin
package com.rar.echodash.voice

import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the three TFLite graphs (shared melspectrogram + embedding + the chosen wake-word head)
 * from assets/wake/ and adapts them to [WakeDetector.TfGraph]. Untested Android glue — the pure
 * inference math is covered by WakeDetectorTest against fake graphs.
 *
 * CRITICAL: the melspectrogram model has a dynamic sample-count input, so we MUST resizeInput to
 * [1, 1760] then allocateTensors() once at load; otherwise Android's auto-allocation overflows
 * ("BytesRequired number of elements overflowed", openWakeWord issue #223). Embedding and head
 * have fixed shapes and need no resize. Models are float32; standard tensorflow-lite (no
 * select-tf-ops) is sufficient — the melspec graph uses builtin conv ops, verified in research.
 */
object TfliteWakeGraphs {
    private const val TAG = "TfliteWakeGraphs"
    private const val MEL_SAMPLES = 1760
    private const val MEL_OUT = 8 * 32
    private const val EMB_IN = 76 * 32
    private const val EMB_OUT = 96
    private const val HEAD_IN = 16 * 96
    private const val HEAD_OUT = 1

    /** Returns (melspec, embedding, head) graphs, or null on ANY failure (caller falls back). */
    fun load(
        assets: AssetManager,
        wakeWord: String,
    ): Triple<WakeDetector.TfGraph, WakeDetector.TfGraph, WakeDetector.TfGraph>? {
        return try {
            val mel = Interpreter(loadModel(assets, "melspectrogram.tflite"))
            mel.resizeInput(0, intArrayOf(1, MEL_SAMPLES))
            mel.allocateTensors()
            val emb = Interpreter(loadModel(assets, "embedding_model.tflite"))
            val head = Interpreter(loadModel(assets, "$wakeWord.tflite"))
            Triple(
                graph(mel, MEL_SAMPLES, MEL_OUT),
                graph(emb, EMB_IN, EMB_OUT),
                graph(head, HEAD_IN, HEAD_OUT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to load wake models for '$wakeWord'", e)
            null
        }
    }

    private fun loadModel(assets: AssetManager, name: String): ByteBuffer {
        val bytes = assets.open("wake/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    /** Wrap a fixed-shape interpreter as a flat-float TfGraph using direct float buffers. */
    private fun graph(interp: Interpreter, inSize: Int, outSize: Int) = WakeDetector.TfGraph { input ->
        val inBuf = ByteBuffer.allocateDirect(inSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        inBuf.put(input)
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        interp.run(inBuf, outBuf)
        outBuf.rewind()
        FloatArray(outSize) { outBuf.get() }
    }
}
```

- [ ] **Step 4: Rewire `App.kt`**

Add these imports to `App.kt` (with the other `com.rar.echodash.voice.*` imports, ~lines 56–63):

```kotlin
import com.rar.echodash.voice.TfliteWakeGraphs
import com.rar.echodash.voice.WakeDetector
```

Replace the entire `startVoice()` function (currently ~lines 285–305) with:

```kotlin
    /**
     * Reactively (re)run the voice satellite. Restarts on any change to voice.enabled,
     * voice.wakeWord, or voice.wakeThreshold: it rebuilds the TFLite graphs + WakeDetector and
     * starts the satellite in local-wake mode. If the models fail to load, it logs one warning
     * and falls back to localWake=false (HA-side wake, the original always-streaming behavior).
     */
    fun startVoice() {
        scope.launch {
            configStore.config
                .map { Triple(it.voice.enabled, it.voice.wakeWord, it.voice.wakeThreshold) }
                .distinctUntilChanged()
                .collect { (enabled, wakeWord, threshold) ->
                    // Tear down any running instance first so a config change fully restarts it.
                    voiceNsd.unregister()
                    satellite.stop()
                    micStreamer.stop()
                    if (enabled) {
                        val graphs = TfliteWakeGraphs.load(appContext.assets, wakeWord)
                        val detector = if (graphs != null) {
                            WakeDetector(graphs.first, graphs.second, graphs.third, threshold) {
                                System.currentTimeMillis()
                            }
                        } else {
                            android.util.Log.w("AppDeps", "wake models failed to load; falling back to HA-side wake")
                            null
                        }
                        satellite.start(localWake = detector != null, detector = detector, wakeWord = wakeWord)
                        voiceNsd.register()
                    } else {
                        timerChime.stop()
                        voiceOverlay.value = VoiceOverlayState()
                        timersUi.value = TimersUiState()
                    }
                }
        }
    }
```

Note: `appContext` is the private field already on `AppDeps`; `distinctUntilChanged`/`map`/`launch` are already imported. The `satellite.stop()` then `satellite.start(...)` on a config change may briefly hit the still-closing port; `SatelliteServer`'s bind-retry loop absorbs that.

- [ ] **Step 5: Update the config web page**

In `app/src/main/assets/config/app.js`, add a `WAKE_WORD_OPTIONS` const immediately after the `TONE_OPTIONS` block (~line 27):

```javascript
const WAKE_WORD_OPTIONS = [
  ["okay_nabu", "Okay Nabu"],
  ["hey_jarvis", "Hey Jarvis"],
  ["alexa", "Alexa"],
];
```

In `renderVoice()`, add the two default initializers next to the existing ones (after `if (v.wakeSoundVolume == null) v.wakeSoundVolume = 80;`, ~line 545):

```javascript
  if (v.wakeWord == null) v.wakeWord = "okay_nabu";
  if (v.wakeThreshold == null) v.wakeThreshold = 50;
```

Immediately after the enabled-toggle row (`host.appendChild(labeledRow("Voice satellite (Wyoming)", toggle));`, ~line 550), insert the wake-word select and sensitivity number:

```javascript
  const wakeSel = el("select");
  WAKE_WORD_OPTIONS.forEach(([val, lbl]) => {
    const o = el("option", null, lbl); o.value = val;
    if (v.wakeWord === val) o.selected = true;
    wakeSel.appendChild(o);
  });
  wakeSel.addEventListener("change", () => v.wakeWord = wakeSel.value);
  host.appendChild(labeledRow("Wake word", wakeSel));

  const sens = el("input"); sens.type = "number"; sens.min = 10; sens.max = 95; sens.value = v.wakeThreshold;
  sens.addEventListener("change", () => v.wakeThreshold = Math.round(parseFloat(sens.value) || 50));
  host.appendChild(labeledRow("Wake sensitivity", sens));
```

Replace the closing muted-hint block of `renderVoice()` (the `host.appendChild(el("div", "muted", ...))` at ~lines 593–595) with:

```javascript
  host.appendChild(el("div", "muted",
    "Home Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol integration at <this-device-ip>:10600. " +
    "The wake word is now detected on-device — pick it above (sensitivity 10–95, higher = stricter, clamped on save). " +
    "HA's own streaming wake-word setting is no longer used, and mic audio only reaches HA after the wake word is heard. " +
    "Wake sound: chirps when the wake word is heard and when it stops listening; volume 0 disables it. " +
    "If the on-device models fail to load, the satellite silently falls back to HA-side wake."));
```

- [ ] **Step 6: Run the full gate** — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`, expect exit 0. (Confirms the TFLite dependency resolves, the assets bundle, and all glue compiles. The ~4.7 MB of models are packaged into the debug APK.)

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/assets/wake/melspectrogram.tflite \
        app/src/main/assets/wake/embedding_model.tflite \
        app/src/main/assets/wake/okay_nabu.tflite \
        app/src/main/assets/wake/hey_jarvis.tflite \
        app/src/main/assets/wake/alexa.tflite \
        app/src/main/java/com/rar/echodash/voice/TfliteWakeGraphs.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/main/assets/config/app.js
git commit -m "Wake word: TFLite dependency + models, graph loader, App wiring, config page

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

- [ ] **Step 8: On-device verification (manual, per spec §8)**

Flash the debug APK. In the config page, enable Voice, pick a wake word, save. Then:
- `adb logcat | grep -E "WakeDetector|SatelliteServer|TfliteWakeGraphs"` — confirm no model-load warning (fallback path), watch the DEBUG "wake max score" lines while quiet, and an INFO "wake '<name>' score=…" line when you say the wake word.
- Confirm HA's pipeline runs STT→TTS end-to-end after the wake word (no audio streamed before it).
- Confirm timers and TTS playback are unchanged.

---

## Notes for the implementer

- Tasks are independently gate-able in order 1 → 2 → 3. Task 2's gate passes before App is rewired because `satellite.start()` resolves to the new default parameters. Task 3 flips App to local-wake mode.
- The `localWake=false` path is deliberately byte-identical to the original: all existing SatelliteSession/SatelliteServer tests pass unchanged, and the legacy `run-pipeline` (`start_stage:"wake"`, `restart_on_end:true`) plus empty `info.wake` are preserved.
- `SatelliteSession` stays pure (no android/threads/TFLite). All threading and inference live in `SatelliteServer` (detector daemon thread + bounded queue) and `TfliteWakeGraphs`.
- `TfliteWakeGraphs.load` returns interpreters that live for the satellite's run; a wake-word/threshold change calls `satellite.stop()`/`start()`, which builds fresh graphs. (The previous interpreters are released by GC — acceptable given how rarely the config changes.)
```