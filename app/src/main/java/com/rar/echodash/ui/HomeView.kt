package com.rar.echodash.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.ha.ConnState
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
import com.rar.echodash.ui.model.AqiPill
import com.rar.echodash.ui.model.EvCard
import com.rar.echodash.ui.model.WeatherPill
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
private fun rememberMinuteTicker(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000 - now.longValue % 60_000)
        }
    }
    return now
}

@Composable
internal fun DuskBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFF0B1026),
                0.55f to Color(0xFF2B2E4A),
                0.8f to Color(0xFF7A4A6B),
                1.0f to Color(0xFFC98A5E),
            )
        )
        val rng = Random(42)
        repeat(80) {
            drawCircle(
                color = Color.White.copy(alpha = 0.2f + rng.nextFloat() * 0.5f),
                radius = 0.4f + rng.nextFloat() * 1.8f,
                center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height * 0.55f),
            )
        }
    }
}

/** Crossfading photo backdrop for the current slideshow [file]; dusk gradient when null/undecodable. */
@Composable
private fun PhotoBackdrop(file: File?) {
    if (file == null) { DuskBackground(); return }
    Crossfade(targetState = file, animationSpec = tween(1000), label = "photo") { f ->
        val bitmap = remember(f) {
            runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            DuskBackground()
        }
    }
}

/** Home: photo backdrop + 35% scrim + clock/date/weather pill; offline dot + long-press menu. */
@Composable
fun HomeView(
    photos: List<File>,
    slideshowSeconds: Int,
    pill: WeatherPill?,
    aqi: AqiPill?,
    evs: List<EvCard> = emptyList(),
    clockFormat: ClockFormat,
    connState: ConnState,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    nowPlaying: NowPlayingState,
    art: ArtBitmaps?,
    takeoverVisible: Boolean,
    onMediaPlay: () -> Unit,
    onMediaPause: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberMinuteTicker()

    val order = remember(photos) { photos.shuffled() }
    var photoIndex by remember(order) { mutableIntStateOf(0) }
    // Keying on photoIndex re-arms the countdown, so a manual swipe restarts the timer. Keying on
    // takeoverVisible pauses advancing while the now-playing takeover is showing and resumes after.
    LaunchedEffect(order, photoIndex, slideshowSeconds, takeoverVisible) {
        if (order.size > 1 && !takeoverVisible) {
            delay(slideshowSeconds * 1000L)
            photoIndex += 1
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .pointerInput(order) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (order.size > 1 && abs(dx) > 60.dp.toPx()) {
                            photoIndex += if (dx < 0) 1 else -1
                        }
                    },
                ) { _, dragAmount -> dx += dragAmount }
            }
    ) {
        Crossfade(targetState = takeoverVisible, animationSpec = tween(1000), label = "home-backdrop") { active ->
            if (active) {
                NowPlayingHome(
                    state = nowPlaying,
                    art = art,
                    onPlay = onMediaPlay,
                    onPause = onMediaPause,
                    onNext = onMediaNext,
                    onPrev = onMediaPrev,
                    onVolume = onMediaVolume,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    PhotoBackdrop(order.getOrNull(Math.floorMod(photoIndex, maxOf(order.size, 1))))
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
            }
        }

        if (takeoverVisible) {
            // Compact time-only clock while the player owns the screen: the big bottom-left
            // clock sits where the takeover's volume slider is, and the pills crowd the art.
            val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
            Text(
                SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)),
                color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 8.dp),
            )
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 20.dp)) {
                val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
                // Nudged down to tuck the time against the date line below it.
                Text(
                    SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)),
                    color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.offset(y = 6.dp),
                )
                Text(
                    dateLine(now),
                    color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp,
                )
            }

            if (pill != null || aqi != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pill != null) {
                        Row(
                            Modifier
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val dim = if (pill.stale) 0.4f else 0.95f
                            Icon(
                                imageVector = weatherIcon(pill.icon),
                                contentDescription = null,
                                tint = Color.White.copy(alpha = dim),
                                modifier = Modifier.size(22.dp),
                            )
                            val text = listOfNotNull(pill.conditionText, pill.temperature).joinToString(" · ")
                            Text(text, color = Color.White.copy(alpha = dim), fontSize = 18.sp)
                        }
                    }
                    if (aqi != null) {
                        Row(
                            Modifier
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val dim = if (aqi.stale) 0.4f else 1f
                            Text("AQI", color = Color.White.copy(alpha = 0.7f * dim), fontSize = 18.sp)
                            Text(
                                aqi.value.toString(),
                                color = Color(aqi.band.colorArgb).copy(alpha = dim),
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = evs.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it) }
                }
            }
        }

        if (connState != ConnState.CONNECTED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(8.dp)
                    .background(Color(0xFFE0A030), CircleShape)
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Configure: $configUrl  ·  PIN $configPin") },
                onClick = { menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Android settings") },
                onClick = {
                    menuOpen = false
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                },
            )
            DropdownMenuItem(
                text = { Text("Log out") },
                onClick = { menuOpen = false; onLogout() },
            )
        }
    }
}

/** One EV charging pill: plug icon + name, then a battery gauge with one combined detail line. */
@Composable
private fun EvCardView(card: EvCard) {
    Column(
        Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.Power, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
            Text(card.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        val soc = card.socPct
        val detail = listOfNotNull(soc?.let { "$it%" }, card.statusLine).joinToString(" · ")
        if (soc != null || detail.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (soc != null) {
                    Box(
                        Modifier
                            .size(width = 96.dp, height = 8.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(soc / 100f)
                                .fillMaxHeight()
                                // clip() is required: background() draws a rounded shape but does
                                // not clip children, so the shimmer band would bleed past the fill.
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF7BC67E)),
                        ) {
                            // Only run the infinite animation while actually charging.
                            if (card.charging) {
                                val shimmer = rememberInfiniteTransition(label = "evShimmer")
                                val fraction by shimmer.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1800, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart,
                                    ),
                                    label = "evShimmerX",
                                )
                                Box(
                                    Modifier
                                        // Sweep a 24dp band left-to-right across the full track
                                        // width; the fill's clip keeps it inside the filled region.
                                        .offset(x = (fraction * (96 + 24)).dp - 24.dp)
                                        .width(24.dp)
                                        .fillMaxHeight()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = 0.35f),
                                                    Color.Transparent,
                                                ),
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(detail, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                }
            }
        }
    }
}
