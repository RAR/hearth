# Timer Alarm System Sounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the synthesized default timer alarm with real AOSP system alarm audio — bundle 7 ogg alarms as app assets, selectable through the existing `timerTone` setting, with **Argon** the new default; the 4 synthesized tones stay as options.

**Architecture:** A new pure/plain-JVM `TimerSounds` object maps the 7 system-alarm tone keys to bundled asset paths (synthesized keys return `null`). `TimerChime` gains an `assetFd` injector and, for asset tones, plays through `MediaPlayer` (`USAGE_ALARM`/`SONIFICATION`) — looping for `start`, one-shot for `playOnce` — falling back to the untouched synthesized AudioTrack loop on any failure so an alarm never fails silent. `VoiceSettings` widens its tone set to 11 keys and moves its default/fallback from `twotone` to `argon`. The web config lists the new tones and defaults to `argon`.

**Tech Stack:** Kotlin, Android `MediaPlayer`/`AudioTrack`, kotlinx.serialization config model, plain-JVM JUnit4, vanilla-JS config page.

## Global Constraints

- Work directly on master; commit after each task.
- Gate before EVERY commit: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug` and `node --check app/src/main/assets/config/app.js`.
- Every commit message ends with trailer: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- NO new dependencies. Plain-JVM JUnit4 tests only (no Robolectric, no Compose/instrumented tests). Android framework classes (MediaPlayer, AssetFileDescriptor, AudioTrack) are NOT unit-testable — keep them behind thin untested code, pure logic in testable units.
- Match existing code style/comment density (read `TimerChime.kt` as the reference — comments state HAL constraints, not narration).

## File Structure

- **Create** `app/src/main/assets/sounds/ATTRIBUTION.txt` — origin + license note for the 7 bundled oggs (the 7 `.ogg` files already sit untracked in this dir; Task 1 only writes the attribution file and `git add`s all of them).
- **Create** `app/src/main/java/com/rar/echodash/voice/TimerSounds.kt` — pure tone-key → asset-path map. One responsibility, unit-testable.
- **Create** `app/src/test/java/com/rar/echodash/voice/TimerSoundsTest.kt` — covers the 7 keys, the 4 synthesized keys, unknowns.
- **Modify** `app/src/main/java/com/rar/echodash/config/DashConfig.kt` — `VoiceSettings` default/fallback `twotone` → `argon`, `TONES` grows to 11 (lines 144, 150, 155, 164).
- **Modify** `app/src/main/java/com/rar/echodash/voice/TimerChime.kt` — full rewrite: `assetFd` constructor arg + MediaPlayer asset branch with synth fallback; synthesized AudioTrack bodies relocated verbatim into private helpers.
- **Modify** `app/src/main/java/com/rar/echodash/App.kt` — pass `assetFd` to the `TimerChime()` constructor (line 397).
- **Modify** `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` — update the `twotone` default/fallback assertions to `argon`; add system-key acceptance (lines 240–249, 260–269).
- **Modify** `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt` — update the two `twotone` default/fallback assertions to `argon` (lines 239, 248–249).
- **Modify** `app/src/main/assets/config/app.js` — `TONE_OPTIONS` reordered/extended (lines 23–28), null default `twotone` → `argon` (line 835).

---

### Task 1: Kotlin side — assets, TimerSounds, VoiceSettings, TimerChime, wiring, tests

**Files:**
- Create: `app/src/main/assets/sounds/ATTRIBUTION.txt`
- Create: `app/src/main/java/com/rar/echodash/voice/TimerSounds.kt`
- Create: `app/src/test/java/com/rar/echodash/voice/TimerSoundsTest.kt`
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt:141-169`
- Modify: `app/src/main/java/com/rar/echodash/voice/TimerChime.kt` (full file)
- Modify: `app/src/main/java/com/rar/echodash/App.kt:397`
- Test/modify: `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt:240-269`
- Test/modify: `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt:230-250`

