package com.rar.echodash.vaca

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        kiosk.onLightLevel(0f)
        kiosk.onLightLevel(400f)
        assertTrue(device.calls.contains("brightness:10"))
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
}
