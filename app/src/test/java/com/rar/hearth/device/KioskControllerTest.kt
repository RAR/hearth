package com.rar.hearth.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.rar.hearth.device.KioskController.Companion.AUTO_SETTLE_MS
import com.rar.hearth.device.KioskController.Companion.RAMP_DOWN_MS_PER_PCT
import com.rar.hearth.device.KioskController.Companion.RAMP_STEP_MS
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KioskControllerTest {

    private class FakeDevice : KioskDevice {
        val calls = mutableListOf<String>()
        override fun setScreenOn(on: Boolean) { calls += "screen:$on" }
        override fun setBrightness(percent: Int) { calls += "brightness:$percent" }
        override fun setKeepScreenOn(alwaysOn: Boolean) { calls += "keepOn:$alwaysOn" }
        override fun setScreensaver(active: Boolean) { calls += "saver:$active" }
        override fun setDarkMode(dark: Boolean) { calls += "dark:$dark" }
        override fun showToast(message: String) { calls += "toast:$message" }
        override fun refresh() { calls += "refresh" }
    }

    private fun settings(jsonText: String): JsonObject =
        Json.parseToJsonElement(jsonText).jsonObject

    /** Run past any pending brightness settle plus a full-range ramp. */
    private fun TestScope.finishBrightness() {
        advanceTimeBy(AUTO_SETTLE_MS + 100 * RAMP_DOWN_MS_PER_PCT + 1_000)
        runCurrent()
    }

    @Test
    fun appliesScreenSettingsToDevice() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings(
            """{"screen_on":false,"dark_mode":false,"screen_saver":true,"screen_always_on":false}"""))
        assertTrue(device.calls.contains("screen:false"))
        assertTrue(device.calls.contains("dark:false"))
        assertTrue(device.calls.contains("saver:true"))
        assertTrue(device.calls.contains("keepOn:false"))
        kiosk.cancelTimers()
    }

    @Test
    fun manualBrightnessOnlyAppliesWhenAutoBrightnessOff() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_brightness":30}"""))
        assertTrue("auto on: no direct brightness", device.calls.none { it.startsWith("brightness:") })
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))
        assertTrue(device.calls.contains("brightness:30"))
        kiosk.cancelTimers()
    }

    @Test
    fun autoBrightnessFollowsLightLevel() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(0f)                             // first sample: applied at once
        assertTrue(device.calls.contains("brightness:10"))
        kiosk.onLightLevel(400f)
        finishBrightness()
        assertTrue(device.calls.contains("brightness:100"))
        device.calls.clear()
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))
        device.calls.clear()
        kiosk.onLightLevel(400f)
        assertTrue("manual mode ignores lux", device.calls.none { it.startsWith("brightness:") })
        kiosk.cancelTimers()
    }

    @Test
    fun screenTimeoutSleepsScreenWhenNotAlwaysOn() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_always_on":false,"screen_timeout":15}"""))
        device.calls.clear()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(device.calls.contains("screen:false"))
        // interaction wakes it again
        kiosk.onUserInteraction()
        assertTrue(device.calls.contains("screen:true"))
        kiosk.cancelTimers()
    }

    @Test
    fun alwaysOnPreventsTimeout() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_timeout":15}"""))
        device.calls.clear()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(device.calls.none { it == "screen:false" })
        kiosk.cancelTimers()
    }

    @Test
    fun userInteractionClearsScreensaverAndSendsFeedback() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        var feedback: JsonObject? = null
        kiosk.sendFeedback = { feedback = it }
        kiosk.applySettings(settings("""{"screen_saver":true}"""))
        device.calls.clear()
        kiosk.onUserInteraction()
        assertTrue(device.calls.contains("saver:false"))
        assertEquals(false, feedback!!["screen_saver"]!!.jsonPrimitive.boolean)
        kiosk.cancelTimers()
    }

    @Test
    fun persistsAndRestoresState() = runTest {
        var persisted: String? = null
        val kiosk = KioskController(this, FakeDevice(), persist = { persisted = it })
        kiosk.applySettings(settings("""{"screen_brightness":25,"dark_mode":false}"""))
        kiosk.cancelTimers()

        val device2 = FakeDevice()
        val restored = KioskController(this, device2, restoredJson = persisted)
        assertEquals(25, restored.currentSettings()["screen_brightness"]!!.jsonPrimitive.int)
        assertEquals(false, restored.currentSettings()["dark_mode"]!!.jsonPrimitive.boolean)
        restored.pushToDevice()
        assertTrue(device2.calls.contains("dark:false"))
        restored.cancelTimers()
    }

    @Test
    fun ignoresUnsupportedAndMalformedKeys() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        var feedbackCount = 0
        kiosk.sendFeedback = { feedbackCount++ }
        kiosk.applySettings(settings(
            """{"wake_word":"ok_nabu","mic_gain":5,"ha_url":"http://x","screen_on":"maybe"}"""))
        assertEquals("nothing supported changed -> no feedback", 0, feedbackCount)
        assertTrue(device.calls.isEmpty())
        kiosk.cancelTimers()
    }

    @Test
    fun feedbackContainsAllSupportedKeys() = runTest {
        val kiosk = KioskController(this, FakeDevice())
        var feedback: JsonObject? = null
        kiosk.sendFeedback = { feedback = it }
        kiosk.applySettings(settings("""{"screen_brightness":80}"""))
        val fb = feedback!!
        for (key in listOf("screen_on", "screen_brightness", "screen_auto_brightness",
            "screen_always_on", "screen_saver", "dark_mode", "screen_timeout")) {
            assertTrue("missing $key", fb.containsKey(key))
        }
        assertEquals(80, fb["screen_brightness"]!!.jsonPrimitive.int)
        kiosk.cancelTimers()
    }

    @Test
    fun actionsMapToDevice() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.handleAction("screen-sleep", null)
        assertTrue(device.calls.contains("screen:false"))
        kiosk.handleAction("screen-wake", null)
        assertTrue(device.calls.contains("screen:true"))
        kiosk.handleAction("wake", null)
        kiosk.handleAction("refresh", null)
        assertTrue(device.calls.contains("refresh"))
        kiosk.handleAction("toast-message", JsonPrimitive("hello"))
        assertTrue(device.calls.contains("toast:hello"))
        kiosk.handleAction("toast-message",
            Json.parseToJsonElement("""{"message":"dinner"}"""))
        assertTrue(device.calls.contains("toast:dinner"))
        kiosk.handleAction("update-custom-files", null) // ignored, no crash
        kiosk.cancelTimers()
    }

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
        kiosk.setNightDim(true, 0)                         // ramps down to 0
        finishBrightness()
        device.calls.clear()
        kiosk.setNightDim(false, 0)
        finishBrightness()
        assertTrue("auto reapplies formula from lastLux", device.calls.contains("brightness:100"))
        kiosk.cancelTimers()

        // manual: reapplies the stored manual value
        val device2 = FakeDevice()
        val kiosk2 = KioskController(this, device2)
        kiosk2.applySettings(settings("""{"screen_auto_brightness":false,"screen_brightness":35}"""))
        kiosk2.setNightDim(true, 0)                        // ramps down to 0
        finishBrightness()
        device2.calls.clear()
        kiosk2.setNightDim(false, 0)
        finishBrightness()
        assertTrue("manual reapplies stored value", device2.calls.contains("brightness:35"))
        kiosk2.cancelTimers()
    }

    @Test
    fun haBrightnessDuringNightDimStoresWithoutApplyingThenAppliesOnClear() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))  // manual mode
        kiosk.setNightDim(true, 0)                         // ramps down to 0
        finishBrightness()
        device.calls.clear()
        kiosk.applySettings(settings("""{"screen_brightness":40}"""))          // stored, NOT applied
        assertTrue("no brightness applied while night-dim", device.calls.none { it.startsWith("brightness:") })
        kiosk.setNightDim(false, 0)                        // manual -> stored 40 applied
        finishBrightness()
        assertTrue(device.calls.contains("brightness:40"))
        kiosk.cancelTimers()
    }

    @Test
    fun lastLuxTracksWhileNightDimSuppressed() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)          // auto-brightness on
        kiosk.setNightDim(true, 0)
        kiosk.onLightLevel(400f)                           // suppressed, but must still record lastLux
        device.calls.clear()
        kiosk.setNightDim(false, 0)
        finishBrightness()
        assertTrue("clear reapplies auto formula from lux seen during suppression",
            device.calls.contains("brightness:100"))
        kiosk.cancelTimers()
    }

    @Test
    fun pushToDeviceRepinsNightDim() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_auto_brightness":false,"screen_brightness":50}"""))
        kiosk.setNightDim(true, 0)
        device.calls.clear()
        kiosk.pushToDevice()                               // window re-attach must not lift the pin
        assertTrue("re-attach re-pins the night value", device.calls.contains("brightness:0"))
        assertTrue("manual brightness must not override the pin",
            !device.calls.contains("brightness:50"))
        kiosk.cancelTimers()
    }

    @Test
    fun clearingNightDimWhenNeverPinnedTouchesNothing() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)          // auto-brightness on, no lux seen yet
        device.calls.clear()
        kiosk.setNightDim(false, 0)                        // first-composition mirror fires this
        assertTrue("must not force autoPercent(0)=10% at startup",
            device.calls.none { it.startsWith("brightness:") })
        kiosk.cancelTimers()
    }

    private fun brightnessValues(device: FakeDevice): List<Int> =
        device.calls.filter { it.startsWith("brightness:") }.map { it.removePrefix("brightness:").toInt() }

    @Test
    fun dimmingWaitsOutTheSettleWindow() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(400f)                           // first sample: immediate 100
        device.calls.clear()
        kiosk.onLightLevel(0f)                             // dark: must not touch the screen yet
        advanceTimeBy(AUTO_SETTLE_MS - 500)
        runCurrent()
        assertTrue("dimming must wait out the settle window",
            device.calls.none { it.startsWith("brightness:") })
        finishBrightness()
        assertEquals(10, brightnessValues(device).last())
        kiosk.cancelTimers()
    }

    @Test
    fun aTransientShadowNeverReachesTheBacklight() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(400f)                           // first sample: immediate 100
        device.calls.clear()
        kiosk.onLightLevel(0f)                             // someone walks past the sensor
        advanceTimeBy(1_000)
        runCurrent()
        kiosk.onLightLevel(400f)                           // ...and is gone again
        finishBrightness()
        assertTrue("a shadow inside the window must not move the backlight",
            device.calls.none { it.startsWith("brightness:") })
        kiosk.cancelTimers()
    }

    @Test
    fun brighteningIsNotDelayed() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(0f)                             // first sample: immediate 10
        device.calls.clear()
        kiosk.onLightLevel(400f)                           // lamp switched on
        advanceTimeBy(RAMP_STEP_MS)
        runCurrent()
        assertTrue("brightening starts at once, no settle", brightnessValues(device).isNotEmpty())
        kiosk.cancelTimers()
    }

    @Test
    fun brightnessRampsInStepsRatherThanJumping() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.onLightLevel(400f)                           // immediate 100
        device.calls.clear()
        kiosk.setNightDim(true, 0)                         // night entry: fade 100 -> 0
        finishBrightness()
        val values = brightnessValues(device)
        assertTrue("a fade is many steps, not one jump: got $values", values.size > 10)
        assertEquals("ends exactly on target", 0, values.last())
        assertTrue("monotonically decreasing: $values",
            values.zipWithNext().all { (a, b) -> b <= a })
        kiosk.cancelTimers()
    }

    @Test
    fun explicitBrightnessStillLandsImmediately() = runTest {
        val device = FakeDevice()
        val kiosk = KioskController(this, device)
        kiosk.applySettings(settings("""{"screen_auto_brightness":false}"""))
        device.calls.clear()
        kiosk.applySettings(settings("""{"screen_brightness":80}"""))
        assertTrue("slider drags must not fade", device.calls.contains("brightness:80"))
        kiosk.cancelTimers()
    }
}
