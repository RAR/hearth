# Timer Alarm Tone Presets + Volume — Implementation Plan (TDD)

**Date:** 2026-07-12
**Spec:** `docs/superpowers/specs/2026-07-12-timer-tone-presets-design.md` (binding)
**Status:** Plan — not yet implemented

## Goal

Let the user pick the timer-finished alarm sound (one of four synthesized presets) and its
volume from the web config page, with a "Preview" button that auditions the choice without
saving or arming a real timer. Today `TimerChime` plays one hardcoded two-tone loop at a fixed
amplitude. This factors synthesis into a pure, JVM-testable `ToneGenerator`, adds two config
fields, wires the choice into the alert path, and exposes a PIN-gated preview endpoint.

## Architecture

- **`voice/ToneGenerator.kt`** (new, pure JVM): `object ToneGenerator` with
  `fun render(tone, volume, rate): ShortArray` returning exactly ONE cycle (sound + trailing
  silence gap) of 16-bit mono PCM. No Android imports → unit-testable.
- **`voice/TimerChime.kt`** (refactor): keeps the AudioTrack / `STREAM_ALARM` / daemon-thread /
  idempotency design. `start(tone, volume)` renders one cycle via `ToneGenerator` and loops that
  single buffer (the gap is now baked in, so the loop is one write per iteration). New
  `playOnce(tone, volume)` plays exactly one cycle on its own throwaway thread + AudioTrack and
  stops itself — best-effort, never throws, safe alongside a running loop.
- **`config/DashConfig.kt`**: `VoiceSettings` gains `timerTone` + `timerVolume` and its own
  `clamped()` that normalizes both; `DashConfig.clamped()` calls it. The endpoint reuses the same
  `VoiceSettings.clamped()` so normalization lives in one place.
- **`web/ConfigServer.kt`**: new constructor param `previewChime: (String, Int) -> Unit` and a
  PIN-gated `POST /api/voice/preview-chime` route that clamps body (defaulting to saved config)
  and fires the callback.
- **`App.kt`**: the timer-alert `LaunchedEffect` calls `start(config.voice.timerTone,
  config.voice.timerVolume)`; `AppDeps` injects `previewChime = { t, v -> timerChime.playOnce(t, v) }`.
- **`assets/config/app.js`**: `renderVoice()` gains a tone `<select>`, a volume number input, and a
  Preview button POSTing the current (unsaved) selections.

## Tech Stack

Kotlin, Jetpack Compose (App.kt only), kotlinx.serialization, NanoHTTPD (config server),
`android.media.AudioTrack` (playback), plain-JVM JUnit4 tests, vanilla JS config page.

## Global Constraints

- **Kotlin 2.1.0**, **compileSdk 34** — never bump either.
- **media3 1.4.1**, **NanoHTTPD 2.3.1** — pinned. **NO new dependencies** of any kind.
- Tests are **plain-JVM JUnit4 only** (no Robolectric, no instrumentation). `ToneGenerator`,
  `DashConfig`, and `ConfigServer` are all reachable in plain JVM. `TimerChime` and `App.kt` touch
  Android (`AudioTrack`, Compose) and are **not** unit-tested — verified on-device.
- **Gate** (must pass before each commit):
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
- Always invoke Gradle via **`./gradlew`** (never a system `gradle`).
- **KDoc hazard:** never write a literal end-of-block-comment sequence (asterisk-slash) *inside* a
  block/KDoc comment — it closes the comment early and breaks the build. Keep comment prose free of it.
- Follow existing idioms exactly: `clamped()` block-body-with-locals style, `coerceIn`, the injected
  lambda pattern in `ConfigServer`'s constructor, the `el`/`labeledRow`/`clear` DOM helpers in app.js.

## Tone definitions (verbatim from spec — all at `rate` Hz, 16-bit mono)

Amplitude for every tone: `amp = (volume / 100.0) * 0.6 * Short.MAX_VALUE`. Volume 100 reproduces
today's fixed 0.6-headroom amplitude; volume 0 renders all-zero silence (not special-cased).

| Tone | Sound | Gap | One-cycle length @22050 |
|---|---|---|---|
| `twotone` | 200 ms 880 Hz + 200 ms 1320 Hz | ~1 s | 30870 |
| `beeps` | 3 × (120 ms 1000 Hz) with 80 ms gaps between | ~1 s pause | 33516 |
| `chime` | 350 ms E6 (1318.5 Hz) + 350 ms C6 (1046.5 Hz), each linear-decay to 0 | ~1.6 s | 50714 |
| `trill` | 1 s alternating 1400/1800 Hz in 60 ms segments | ~0.6 s | 35280 |

