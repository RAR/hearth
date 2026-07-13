# Night Mode Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the room goes dark, replace the dashboard with a huge dim time-only clock on pure black and drop the backlight to a configured near-minimum level; return to the normal dashboard at normal brightness when the room lightens, on touch, or when an override needs the screen.

**Architecture:** A new plain-Kotlin `night/NightModeController` state machine (JVM-unit-testable like `NowPlayingStore`) owns entry/exit hysteresis from timestamped lux/override/touch/tick inputs and exposes two `StateFlow<Boolean>`s (`nightActive`, `ticking`). `KioskController.setNightDim` performs the brightness handoff (KioskController stays the single brightness owner). A new `ui/NightClockOverlay` composable renders the black clock as the top layer in `DashboardShell`, and `DashConfig.NightSettings` plus a config-page Night card carry the settings with a live lux reading surfaced through `ConfigServer`.

**Tech Stack:** Kotlin 2.1.0, Compose, JUnit4.

## Global Constraints

- Kotlin 2.1.0; compileSdk 34 (never bump); media3 exactly 1.4.1; NanoHTTPD 2.3.1; **no new dependencies**.
- Device is Android 11 / API 30 (LineageOS 18.1, Echo Show 5, 960×480 landscape).
- Plain-JVM JUnit4 tests only; no Robolectric; no Android classes in tests.
- Commit trailer on every commit:
  `Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi`
- Build gate command (run from repo root, at the end of every task before commit):
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`

## Resolved names (binding for all tasks)

- **"Voice overlay active"** = `voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN`
  (`app/src/main/java/com/rar/echodash/voice/VoiceOverlayState.kt` — `phase` field line 7, `VoiceOverlayPhase` enum line 4; same expression already used in `App.kt:468`).
- **"Timers active"** = `timersState.chips.isNotEmpty() || timersState.alert != null`
  (`app/src/main/java/com/rar/echodash/voice/TimerUi.kt:9` — `TimersUiState(val chips: List<TimerChip> = emptyList(), val alert: TimerAlert? = null)`).
- **Controller API for the ticker decision:** `NightModeController` exposes a **second** `StateFlow<Boolean> ticking` alongside `nightActive`. `ticking` is true when night is active OR an otherwise-ready entry is being held off by an override or the post-touch hold; App drives the 5 s `onTick` ticker only while `ticking` is true.
- **Controller StateFlow test pattern (copied from `NowPlayingStore`):** construct the class directly, call the mutators synchronously, and read `controller.nightActive.value` / `controller.ticking.value` immediately — plain JUnit4 `@Test` methods with `assertTrue`/`assertFalse`, **no** `runTest`, no coroutines-test, no Android classes (mirrors `app/src/test/java/com/rar/echodash/media/NowPlayingStoreTest.kt`, e.g. `val s = NowPlayingStore(); s.onEngine(...); assertEquals(..., s.state.value...)`).

---

## Task 1 — NightModeController + full JVM test suite

**Files:**
- Create `app/src/main/java/com/rar/echodash/night/NightModeController.kt`
- Create `app/src/test/java/com/rar/echodash/night/NightModeControllerTest.kt`

**Interfaces:**
- Produces:
  - `class NightModeController`
  - `val nightActive: StateFlow<Boolean>`
  - `val ticking: StateFlow<Boolean>`
  - `fun onConfig(enabled: Boolean, thresholdLux: Int)`
  - `fun onLux(lux: Float, nowMs: Long)`
  - `fun onOverride(active: Boolean, nowMs: Long)`
  - `fun onUserInteraction(nowMs: Long)`
  - `fun onTick(nowMs: Long)`
  - `companion object { const val ENTER_DWELL_MS = 30_000L; const val EXIT_DWELL_MS = 10_000L; const val TOUCH_HOLD_MS = 60_000L }`
- Consumes: nothing (pure Kotlin, `kotlinx.coroutines.flow` only).

Semantics — **dark-latch model.** A `darkLatch` is the hysteresis memory: it turns ON after lux stays strictly `< thresholdLux` continuously for `ENTER_DWELL_MS` (`belowSinceMs`), and turns OFF only after lux stays `>= max(2*threshold, threshold+10)` continuously for `EXIT_DWELL_MS` (`aboveSinceMs`). Dead-band samples (between the thresholds) clear both dwell clocks but never flip the latch — this is load-bearing: when a touch or override wakes the screen in a dark room, the screen's own glow can lift the sensor into the dead band, and the latch must survive that so night re-enters when the suppression ends (otherwise the screen would strand itself bright all night). The visible state is derived: `nightActive = enabled && darkLatch && !overrideActive && !withinTouchHold` — so touch/override/disable "exits" are suppressions of a still-set latch, re-entry after suppression is immediate, and no separate dwell bookkeeping for suppression is needed. A threshold change resets both dwell clocks and the latch. `onConfig` re-evaluates using the last observed timestamp.

### Steps

- [ ] **Write the failing test.** Create `app/src/test/java/com/rar/echodash/night/NightModeControllerTest.kt` with exactly:

```kotlin
package com.rar.echodash.night

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeControllerTest {

    private fun enabled(c: NightModeController, threshold: Int = 10) = c.onConfig(true, threshold)

    @Test
    fun entersAfterLuxBelowThresholdFor30sNotBefore() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0)
        c.onLux(5f, 29_999)
        assertFalse("not before 30s", c.nightActive.value)
        c.onLux(5f, 30_000)
        assertTrue("enters at 30s", c.nightActive.value)
    }

    @Test
    fun sampleAtOrAboveThresholdDuringDwellResets() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0)
        c.onLux(10f, 10_000)   // == entry threshold clears belowSince
        c.onLux(5f, 10_001)
        c.onLux(5f, 39_999)    // only 29,998 ms since the reset
        assertFalse(c.nightActive.value)
        c.onLux(5f, 40_001)
        assertTrue(c.nightActive.value)
    }

    @Test
    fun exitsAfterLuxAboveExitFor10sButNotOn5sSpike() {
        val c = NightModeController()
        enabled(c)                       // threshold 10, exit = max(20, 20) = 20
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onLux(50f, 31_000)             // spike up
        c.onLux(5f, 36_000)              // back to dark within 5 s
        assertTrue("spike shorter than 10s holds night", c.nightActive.value)
        c.onLux(50f, 40_000)
        c.onLux(50f, 50_000)             // 10 s continuously >= exit
        assertFalse(c.nightActive.value)
    }

    @Test
    fun deadBandHoldsStateBothDirections() {
        val c = NightModeController()
        enabled(c)                       // entry 10, exit 20, dead band [10,20)
        c.onLux(15f, 0)
        c.onLux(15f, 60_000)
        assertFalse("dead-band never enters", c.nightActive.value)
        c.onLux(5f, 61_000); c.onLux(5f, 91_000)
        assertTrue(c.nightActive.value)
        c.onLux(15f, 92_000)
        c.onLux(15f, 120_000)
        assertTrue("dead-band never exits", c.nightActive.value)
    }

    @Test
    fun touchExitsImmediatelyAndBlocksReentryFor60s() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onUserInteraction(30_000)
        assertFalse("touch exits immediately", c.nightActive.value)
        c.onTick(89_999)                 // 59,999 ms after touch
        assertFalse("still within 60s hold", c.nightActive.value)
        c.onTick(90_000)                 // 60,000 ms after touch, room stayed dark
        assertTrue("re-enters right after hold expires", c.nightActive.value)
    }

    @Test
    fun overrideExitsImmediatelyAndReentersWhenCleared() {
        val c = NightModeController()
        enabled(c)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue(c.nightActive.value)
        c.onOverride(true, 31_000)
        assertFalse("override exits immediately", c.nightActive.value)
        c.onOverride(false, 32_000)
        assertTrue("override cleared in dark room re-enters immediately", c.nightActive.value)
    }

    @Test
    fun disabledNeverEntersAndDisablingExits() {
        val c = NightModeController()
        c.onConfig(false, 10)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertFalse("disabled never enters", c.nightActive.value)
        c.onConfig(true, 10)
        c.onLux(5f, 31_000); c.onLux(5f, 61_000)
        assertTrue(c.nightActive.value)
        c.onConfig(false, 10)
        assertFalse("disabling while active exits", c.nightActive.value)
    }

    @Test
    fun thresholdChangeResetsDwellClocks() {
        val c = NightModeController()
        enabled(c, 10)
        c.onLux(5f, 0)
        c.onLux(5f, 29_000)              // 29 s of dwell accumulated
        c.onConfig(true, 8)              // threshold change resets belowSince
        assertFalse(c.nightActive.value)
        c.onLux(5f, 30_000)              // new dwell starts here
        c.onLux(5f, 59_000)
        assertFalse("dwell restarted after threshold change", c.nightActive.value)
        c.onLux(5f, 60_000)
        assertTrue(c.nightActive.value)
    }

    @Test
    fun noLuxSamplesNeverEnters() {
        val c = NightModeController()
        enabled(c)
        c.onTick(0)
        c.onTick(30_000)
        c.onTick(120_000)
        assertFalse(c.nightActive.value)
    }

    @Test
    fun screenGlowInDeadBandDoesNotStrandScreenBrightAfterTouch() {
        // The waking screen's own glow can lift the sensor into the dead band [threshold, exit).
        // That must NOT clear the dark latch, or the screen re-lights the sensor forever and
        // night never returns.
        val c = NightModeController()
        enabled(c)                       // entry 10, exit 20
        c.onLux(3f, 0); c.onLux(3f, 30_000)
        assertTrue(c.nightActive.value)
        c.onUserInteraction(30_000)      // wake -> screen glow raises ambient into the dead band
        assertFalse(c.nightActive.value)
        c.onLux(14f, 40_000)
        c.onLux(14f, 80_000)
        c.onTick(90_000)                 // touch-hold expired; latch must have survived the glow
        assertTrue("dead-band glow while woken must not block re-entry", c.nightActive.value)
    }

    @Test
    fun tickingRunsWhileLatchedOrSuppressedOnly() {
        val c = NightModeController()
        enabled(c)
        assertFalse("fully off -> no ticker", c.ticking.value)
        c.onLux(5f, 0); c.onLux(5f, 30_000)
        assertTrue("active -> ticker", c.ticking.value)
        c.onUserInteraction(31_000)
        assertTrue("touch-hold with dark latch -> ticker", c.ticking.value)
        c.onLux(50f, 32_000)
        c.onLux(50f, 42_000)             // 10 s sustained light clears the latch
        c.onTick(91_001)                 // touch-hold also expired
        assertFalse("bright room, hold expired -> no ticker", c.ticking.value)
    }
}
```

- [ ] **Run to see it fail.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.night.NightModeControllerTest'`
  Expected failure: compilation error — `Unresolved reference: NightModeController` (the class does not exist yet).

