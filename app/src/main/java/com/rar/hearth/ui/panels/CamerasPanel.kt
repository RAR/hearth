package com.rar.hearth.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.hearth.camera.CameraFeed
import com.rar.hearth.camera.StreamResolver
import com.rar.hearth.config.CameraConfig

/**
 * On-demand camera viewer: a fixed-width selector column on the left, the live feed filling the
 * rest. First camera auto-selected on entry; muted by default with a corner unmute toggle.
 * Switching cameras (via `key`) disposes the old player before starting the next; leaving the
 * panel disposes CameraFeed and releases the player.
 */
@Composable
fun CamerasPanel(cameras: List<CameraConfig>, resolver: StreamResolver) {
    if (cameras.isEmpty()) {
        EmptyHint("Add a camera in the web config")
        return
    }
    var selected by remember(cameras) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(true) }
    val current = cameras[selected.coerceIn(0, cameras.lastIndex)]

    Row(
        Modifier.fillMaxSize().background(Color(0xFF12141C)).padding(end = 84.dp),
    ) {
        Column(
            Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cameras.forEachIndexed { i, cam ->
                val isSel = i == selected
                Text(
                    cam.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) Color(0xFF3A6EA5) else Color(0xFF232733))
                        .clickable { selected = i }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            key(current.name) {
                CameraFeed(current, resolver, muted, Modifier.fillMaxSize())
            }
            IconButton(
                onClick = { muted = !muted },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = Color.White,
                )
            }
        }
    }
}
