package com.rar.echodash.vaca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Device-side operations. Implemented by AndroidKioskDevice (window + Compose UI state). */
interface KioskDevice {
    fun setScreenOn(on: Boolean)
    fun setBrightness(percent: Int)
    fun setKeepScreenOn(alwaysOn: Boolean)
    fun setScreensaver(active: Boolean)
    fun setDarkMode(dark: Boolean)
    fun showToast(message: String)
    fun refresh()
}

/**
 * Maps VACA settings/actions onto the kiosk. Confined to [scope]'s dispatcher —
 * callers must hop into that scope. Defaults mirror the integration's entities.
 */
class KioskController(
    private val scope: CoroutineScope,
    private val device: KioskDevice,
    private val persist: (String) -> Unit = {},
    restoredJson: String? = null,
) {
    private var screenOn = true
    private var brightness = 50
    private var autoBrightness = true
    private var alwaysOn = true
    private var screensaver = false
    private var darkMode = true
    private var timeoutSeconds = 60
    private var timeoutJob: Job? = null
    private var nightDim = false
    private var lastLux = 0f

    /** Pushes settings feedback to HA; set by app wiring. */
    var sendFeedback: (JsonObject) -> Unit = {}

    init {
        restoredJson
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { readInto(it) }
    }

    /** Re-apply state to the device (call after the window bridge attaches, and after restore). */
    fun pushToDevice() {
        device.setScreenOn(screenOn)
        if (!autoBrightness) device.setBrightness(brightness)
        device.setKeepScreenOn(alwaysOn)
        device.setScreensaver(screensaver)
        device.setDarkMode(darkMode)
        armTimeout()
    }

    fun applySettings(settings: JsonObject) {
        var changed = false
        settings.forEach { (key, value) ->
            when (key) {
                "screen_on" -> value.asBoolean()?.let {
                    screenOn = it; device.setScreenOn(it); changed = true
                }
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
                "screen_always_on" -> value.asBoolean()?.let {
                    alwaysOn = it; device.setKeepScreenOn(it); changed = true
                }
                "screen_saver" -> value.asBoolean()?.let {
                    screensaver = it; device.setScreensaver(it); changed = true
                }
                "dark_mode" -> value.asBoolean()?.let {
                    darkMode = it; device.setDarkMode(it); changed = true
                }
                "screen_timeout" -> value.asInt()?.let {
                    timeoutSeconds = it; changed = true
                }
                else -> Unit // voice/browser/media keys: handled elsewhere or ignored
            }
        }
        if (changed) {
            armTimeout()
            persistAndFeedback()
        }
    }

    fun handleAction(action: String, payload: JsonElement?) {
        when (action) {
            "screen-wake", "wake" -> setScreen(true)
            "screen-sleep" -> setScreen(false)
            "refresh" -> device.refresh()
            "toast-message" -> device.showToast(toastText(payload))
            else -> Unit
        }
    }

    /** Call on any user touch: wakes the screen, clears the screensaver, re-arms the timeout. */
    fun onUserInteraction() {
        if (screensaver) {
            screensaver = false
            device.setScreensaver(false)
            persistAndFeedback()
        }
        if (!screenOn) setScreen(true) else armTimeout()
    }

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

    fun currentSettings(): JsonObject = buildJsonObject {
        put("screen_on", screenOn)
        put("screen_brightness", brightness)
        put("screen_auto_brightness", autoBrightness)
        put("screen_always_on", alwaysOn)
        put("screen_saver", screensaver)
        put("dark_mode", darkMode)
        put("screen_timeout", timeoutSeconds)
    }

    /** Tests call this so runTest doesn't hang on an armed timeout. */
    fun cancelTimers() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun setScreen(on: Boolean) {
        screenOn = on
        device.setScreenOn(on)
        armTimeout()
        persistAndFeedback()
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
        if (alwaysOn || !screenOn || timeoutSeconds <= 0) return
        timeoutJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            setScreen(false)
        }
    }

    private fun persistAndFeedback() {
        val current = currentSettings()
        persist(current.toString())
        sendFeedback(current)
    }

    private fun readInto(saved: JsonObject) {
        saved["screen_on"]?.asBoolean()?.let { screenOn = it }
        saved["screen_brightness"]?.asInt()?.let { brightness = it }
        saved["screen_auto_brightness"]?.asBoolean()?.let { autoBrightness = it }
        saved["screen_always_on"]?.asBoolean()?.let { alwaysOn = it }
        saved["screen_saver"]?.asBoolean()?.let { screensaver = it }
        saved["dark_mode"]?.asBoolean()?.let { darkMode = it }
        saved["screen_timeout"]?.asInt()?.let { timeoutSeconds = it }
    }

    private fun toastText(payload: JsonElement?): String = when (payload) {
        is JsonPrimitive -> payload.contentOrNull ?: ""
        is JsonObject ->
            (payload["message"] as? JsonPrimitive)?.contentOrNull
                ?: (payload["text"] as? JsonPrimitive)?.contentOrNull
                ?: payload.toString()
        else -> ""
    }

    private fun JsonElement.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
}
