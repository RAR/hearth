package com.rar.hearth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rar.hearth.vaca.AndroidKioskDevice

class MainActivity : ComponentActivity() {
    private lateinit var deps: AppDeps
    private var attachedHooks: AndroidKioskDevice.WindowHooks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // When a bottom swipe transiently reveals the system bars, draw them over the dashboard
        // as floating buttons instead of opaque black bands: transparent bar colors, and no
        // system-enforced contrast scrim behind the 3-button nav (API 29+; this device is 30).
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        deps = (application as HearthApplication).deps
        if (deps.configStore.config.value.voice.enabled &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQ)
        }
        deps.startConfigServer()
        val hooks = object : AndroidKioskDevice.WindowHooks {
            override fun setWindowBrightness(percent: Int) {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (percent < 0) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        // Floor 0.001 (not 0.0 = BRIGHTNESS_OVERRIDE_OFF): percent 0 lands on
                        // backlight step 1/255, the panel's dimmest still-lit state; percent 1
                        // (0.01 ~ step 4) remains available as a brighter night floor.
                        (percent.coerceIn(0, 100) / 100f).coerceAtLeast(0.001f)
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
        setContent { HearthApp(deps) }
    }

    override fun onDestroy() {
        attachedHooks?.let { deps.kioskDevice.detach(it) }
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        deps.kiosk.onUserInteraction()
    }

    private companion object { const val RECORD_AUDIO_REQ = 4201 }
}
