package com.rar.echodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Compose-observable kiosk state driven by AndroidKioskDevice. */
class KioskUiState {
    var screenOff by mutableStateOf(false)
    var screensaver by mutableStateOf(false)
    var darkMode by mutableStateOf(true)
    var toast by mutableStateOf<String?>(null)
    var toastKey by mutableIntStateOf(0)
}

/** Render order: bright-mode scrim < toast < screensaver < screen-off. */
@Composable
fun KioskOverlays(ui: KioskUiState, onWakeTouch: () -> Unit) {
    if (!ui.darkMode) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
    }
    ui.toast?.let { msg ->
        LaunchedEffect(ui.toastKey) {
            delay(4_000)
            ui.toast = null
        }
        Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xCC222222)) {
                Text(
                    msg,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
        }
    }
    if (ui.screensaver && !ui.screenOff) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .pointerInput(Unit) { detectTapGestures { onWakeTouch() } },
        )
    }
    if (ui.screenOff) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onWakeTouch() } },
        )
    }
}
