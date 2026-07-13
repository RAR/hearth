package com.rar.echodash.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState

@Composable
fun MediaPanel(
    state: NowPlayingState,
    art: ArtBitmaps?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("On this device", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            if (!state.active) {
                Text("Nothing playing", color = Color.White, fontSize = 22.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF11151F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            Image(art.sharp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            state.title ?: "Playing",
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOfNotNull(state.artist, state.album).joinToString(" — ")
                        if (sub.isNotBlank()) {
                            Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.canSkip) TransportButton(Icons.Outlined.SkipPrevious) { onPrev() }
                    TransportButton(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                        if (state.playing) onPause() else onPlay()
                    }
                    TransportButton(Icons.Outlined.Stop) { onStop() }
                    if (state.canSkip) TransportButton(Icons.Outlined.SkipNext) { onNext() }
                }
            }
            var slider by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
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