**Interfaces:**
- Consumes: existing `ToneGenerator.render(tone: String, volume: Int, rate: Int): ShortArray` (synth path, unchanged); `VoiceSettings.clamped()` (existing, being modified here); `appContext.assets.openFd(path): AssetFileDescriptor` in `AppDeps` (context var is `appContext`, declared `AppDeps.kt`/`App.kt:122`).
- Produces:
  - `com.rar.echodash.voice.TimerSounds.assetPath(tone: String): String?` — `"argon" -> "sounds/alarm_argon.ogg"`, synthesized/unknown keys `-> null`.
  - `TimerChime(assetFd: (String) -> AssetFileDescriptor? = { null })` — constructor now takes an asset-fd opener; `start(tone: String, volume: Int)`, `stop()`, `playOnce(tone: String, volume: Int)` signatures unchanged.
  - `VoiceSettings.TONES` — 11-key set; default and `clamped()` fallback are `"argon"`.
  - Shared tone-key contract (used verbatim by Task 2): `argon, oxygen, krypton, timer, beep, helium, cyan, twotone, beeps, chime, trill`.

- [ ] **Step 1: Write `ATTRIBUTION.txt` and stage the ogg assets**

Create `app/src/main/assets/sounds/ATTRIBUTION.txt`:

```text
Timer alarm sounds
==================

These seven .ogg files are alarm/timer sounds from the Android Open Source
Project (AOSP) and LineageOS, pulled from the Amazon Echo Show's
/product/media/audio/alarms/ (identical files ship on the Echo Show 5 and 8).

Licensed under the Apache License, Version 2.0.
See https://www.apache.org/licenses/LICENSE-2.0

  asset file          source file        tone key
  -----------------   -----------------  --------
  alarm_argon.ogg     Argon.ogg          argon
  alarm_oxygen.ogg    Oxygen.ogg         oxygen
  alarm_krypton.ogg   Krypton.ogg        krypton
  alarm_timer.ogg     Timer.ogg          timer
  alarm_beep.ogg      Alarm_Beep_03.ogg  beep
  alarm_helium.ogg    Helium.ogg         helium
  alarm_cyan.ogg      CyanAlarm.ogg      cyan
```

Confirm the 7 oggs are present (they already sit untracked in the dir — no copying):

Run: `ls app/src/main/assets/sounds/`
Expected: `ATTRIBUTION.txt  alarm_argon.ogg  alarm_beep.ogg  alarm_cyan.ogg  alarm_helium.ogg  alarm_krypton.ogg  alarm_oxygen.ogg  alarm_timer.ogg`