Unknown/blank tone → `twotone` (identical output).

---

# Task 1 — ToneGenerator + config fields (pure JVM, fully tested)

Delivers the synthesizer and the two config fields with their clamp rules. Everything here runs and
is tested in plain JVM.

## Files

- **NEW** `app/src/main/java/com/rar/echodash/voice/ToneGenerator.kt`
- **NEW** `app/src/test/java/com/rar/echodash/voice/ToneGeneratorTest.kt`
- **MODIFY** `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
  - `VoiceSettings` data class at line 82–83 (add fields + `clamped()` + companion).
  - `DashConfig.clamped()` return `copy(...)` block (line 125–156): add `voice = voice.clamped(),`.
- **MODIFY** `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (append new @Test methods).

## Interfaces

- **Produces:** `object ToneGenerator { fun render(tone: String, volume: Int, rate: Int): ShortArray }`
- **Produces:** `VoiceSettings(enabled: Boolean = false, timerTone: String = "twotone", timerVolume: Int = 80)`
  with `fun clamped(): VoiceSettings` and `companion object { val TONES: Set<String> }`.
- **Consumes:** nothing new.

## Steps

### 1.1 — Write `ToneGeneratorTest.kt` first (TDD: it will not compile until 1.2)

Create `app/src/test/java/com/rar/echodash/voice/ToneGeneratorTest.kt`:

```kotlin
package com.rar.echodash.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneGeneratorTest {

    private val rate = 22050

    /** Max absolute sample value in a buffer (peak amplitude). */
    private fun peak(buf: ShortArray): Int = buf.maxOf { abs(it.toInt()) }

    /** Assert an actual length is within one frame of the expected cycle length (spec: +/-1). */
    private fun assertNearLen(expected: Int, buf: ShortArray) {
        assertTrue("expected ~$expected frames, got ${buf.size}", abs(buf.size - expected) <= 1)
    }

    @Test
    fun eachPresetRendersExpectedCycleLength() {
        // Derivations (integer math, rate=22050):
        //   twotone: 2*(rate*200/1000) + rate                 = 2*4410 + 22050 = 30870
        //   beeps:   3*(rate*120/1000) + 2*(rate*80/1000) + rate = 7938 + 3528 + 22050 = 33516
        //   chime:   2*(rate*350/1000) + (rate*1600/1000)     = 15434 + 35280 = 50714
        //   trill:   rate + (rate*600/1000)                   = 22050 + 13230 = 35280
        assertNearLen(30870, ToneGenerator.render("twotone", 100, rate))
        assertNearLen(33516, ToneGenerator.render("beeps", 100, rate))
        assertNearLen(50714, ToneGenerator.render("chime", 100, rate))
        assertNearLen(35280, ToneGenerator.render("trill", 100, rate))
    }

    @Test
    fun everyPresetIsNonEmpty() {
        for (t in listOf("twotone", "beeps", "chime", "trill")) {
            assertTrue("$t was empty", ToneGenerator.render(t, 80, rate).isNotEmpty())
        }
    }

    @Test
    fun volumeZeroRendersSilenceForEveryPreset() {
        for (t in listOf("twotone", "beeps", "chime", "trill")) {
            val buf = ToneGenerator.render(t, 0, rate)
            assertTrue("$t at volume 0 was not silent", buf.all { it.toInt() == 0 })
        }
    }

    @Test
    fun volume100PeaksNearSixtyPercentHeadroom() {
        val p = peak(ToneGenerator.render("twotone", 100, rate))
        // amp at v=100 is 0.6*Short.MAX_VALUE = 19660.2; truncation keeps the peak just under it,
        // and a dense sine gets close to the crest.
        assertTrue("peak $p too low", p >= (0.58 * Short.MAX_VALUE).toInt())
        assertTrue("peak $p exceeds headroom", p <= (0.6 * Short.MAX_VALUE).toInt() + 1)
    }

    @Test
    fun amplitudeScalesWithVolume() {
        val p0 = peak(ToneGenerator.render("twotone", 0, rate))
        val p50 = peak(ToneGenerator.render("twotone", 50, rate))
        val p100 = peak(ToneGenerator.render("twotone", 100, rate))
        assertEquals(0, p0)
        assertTrue("expected p50 between 0 and p100", p50 in 1 until p100)
    }

    @Test
    fun unknownToneFallsBackToTwotoneIdentically() {
        val fallback = ToneGenerator.render("not-a-real-tone", 100, rate)
        val twotone = ToneGenerator.render("twotone", 100, rate)
        assertTrue(fallback.contentEquals(twotone))
    }

    @Test
    fun rateParameterIsRespected() {
        // twotone @8000: 2*(8000*200/1000) + 8000 = 3200 + 8000 = 11200
        assertNearLen(11200, ToneGenerator.render("twotone", 100, 8000))
        assertTrue(ToneGenerator.render("twotone", 100, 8000).size <
            ToneGenerator.render("twotone", 100, rate).size)
    }
}
```