- [ ] **Minimal implementation.** Create `app/src/main/java/com/rar/echodash/night/NightModeController.kt` with exactly:

```kotlin
package com.rar.echodash.night

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ambient-light night-clock state machine. Pure Kotlin (no Android imports) so it unit-tests on the
 * JVM like NowPlayingStore. All inputs arrive on the main thread (the sensor-callback hop plus
 * Compose effects), so no locking is required. Timing is timestamp-based: callers pass nowMs
 * (SystemClock.elapsedRealtime in production; a fake Long clock in tests).
 */
class NightModeController {
    private val _nightActive = MutableStateFlow(false)
    /** True while the dim night clock should own the screen. Threaded into DashboardShell. */
    val nightActive: StateFlow<Boolean> = _nightActive

    private val _ticking = MutableStateFlow(false)
    /**
     * True while App must run the 5 s re-evaluation ticker: night is active, or an otherwise-ready
     * entry is being suppressed by an override or the post-touch hold (so touch-hold expiry in a
     * silent, dark room still nudges a re-entry). False when the feature is off or fully idle.
     */
    val ticking: StateFlow<Boolean> = _ticking

    private var enabled = false
    private var thresholdLux = 10
    // The hysteresis memory: set after ENTER_DWELL below the threshold, cleared only after
    // EXIT_DWELL at/above the exit threshold. Dead-band samples (e.g. the woken screen's own
    // glow on the sensor) clear the dwell clocks but never the latch — touch/override "exits"
    // are suppressions of a still-set latch, so night returns when the suppression ends.
    private var darkLatch = false
    private var belowSinceMs: Long? = null
    private var aboveSinceMs: Long? = null
    private var lastTouchMs: Long? = null
    private var overrideActive = false
    private var lastNowMs = 0L

    fun onConfig(enabled: Boolean, thresholdLux: Int) {
        val thresholdChanged = thresholdLux != this.thresholdLux
        this.enabled = enabled
        this.thresholdLux = thresholdLux
        if (thresholdChanged) { belowSinceMs = null; aboveSinceMs = null; darkLatch = false }
        evaluate(lastNowMs)
    }

    fun onLux(lux: Float, nowMs: Long) {
        lastNowMs = nowMs
        belowSinceMs = if (lux < thresholdLux) belowSinceMs ?: nowMs else null
        aboveSinceMs = if (lux >= exitThreshold()) aboveSinceMs ?: nowMs else null
        evaluate(nowMs)
    }

    fun onOverride(active: Boolean, nowMs: Long) {
        lastNowMs = nowMs
        overrideActive = active
        evaluate(nowMs)
    }

    fun onUserInteraction(nowMs: Long) {
        lastNowMs = nowMs
        lastTouchMs = nowMs
        evaluate(nowMs)
    }

    fun onTick(nowMs: Long) {
        lastNowMs = nowMs
        evaluate(nowMs)
    }

    private fun evaluate(nowMs: Long) {
        if (enabled) {
            belowSinceMs?.let { if (!darkLatch && nowMs - it >= ENTER_DWELL_MS) darkLatch = true }
            aboveSinceMs?.let { if (darkLatch && nowMs - it >= EXIT_DWELL_MS) darkLatch = false }
        } else {
            darkLatch = false
        }
        val withinTouchHold = lastTouchMs?.let { nowMs - it < TOUCH_HOLD_MS } ?: false
        _nightActive.value = enabled && darkLatch && !overrideActive && !withinTouchHold
        _ticking.value = enabled && (darkLatch || overrideActive || withinTouchHold)
    }

    private fun exitThreshold(): Int = maxOf(thresholdLux * 2, thresholdLux + 10)

    companion object {
        const val ENTER_DWELL_MS = 30_000L
        const val EXIT_DWELL_MS = 10_000L
        const val TOUCH_HOLD_MS = 60_000L
    }
}
```