(Ogg is on aapt's default no-compress list, so `assets.openFd()` works — the assets stay STORED. No build-config change needed.)

- [ ] **Step 2: Write the failing `TimerSounds` test**

Create `app/src/test/java/com/rar/echodash/voice/TimerSoundsTest.kt`:

```kotlin
package com.rar.echodash.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerSoundsTest {

    @Test
    fun systemTonesMapToAssetPaths() {
        assertEquals("sounds/alarm_argon.ogg", TimerSounds.assetPath("argon"))
        assertEquals("sounds/alarm_oxygen.ogg", TimerSounds.assetPath("oxygen"))
        assertEquals("sounds/alarm_krypton.ogg", TimerSounds.assetPath("krypton"))
        assertEquals("sounds/alarm_timer.ogg", TimerSounds.assetPath("timer"))
        assertEquals("sounds/alarm_beep.ogg", TimerSounds.assetPath("beep"))
        assertEquals("sounds/alarm_helium.ogg", TimerSounds.assetPath("helium"))
        assertEquals("sounds/alarm_cyan.ogg", TimerSounds.assetPath("cyan"))
    }

    @Test
    fun synthesizedTonesHaveNoAsset() {
        assertNull(TimerSounds.assetPath("twotone"))
        assertNull(TimerSounds.assetPath("beeps"))
        assertNull(TimerSounds.assetPath("chime"))
        assertNull(TimerSounds.assetPath("trill"))
    }

    @Test
    fun unknownKeyHasNoAsset() {
        assertNull(TimerSounds.assetPath("wobble"))
        assertNull(TimerSounds.assetPath(""))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.TimerSoundsTest"`
Expected: compile FAIL — `unresolved reference: TimerSounds`.

- [ ] **Step 4: Create `TimerSounds.kt`**

Create `app/src/main/java/com/rar/echodash/voice/TimerSounds.kt`:

```kotlin
package com.rar.echodash.voice

/**
 * Maps timer-alarm tone keys to bundled ogg assets. The 7 system alarms play through MediaPlayer;
 * the 4 synthesized tones (twotone/beeps/chime/trill) have no asset and return null so the caller
 * falls back to [ToneGenerator]/AudioTrack. Pure/plain-JVM: no Android types, unit-testable.
 */
object TimerSounds {
    private val ASSETS: Map<String, String> = mapOf(
        "argon" to "sounds/alarm_argon.ogg",
        "oxygen" to "sounds/alarm_oxygen.ogg",
        "krypton" to "sounds/alarm_krypton.ogg",
        "timer" to "sounds/alarm_timer.ogg",
        "beep" to "sounds/alarm_beep.ogg",
        "helium" to "sounds/alarm_helium.ogg",
        "cyan" to "sounds/alarm_cyan.ogg",
    )

    /** Asset path under assets/ for [tone], or null for a synthesized tone / unknown key. */
    fun assetPath(tone: String): String? = ASSETS[tone]
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.voice.TimerSoundsTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Update the config tests to expect `argon` (failing)**

In `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`, replace the `voiceTimerDefaults` test (currently lines 240–249):

```kotlin
    @Test
    fun voiceTimerDefaults() {
        val v = DashConfig().voice
        assertEquals("argon", v.timerTone)
        assertEquals(80, v.timerVolume)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("argon", cfg.voice.timerTone)
        assertEquals(80, cfg.voice.timerVolume)
    }
```

And replace the `clampedNormalizesUnknownToneToTwotone` test (currently lines 260–269) with an `argon`-named version that also asserts every bundled system key survives:

```kotlin
    @Test
    fun clampedNormalizesUnknownToneToArgon() {
        assertEquals("argon",
            DashConfig(voice = VoiceSettings(timerTone = "wobble")).clamped().voice.timerTone)
        assertEquals("argon",
            DashConfig(voice = VoiceSettings(timerTone = "   ")).clamped().voice.timerTone)
        // a synthesized tone survives (trimmed)
        assertEquals("trill",
            DashConfig(voice = VoiceSettings(timerTone = "  trill  ")).clamped().voice.timerTone)
        // the bundled system-alarm keys are all accepted
        for (t in listOf("argon", "oxygen", "krypton", "timer", "beep", "helium", "cyan")) {
            assertEquals(t, DashConfig(voice = VoiceSettings(timerTone = t)).clamped().voice.timerTone)
        }
    }
```

In `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`, update the two default/fallback assertions.

Line 239, inside `previewChimeClampsAndFires` — change:

```kotlin
        assertEquals(listOf("twotone" to 100), previewCalls) // unknown->twotone, 250->100
```

to:

```kotlin
        assertEquals(listOf("argon" to 100), previewCalls) // unknown->argon, 250->100
```

Lines 248–249, inside `previewChimeDefaultsToSavedConfigWhenFieldsOmitted` — change:

```kotlin
        // saved config is default VoiceSettings: twotone @ 80
        assertEquals(listOf("twotone" to 80), previewCalls)
```

to:

```kotlin
        // saved config is default VoiceSettings: argon @ 80
        assertEquals(listOf("argon" to 80), previewCalls)
```

- [ ] **Step 7: Run the config tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest" --tests "com.rar.echodash.web.ConfigServerTest"`
Expected: FAIL — `expected:<argon> but was:<twotone>` in `voiceTimerDefaults`, `clampedNormalizesUnknownToneToArgon`, and both preview tests (default is still `twotone`).

- [ ] **Step 8: Update `VoiceSettings` — default, fallback, and the 11-key tone set**

In `app/src/main/java/com/rar/echodash/config/DashConfig.kt`, change the default (line 144):

```kotlin
    val timerTone: String = "argon",
```

Update the `clamped()` KDoc (line 150) and its fallback (line 155):

```kotlin
    /** Normalize the timer-alarm fields: trim + unknown/blank tone falls to "argon",
     *  volumes coerced into 0..100. Wake word clamps to the bundled set (unknown -> okay_nabu);
     *  wake threshold (score * 100) coerced into 10..95. Shared by DashConfig.clamped and the
     *  preview endpoint. */
    fun clamped(): VoiceSettings = copy(
        timerTone = timerTone.trim().let { if (it in TONES) it else "argon" },
```

Grow the `TONES` set + its comment (lines 163–164):

```kotlin
        /** The eleven recognized timer-alarm tone ids: 7 bundled system alarms + 4 synthesized. */
        val TONES: Set<String> = setOf(
            "argon", "oxygen", "krypton", "timer", "beep", "helium", "cyan",
            "twotone", "beeps", "chime", "trill",
        )
```

- [ ] **Step 9: Run the config tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest --tests "com.rar.echodash.config.DashConfigTest" --tests "com.rar.echodash.web.ConfigServerTest"`
Expected: PASS.

- [ ] **Step 10: Rewrite `TimerChime.kt` with the MediaPlayer asset branch + synth fallback**

Replace the entire contents of `app/src/main/java/com/rar/echodash/voice/TimerChime.kt`. The two synthesized AudioTrack bodies are relocated **verbatim** into private helpers (`startSynthLoop` / `playSynthOnce`) — their logic is unchanged; the prime-before-play HAL recipe stands. This is thin untested Android code (no unit test — verified by `assembleDebug` compiling and on-device flash):

```kotlin
package com.rar.echodash.voice

import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays the "timer done" alarm. Bundled system alarms (see [TimerSounds]) play through MediaPlayer
 * on the alarm stream; the four synthesized tones play through AudioTrack, whose waveform is
 * rendered by [ToneGenerator] as one cycle (sound + trailing gap) that [start] loops until [stop].
 * Both are idempotent. Any failure on the asset path falls back to the synthesized "twotone" loop --
 * an alarm must never fail silent. [playOnce] auditions a single file/cycle for the config preview.
 *
 * [assetFd] opens a bundled asset (App.kt passes assets.openFd; tests pass { null }).
 */
class TimerChime(private val assetFd: (String) -> AssetFileDescriptor? = { null }) {
    @Volatile private var playing = false
    private var worker: Thread? = null
    private var player: MediaPlayer? = null

    /**
     * Loop [tone] at [volume] until [stop]. Idempotent: a second call while playing is a no-op.
     * System-alarm tones loop via MediaPlayer; on any asset failure we fall back to the synthesized
     * "twotone" loop at the same volume. Synthesized tones take the AudioTrack path directly.
     */
    @Synchronized
    fun start(tone: String, volume: Int) {
        if (playing) return
        playing = true
        val asset = TimerSounds.assetPath(tone)
        if (asset != null) {
            if (volume <= 0) return                 // muted alarm -> nothing to play
            if (startAsset(asset, volume)) return   // MediaPlayer loop running
            startSynthLoop("twotone", volume)       // asset failed -> synthesized fallback
        } else {
            startSynthLoop(tone, volume)            // synthesized tone
        }
    }

    /** Start bundled [asset] looping via MediaPlayer at [volume]. Returns true on success; on any
     *  failure releases the player/fd and returns false so [start] can fall back to synthesis. */
    private fun startAsset(asset: String, volume: Int): Boolean {
        val fd = assetFd(asset) ?: return false
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.isLooping = true
            mp.setVolume(volume / 100f, volume / 100f)
            mp.prepare()                            // synchronous; the bundled files are small
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            Log.w(TAG, "alarm asset '$asset' failed; falling back to synth", e)
            runCatching { fd.close() }
            runCatching { mp.release() }
            false
        }
    }

    /** Synthesized AudioTrack loop -- the prime-before-play HAL recipe. Unchanged from the
     *  no-asset implementation; used for synthesized tones and as the asset-failure fallback. */
    private fun startSynthLoop(tone: String, volume: Int) {
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
                // Prime one full cycle BEFORE play(): this device's HAL renders a track that
                // was started on an empty buffer silently until further writes arrive, which
                // swallowed the first alarm cycle. The buffer holds exactly one cycle, so this
                // write fills it while stopped; subsequent loop writes block-and-pace as before.
                var primed = 0
                while (playing && primed < cycle.size) {
                    val n = track.write(cycle, primed, cycle.size - primed)
                    if (n <= 0) break
                    primed += n
                }
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

    /** Stop any running loop and release the MediaPlayer if active. Idempotent: a second call
     *  finds player == null and does nothing, so an active MediaPlayer is released exactly once. */
    @Synchronized
    fun stop() {
        playing = false
        worker = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
    }

    /**
     * Play exactly ONE file/cycle of [tone] at [volume], then stop and release. Best-effort: swallows
     * all failures and never throws. System-alarm tones play the whole file once through MediaPlayer
     * (released on completion); synthesized tones render one AudioTrack cycle on a daemon thread.
     * Does NOT touch [playing]/[worker]/[player], so it is safe to call while a [start] loop runs
     * (the OS mixes both on the alarm output) and can never leave a loop running. Used by the preview.
     */
    fun playOnce(tone: String, volume: Int) {
        val asset = TimerSounds.assetPath(tone)
        if (asset == null) { playSynthOnce(tone, volume); return }   // synthesized preview
        if (volume <= 0) return
        val fd = assetFd(asset)
        if (fd == null) { playSynthOnce("twotone", volume); return } // missing asset -> synth fallback
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.isLooping = false
            mp.setVolume(volume / 100f, volume / 100f)
            // MediaPlayer binds its completion callback to the creating thread's Looper, falling back
            // to the main Looper when the caller (a web-server worker) has none -- so this fires.
            mp.setOnCompletionListener { it.release() }
            mp.prepare()                            // synchronous; the bundled files are small
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "alarm preview '$asset' failed; falling back to synth", e)
            runCatching { fd.close() }
            runCatching { mp.release() }
            playSynthOnce("twotone", volume)
        }
    }

    /** One synthesized AudioTrack cycle on a daemon thread. Unchanged from the no-asset playOnce. */
    private fun playSynthOnce(tone: String, volume: Int) {
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
                // Prime the full cycle into the buffer BEFORE play(): starting a MODE_STREAM
                // track on an empty buffer leaves this device's HAL rendering silently (the
                // mixer consumes frames in real time but no sound reaches the speaker unless
                // writes keep arriving, as the looping alarm path does). Write-then-play is the
                // canonical one-shot recipe and never starts in underrun.
                var off = 0
                while (off < cycle.size) {
                    val n = track.write(cycle, off, cycle.size - off)
                    if (n <= 0) break
                    off += n
                }
                track.play()
                // MODE_STREAM: write() returns as soon as data is queued, not once it has
                // rendered, so we must wait for the hardware playback head to reach the frames
                // we wrote before releasing -- otherwise the native track is destroyed with the
                // whole cycle still unplayed and nothing is heard. Same fix as
                // AndroidPcmSink.finish().
                val target = cycle.size.toLong()
                val cycleMs = cycle.size * 1000L / rate
                val bufferMs = track.bufferSizeInFrames * 1000L / rate
                val deadline = System.currentTimeMillis() + cycleMs + bufferMs + 500L
                while (System.currentTimeMillis() < deadline) {
                    // getPlaybackHeadPosition() is a 32-bit frame counter (unsigned).
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= target) break
                    Thread.sleep(20)
                }
                runCatching { track.stop() }
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