### 1.2 — Write `ToneGenerator.kt` to make the test pass

Create `app/src/main/java/com/rar/echodash/voice/ToneGenerator.kt`:

```kotlin
package com.rar.echodash.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM synthesizer for the timer-alarm presets. [render] returns ONE full cycle
 * (audible portion followed by the trailing silence gap) of 16-bit mono PCM at [rate] Hz, so a
 * player can loop the single buffer with a gap between repeats. No Android imports, so this is
 * unit-testable. Playback (AudioTrack) lives in TimerChime.
 *
 * Amplitude is (volume / 100) * 0.6 * Short.MAX_VALUE: volume 100 matches the historic fixed
 * 0.6-headroom loudness, volume 0 renders pure silence. Unknown tones fall back to "twotone".
 */
object ToneGenerator {

    fun render(tone: String, volume: Int, rate: Int): ShortArray {
        val amp = (volume / 100.0) * 0.6 * Short.MAX_VALUE
        return when (tone) {
            "beeps" -> beeps(amp, rate)
            "chime" -> chime(amp, rate)
            "trill" -> trill(amp, rate)
            else -> twotone(amp, rate) // "twotone" and any unknown value
        }
    }

    private fun twotone(amp: Double, rate: Int): ShortArray {
        val beep = rate * 200 / 1000 // 200 ms per beep
        val gap = rate               // ~1 s trailing silence
        val out = ShortArray(beep * 2 + gap)
        for (i in 0 until beep) {
            out[i] = (sin(2 * PI * 880.0 * i / rate) * amp).toInt().toShort()
            out[beep + i] = (sin(2 * PI * 1320.0 * i / rate) * amp).toInt().toShort()
        }
        return out
    }

    private fun beeps(amp: Double, rate: Int): ShortArray {
        val beep = rate * 120 / 1000 // 120 ms beep
        val gap = rate * 80 / 1000   // 80 ms between beeps
        val pause = rate             // ~1 s trailing silence
        val out = ShortArray(beep * 3 + gap * 2 + pause)
        for (b in 0 until 3) {
            val base = b * (beep + gap)
            for (i in 0 until beep) {
                out[base + i] = (sin(2 * PI * 1000.0 * i / rate) * amp).toInt().toShort()
            }
        }
        return out
    }

    private fun chime(amp: Double, rate: Int): ShortArray {
        val note = rate * 350 / 1000  // 350 ms per note
        val gap = rate * 1600 / 1000  // ~1.6 s trailing silence
        val out = ShortArray(note * 2 + gap)
        for (i in 0 until note) {
            val env = 1.0 - i.toDouble() / note // linear decay to 0 across each note
            out[i] = (sin(2 * PI * 1318.5 * i / rate) * amp * env).toInt().toShort()
            out[note + i] = (sin(2 * PI * 1046.5 * i / rate) * amp * env).toInt().toShort()
        }
        return out
    }

    private fun trill(amp: Double, rate: Int): ShortArray {
        val sound = rate            // 1 s of alternation
        val seg = rate * 60 / 1000  // 60 ms segments
        val gap = rate * 600 / 1000 // ~0.6 s trailing silence
        val out = ShortArray(sound + gap)
        for (i in 0 until sound) {
            val freq = if ((i / seg) % 2 == 0) 1400.0 else 1800.0
            out[i] = (sin(2 * PI * freq * i / rate) * amp).toInt().toShort()
        }
        return out
    }
}
```

