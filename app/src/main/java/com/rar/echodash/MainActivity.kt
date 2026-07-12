package com.rar.echodash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rar.echodash.vaca.AndroidKioskDevice

class MainActivity : ComponentActivity() {
    private lateinit var deps: AppDeps
    private var attachedHooks: AndroidKioskDevice.WindowHooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        deps = (application as EchoDashApplication).deps
        deps.startConfigServer()
        val hooks = object : AndroidKioskDevice.WindowHooks {
            override fun setWindowBrightness(percent: Int) {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (percent < 0) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        (percent.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f)
                    }
                }
            }

            override fun setKeepScreenOn(on: Boolean) {
                if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        attachedHooks = hooks
        deps.kioskDevice.attach(hooks)
        deps.kiosk.pushToDevice()
        setContent { EchoDashApp(deps) }
    }

    override fun onDestroy() {
        attachedHooks?.let { deps.kioskDevice.detach(it) }
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        deps.kiosk.onUserInteraction()
    }
}
