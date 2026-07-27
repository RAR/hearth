package com.rar.hearth.device

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
import kotlin.math.abs

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
    private var nightDimPercent = 0
    private var lastLux = 0f
    // Brightness the device is actually showing (tracks the ramp, not the target). -1 = never set.
    private var appliedBrightness = -1
    private var rampJob: Job? = null
    private var settleJob: Job? = null

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
        if (nightDim) applyNow(nightDimPercent) else if (!autoBrightness) applyNow(brightness)
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
                // Explicit brightness changes land immediately: someone is dragging a slider and a
                // fade would read as lag. Only the ambient/night paths below are smoothed.
                "screen_brightness" -> value.asInt()?.let {
                    brightness = it.coerceIn(0, 100)
                    if (!autoBrightness && !nightDim) applyNow(brightness)
                    changed = true
                }
                "screen_auto_brightness" -> value.asBoolean()?.let {
                    autoBrightness = it
                    if (!it && !nightDim) applyNow(brightness)
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
        val target = autoPercent(lux)
        when {
            // Nothing on screen yet (first sample after boot): don't make startup wait.
            appliedBrightness < 0 -> applyNow(target)
            // Brightening is a response to something the room just did - a lamp switched on,
            // someone walked in. Follow it at once so the screen never feels asleep.
            target > appliedBrightness -> rampTo(target)
            // Dimming waits: a passing shadow or a brief flick settles back inside the window
            // and never reaches the backlight at all.
            else -> settleThenApplyAuto()
        }
    }

    /** Night clock dimming: while active, pins brightness to [percent] and ignores auto-brightness
     *  lux updates and HA screen_brightness changes; clearing restores the normal auto/manual value.
     *  Ramped both ways - NightModeController's own dwell already supplies the "wait a moment". */
    fun setNightDim(active: Boolean, percent: Int) {
        if (!active && !nightDim) return   // clearing when never pinned: don't disturb brightness
        nightDim = active
        if (active) {
            nightDimPercent = percent
            rampTo(percent)
        } else if (autoBrightness) {
            rampTo(autoPercent(lastLux))
        } else {
            rampTo(brightness)
        }
    }

    private fun autoPercent(lux: Float): Int = (10 + (lux.coerceIn(0f, 400f) / 400f) * 90).toInt()

    /**
     * Trailing-edge settle for downward ambient changes. The first dark sample starts the clock;
     * later samples fold into the same window, so the backlight moves at most once per window and
     * always to the freshest reading. A window that ends bright resolves to no change at all.
     */
    private fun settleThenApplyAuto() {
        if (settleJob?.isActive == true) return
        settleJob = scope.launch {
            delay(AUTO_SETTLE_MS)
            settleJob = null
            if (!nightDim && autoBrightness) rampTo(autoPercent(lastLux))
        }
    }

    /** Push [percent] at once, abandoning any pending settle or in-flight ramp. */
    private fun applyNow(percent: Int) {
        settleJob?.cancel(); settleJob = null
        rampJob?.cancel(); rampJob = null
        appliedBrightness = percent
        device.setBrightness(percent)
    }

    /**
     * Fade from the current brightness to [percent] in RAMP_STEP_MS steps. Dimming is deliberately
     * the slower direction: it is the one that reads as the screen lurching. Nothing to fade from
     * (first ever push) falls through to an immediate set.
     */
    private fun rampTo(percent: Int) {
        settleJob?.cancel(); settleJob = null
        if (appliedBrightness < 0) { applyNow(percent); return }
        val from = appliedBrightness
        val delta = percent - from
        if (delta == 0) { rampJob?.cancel(); rampJob = null; return }
        rampJob?.cancel()
        val perPct = if (delta < 0) RAMP_DOWN_MS_PER_PCT else RAMP_UP_MS_PER_PCT
        val steps = maxOf(1, (abs(delta) * perPct / RAMP_STEP_MS).toInt())
        rampJob = scope.launch {
            for (i in 1..steps) {
                delay(RAMP_STEP_MS)
                // Integer lerp: monotone, and exact at i == steps whatever the rounding.
                appliedBrightness = from + delta * i / steps
                device.setBrightness(appliedBrightness)
            }
            rampJob = null
        }
    }

    fun currentSettings(): JsonObject = buildJsonObject {
        put("screen_on", screenOn)
        put("screen_brightness", brightness)
        put("screen_auto_brightness", autoBrightness)
        put("screen_always_on", alwaysOn)
        put("screen_saver", screensaver)
        put("dark_mode", darkMode)
        put("screen_timeout", timeoutSeconds)
    }

    /** Tests call this so runTest doesn't hang on an armed timeout, settle, or brightness ramp. */
    fun cancelTimers() {
        timeoutJob?.cancel()
        timeoutJob = null
        settleJob?.cancel()
        settleJob = null
        rampJob?.cancel()
        rampJob = null
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

    companion object {
        /** How long a downward ambient reading must stand before it reaches the backlight. */
        const val AUTO_SETTLE_MS = 4_000L
        /** Ramp granularity. 50 ms is smooth to the eye and cheap enough for the Echo's window updates. */
        const val RAMP_STEP_MS = 50L
        /** ~40 %/s down: slow enough to read as a fade rather than a step. */
        const val RAMP_DOWN_MS_PER_PCT = 25L
        /** ~125 %/s up: still a fade, but fast enough that a touch or a lamp feels answered. */
        const val RAMP_UP_MS_PER_PCT = 8L
    }
}