### 1.3 — Run ToneGenerator tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.voice.ToneGeneratorTest"
```
Expected: BUILD SUCCESSFUL, all 7 tests pass.

### 1.4 — Extend `VoiceSettings` in `DashConfig.kt`

Replace the current one-line declaration at lines 82–83:

```kotlin
@Serializable
data class VoiceSettings(val enabled: Boolean = false)
```

with:

```kotlin
@Serializable
data class VoiceSettings(
    val enabled: Boolean = false,
    val timerTone: String = "twotone",
    val timerVolume: Int = 80,
) {
    /** Normalize the timer-alarm fields: trim + unknown/blank tone falls to "twotone",
     *  volume coerced into 0..100. Shared by DashConfig.clamped and the preview endpoint. */
    fun clamped(): VoiceSettings = copy(
        timerTone = timerTone.trim().let { if (it in TONES) it else "twotone" },
        timerVolume = timerVolume.coerceIn(0, 100),
    )

    companion object {
        /** The four recognized preset ids. */
        val TONES: Set<String> = setOf("twotone", "beeps", "chime", "trill")
    }
}
```

### 1.5 — Call `voice.clamped()` from `DashConfig.clamped()`

In the `return copy(...)` of `DashConfig.clamped()` (lines 125–156), add a `voice` line. The
current tail of the copy is:

```kotlin
            panelOptions = panelOptions.copy(
                thermostatStep = panelOptions.thermostatStep.coerceIn(0.1, 5.0),
                forecastDays = panelOptions.forecastDays.coerceIn(1, 5),
                sensorDecimals = panelOptions.sensorDecimals.coerceIn(0, 3),
                doorbellPopupSeconds = panelOptions.doorbellPopupSeconds.coerceIn(5, 120),
            ),
        )
```

Change it to append `voice`:

```kotlin
            panelOptions = panelOptions.copy(
                thermostatStep = panelOptions.thermostatStep.coerceIn(0.1, 5.0),
                forecastDays = panelOptions.forecastDays.coerceIn(1, 5),
                sensorDecimals = panelOptions.sensorDecimals.coerceIn(0, 3),
                doorbellPopupSeconds = panelOptions.doorbellPopupSeconds.coerceIn(5, 120),
            ),
            voice = voice.clamped(),
        )
```

### 1.6 — Append config tests to `DashConfigTest.kt`

The existing `voiceSurvivesClamped` test (line 224–227) still passes as-is (round-trip of
`enabled = true` through `clamped`, which now also normalizes the new fields to their defaults).
Add these methods just before the closing brace of the class (after line 227):

```kotlin
    @Test
    fun voiceTimerDefaults() {
        val v = DashConfig().voice
        assertEquals("twotone", v.timerTone)
        assertEquals(80, v.timerVolume)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("twotone", cfg.voice.timerTone)
        assertEquals(80, cfg.voice.timerVolume)
    }

    @Test
    fun voiceTimerRoundTrips() {
        val cfg = DashConfig(voice = VoiceSettings(enabled = true, timerTone = "chime", timerVolume = 45))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("chime", decodeConfig(text).voice.timerTone)
        assertEquals(45, decodeConfig(text).voice.timerVolume)
    }

    @Test
    fun clampedNormalizesUnknownToneToTwotone() {
        assertEquals("twotone",
            DashConfig(voice = VoiceSettings(timerTone = "wobble")).clamped().voice.timerTone)
        assertEquals("twotone",
            DashConfig(voice = VoiceSettings(timerTone = "   ")).clamped().voice.timerTone)
        // a known tone survives (trimmed)
        assertEquals("trill",
            DashConfig(voice = VoiceSettings(timerTone = "  trill  ")).clamped().voice.timerTone)
    }

    @Test
    fun clampedCoercesTimerVolume() {
        assertEquals(100, DashConfig(voice = VoiceSettings(timerVolume = 250)).clamped().voice.timerVolume)
        assertEquals(0, DashConfig(voice = VoiceSettings(timerVolume = -5)).clamped().voice.timerVolume)
        assertEquals(45, DashConfig(voice = VoiceSettings(timerVolume = 45)).clamped().voice.timerVolume)
    }
```

### 1.7 — Run config + tone tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest" --tests "com.rar.echodash.voice.ToneGeneratorTest"
```
Expected: BUILD SUCCESSFUL, all config + tone tests pass.

### 1.8 — Full gate + commit

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