- [ ] **Run to see it pass.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.night.NightModeControllerTest'`
  Expected: all 11 tests pass.

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/night/NightModeController.kt app/src/test/java/com/rar/echodash/night/NightModeControllerTest.kt
git commit -m "feat(night): ambient-light night-mode state machine

NightModeController with a dark latch (dwell entry, hysteresis exit;
dead-band samples hold the latch so the woken screen's own glow cannot
strand the screen bright), touch-hold and override suppression, and a
ticking StateFlow so App knows when to re-evaluate. Full plain-JVM test
suite (spec tests 1-9 + glow regression + ticking).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 2 — KioskController.setNightDim + tests 10–12

**Files:**
- Modify `app/src/main/java/com/rar/echodash/vaca/KioskController.kt`
  - fields block (anchor: `private var timeoutSeconds = 60` / `private var timeoutJob: Job? = null`, lines 45–46)
  - `onLightLevel` (lines 125–130)
  - `applySettings` `screen_brightness` + `screen_auto_brightness` cases (lines 74–83)
- Modify `app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt` (append tests before the final `}`)

**Interfaces:**
- Produces: `fun setNightDim(active: Boolean, percent: Int)` on `KioskController`.
- Consumes: `KioskDevice.setBrightness(percent: Int)` (existing).

Behavior: `setNightDim(true, p)` pins `device.setBrightness(p)`; while active, `onLightLevel` and HA `screen_brightness`/`screen_auto_brightness` changes update stored state but never call `setBrightness`. `setNightDim(false, _)` reapplies immediately: auto → `autoPercent(lastLux)`, manual → stored `brightness`. `lastLux` is tracked in `onLightLevel` even while night-dim suppresses application. Runtime-only state (not in `currentSettings()`).

### Steps

- [ ] **Write the failing tests.** Append these three tests to `app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt`, immediately before the closing brace of the class (after `actionsMapToDevice`):

```kotlin
    @Test
    fun nightDimPinsBrightnessAndSuppressesAutoBrightness() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)          // auto-brightness on by default
        kiosk.setNightDim(true, 0)
        assertTrue(device.calls.contains("brightness:0"))
        device.calls.clear()
        kiosk.onLightLevel(400f)                           // would be brightness:100 normally
        assertTrue("night-dim suppresses auto-brightness", device.calls.none { it.startsWith("brightness:") })
        kiosk.cancelTimers()
    }

    @Test
    fun clearingNightDimReappliesAutoOrManual() = runTest {
        // auto: reapplies the formula from the last seen lux
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(400f)                           // lastLux = 400 -> brightness:100
        kiosk.setNightDim(true, 0)                         // brightness:0
        device.calls.clear()
        kiosk.setNightDim(false, 0)
        assertTrue("auto reapplies formula from lastLux", device.calls.contains("brightness:100"))
        kiosk.cancelTimers()

        // manual: reapplies the stored manual value
        val device2 = FakeDevice()
        val kiosk2 = KioskController(this, device2)
        kiosk2.applySettings(settings("""{"screen_auto_brightness":false,"screen_brightness":35}"""))
        kiosk2.setNightDim(true, 0)                        // brightness:0
        device2.calls.clear()
        kiosk2.setNightDim(false, 0)
        assertTrue("manual reapplies stored value", device2.calls.contains("brightness:35"))
        kiosk2.cancelTimers()
    }

    @Test
    fun haBrightnessDuringNightDimStoresWithoutApplyingThenAppliesOnClear() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))  // manual mode
        kiosk.setNightDim(true, 0)                         // brightness:0
        device.calls.clear()
        kiosk.applySettings(settings("""{"screen_brightness":40}"""))          // stored, NOT applied
        assertTrue("no brightness applied while night-dim", device.calls.none { it.startsWith("brightness:") })
        kiosk.setNightDim(false, 0)                        // manual -> stored 40 applied
        assertTrue(device.calls.contains("brightness:40"))
        kiosk.cancelTimers()
    }
```

- [ ] **Run to see it fail.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.vaca.KioskControllerTest'`
  Expected failure: compilation error — `Unresolved reference: setNightDim`.

- [ ] **Minimal implementation.** Apply three edits to `app/src/main/java/com/rar/echodash/vaca/KioskController.kt`.