- [ ] **Step 11: Wire the `assetFd` opener in `App.kt`**

In `app/src/main/java/com/rar/echodash/App.kt`, change line 397 from:

```kotlin
    val timerChime = TimerChime()
```

to:

```kotlin
    val timerChime = TimerChime(assetFd = { runCatching { appContext.assets.openFd(it) }.getOrNull() })
```

(`appContext` is the application `Context` held by `AppDeps`, declared at `App.kt:122` as `private val appContext = context.applicationContext`.)

- [ ] **Step 12: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all unit tests pass (including the new `TimerSoundsTest` and the updated `DashConfigTest`/`ConfigServerTest`), debug APK assembles.

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit 0 (app.js untouched this task, but the gate requires it every commit).

- [ ] **Step 13: Commit**

```bash
git add app/src/main/assets/sounds/ATTRIBUTION.txt \
        app/src/main/assets/sounds/alarm_argon.ogg \
        app/src/main/assets/sounds/alarm_oxygen.ogg \
        app/src/main/assets/sounds/alarm_krypton.ogg \
        app/src/main/assets/sounds/alarm_timer.ogg \
        app/src/main/assets/sounds/alarm_beep.ogg \
        app/src/main/assets/sounds/alarm_helium.ogg \
        app/src/main/assets/sounds/alarm_cyan.ogg \
        app/src/main/java/com/rar/echodash/voice/TimerSounds.kt \
        app/src/main/java/com/rar/echodash/voice/TimerChime.kt \
        app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/test/java/com/rar/echodash/voice/TimerSoundsTest.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt \
        app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt
git commit -m "$(cat <<'EOF'
feat(voice): bundle 7 AOSP system alarm sounds, Argon default

Add alarm_{argon,oxygen,krypton,timer,beep,helium,cyan}.ogg assets +
ATTRIBUTION.txt; TimerSounds maps the 7 tone keys to asset paths. TimerChime
gains an assetFd injector and plays system alarms via MediaPlayer
(USAGE_ALARM/SONIFICATION, looping for start, one-shot for playOnce), falling
back to the synthesized twotone loop on any failure so an alarm never fails
silent. VoiceSettings default + clamped() fallback move twotone -> argon;
TONES grows to 11 keys.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

### Task 2: Web side — `app.js` tone list + null default

**Files:**
- Modify: `app/src/main/assets/config/app.js:23-28` (TONE_OPTIONS)
- Modify: `app/src/main/assets/config/app.js:835` (load-time null default)

**Interfaces:**
- Consumes: the shared tone-key contract from Task 1 — the 11 keys `argon, oxygen, krypton, timer, beep, helium, cyan, twotone, beeps, chime, trill`. The web values must match these keys exactly (the device re-normalizes through `VoiceSettings.clamped()`, so an off-by-one key would silently fall back to `argon`).
- Produces: nothing consumed by other tasks (leaf change to the config UI).

- [ ] **Step 1: Reorder/extend `TONE_OPTIONS`**

In `app/src/main/assets/config/app.js`, replace the `TONE_OPTIONS` array (currently lines 23–28):

```javascript
const TONE_OPTIONS = [
  ["twotone", "Two-tone"],
  ["beeps", "Beeps"],
  ["chime", "Chime"],
  ["trill", "Trill"],
];
```

with the 7 system sounds first, then the 4 synthesized:

```javascript
const TONE_OPTIONS = [
  ["argon", "Argon"],
  ["oxygen", "Oxygen"],
  ["krypton", "Krypton"],
  ["timer", "Timer (Android)"],
  ["beep", "Alarm beep"],
  ["helium", "Helium"],
  ["cyan", "Cyan alarm"],
  ["twotone", "Two-tone"],
  ["beeps", "Beeps"],
  ["chime", "Chime"],
  ["trill", "Trill"],
];
```

- [ ] **Step 2: Change the load-time null default to `argon`**

In `app/src/main/assets/config/app.js`, change line 835 from:

```javascript
  if (v.timerTone == null) v.timerTone = "twotone";
