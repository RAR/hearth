package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.vaca.MediaUiState

@Composable
fun MediaPanel(
    mediaUi: MediaUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("On this device", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Text(mediaUi.nowPlaying, color = Color.White, fontSize = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TransportButton(if (mediaUi.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                    if (mediaUi.playing) onPause() else onPlay()
                }
                TransportButton(Icons.Outlined.Stop) { onStop() }
            }
            var slider by remember(mediaUi.volume) { mutableFloatStateOf(mediaUi.volume.toFloat()) }
            Text("Volume ${slider.toInt()}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onVolume(slider.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
