package com.rar.echodash.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
import com.rar.echodash.media.formatTrackTime
import com.rar.echodash.ui.model.takeoverLayout
import kotlinx.coroutines.delay

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
    onSeek: (Long) -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two growable regions dividing the width — the one true proportional split in the design.
        val layout = takeoverLayout(maxWidth.value, maxHeight.value)
        if (art != null) {
            Image(art.blurred, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            DuskBackground()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Browse the MA library (jumps to the MEDIA view). TopEnd: TopStart holds the compact
        // clock HomeView draws above this layer. 48 dp (not the transport 64) so it clears the
        // art card's top edge on the smallest canvas (787×394, art at its height-limited 360dp);
        // taller screens only add clearance.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp)) {
            NpTransportButton(Icons.AutoMirrored.Outlined.QueueMusic, size = 48.dp, iconSize = 24.dp) {
                onBrowse()
            }
        }

        // Sharp art card, right side, clear of the pills row.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
                .size(layout.artSizeDp.dp)
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

        // Left-center: metadata + progress + transport + volume, held clear of the art card.
        Column(
            Modifier
                .align(Alignment.CenterStart)
                // No end pad: metaMaxWidthDp already reserves the art width + 32dp clearance, so the
                // fixed-width column can't reach the art card at any screen size (Show 5:
                // 787 − 360 − 128 = 299). width() (not widthIn) fills the whole gap so the transport
                // group can center in it rather than hug the wrapped text.
                .padding(start = 48.dp)
                .width(layout.metaMaxWidthDp.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Single line that bounce-scrolls when it overflows, instead of wrapping/ellipsing.
            BounceMarqueeText(
                state.title ?: "Playing",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
            val sub = listOfNotNull(state.artist, state.album).joinToString(" — ")
            if (sub.isNotBlank()) {
                BounceMarqueeText(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp,
                    fontWeight = FontWeight.Normal)
            }
            // Progress + optional seek. Only when a track length is known (radio/ICY has none).
            if (state.durationMs > 0) {
                TrackProgressRow(state = state, onSeek = onSeek)
            }
            // Fixed group width = the full three-button transport row, so the volume bar sits
            // centered under the buttons whether or not prev/next are showing. Centered in the
            // full-width meta column above.
            Column(
                Modifier.width(224.dp).align(Alignment.CenterHorizontally),
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
private fun NpTransportButton(
    icon: ImageVector,
    size: Dp = 64.dp,
    iconSize: Dp = 30.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/**
 * One-line text that bounce-scrolls back and forth when it overflows its width, and sits perfectly
 * static when it fits. `horizontalScroll(enabled = false)` measures the text against an unbounded
 * width, so [rememberScrollState].maxValue becomes the overflow in pixels (0 when it fits, i.e. no
 * animation). The gesture is disabled — this is display-only motion, not a user scroller.
 */
@Composable
private fun BounceMarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
) {
    val scrollState = rememberScrollState()
    Text(
        text,
        color = color, fontSize = fontSize, fontWeight = fontWeight,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        modifier = Modifier.horizontalScroll(scrollState, enabled = false),
    )
    // Keyed on text AND maxValue: maxValue is Int.MAX_VALUE/0 until the unbounded measure settles,
    // then flips to the real overflow, restarting this effect with a truthful value. A fitting text
    // (maxValue 0) returns early, so nothing animates and no idle coroutine spins.
    LaunchedEffect(text, scrollState.maxValue) {
        val max = scrollState.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@LaunchedEffect
        // ~40 dp/s: max px * 25 ms/px (approx at density 1), floored so short overflows still glide.
        val travelMs = (max * 25).coerceAtLeast(400)
        while (true) {
            delay(1800) // dwell at the start
            scrollState.animateScrollTo(max, tween(durationMillis = travelMs, easing = LinearEasing))
            delay(1800) // dwell at the end
            scrollState.animateScrollTo(0, tween(durationMillis = travelMs, easing = LinearEasing))
        }
    }
}

/**
 * Position / progress bar / duration row for the takeover. Only drawn when [NowPlayingState.durationMs]
 * is known. A 1s ticker advances the extrapolated position while playing (frozen when paused). When
 * [NowPlayingState.canSeek] (companion media_player with the SEEK feature — never SendSpin, which has
 * no seek command) the bar is a draggable [Slider]; otherwise it is a thin non-interactive rail so the
 * thumb never falsely reads as draggable.
 */
@Composable
private fun TrackProgressRow(state: NowPlayingState, onSeek: (Long) -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val displayed = state.displayedPositionMs(nowMs)
    val durationSec = state.durationMs / 1000f

    // Drag state kept local (an explicit flag + a float), so the 1s tick can't fight an in-progress
    // drag: while dragging we show dragSec, otherwise the live extrapolated position.
    var dragging by remember { mutableStateOf(false) }
    var dragSec by remember { mutableFloatStateOf(0f) }
    val shownMs = if (dragging) (dragSec * 1000).toLong() else displayed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(formatTrackTime(shownMs), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.canSeek) {
                Slider(
                    value = if (dragging) dragSec else (displayed / 1000f).coerceIn(0f, durationSec),
                    onValueChange = { dragging = true; dragSec = it },
                    onValueChangeFinished = { dragging = false; onSeek(dragSec.toLong()) },
                    valueRange = 0f..durationSec,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val frac = (displayed.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.24f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.85f)),
                    )
                }
            }
        }
        Text(formatTrackTime(state.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}