```

to:

```javascript
  if (v.timerTone == null) v.timerTone = "argon";
```

(The preview button/endpoint and the `toneSel` render loop that reads `TONE_OPTIONS` — lines 859–866 — need no change: they iterate the array and match `v.timerTone` generically.)

- [ ] **Step 3: Syntax-check `app.js`**

Run: `node --check app/src/main/assets/config/app.js`
Expected: no output, exit 0.

- [ ] **Step 4: Run the full gate**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL — the APK re-packages the edited `app.js` asset; all unit tests still pass (unaffected by this task).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/config/app.js
git commit -m "$(cat <<'EOF'
feat(config-web): list 7 system alarm tones, default Argon

TONE_OPTIONS lists the 7 bundled system alarms first (Argon, Oxygen, Krypton,
Timer (Android), Alarm beep, Helium, Cyan alarm) then the 4 synthesized tones;
the load-time null default becomes "argon" (was "twotone").

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi
EOF
)"
```

---

## Post-implementation on-device verification (from the spec — not a commit gate)

After both commits, per the spec's Verification section:

- Flash both Echos.
- `POST /api/voice/preview-chime` with `{"tone":"argon"}` for an audible check.
- Set `voice.timerTone = "argon"` on both device configs via `/api/config` — stored configs still say `"twotone"`, and a stored value wins over the new default.
- Wyoming-inject a short timer on the Show 8 and let it finish: logcat should show the MediaPlayer path, and the alarm loops until the finished-overlay dismiss calls `stop()`.