```
git add app/src/main/java/com/rar/echodash/voice/ToneGenerator.kt \
        app/src/test/java/com/rar/echodash/voice/ToneGeneratorTest.kt \
        app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "feat(voice): add ToneGenerator presets and timer tone/volume config

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 2 — Playback refactor, App wiring, preview endpoint

Rewires `TimerChime` to the generator, passes the config choice at the alert call-site, injects the
preview callback, and adds the PIN-gated endpoint with a route test.

## Files

- **MODIFY** `app/src/main/java/com/rar/echodash/voice/TimerChime.kt` (rewrite `start`, add `playOnce`).
- **MODIFY** `app/src/main/java/com/rar/echodash/App.kt`
  - `AppDeps` ConfigServer construction (lines 106–117): add `previewChime` arg.
  - alert `LaunchedEffect` (line 425): pass tone + volume to `start`.
- **MODIFY** `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`
  - constructor (lines 20–30): add `previewChime` param.
  - route `when` (lines 48–57): add the preview route; add handler + one import.
- **MODIFY** `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`
  - `setUp` ConfigServer construction (add `previewChime`), plus new route tests.

## Interfaces

- **Produces:** `TimerChime.start(tone: String, volume: Int)`, `TimerChime.playOnce(tone: String, volume: Int)`.
- **Produces:** `ConfigServer(..., previewChime: (String, Int) -> Unit, ...)` and route
  `POST /api/voice/preview-chime`.
- **Consumes:** `ToneGenerator.render` (Task 1), `VoiceSettings(...).clamped()` (Task 1),
  `ConfigStore.config.value.voice` for endpoint defaults.

### 2.1 — Rewrite `TimerChime.kt`

There is **no JVM unit test** for this file: `android.media.AudioTrack`/`AudioManager` are Android
framework classes with no plain-JVM implementation, and the constraint bars Robolectric. It is
compile-gated by `assembleDebug` and verified on-device (Task 2 manual check). Replace the whole
file with:

```kotlin
package com.rar.echodash.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays the "timer done" alarm through AudioTrack on the alarm stream. The waveform is synthesized
 * by [ToneGenerator] as one cycle (sound + trailing gap); [start] loops that single buffer until
 * [stop]. Both are idempotent. [playOnce] auditions a single cycle for the config-page preview.
 * No bundled audio asset.
 */
class TimerChime {
    @Volatile private var playing = false
    private var worker: Thread? = null

