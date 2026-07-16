package com.rar.echodash.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.draw.alpha
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

/**
 * Home-screen now-playing backdrop: blurred art (or dusk gradient) full-screen under a dark scrim,
 * a sharp art card on the right, and title/artist-album/transport/volume on the left-center. Sits
 * BELOW the clock/pills/overlays (those are separate layers drawn above in HomeView). The transport
 * omits stop (the panel keeps stop); prev/next show only when [NowPlayingState.canSkip].
 */
@Composable
fun NowPlayingHome(
    state: NowPlayingState,
    art: ArtBitmaps?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            Image(art.blurred, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            DuskBackground()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Sharp art card, right side, clear of the pills row.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
                .size(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF11151F)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(art.sharp, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.MusicNote, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(96.dp))
            }
        }

        // Left-center: metadata + transport + volume, held clear of the art card.
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, end = 440.dp)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                state.title ?: "Playing",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(state.artist, state.album).joinToString(" — ")
            if (sub.isNotBlank()) {
                Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Fixed group width = the full three-button transport row, so the volume bar sits
            // centered under the buttons whether or not prev/next are showing.
            Column(
                Modifier.width(224.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.canSkip) NpTransportButton(Icons.Outlined.SkipPrevious) { onPrev() }
                    NpTransportButton(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) {
                        if (state.playing) onPause() else onPlay()
                    }
                    if (state.canSkip) NpTransportButton(Icons.Outlined.SkipNext) { onNext() }
                }
                var slider by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
                // While Music Assistant has this player muted, show it: muted-speaker glyph +
                // dimmed slider -- otherwise the takeover reads "playing at volume N" while
                // silent. Display only (mute/unmute lives in MA); the slider still sets the
                // underlying group volume, which applies once MA unmutes.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.muted) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                    }
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = { onVolume(slider.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).alpha(if (state.muted) 0.35f else 1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NpTransportButton(icon: ImageVector, onClick: () -> Unit) {
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
