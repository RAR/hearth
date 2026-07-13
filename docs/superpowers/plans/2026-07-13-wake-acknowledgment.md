# Wake-Word Acknowledgment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Obvious wake-word feedback — pulsing cyan screen-edge glow while listening, rising chirp on detection, falling chirp when speech capture ends, with a volume setting.

**Architecture:** The Wyoming `detection`/`transcript` events already reach the pure `SatelliteSession`; it gains an `Earcon` action. A new `EarconPlayer` (TimerChime's proven prime-before-play recipe, on STREAM_MUSIC) plays chirps synthesized by `ToneGenerator.earcon()`. A `WakeGlow` composable renders while the overlay phase is LISTENING. One config knob: `voice.wakeSoundVolume`.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, AudioTrack, NanoHTTPD config server, vanilla JS config page, JUnit4 plain JVM.

**Spec:** docs/superpowers/specs/2026-07-13-wake-acknowledgment-design.md

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 NEVER bump; NO new dependencies; plain-JVM JUnit4 tests only (no android.* in tests).
- Build gate (repo root): `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug` must exit 0.
- Every commit message ends with: `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Old saved configs (no `wakeSoundVolume`) must load with default 80.
- Earcons play on STREAM_MUSIC; the timer alarm stays on STREAM_ALARM. AudioTrack must be primed BEFORE play() (device HAL renders empty-started tracks silently).
- In `SatelliteSession.onEvent`, the `Earcon` action must PRECEDE the `Overlay` action in the returned list (existing tests assert `.last() as Overlay`).

---

### Task 1: Earcon action, tone synthesis, config knob (pure JVM)

**Files:**
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt` (actions ~line 16, detection/transcript ~lines 81-82)
- Modify: `app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt` (Out interface ~line 31, dispatch ~line 175)
- Modify: `app/src/main/java/com/rar/echodash/voice/ToneGenerator.kt`
- Modify: `app/src/main/java/com/rar/echodash/config/DashConfig.kt` (VoiceSettings ~line 101)
- Test: `app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt`, `.../voice/ToneGeneratorTest.kt`, `.../voice/SatelliteServerTest.kt`, `.../config/DashConfigTest.kt`

**Interfaces:**
- Produces: `enum class EarconKind { WAKE, DONE }`; `SatelliteAction.Earcon(kind)`; `SatelliteServer.Out.onEarcon(kind: EarconKind)`; `ToneGenerator.earcon(kind: String, volume: Int, rate: Int): ShortArray` (kinds "wake"/"done"/"preview"); `VoiceSettings.wakeSoundVolume: Int = 80` — Task 2 consumes all of these.

- [ ] **Step 1: Write the failing tests**

SatelliteSessionTest.kt — add inside the class (it has `event(type, json)` helpers; follow existing style):

```kotlin
    @Test
    fun detectionAndTranscriptEmitEarconsBeforeOverlay() {
        val s = SatelliteSession("1.0")
        val wake = s.onEvent(event("detection", """{"name":"ok_nabu"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.WAKE), wake.first())
        assertTrue(wake.last() is SatelliteAction.Overlay)
        val done = s.onEvent(event("transcript", """{"text":"turn on the light"}"""))
        assertEquals(SatelliteAction.Earcon(EarconKind.DONE), done.first())
        assertTrue(done.last() is SatelliteAction.Overlay)
    }
```

ToneGeneratorTest.kt — add (rate 16000 keeps the sample math exact):

```kotlin
    @Test
    fun earconLengthsAndSilence() {
        val rate = 16000
        val wake = ToneGenerator.earcon("wake", 80, rate)
        val done = ToneGenerator.earcon("done", 80, rate)
        val preview = ToneGenerator.earcon("preview", 80, rate)
        assertEquals(rate * 130 / 1000 + rate * 150 / 1000, wake.size)
        assertEquals(rate * 100 / 1000 + rate * 120 / 1000, done.size)
        assertEquals(wake.size + rate * 150 / 1000 + done.size, preview.size)
        // Audible at volume 80, pure silence at 0, unknown kind falls back to wake.
        assertTrue(wake.any { it.toInt() != 0 })
        assertTrue(ToneGenerator.earcon("wake", 0, rate).all { it.toInt() == 0 })
        assertEquals(wake.size, ToneGenerator.earcon("bogus", 80, rate).size)
    }
```

DashConfigTest.kt — extend in the file's existing style: `VoiceSettings(wakeSoundVolume = 150).clamped()` → 100; `-5` → 0; a voice JSON blob without the key decodes to 80.

SatelliteServerTest.kt — the `RecordingOut` fake gains `override fun onEarcon(kind: EarconKind) { }` (record it in the fake's list if the fake records other calls; match its existing pattern).

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.voice.*' --tests 'com.rar.echodash.config.DashConfigTest'`
Expected: compilation FAILS (no EarconKind / earcon / wakeSoundVolume).

- [ ] **Step 3: Implement**

SatelliteSession.kt — below the `SatelliteAction` interface (before the class), add:

```kotlin
/** Which acknowledgment chirp to play. */
enum class EarconKind { WAKE, DONE }
```

Inside `SatelliteAction`, after `Timers`:

```kotlin
    data class Earcon(val kind: EarconKind) : SatelliteAction
```

The two event cases become:

```kotlin
        "detection" -> listOf(
            SatelliteAction.Earcon(EarconKind.WAKE),
            overlayAction(VoiceOverlayState(VoiceOverlayPhase.LISTENING)),
        )
        "transcript" -> listOf(
            SatelliteAction.Earcon(EarconKind.DONE),
            overlayAction(VoiceOverlayState(VoiceOverlayPhase.TRANSCRIPT, textOf(event))),
        )
```

SatelliteServer.kt — `Out` gains `fun onEarcon(kind: EarconKind)` after `onTimers`; `dispatch()`'s `when` gains `is SatelliteAction.Earcon -> out.onEarcon(a.kind)` after the Timers case.

ToneGenerator.kt — add after `render` (and update the class KDoc's first line to mention earcons):

```kotlin
    /**
     * One-shot voice acknowledgment chirps (no trailing gap — these are not looping alarm
     * cycles). "wake" rises (660→880 Hz), "done" falls (880→660 Hz), "preview" is
     * wake + 150 ms silence + done for the config page. Unknown kinds fall back to "wake".
     */
    fun earcon(kind: String, volume: Int, rate: Int): ShortArray {
        val amp = (volume / 100.0) * 0.6 * Short.MAX_VALUE
        return when (kind) {
            "done" -> chirp(amp, rate, 880.0 to 100, 660.0 to 120)
            "preview" -> earcon("wake", volume, rate) +
                ShortArray(rate * 150 / 1000) +
                earcon("done", volume, rate)
            else -> chirp(amp, rate, 660.0 to 130, 880.0 to 150) // "wake" and any unknown value
        }
    }

    /** Two consecutive notes, each (frequency Hz to duration ms), with 8 ms linear ramps. */
    private fun chirp(amp: Double, rate: Int, first: Pair<Double, Int>, second: Pair<Double, Int>): ShortArray {
        val ramp = rate * 8 / 1000
        fun note(freq: Double, ms: Int): ShortArray {
            val n = rate * ms / 1000
            return ShortArray(n) { i ->
                val env = minOf(1.0, i.toDouble() / ramp, (n - 1 - i).toDouble() / ramp)
                (sin(2 * PI * freq * i / rate) * amp * env).toInt().toShort()
            }
        }
        return note(first.first, first.second) + note(second.first, second.second)
    }
```

DashConfig.kt — `VoiceSettings` gains `val wakeSoundVolume: Int = 80` after `timerVolume`, and `clamped()` gains `wakeSoundVolume = wakeSoundVolume.coerceIn(0, 100),`.

- [ ] **Step 4: Run the full gate** — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`, expect exit 0. (App.kt still compiles: the new Out method is only added to the interface in this task IF App's anonymous Out object also gains a stub — add `override fun onEarcon(kind: EarconKind) { }` to the `object : SatelliteServer.Out` in App.kt (~line 245) with a `// wired to EarconPlayer in the next commit` comment; Task 2 replaces the body.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/SatelliteSession.kt \
        app/src/main/java/com/rar/echodash/voice/SatelliteServer.kt \
        app/src/main/java/com/rar/echodash/voice/ToneGenerator.kt \
        app/src/main/java/com/rar/echodash/config/DashConfig.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteSessionTest.kt \
        app/src/test/java/com/rar/echodash/voice/ToneGeneratorTest.kt \
        app/src/test/java/com/rar/echodash/voice/SatelliteServerTest.kt \
        app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "Wake acknowledgment: earcon action, chirp synthesis, volume setting

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

### Task 2: EarconPlayer, WakeGlow, wiring, config page

**Files:**
- Create: `app/src/main/java/com/rar/echodash/voice/EarconPlayer.kt`
- Modify: `app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt`
- Modify: `app/src/main/java/com/rar/echodash/App.kt` (deps ~line 230, Out impl ~line 245, ConfigServer args ~line 113, composable ~line 548)
- Modify: `app/src/main/java/com/rar/echodash/web/ConfigServer.kt` (ctor ~line 32, routes ~line 60, handler near handlePreviewChime ~line 103)
- Modify: `app/src/main/assets/config/app.js` (renderVoice ~line 538)

**Interfaces:**
- Consumes (Task 1): `EarconKind`, `SatelliteAction.Earcon`, `Out.onEarcon`, `ToneGenerator.earcon`, `VoiceSettings.wakeSoundVolume`.
- Produces: `EarconPlayer.play(kind: String, volume: Int)`; `@Composable WakeGlow(visible: Boolean)`; `POST /api/voice/preview-wake`.

No new unit tests (AudioTrack/Compose/HTTP-glue; the build gate covers compilation, endpoint mirrors the tested chime handler). Steps:

- [ ] **Step 1: EarconPlayer**

Create `app/src/main/java/com/rar/echodash/voice/EarconPlayer.kt`:

```kotlin
package com.rar.echodash.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * One-shot voice acknowledgment chirps on STREAM_MUSIC (they track media volume like the TTS
 * responses; the timer alarm keeps STREAM_ALARM). Same HAL recipe as TimerChime.playOnce:
 * prime the full buffer BEFORE play(), then wait for the playback head before releasing.
 * Best-effort: swallows all failures, never throws. Each call runs on its own daemon thread.
 */
class EarconPlayer {
    /** Play one [ToneGenerator.earcon] cycle of [kind] at [volume]; no-op when volume <= 0. */
    fun play(kind: String, volume: Int) {
        if (volume <= 0) return
        thread(name = "Earcon", isDaemon = true) {
            val rate = 22050
            val pcm = ToneGenerator.earcon(kind, volume, rate)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, pcm.size * 2), AudioTrack.MODE_STREAM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "earcon init failed", e); return@thread
            }
            try {
                // Prime BEFORE play(): this HAL renders a track started on an empty buffer
                // silently (see TimerChime for the full story).
                var off = 0
                while (off < pcm.size) {
                    val n = track.write(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                track.play()
                // Wait for the hardware head to consume what we wrote before releasing,
                // else the native track dies with the chirp still unplayed.
                val target = pcm.size.toLong()
                val deadline = System.currentTimeMillis() +
                    pcm.size * 1000L / rate + track.bufferSizeInFrames * 1000L / rate + 500L
                while (System.currentTimeMillis() < deadline) {
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= target) break
                    Thread.sleep(20)
                }
                runCatching { track.stop() }
            } catch (e: Exception) {
                Log.w(TAG, "earcon playback failed", e)
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private companion object { const val TAG = "EarconPlayer" }
}
```

- [ ] **Step 2: WakeGlow composable**

In `ui/VoiceOverlay.kt`, add above `VoiceOverlay`:

```kotlin
/**
 * Full screen-edge glow shown while the satellite is listening (wake word heard, speech not
 * yet captured). Four thin gradient strips hugging the edges — cheap fill for this GPU; the
 * pulse animation only runs while the glow is composed.
 */
@Composable
fun WakeGlow(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(400)), modifier = modifier) {
        val pulse = rememberInfiniteTransition(label = "wakeGlow")
        val alpha by pulse.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "wakeGlowAlpha",
        )
        val color = Color(0xFF4FC3F7).copy(alpha = alpha)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(color, Color.Transparent))),
            )
            Box(
                Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, color))),
            )
            Box(
                Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterStart)
                    .background(Brush.horizontalGradient(listOf(color, Color.Transparent))),
            )
            Box(
                Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, color))),
            )
        }
    }
}
```

Add the missing imports following the file's ordering: `androidx.compose.animation.AnimatedVisibility`, `androidx.compose.animation.core.FastOutSlowInEasing`, `androidx.compose.animation.core.RepeatMode`, `androidx.compose.animation.core.animateFloat`, `androidx.compose.animation.core.infiniteRepeatable`, `androidx.compose.animation.core.rememberInfiniteTransition`, `androidx.compose.animation.core.tween`, `androidx.compose.animation.fadeIn`, `androidx.compose.animation.fadeOut`, `androidx.compose.foundation.layout.fillMaxHeight`, `androidx.compose.foundation.layout.fillMaxWidth`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.layout.width`, `androidx.compose.runtime.getValue`, `androidx.compose.ui.graphics.Brush`.

- [ ] **Step 3: App wiring**

In App.kt:
- Beside `val timerChime = TimerChime()` (~line 230): `val earconPlayer = EarconPlayer()`.
- The Task-1 stub in the Out object becomes:

```kotlin
            override fun onEarcon(kind: EarconKind) = earconPlayer.play(
                if (kind == EarconKind.WAKE) "wake" else "done",
                configStore.config.value.voice.wakeSoundVolume,
            )
```

  (Verify the config store's actual property path — ConfigServer receives `store = configStore` and its handler reads `store.config.value.voice`; use the same expression that compiles in App's scope.)
- ConfigServer construction (~line 113) gains, after `previewChime = ...`:

```kotlin
        previewEarcon = { volume -> earconPlayer.play("preview", volume) },
```

- In the dashboard composable (~line 548), directly before `VoiceOverlay(voiceOverlayState)`:

```kotlin
                    WakeGlow(voiceOverlayState.phase == VoiceOverlayPhase.LISTENING)
```

  Import `com.rar.echodash.ui.WakeGlow` and `com.rar.echodash.voice.VoiceOverlayPhase`/`EarconKind` as needed (check what App.kt already imports).

- [ ] **Step 4: ConfigServer endpoint**

- Constructor gains `private val previewEarcon: (Int) -> Unit,` after `previewChime`.
- Route table (~line 60) gains: `uri == "/api/voice/preview-wake" && method == Method.POST -> handlePreviewWake(session)` next to the chime route.
- Handler after `handlePreviewChime`:

```kotlin
    private fun handlePreviewWake(session: IHTTPSession): Response {
        val obj = runCatching { ConfigJson.json.parseToJsonElement(readBody(session)) as JsonObject }.getOrNull()
        val saved = store.config.value.voice
        val volume = obj?.get("volume")?.jsonPrimitive?.intOrNull ?: saved.wakeSoundVolume
        val norm = VoiceSettings(wakeSoundVolume = volume).clamped()
        previewEarcon(norm.wakeSoundVolume)
        return ok("""{"ok":true}""")
    }
```

If ConfigServer has unit tests constructing it, add a no-op `previewEarcon = { }` there.

- [ ] **Step 5: Config page**

In `renderVoice()` in app.js:
- Defaults block gains: `if (v.wakeSoundVolume == null) v.wakeSoundVolume = 80;`
- After the existing Preview button (`host.appendChild(preview);`), add:

```js
  const wakeVol = el("input"); wakeVol.type = "number"; wakeVol.min = 0; wakeVol.max = 100; wakeVol.value = v.wakeSoundVolume;
  wakeVol.addEventListener("change", () => v.wakeSoundVolume = Math.round(parseFloat(wakeVol.value) || 0));
  host.appendChild(labeledRow("Wake sound volume", wakeVol));

  const wakePreview = el("button", "ghost small", "Preview");
  wakePreview.type = "button";
  wakePreview.addEventListener("click", async () => {
    // Audition the CURRENT (possibly unsaved) volume. Best-effort; ignore failures.
    wakePreview.disabled = true;
    try {
      await api("POST", "/api/voice/preview-wake", { volume: v.wakeSoundVolume });
    } catch (e) { /* device may be unreachable; nothing to persist */ }
    finally { wakePreview.disabled = false; }
  });
  host.appendChild(wakePreview);
```

- Muted hint text gains a trailing sentence: `" Wake sound: chirps when the wake word is heard and when it stops listening; volume 0 disables it."`

- [ ] **Step 6: Run the full gate** — `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`, expect exit 0.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rar/echodash/voice/EarconPlayer.kt \
        app/src/main/java/com/rar/echodash/ui/VoiceOverlay.kt \
        app/src/main/java/com/rar/echodash/App.kt \
        app/src/main/java/com/rar/echodash/web/ConfigServer.kt \
        app/src/main/assets/config/app.js
git commit -m "Wake acknowledgment: edge glow, earcon playback, config knob + preview

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```