    /** Loop [tone] at [volume] until [stop]. Idempotent: a second call while playing is a no-op. */
    @Synchronized
    fun start(tone: String, volume: Int) {
        if (playing) return
        playing = true
        worker = thread(name = "TimerChime", isDaemon = true) {
            val rate = 22050
            val cycle = ToneGenerator.render(tone, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, cycle.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "chime init failed", e); playing = false; return@thread
            }
            try {
                track.play()
                // The gap is baked into the rendered cycle, so each loop iteration is one write.
                while (playing) {
                    var off = 0
                    while (playing && off < cycle.size) off += track.write(cycle, off, cycle.size - off)
                }
            } catch (e: Exception) {
                Log.w(TAG, "chime playback failed", e)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    /** Stop any running loop. Idempotent. */
    @Synchronized
    fun stop() {
        playing = false
        worker = null
    }

    /**
     * Play exactly ONE cycle of [tone] at [volume], then stop and release. Best-effort: swallows
     * all failures and never throws. Runs on its own daemon thread with its own AudioTrack and does
     * NOT touch [playing]/[worker], so it is safe to call while a [start] loop is running (the OS
     * mixes both on STREAM_ALARM) and can never leave a loop running. Used by the config preview.
     */
    fun playOnce(tone: String, volume: Int) {
        thread(name = "TimerChimePreview", isDaemon = true) {
            val rate = 22050
            val cycle = ToneGenerator.render(tone, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_ALARM, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, cycle.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "preview init failed", e); return@thread
            }
            try {
                track.play()
                var off = 0
                while (off < cycle.size) off += track.write(cycle, off, cycle.size - off)
                // MODE_STREAM: stop() lets the already-queued buffer drain to completion before the
                // track halts, so the full cycle is heard. (pause() would truncate it.)
                track.stop()
            } catch (e: Exception) {
                Log.w(TAG, "preview playback failed", e)
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private companion object { const val TAG = "TimerChime" }
}
```

Concurrency choice (documented per spec): `playOnce` shares no mutable state with `start`/`stop`
and allocates a fresh AudioTrack on its own thread, so it needs no synchronization and cannot
interfere with the loop's idempotency invariants. The two streams mix in the OS mixer.

### 2.2 — Update the alert call-site in `App.kt`

At line 425, inside `LaunchedEffect(alerting)`, change:

```kotlin
                            deps.timerChime.start()
```
to:
```kotlin
                            deps.timerChime.start(config.voice.timerTone, config.voice.timerVolume)
```
`config` is already collected in this scope (line 321). Per spec, a config change while ringing does
not restart the chime — acceptable; the next alarm uses the new settings.

### 2.3 — Inject `previewChime` into ConfigServer in `AppDeps`

In the `ConfigServer(...)` construction (lines 106–117), add a `previewChime` argument. The current
tail of that call is:

```kotlin
        connState = { ws.connectionState.value.name },
        assetReader = { path ->
            runCatching { appContext.assets.open("config/$path").readBytes() }.getOrNull()
        },
    )
```

Change to:

```kotlin
        connState = { ws.connectionState.value.name },
        previewChime = { tone, volume -> timerChime.playOnce(tone, volume) },
        assetReader = { path ->
            runCatching { appContext.assets.open("config/$path").readBytes() }.getOrNull()
        },
    )
```

Note: `timerChime` is declared later (line 209) than `configServer` (line 106), but the lambda body
is not evaluated at construction — only when an HTTP request fires, long after all `AppDeps`
property initializers have run. This deferred-evaluation pattern matches the existing `pin` /
`entitiesJson` lambdas and needs no reordering.

### 2.4 — Write the ConfigServer route tests first (TDD)

Update `ConfigServerTest.kt`. First, in `setUp` (lines 33–48), add a recording field and the new
constructor arg. Add a field near the other private fields (after line 27's `requestedAssetPaths`):

```kotlin
    private val previewCalls = mutableListOf<Pair<String, Int>>()
```

Then add `previewChime` to the `ConfigServer(...)` call in `setUp`, right after `connState`:

```kotlin
            connState = { "OFFLINE" },
            previewChime = { tone, volume -> previewCalls += tone to volume },
            assetReader = { path ->
```

Add these tests before the closing brace of the class (after line 199):

```kotlin
    @Test
    fun previewChimeRequiresSession() {
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime")
            .post("""{"tone":"beeps","volume":50}""".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(401, r.code) }
        assertTrue(previewCalls.isEmpty())
    }

    @Test
    fun previewChimeClampsAndFires() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime").header("Cookie", cookie)
            .post("""{"tone":"nope","volume":250}""".toRequestBody(json)).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body!!.string().contains("\"ok\":true"))
            }
        assertEquals(listOf("twotone" to 100), previewCalls) // unknown->twotone, 250->100
    }

    @Test
    fun previewChimeDefaultsToSavedConfigWhenFieldsOmitted() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/voice/preview-chime").header("Cookie", cookie)
            .post("{}".toRequestBody(json)).build())
            .execute().use { r -> assertEquals(200, r.code) }
        // saved config is default VoiceSettings: twotone @ 80
        assertEquals(listOf("twotone" to 80), previewCalls)
    }
```

### 2.5 — Add the endpoint to `ConfigServer.kt`

Add the constructor param after `connState` (line 28):

```kotlin
    private val connState: () -> String,
    private val previewChime: (String, Int) -> Unit,
    private val assetReader: (String) -> ByteArray?,
```

Add the route inside the authed `when` (after the `/api/setup/complete` arm, line 55):

```kotlin
                uri == "/api/setup/complete" && method == Method.POST -> handleSetupComplete(session)
                uri == "/api/voice/preview-chime" && method == Method.POST -> handlePreviewChime(session)
                else -> error(Response.Status.NOT_FOUND, "not found")
```

Add the handler method (e.g. after `handleStatus`, near line 95):

```kotlin
    private fun handlePreviewChime(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }.getOrNull()
        val saved = store.config.value.voice
        val tone = obj?.get("tone")?.jsonPrimitive?.contentOrNull ?: saved.timerTone
        val volume = obj?.get("volume")?.jsonPrimitive?.intOrNull ?: saved.timerVolume
        val norm = VoiceSettings(timerTone = tone, timerVolume = volume).clamped()
        previewChime(norm.timerTone, norm.timerVolume)
        return ok("""{"ok":true}""")
    }