Edit 1 — add fields. Replace:
```kotlin
    private var timeoutSeconds = 60
    private var timeoutJob: Job? = null
```
with:
```kotlin
    private var timeoutSeconds = 60
    private var timeoutJob: Job? = null
    private var nightDim = false
    private var lastLux = 0f
```

Edit 2 — replace `onLightLevel`:
```kotlin
    /** Ambient light in lux; drives brightness while auto-brightness is on. */
    fun onLightLevel(lux: Float) {
        if (!autoBrightness) return
        val percent = (10 + (lux.coerceIn(0f, 400f) / 400f) * 90).toInt()
        device.setBrightness(percent)
    }
```
with:
```kotlin
    /** Ambient light in lux; drives brightness while auto-brightness is on (unless night-dim pins it).
     *  lastLux is always tracked so setNightDim(false) can reapply the auto formula on the way out. */
    fun onLightLevel(lux: Float) {
        lastLux = lux
        if (nightDim || !autoBrightness) return
        device.setBrightness(autoPercent(lux))
    }

    /** Night clock dimming: while active, pins brightness to [percent] and ignores auto-brightness
     *  lux updates and HA screen_brightness changes; clearing restores the normal auto/manual value. */
    fun setNightDim(active: Boolean, percent: Int) {
        nightDim = active
        if (active) {
            device.setBrightness(percent)
        } else if (autoBrightness) {
            device.setBrightness(autoPercent(lastLux))
        } else {
            device.setBrightness(brightness)
        }
    }

    private fun autoPercent(lux: Float): Int = (10 + (lux.coerceIn(0f, 400f) / 400f) * 90).toInt()
```

Edit 3 — gate the two brightness-applying settings cases. Replace:
```kotlin
                "screen_brightness" -> value.asInt()?.let {
                    brightness = it.coerceIn(0, 100)
                    if (!autoBrightness) device.setBrightness(brightness)
                    changed = true
                }
                "screen_auto_brightness" -> value.asBoolean()?.let {
                    autoBrightness = it
                    if (!it) device.setBrightness(brightness)
                    changed = true
                }
```
with:
```kotlin
                "screen_brightness" -> value.asInt()?.let {
                    brightness = it.coerceIn(0, 100)
                    if (!autoBrightness && !nightDim) device.setBrightness(brightness)
                    changed = true
                }
                "screen_auto_brightness" -> value.asBoolean()?.let {
                    autoBrightness = it
                    if (!it && !nightDim) device.setBrightness(brightness)
                    changed = true
                }
```

- [ ] **Run to see it pass.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.vaca.KioskControllerTest'`
  Expected: all KioskControllerTest tests pass (including the 3 new ones).

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/vaca/KioskController.kt app/src/test/java/com/rar/echodash/vaca/KioskControllerTest.kt
git commit -m "feat(night): KioskController.setNightDim brightness handoff

Pins brightness while night-dim, suppresses auto-brightness and HA
screen_brightness application, tracks lastLux, and reapplies auto
formula or stored manual value on clear (spec tests 10-12).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 3 — NightSettings in DashConfig + clamped() + test 13

**Files:**
- Modify `app/src/main/java/com/rar/echodash/config/DashConfig.kt`
  - add `NightSettings` after `MediaSettings` (line 111)
  - add `night` field to `DashConfig` (anchor: `val media: MediaSettings = MediaSettings(),` line 122)
  - add `night = night.clamped()` in `clamped()` (anchor: `media = media.clamped(),` line 187)
- Modify `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt` (append tests before final `}`)

**Interfaces:**
- Produces:
  - `data class NightSettings(val enabled: Boolean = false, val thresholdLux: Int = 10, val brightness: Int = 0) { fun clamped(): NightSettings }`
  - `DashConfig.night: NightSettings` (default `NightSettings()`)
- Consumes: existing `DashConfig.clamped()` pipeline.

### Steps

- [ ] **Write the failing test.** Append to `app/src/test/java/com/rar/echodash/config/DashConfigTest.kt`, before the class's closing brace:

```kotlin
    @Test
    fun nightDefaults() {
        val n = DashConfig().night
        assertEquals(false, n.enabled)
        assertEquals(10, n.thresholdLux)
        assertEquals(0, n.brightness)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(false, cfg.night.enabled)
        assertEquals(10, cfg.night.thresholdLux)
        assertEquals(0, cfg.night.brightness)
    }

    @Test
    fun nightRoundTrips() {
        val cfg = DashConfig(night = NightSettings(enabled = true, thresholdLux = 25, brightness = 3))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals(true, decodeConfig(text).night.enabled)
        assertEquals(25, decodeConfig(text).night.thresholdLux)
        assertEquals(3, decodeConfig(text).night.brightness)
    }

    @Test
    fun nightClampsBounds() {
        val hi = DashConfig(night = NightSettings(thresholdLux = 5000, brightness = 250)).clamped().night
        assertEquals(1000, hi.thresholdLux)   // ceil 1000
        assertEquals(100, hi.brightness)       // ceil 100
        val lo = DashConfig(night = NightSettings(thresholdLux = 0, brightness = -5)).clamped().night
        assertEquals(1, lo.thresholdLux)       // floor 1
        assertEquals(0, lo.brightness)          // floor 0
    }

    @Test
    fun nightSurvivesClampedAndDefaultsOnOldConfig() {
        assertEquals(true, DashConfig(night = NightSettings(enabled = true)).clamped().night.enabled)
        // old config document with no "night" key -> defaults fill in
        val cfg = decodeConfig("""{"version":1,"home":{"photoFolder":"nas"}}""")
        assertEquals(false, cfg.night.enabled)
        assertEquals(10, cfg.night.thresholdLux)
    }
```

- [ ] **Run to see it fail.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.config.DashConfigTest'`
  Expected failure: compilation error — `Unresolved reference: NightSettings` and `Unresolved reference: night`.

- [ ] **Minimal implementation.** Apply three edits to `app/src/main/java/com/rar/echodash/config/DashConfig.kt`.

