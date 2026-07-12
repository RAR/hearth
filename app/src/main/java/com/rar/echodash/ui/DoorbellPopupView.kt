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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.camera.CameraFeed
import com.rar.echodash.camera.DoorbellPopup
import com.rar.echodash.camera.StreamResolver
import com.rar.echodash.config.CameraConfig
import kotlinx.coroutines.delay

/**
 * Full-screen doorbell overlay above everything (including the rail). Shows the mapped camera's live
 * feed unmuted, labeled with a countdown. Tap anywhere to dismiss; auto-dismiss at [DoorbellPopup.untilMs].
 * A new [popup] value (re-trigger extends, other doorbell switches) restarts the countdown. If the
 * stream is dead, CameraFeed's own error overlay shows underneath — the ring notice is never lost.
 */
@Composable
fun DoorbellPopupView(
    popup: DoorbellPopup,
    camera: CameraConfig?,
    resolver: StreamResolver,
    onDismiss: () -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    var remaining by remember(popup) {
        mutableIntStateOf(((popup.untilMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(popup) {
        while (true) {
            val secs = ((popup.untilMs - System.currentTimeMillis()) / 1000L).toInt()
            remaining = secs.coerceAtLeast(0)
            if (secs <= 0) {
                latestDismiss()
                break
            }
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(popup) { detectTapGestures { latestDismiss() } },
    ) {
        if (camera != null) {
            key(popup.cameraName) {
                CameraFeed(camera, resolver, muted = false, modifier = Modifier.fillMaxSize())
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF12141C)), contentAlignment = Alignment.Center) {
                Text("${popup.cameraName}\nstream unavailable", color = Color.White)
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC000000),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Text(
                "${popup.cameraName}  ·  ${remaining}s",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