```

Add the two imports at the top of the file (with the other `config` / `json` imports):

```kotlin
import com.rar.echodash.config.VoiceSettings
```
```kotlin
import kotlinx.serialization.json.intOrNull
```

(`ConfigJson`, `JsonObject`, `jsonPrimitive`, `contentOrNull`, `buildJsonObject`, `put` are already
imported.)

### 2.6 — Run the web + config + tone tests

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests "com.rar.echodash.web.ConfigServerTest" --tests "com.rar.echodash.config.DashConfigTest" --tests "com.rar.echodash.voice.ToneGeneratorTest"
```
Expected: BUILD SUCCESSFUL; the three new `previewChime*` tests pass alongside the existing suite.

### 2.7 — Full gate

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL (compiles the App.kt/TimerChime Android changes).

### 2.8 — On-device sanity (manual, not automated — Android audio has no JVM test)

Optional but recommended before commit: install the debug APK, set a short HA timer, confirm the
alarm loops the configured tone; then open the config page and press Preview (Task 3) — a single
cycle should play. (Task 3 adds the button; if committing Task 2 alone, verify via a `curl` POST
with a valid session cookie.)

### 2.9 — Commit

```
git add app/src/main/java/com/rar/echodash/voice/TimerChime.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/main/java/com/rar/echodash/web/ConfigServer.kt \
        app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt
git commit -m "feat(voice): wire timer tone presets into playback and add preview endpoint

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

# Task 3 — Config-page Voice card controls

Adds the tone `<select>`, volume number input, and Preview button to `renderVoice()`.

## Files

- **MODIFY** `app/src/main/assets/config/app.js` — `renderVoice()` (lines 499–510). `index.html`
  needs **no change** (the `#voice` mount already exists).

## Interfaces

- **Consumes:** `POST /api/voice/preview-chime` (Task 2), `config.voice.timerTone`,
  `config.voice.timerVolume`, existing `el` / `labeledRow` / `api` helpers.
- **Produces:** UI only.

There is **no JVM test** for `app.js` — it is a browser asset with no unit-test harness in this
project (consistent with the existing config page having none). Verified on-device by loading the
config page and clicking Preview.

### 3.1 — Add a tone-options constant

Near the top-level constants (e.g. after `PANEL_LABELS`, around line 18), add:

```javascript
const TONE_OPTIONS = [
  ["twotone", "Two-tone"],
  ["beeps", "Beeps"],
  ["chime", "Chime"],
  ["trill", "Trill"],
];
```

### 3.2 — Extend `renderVoice()`

The current body (lines 499–510) ends with the muted hint. Insert the new controls between the
`enabled` toggle row and the muted hint. Replace the whole function with:

```javascript
function renderVoice() {
  const host = document.getElementById("voice");
  clear(host);
  if (!config.voice) config.voice = { enabled: false };
  const v = config.voice;
  if (v.timerTone == null) v.timerTone = "twotone";
  if (v.timerVolume == null) v.timerVolume = 80;

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!v.enabled;
  toggle.setAttribute("aria-label", "Voice satellite enabled");
  toggle.addEventListener("change", () => v.enabled = toggle.checked);
  host.appendChild(labeledRow("Voice satellite (Wyoming)", toggle));

  const toneSel = el("select");
  TONE_OPTIONS.forEach(([val, lbl]) => {
    const o = el("option", null, lbl); o.value = val;
    if (v.timerTone === val) o.selected = true;
    toneSel.appendChild(o);
  });
  toneSel.addEventListener("change", () => v.timerTone = toneSel.value);
  host.appendChild(labeledRow("Timer alarm", toneSel));

  const vol = el("input"); vol.type = "number"; vol.min = 0; vol.max = 100; vol.value = v.timerVolume;
  vol.addEventListener("change", () => v.timerVolume = Math.round(parseFloat(vol.value) || 0));
  host.appendChild(labeledRow("Alarm volume", vol));

  const preview = el("button", "ghost small", "Preview");
  preview.type = "button";
  preview.addEventListener("click", async () => {
    // Audition the CURRENT (possibly unsaved) selections. Best-effort; ignore failures.
    preview.disabled = true;
    try {
      await api("POST", "/api/voice/preview-chime", { tone: v.timerTone, volume: v.timerVolume });
    } catch (e) { /* device may be unreachable; nothing to persist */ }
    finally { preview.disabled = false; }
  });
  host.appendChild(preview);

  host.appendChild(el("div", "muted",
    "Home Assistant should auto-discover the satellite; otherwise add the Wyoming Protocol integration at <this-device-ip>:10600. Pick the pipeline and wake word in HA's Assist satellite settings."));
}
```

Notes: the volume field is clamped server-side on both save and preview, so the input's `min`/`max`
are advisory; `parseFloat(...) || 0` guards a blank/NaN entry. The Preview POST sends the live model
values, so it works before Save (spec requirement).