Edit 1 — add `NightSettings` after `MediaSettings`. Replace:
```kotlin
@Serializable
data class MediaSettings(
    val companionEntity: String? = null,
    val pausedDismissSeconds: Int = 60,
) {
    /** Trim the companion entity id; blank -> null (unconfigured). Clamp the paused-dismiss delay. */
    fun clamped(): MediaSettings = copy(
        companionEntity = companionEntity?.trim()?.ifBlank { null },
        pausedDismissSeconds = pausedDismissSeconds.coerceIn(5, 3600),
    )
}
```
with:
```kotlin
@Serializable
data class MediaSettings(
    val companionEntity: String? = null,
    val pausedDismissSeconds: Int = 60,
) {
    /** Trim the companion entity id; blank -> null (unconfigured). Clamp the paused-dismiss delay. */
    fun clamped(): MediaSettings = copy(
        companionEntity = companionEntity?.trim()?.ifBlank { null },
        pausedDismissSeconds = pausedDismissSeconds.coerceIn(5, 3600),
    )
}

@Serializable
data class NightSettings(
    val enabled: Boolean = false,
    val thresholdLux: Int = 10,
    val brightness: Int = 0,      // 0 = minimum backlight (window-brightness floor 0.01)
) {
    fun clamped(): NightSettings = copy(
        thresholdLux = thresholdLux.coerceIn(1, 1000),
        brightness = brightness.coerceIn(0, 100),
    )
}
```

Edit 2 — add the field. Replace:
```kotlin
    val voice: VoiceSettings = VoiceSettings(),
    val media: MediaSettings = MediaSettings(),
) {
```
with:
```kotlin
    val voice: VoiceSettings = VoiceSettings(),
    val media: MediaSettings = MediaSettings(),
    val night: NightSettings = NightSettings(),
) {
```

Edit 3 — wire into `clamped()`. Replace:
```kotlin
            voice = voice.clamped(),
            media = media.clamped(),
        )
    }
```
with:
```kotlin
            voice = voice.clamped(),
            media = media.clamped(),
            night = night.clamped(),
        )
    }
```

- [ ] **Run to see it pass.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.config.DashConfigTest'`
  Expected: all DashConfigTest tests pass (including the 4 new ones).

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/config/DashConfig.kt app/src/test/java/com/rar/echodash/config/DashConfigTest.kt
git commit -m "feat(night): NightSettings config with clamped bounds

enabled/thresholdLux/brightness with defaults, clamped() (1-1000 lux,
0-100 %), wired into DashConfig.clamped(); referencedEntityIds
unchanged (spec test 13).

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 4 — NightClockOverlay composable + DashboardShell integration

**Compile-gated task — no JVM unit test.** `NightClockOverlay` is a `@Composable` and `DashboardShell` is Compose UI; both are pure Android/Compose with no plain-JVM surface (no Robolectric per Global Constraints). Verification for this task is the `:app:assembleDebug` compile in the build gate, not a new test. State-machine correctness is already covered by Task 1's suite. The two new `DashboardShell` params are given defaults (`nightActive = false`, `onNightWake = {}`) so this task compiles green on its own; Task 5 supplies the real values.

**Files:**
- Create `app/src/main/java/com/rar/echodash/ui/NightClockOverlay.kt`
- Modify `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`
  - `DashboardShell` signature (anchor: `streamResolver: StreamResolver,` line 76 / closing `) {` line 77)
  - render the overlay as the top layer (anchor: the `IconRail(...)` block + closing braces, lines 179–187)

**Interfaces:**
- Produces:
  - `@Composable fun NightClockOverlay(active: Boolean, clockFormat: ClockFormat, onWake: () -> Unit)`
  - `DashboardShell` gains params `nightActive: Boolean` and `onNightWake: () -> Unit`.
- Consumes: `clockIs24(format: ClockFormat, systemIs24: Boolean): Boolean` (`ui/DashViews.kt:74`); `config.home.clockFormat`.

### Steps

- [ ] **Write the implementation (no test — compile-gated).** Create `app/src/main/java/com/rar/echodash/ui/NightClockOverlay.kt` with exactly:

