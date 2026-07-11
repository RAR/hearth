package com.rar.echodash.vaca

import com.rar.echodash.ui.KioskUiState

/**
 * KioskDevice backed by Compose UI state (overlays) plus window-level hooks
 * that exist only while MainActivity is alive. All calls arrive on the main
 * thread (KioskController runs on Dispatchers.Main.immediate).
 */
class AndroidKioskDevice(
    private val ui: KioskUiState,
    private val onRefresh: () -> Unit,
) : KioskDevice {

    interface WindowHooks {
        /** 0-100 maps onto window brightness (floored at 0.01); negative restores system default. */
        fun setWindowBrightness(percent: Int)
        fun setKeepScreenOn(on: Boolean)
    }

    private var hooks: WindowHooks? = null
    private var lastBrightness: Int = -1
    private var keepOn = true

    fun attach(h: WindowHooks) {
        hooks = h
        h.setKeepScreenOn(keepOn)
        if (!ui.screenOff) h.setWindowBrightness(lastBrightness)
    }

    fun detach(h: WindowHooks) {
        if (hooks === h) hooks = null
    }

    override fun setScreenOn(on: Boolean) {
        ui.screenOff = !on
        hooks?.setWindowBrightness(if (on) lastBrightness else 0)
    }

    override fun setBrightness(percent: Int) {
        lastBrightness = percent
        if (!ui.screenOff) hooks?.setWindowBrightness(percent)
    }

    override fun setKeepScreenOn(alwaysOn: Boolean) {
        keepOn = alwaysOn
        hooks?.setKeepScreenOn(alwaysOn)
    }

    override fun setScreensaver(active: Boolean) {
        ui.screensaver = active
    }

    override fun setDarkMode(dark: Boolean) {
        ui.darkMode = dark
    }

    override fun showToast(message: String) {
        ui.toast = message
        ui.toastKey++
    }

    override fun refresh() = onRefresh()
}