### 3.3 — Verify the asset compiles into the APK

`app.js` is a raw asset (not compiled), so there is no test. Confirm the build still packages it:

```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Then on-device: open `http://<device-ip>:8080`, log in, expand the
Voice card — the tone select, volume input, and Preview button appear; changing the select and
pressing Preview plays one cycle of that tone at that volume without saving.

### 3.4 — Commit

```
git add app/src/main/assets/config/app.js
git commit -m "feat(config-ui): add timer alarm tone and volume controls with preview

$(printf 'Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi')"
```

---

## Self-review

**Spec coverage (every section → task):**
- Config model (`timerTone`, `timerVolume`, clamp rules, back-compat) → Task 1 (1.4–1.6).
- Four tone definitions + `ToneGenerator.render` contract + fallback → Task 1 (1.1–1.3).
- Volume mapping (`(volume/100)*0.6*Short.MAX_VALUE`, v100=today, v0=silence) → Task 1 code + tests.
- `TimerChime.start(tone, volume)` looping baked-in cycle + `playOnce` → Task 2 (2.1).
- App.kt wiring (alert reads live config) → Task 2 (2.2).
- `POST /api/voice/preview-chime` (PIN-gated, optional fields default to saved, clamp, one cycle,
  200 JSON) → Task 2 (2.3–2.5) + route tests (2.4).
- Web UI (select / number / Preview posting unsaved values) → Task 3.
- Error table: unknown tone→twotone (ToneGenerator + VoiceSettings.clamped tests), volume clamp
  (tests), preview-while-ringing best-effort (playOnce docs), no-session→401 (route test).
- Test list (ToneGeneratorTest, DashConfigTest additions, ConfigServer route test) → all present.
- Out of scope (custom files, per-timer tones, TTS, voice-response volume) → nothing added.

**Placeholder scan:** every code block is complete — full test bodies, full function bodies, exact
imports, exact edit anchors. No "similar to" / TODO / ellipsis placeholders.

**Type consistency across tasks:** `render(tone: String, volume: Int, rate: Int): ShortArray`,
`start(String, Int)`, `playOnce(String, Int)`, `previewChime: (String, Int) -> Unit`,
`VoiceSettings.clamped(): VoiceSettings`, `TONES: Set<String>` — signatures identical everywhere
they appear. Endpoint reads `store.config.value.voice` (StateFlow value, confirmed in ConfigStore)
and `jsonPrimitive.intOrNull` for the numeric field.

## Ambiguities resolved

1. **Where normalization lives.** Spec says preview clamps "the same way as `clamped()`". Rather
   than duplicate the tone/volume rules in the endpoint, I put them in a new `VoiceSettings.clamped()`
   that `DashConfig.clamped()` delegates to and the endpoint reuses — single source of truth.
2. **`playOnce` concurrency.** Spec asks for "simplest correct approach … document your choice."
   Chosen: a self-contained daemon thread + its own AudioTrack that never touches the loop's
   `playing`/`worker` state, so it needs no lock and is safe during a live alarm (OS mixes both on
   STREAM_ALARM). Relies on MODE_STREAM `stop()` draining the queued buffer so the full cycle plays.
3. **AppDeps init order.** `configServer` is constructed before `timerChime` is declared. Resolved by
   passing `previewChime = { t, v -> timerChime.playOnce(t, v) }` — a deferred lambda (evaluated only
   at request time), matching the existing `pin`/`entitiesJson` pattern; no field reordering needed.
4. **`beeps` gap layout.** Spec: "three 120 ms beeps with 80 ms gaps, then ~1 s pause." Read as two
   inter-beep gaps (between the three beeps) and a single ~1 s trailing pause — no 80 ms gap after
   the third beep. Encoded as `3*beep + 2*gap + pause`.
5. **ConfigServer route test seam.** Confirmed present (`ConfigServerTest` boots a real server on
   port 0 with injected lambdas), so the preview route IS unit-tested for auth + clamping via a
   recording `previewChime`. `TimerChime`/`AudioTrack` playback itself stays on-device (no JVM audio
   framework; Robolectric barred by constraints) — stated explicitly in Tasks 2.1 and 2.8.
6. **Preview button placement / label.** No label row (appended directly like the "add" buttons) to
   avoid a redundant empty label; button text "Preview", class `ghost small` to match existing
   secondary buttons.