```kotlin
package com.rar.echodash.ui

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Minute ticker matching HomeView's rememberMinuteTicker: updates on the wall-clock minute edge. */
@Composable
private fun rememberNightMinuteTicker(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000 - now.longValue % 60_000)
        }
    }
    return now
}

/**
 * The night clock: a huge dim-gray time on pure black, faded in/out on [active]. The overlay
 * consumes ALL touches — the waking tap fires [onWake] and must NOT reach the panels underneath.
 * Same 12/24-hour format as HomeView's clock (via clockIs24), AM/PM suffix smaller and dimmer.
 */
@Composable
fun NightClockOverlay(
    active: Boolean,
    clockFormat: ClockFormat,
    onWake: () -> Unit,
) {
    Crossfade(targetState = active, animationSpec = tween(600), label = "night") { on ->
        if (on) {
            val context = LocalContext.current
            val now by rememberNightMinuteTicker()
            val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.type == PointerEventType.Press) onWake()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row {
                    Text(
                        SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)),
                        color = Color(0xFF777777), fontSize = 120.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (!is24) {
                        Text(
                            SimpleDateFormat("a", Locale.getDefault()).format(Date(now)),
                            color = Color(0xFF555555), fontSize = 28.sp,
                            modifier = Modifier.alignByBaseline().padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Wire it into DashboardShell.** Apply two edits to `app/src/main/java/com/rar/echodash/ui/DashboardShell.kt`.

Edit 1 — add the two params. Replace:
```kotlin
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
    streamResolver: StreamResolver,
) {
```
with:
```kotlin
    onLogout: () -> Unit,
    onInteraction: () -> Unit,
    streamResolver: StreamResolver,
    nightActive: Boolean = false,
    onNightWake: () -> Unit = {},
) {
```

Edit 2 — render the overlay as the top layer of the outer Box. Replace:
```kotlin
            IconRail(
                current = current,
                views = views,
                onSelect = onSelect,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
```
with:
```kotlin
            IconRail(
                current = current,
                views = views,
                onSelect = onSelect,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        NightClockOverlay(
            active = nightActive,
            clockFormat = config.home.clockFormat,
            onWake = onNightWake,
        )
    }
}
```

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`. The defaulted `nightActive`/`onNightWake` params mean the existing `App.kt` call site still compiles; Task 5 replaces the defaults with real wiring.

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/ui/NightClockOverlay.kt app/src/main/java/com/rar/echodash/ui/DashboardShell.kt
git commit -m "feat(night): NightClockOverlay + DashboardShell top layer

Black full-screen dim clock faded on nightActive; consumes all touches
and fires onWake. DashboardShell gains nightActive/onNightWake params
and renders the overlay above panels and rail.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 5 — App.kt wiring (AppDeps + Dashboard composable)

**Files:**
- Modify `app/src/main/java/com/rar/echodash/App.kt`
  - imports (anchors: line 2 `import android.content.Context`; line 49 `import com.rar.echodash.media.ArtFetcher`)
  - `AppDeps`: `lastLux` holder (anchor: line 84 `private val appContext = ...`)
  - `nightMode` field (anchor: `kiosk = KioskController(...)` block, lines 147–152)
  - sensor callback (anchor: `val lightSensor = LightSensorReporter(appContext) { lux ->`, lines 170–172)
  - Dashboard composable: night effects + threading (anchors: `DashboardShell(` call line 403; `onInteraction`/`streamResolver` tail lines 458–463; `voiceOverlayState`/`timersState` collection lines 465–467)

**Interfaces:**
- Consumes: `NightModeController` (Task 1), `KioskController.setNightDim` (Task 2), `DashConfig.night` / `NightSettings` (Task 3), `DashboardShell(nightActive, onNightWake)` (Task 4), `VoiceOverlayPhase` (already imported line 57).
- Produces: `AppDeps.nightMode: NightModeController`; `AppDeps.lastLux: Int?`; the config/override/ticker/mirror `LaunchedEffect`s and the `nightActive` threading into `DashboardShell`.

### Steps

- [ ] **Apply the edits (no new test — this is wiring, covered by the build gate + Tasks 1–4 tests).**

Edit 1 — imports. Replace:
```kotlin
import android.content.Context
import androidx.compose.foundation.layout.Box
```
with:
```kotlin
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
```
Replace:
```kotlin
import com.rar.echodash.media.NowPlayingStore
import com.rar.echodash.media.ArtFetcher
```
with:
```kotlin
import com.rar.echodash.media.NowPlayingStore
import com.rar.echodash.media.ArtFetcher
import com.rar.echodash.night.NightModeController
```

Edit 2 — `lastLux` holder. Replace:
```kotlin
    private val appContext = context.applicationContext

    val settings: SettingsStore = PrefsSettingsStore(appContext)
```
with:
```kotlin
    private val appContext = context.applicationContext

    /** Latest ambient-light reading (lux) for the config page's live display; null when no sensor. */
    @Volatile var lastLux: Int? = null

    val settings: SettingsStore = PrefsSettingsStore(appContext)
```

Edit 3 — intentionally none. (The `lux = { lastLux }` ConfigServer argument belongs to Task 6, which creates the constructor parameter — passing it here would break this task's build gate.)

Edit 4 — `nightMode` field. Replace:
```kotlin
    val kiosk = KioskController(
        mainScope,
        kioskDevice,
        persist = { settings.vacaSettingsJson = it },
        restoredJson = settings.vacaSettingsJson,
    )
```
with:
```kotlin
    val kiosk = KioskController(
        mainScope,
        kioskDevice,
        persist = { settings.vacaSettingsJson = it },
        restoredJson = settings.vacaSettingsJson,
    )
    val nightMode = NightModeController()
```

Edit 5 — sensor callback. Replace:
```kotlin
    val lightSensor = LightSensorReporter(appContext) { lux ->
        mainScope.launch { kiosk.onLightLevel(lux) }
        scope.launch {
```
with:
```kotlin
    val lightSensor = LightSensorReporter(appContext) { lux ->
        lastLux = lux.toInt()
        mainScope.launch {
            kiosk.onLightLevel(lux)
            nightMode.onLux(lux, SystemClock.elapsedRealtime())
        }
        scope.launch {
```

Edit 6 — night collectors + config/ticker/mirror effects, just before the `DashboardShell(` call. Replace:
```kotlin
                    DashboardShell(
                        current = view,
                        onSelect = { v ->
```
with:
```kotlin
                    val nightActive by deps.nightMode.nightActive.collectAsStateWithLifecycle()
                    val nightTicking by deps.nightMode.ticking.collectAsStateWithLifecycle()
                    LaunchedEffect(config.night) {
                        deps.nightMode.onConfig(config.night.enabled, config.night.thresholdLux)
                    }
                    // Re-evaluation ticker: only runs while night is active or an entry is being
                    // held off (touch-hold/override in a dark room), so touch-hold expiry still
                    // fires when the room is silent and dark. No ticker when fully off.
                    LaunchedEffect(nightTicking) {
                        if (nightTicking) {
                            while (true) {
                                deps.nightMode.onTick(SystemClock.elapsedRealtime())
                                delay(5_000)
                            }
                        }
                    }
                    // Brightness mirror: KioskController pins/releases the backlight as night flips
                    // or the configured night brightness changes.
                    LaunchedEffect(nightActive, config.night.brightness) {
                        deps.kiosk.setNightDim(nightActive, config.night.brightness)
                    }

                    DashboardShell(
                        current = view,
                        onSelect = { v ->
```

Edit 7 — thread night into the DashboardShell call + touch into `onInteraction`. Replace:
```kotlin
                        onInteraction = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                        },
                        streamResolver = deps.streamResolver,
                    )
```
with:
```kotlin
                        onInteraction = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                            deps.nightMode.onUserInteraction(SystemClock.elapsedRealtime())
                        },
                        streamResolver = deps.streamResolver,
                        nightActive = nightActive,
                        onNightWake = {
                            deps.kiosk.onUserInteraction()
                            idleTimer.onInteraction()
                            deps.nightMode.onUserInteraction(SystemClock.elapsedRealtime())
                        },
                    )
```

Edit 8 — override effect after the voice/timers collectors. Replace:
```kotlin
                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
                    LaunchedEffect(voiceOverlayState.phase) {
```
with:
```kotlin
                    val voiceOverlayState by deps.voiceOverlay.collectAsStateWithLifecycle()
                    val timersState by deps.timersUi.collectAsStateWithLifecycle()
                    // Overrides suppress night mode at normal brightness: music takeover, doorbell
                    // popup, voice interaction, or any active/alerting timer.
                    LaunchedEffect(takeoverVisible, doorbellPopup, voiceOverlayState, timersState) {
                        deps.nightMode.onOverride(
                            takeoverVisible ||
                                doorbellPopup != null ||
                                voiceOverlayState.phase != VoiceOverlayPhase.HIDDEN ||
                                timersState.chips.isNotEmpty() ||
                                timersState.alert != null,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                    LaunchedEffect(voiceOverlayState.phase) {
```

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL` (this closes the Task 4 caller gap — `DashboardShell` now receives `nightActive`/`onNightWake`).

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/App.kt
git commit -m "feat(night): wire night mode into App

AppDeps.nightMode + lastLux holder; sensor callback feeds onLux and the
lux holder; config/override/mirror effects and the conditional 5s
ticker; threads nightActive + onNightWake into DashboardShell. (The
ConfigServer lux argument lands in the next commit with the param.)

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Task 6 — ConfigServer status lux field + config-page Night card

**Files:**
- Modify `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`
  - constructor `lux` provider (anchor: `private val connState: () -> String,` line 30)
  - `handleStatus()` (lines 95–99)
- Modify `app/src/main/java/com/rar/echodash/App.kt`
  - pass `lux = { lastLux }` at the `ConfigServer(...)` construction (anchor: `connState = { ws.connectionState.value.name },`)
- Modify `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`
  - `setUp` constructor (anchor: `connState = { "OFFLINE" },` line ~45)
  - new `statusIncludesLuxReading` test
- Modify `app/src/main/assets/config/index.html` (add Night section after Voice section, before `</div></main>`, lines 169–172)
- Modify `app/src/main/assets/config/app.js`
  - module state (anchor: `let entities = [];` line 4)
  - `tryLoad` (anchors: line 79 status fetch; line 88 `render();`)
  - `render()` (lines 270–277)
  - append `renderNight` / `updateNightLux` / `startStatusPoll` before the boot section (line 564)

**Interfaces:**
- Produces: `ConfigServer` constructor gains `lux: () -> Int? = { null }`; `/api/status` JSON gains `"lux"` (int or null).
- Consumes: `AppDeps.lastLux` (Task 5) via `lux = { lastLux }`.

Note: `ConfigServerSetupTest` and `BrowserFlowReproTest` construct `ConfigServer` with named args and do NOT pass `lux`; the `= { null }` default keeps them compiling untouched.

### Steps

- [ ] **Write the failing test.** In `app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt`, add `lux = { 42 },` to the `setUp` constructor. Replace:
```kotlin
            configured = { false },
            connState = { "OFFLINE" },
            previewChime = { tone, volume -> previewCalls += tone to volume },
```
with:
```kotlin
            configured = { false },
            connState = { "OFFLINE" },
            lux = { 42 },
            previewChime = { tone, volume -> previewCalls += tone to volume },
```
Then append this test before the class's closing brace:
```kotlin
    @Test
    fun statusIncludesLuxReading() {
        val cookie = cookieFrom(login("123456"))
        http.newCall(Request.Builder().url("$base/api/status").header("Cookie", cookie).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                val body = r.body!!.string()
                assertTrue(body.contains("\"lux\":42"))
                assertTrue(body.contains("\"connState\":\"OFFLINE\""))
            }
    }
```

- [ ] **Run to see it fail.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.web.ConfigServerTest'`
  Expected failure: `statusIncludesLuxReading` fails — the status JSON has no `"lux"` field (and the constructor has no `lux` param yet: compilation error `Cannot find a parameter with this name: lux`).

- [ ] **Minimal implementation (server).** Apply two edits to `app/src/main/java/com/rar/echodash/web/ConfigServer.kt`.

Edit 1 — constructor param. Replace:
```kotlin
    private val connState: () -> String,
    private val previewChime: (String, Int) -> Unit,
    private val assetReader: (String) -> ByteArray?,
) : NanoHTTPD(port) {
```
with:
```kotlin
    private val connState: () -> String,
    private val lux: () -> Int? = { null },
    private val previewChime: (String, Int) -> Unit,
    private val assetReader: (String) -> ByteArray?,
) : NanoHTTPD(port) {
```

Edit 2 — hand the provider over in `app/src/main/java/com/rar/echodash/App.kt`. Replace:
```kotlin
        connState = { ws.connectionState.value.name },
        previewChime = { tone, volume -> timerChime.playOnce(tone, volume) },
```
with:
```kotlin
        connState = { ws.connectionState.value.name },
        lux = { lastLux },
        previewChime = { tone, volume -> timerChime.playOnce(tone, volume) },
```

Edit 3 — `handleStatus`. Replace:
```kotlin
    private fun handleStatus(): Response =
        ok(buildJsonObject {
            put("configured", configured())
            put("connState", connState())
        }.toString())
```
with:
```kotlin
    private fun handleStatus(): Response =
        ok(buildJsonObject {
            put("configured", configured())
            put("connState", connState())
            put("lux", lux())            // int, or JSON null when no sensor reading yet
        }.toString())
```

- [ ] **Minimal implementation (config page).** Apply the HTML + JS edits.

HTML — in `app/src/main/assets/config/index.html`, add the Night section. Replace:
```html
        <div id="voice"></div>
      </section>
    </div>
  </main>
```
with:
```html
        <div id="voice"></div>
      </section>

      <section id="night-section" class="card-section">
        <div class="card-head">
          <span class="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 0 1 9.5 4 7 7 0 1 0 20 14.5Z"/></svg>
          </span>
          <div class="card-titles">
            <h2>Night mode</h2>
            <p>Dim clock on a black screen when the room goes dark.</p>
          </div>
        </div>
        <div id="night"></div>
      </section>
    </div>
  </main>
```

JS edit A — module state. In `app/src/main/assets/config/app.js`, replace:
```javascript
let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
```
with:
```javascript
let config = null;      // the live DashConfig model (source of truth)
let entities = [];      // [{id, name, domain, state}]
let lastStatus = null;  // most recent /api/status body (carries the live lux reading)
let statusPollStarted = false;
```

JS edit B — capture status + start the poll in `tryLoad`. Replace:
```javascript
    const sr = await api("GET", "/api/status");
    const status = sr.ok ? await sr.json() : { configured: true };
    showApp();
```
with:
```javascript
    const sr = await api("GET", "/api/status");
    const status = sr.ok ? await sr.json() : { configured: true };
    lastStatus = status;
    showApp();
```
And replace:
```javascript
    renderSetup(status.configured === false);
    render();
    setStatus("Connected", "ok");
```
with:
```javascript
    renderSetup(status.configured === false);
    render();
    startStatusPoll();
    setStatus("Connected", "ok");
```

JS edit C — add `renderNight()` to `render()`. Replace:
```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
}
```
with:
```javascript
function render() {
  renderPanels();
  renderEntities();
  renderMedia();
  renderHome();
  renderOptions();
  renderVoice();
  renderNight();
}
```

JS edit D — append the new functions before the boot section. Replace:
```javascript
// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
```
with:
```javascript
function renderNight() {
  const host = document.getElementById("night");
  clear(host);
  // Defensive defaults for configs saved before night mode existed (same pattern as the Media card).
  if (!config.night) config.night = { enabled: false, thresholdLux: 10, brightness: 0 };
  const n = config.night;
  if (typeof n.thresholdLux !== "number") n.thresholdLux = 10;
  if (typeof n.brightness !== "number") n.brightness = 0;

  const toggle = el("input"); toggle.type = "checkbox"; toggle.checked = !!n.enabled;
  toggle.setAttribute("aria-label", "Night clock enabled");
  toggle.addEventListener("change", () => n.enabled = toggle.checked);
  host.appendChild(labeledRow("Night clock", toggle));

  host.appendChild(labeledRow("Enter below (lux)",
    numberInput(n.thresholdLux, v => n.thresholdLux = Math.round(v || 0))));
  host.appendChild(labeledRow("Night brightness (%)",
    numberInput(n.brightness, v => n.brightness = Math.round(v || 0))));

  const lux = el("div", "muted"); lux.id = "night-lux";
  host.appendChild(lux);
  updateNightLux(lastStatus);

  host.appendChild(el("div", "muted",
    "When the room stays darker than the threshold for ~30 s the screen becomes a dim clock at the " +
    "night brightness. A touch or activity (music, doorbell, voice, timers) wakes it; it returns after " +
    "60 s if still dark. Threshold 1–1000 lux, brightness 0–100 % (0 = dimmest), clamped on save."));
}

function updateNightLux(status) {
  const box = document.getElementById("night-lux");
  if (!box) return;
  if (status && typeof status.lux === "number") box.textContent = "Current reading: " + status.lux + " lux";
  else box.textContent = "Current reading: no sensor";
}

// The base page fetches /api/status once at load; poll it here so the live lux reading refreshes.
function startStatusPoll() {
  if (statusPollStarted) return;
  statusPollStarted = true;
  setInterval(async () => {
    try {
      const r = await api("GET", "/api/status");
      if (r.ok) { lastStatus = await r.json(); updateNightLux(lastStatus); }
    } catch (e) { /* device may be briefly unreachable; ignore */ }
  }, 5000);
}

// ---------- boot ----------
document.getElementById("login-form").addEventListener("submit", doLogin);
```

- [ ] **Run to see it pass.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest --tests 'com.rar.echodash.web.ConfigServerTest'`
  Expected: all ConfigServerTest tests pass (including `statusIncludesLuxReading`).

- [ ] **Run the full build gate.** `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew -q :app:testDebugUnitTest :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit.**
```
git add app/src/main/java/com/rar/echodash/web/ConfigServer.kt app/src/main/java/com/rar/echodash/App.kt app/src/test/java/com/rar/echodash/web/ConfigServerTest.kt app/src/main/assets/config/index.html app/src/main/assets/config/app.js
git commit -m "feat(night): config page Night card + live lux in /api/status

ConfigServer gains a lux provider and emits \"lux\" in /api/status; the
config page adds a Night mode card (enable, entry lux, night brightness)
and polls status for the live reading.

Claude-Session: https://claude.ai/code/session_01Byw9bJ6YQsqTtGzt2aeXSi"
```

---

## Self-review

**Spec coverage (every requirement → task):**
- State machine dark-latch dwell/hysteresis, all transition rules incl. dead-band latch survival (screen-glow regression), constants, ticking coverage → Task 1 (11 tests). ✔
- `ticking` StateFlow so App knows when to tick → Task 1 (interface) + Task 5 (ticker effect). ✔
- `KioskController.setNightDim`, lastLux tracking, auto/manual reapply, HA screen_brightness stored-not-applied, runtime-only → Task 2 (tests 10–12). ✔
- `NightSettings` + `clamped()` + `DashConfig.clamped()` wiring + old-config defaults; `referencedEntityIds` unchanged → Task 3 (test 13). ✔
- `NightClockOverlay` (black, 120sp dim gray, AM/PM 28sp, minute ticker matching HomeView, consumes all touches, Crossfade, top layer in DashboardShell) → Task 4. ✔
- App wiring: AppDeps.nightMode, sensor callback onLux + lastLux, onConfig/onOverride(all four)/onUserInteraction/ticker/mirror effects, nightActive threading, ConfigServer lux provider → Task 5. ✔
- Config page Night card + `/api/status` lux + live reading → Task 6. ✔
- Screen-off interplay: no new coupling required (AndroidKioskDevice.setBrightness is already a no-op while screenOff) — no code needed; documented here. ✔
- Out of scope (HA night-state reporting, scheduling, kiosk timeout changes) — not implemented. ✔

**Placeholder scan:** no "TBD"/"similar to Task N"/"...". Every step carries complete code, and every task ends with a green full build gate — the two new `DashboardShell` params are defaulted in Task 4 so it compiles standalone, then Task 5 supplies real values. ✔

**Type consistency across tasks:** `NightModeController` signatures (Task 1) are consumed verbatim in Task 5. `setNightDim(Boolean, Int)` (Task 2) used verbatim in Task 5's mirror effect. `NightSettings(enabled, thresholdLux, brightness)` (Task 3) read as `config.night.*` in Tasks 5/6 and the JS card. `DashboardShell(nightActive: Boolean, onNightWake: () -> Unit)` (Task 4) matched by Task 5's call. `ConfigServer(lux: () -> Int?)` and its `lux = { lastLux }` call-site argument both land in Task 6 (Task 5 only creates `lastLux`), so every task's build gate is green in order. Resolved override expression uses the exact fields `voiceOverlayState.phase`/`VoiceOverlayPhase.HIDDEN`/`timersState.chips`/`timersState.alert`. ✔
