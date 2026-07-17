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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.ElectricMeter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.EvStation
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.SolarPower
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.ha.ConnState
import com.rar.echodash.media.ArtBitmaps
import com.rar.echodash.media.NowPlayingState
import com.rar.echodash.ui.model.AqiPill
import com.rar.echodash.ui.model.BattFlow
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.EvCard
import com.rar.echodash.ui.model.NotificationItem
import com.rar.echodash.ui.model.RainPill
import com.rar.echodash.ui.model.SolarCard
import com.rar.echodash.ui.model.WeatherIcon
import com.rar.echodash.ui.model.WeatherPill
import com.rar.echodash.ui.model.eventTimeLabel
import com.rar.echodash.ui.model.homeCardWidthDp
import com.rar.echodash.ui.model.homeOverlayCaps
import com.rar.echodash.ui.model.nextEventCard
import com.rar.echodash.ui.model.solarStatsCompact
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
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
    rain: RainPill? = null,
    evs: List<EvCard> = emptyList(),
    solar: SolarCard? = null,
    notifications: List<NotificationItem> = emptyList(),
    onDismiss: (String) -> Unit = {},
    // CONFIG presence of EV/solar cards (not current visibility): reserves the card column in the
    // notification width cap so a card fading in never makes the notification stack jump width.
    reserveCardColumn: Boolean = false,
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
    onBrowse: () -> Unit = {},
    calendarEvents: List<CalendarEvent> = emptyList(),
    onOpenCalendar: () -> Unit = {},
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

    BoxWithConstraints(
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
        // Per-screen sizing from the measured canvas (787×394 on the Show 5). Cheap; recomputed only
        // when the constraints or card-config presence change.
        val cardWidth = homeCardWidthDp(maxWidth.value).dp
        val caps = homeOverlayCaps(maxWidth.value, maxHeight.value, reserveCardColumn)

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
                    onBrowse = onBrowse,
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

            if (pill != null || aqi != null || rain != null) {
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
                            Text("AQI", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
                            Text(
                                aqi.value.toString(),
                                color = Color(aqi.band.colorArgb),
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (rain != null) {
                        Row(
                            Modifier
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = weatherIcon(WeatherIcon.RAIN),
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.size(22.dp),
                            )
                            Text(rain.text, color = Color.White.copy(alpha = 0.95f), fontSize = 18.sp)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = evs.isNotEmpty() || solar != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    evs.forEach { EvCardView(it, cardWidth) }
                    if (solar != null) SolarCardView(solar, cardWidth)
                }
            }

            // Notification area: just below the weather/AQI pill row (top = 70dp). Its width and
            // height caps come from AdaptiveGeometry.homeOverlayCaps: width so a row never slides
            // under the EV/solar card column, height (+ clipToBounds) so the bottom-left clock is
            // never covered. Both grow with the screen (see the design spec's golden table).
            AnimatedVisibility(
                visible = notifications.isNotEmpty(),
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 70.dp),
            ) {
                NotificationArea(
                    notifications = notifications,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = caps.notifMaxWidthDp.dp)
                        .heightIn(max = caps.notifMaxHeightDp.dp)
                        .clipToBounds(),
                )
            }

            // Next-event card: bottom-right, diagonal from the clock. Width-capped by
            // AdaptiveGeometry.homeOverlayCaps.nextEventMaxWidthDp so it never approaches the
            // bottom-left clock block (the cap reserves the worst-case date-line width + clearance);
            // the cap widens on larger screens. Re-derives on the minute tick so "Tomorrow" flips
            // to a time and "Now" appears without waiting for the next 15-minute fetch.
            val nextEvent = remember(calendarEvents, now) { nextEventCard(calendarEvents, now) }
            AnimatedVisibility(
                visible = nextEvent != null,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 28.dp),
            ) {
                // Guarded like the EV/solar stack above: nextEvent is non-null whenever visible=true.
                nextEvent?.let { ev ->
                    val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
                    Row(
                        Modifier
                            .widthIn(max = caps.nextEventMaxWidthDp.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            // Tap opens the agenda panel.
                            .pointerInput(Unit) { detectTapGestures(onTap = { onOpenCalendar() }) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(ev.colorArgb)))
                        Text(
                            eventTimeLabel(ev, now, ZoneId.systemDefault(), is24),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                        )
                        Text(
                            ev.title, color = Color.White, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
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

/** One EV charging pill: EV-station icon + name, a power/energy line, then a battery gauge + SOC/eta.
 *  [cardWidth] defaults to today's 248dp; HomeView passes the per-screen width. */
@Composable
private fun EvCardView(card: EvCard, cardWidth: Dp = 248.dp) {
    Column(
        Modifier
            .width(cardWidth)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.EvStation, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
            Text(
                card.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (card.socPct != null) {
                Text("${card.socPct}%", color = Color.White, fontSize = 14.sp)
            }
        }
        if (card.socPct != null) {
            GaugeBar(card.socPct, card.charging, card.limitPct)
        }
        val stats = listOfNotNull(card.chargeLine, card.etaText).joinToString(" · ")
        if (stats.isNotEmpty()) {
            Text(
                stats, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val GaugeGreen = Color(0xFF7BC67E)
private val GaugeAmber = Color(0xFFE0A030) // matches the connection-status dot

/** Gauge track filling the card's content width: colored fill, optional directional shimmer,
 *  optional limit tick. BoxWithConstraints so the shimmer sweep and tick math use the MEASURED
 *  track width instead of a hard 216 — at a 248dp card (216dp track) behaviour is bit-identical. */
@Composable
private fun GaugeBar(
    fillPct: Int,
    shimmer: Boolean,
    tickPct: Int? = null,
    fill: Color = GaugeGreen,
    reverse: Boolean = false,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
    ) {
        val trackW = maxWidth.value // measured track width in dp
        Box(
            Modifier
                .fillMaxWidth(fillPct / 100f)
                .fillMaxHeight()
                // clip() is required: background() draws a rounded shape but does
                // not clip children, so the shimmer band would bleed past the fill.
                .clip(RoundedCornerShape(4.dp))
                .background(fill),
        ) {
            // Only run the infinite animation while actually charging.
            if (shimmer) {
                val transition = rememberInfiniteTransition(label = "gaugeShimmer")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "gaugeShimmerX",
                )
                Box(
                    Modifier
                        // Sweep a 24dp band left-to-right across the full track width;
                        // the fill's clip keeps it inside the filled region.
                        .offset(x = ((if (reverse) 1f - fraction else fraction) * (trackW + 24)).dp - 24.dp)
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
        if (tickPct != null) {
            // Integer track width preserves the old integer-division tick math (216*tickPct/100
            // truncates); a Float would shift the tick a sub-dp fraction off today's position.
            val trackWi = maxWidth.value.toInt()
            Box(
                Modifier
                    // 2dp tick at the vehicle's charge limit; -1dp centers it on the fraction.
                    .offset(x = (trackWi * tickPct / 100 - 1).dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Home solar pill: sun icon + PV output, battery gauge, home/grid line. [cardWidth] defaults to
 *  today's 248dp; HomeView passes the per-screen width. */
@Composable
private fun SolarCardView(card: SolarCard, cardWidth: Dp = 248.dp) {
    Column(
        Modifier
            .width(cardWidth)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Outlined.SolarPower, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
            Text(
                if (card.pvText != null) "Solar ${card.pvText}" else "Solar",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (card.socPct != null) {
                Text("${card.socPct}%", color = Color.White, fontSize = 14.sp)
            }
        }
        if (card.socPct != null) {
            // Idle gauge keeps the color of the last non-idle direction (green until first activity).
            var lastFlow by remember { mutableStateOf(BattFlow.CHARGING) }
            LaunchedEffect(card.battFlow) {
                if (card.battFlow != BattFlow.IDLE) lastFlow = card.battFlow
            }
            val tint = if (card.battFlow != BattFlow.IDLE) card.battFlow else lastFlow
            GaugeBar(
                card.socPct,
                shimmer = card.battFlow != BattFlow.IDLE,
                fill = if (tint == BattFlow.DISCHARGING) GaugeAmber else GaugeGreen,
                reverse = card.battFlow == BattFlow.DISCHARGING,
            )
        }
        if (card.battText != null || card.homeText != null || card.gridText != null) {
            val statsWhite = Color.White.copy(alpha = 0.9f)
            // Battery + home + grid on one line. Below a 300dp card the row keeps the compact
            // 12sp/14dp/3dp squeeze (all three segments won't otherwise fit the fixed 248dp card);
            // at 300dp+ it relaxes to 14sp/16dp/4dp. AdaptiveGeometry.solarStatsCompact decides.
            val compact = solarStatsCompact(cardWidth.value.toInt())
            val statsFont = if (compact) 12.sp else 14.sp
            val statsIcon = if (compact) 14.dp else 16.dp
            val statsGap = if (compact) 3.dp else 4.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(statsGap),
            ) {
                if (card.battText != null) {
                    Icon(
                        Icons.Outlined.BatteryStd, contentDescription = "Battery",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.battText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                    // Direction arrow mirrors the grid: into the battery = points at it (charging),
                    // away from it = out of it (discharging), dash = idle.
                    Icon(
                        when (card.battFlow) {
                            BattFlow.CHARGING -> Icons.AutoMirrored.Outlined.ArrowBack
                            BattFlow.DISCHARGING -> Icons.AutoMirrored.Outlined.ArrowForward
                            BattFlow.IDLE -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.battFlow) {
                            BattFlow.CHARGING -> "Charging"
                            BattFlow.DISCHARGING -> "Discharging"
                            BattFlow.IDLE -> "Idle"
                        },
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                }
                if (card.homeText != null) {
                    Icon(
                        Icons.Outlined.Home, contentDescription = "Home",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.homeText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                }
                if (card.gridText != null) {
                    Icon(
                        when (card.gridImporting) {
                            true -> Icons.AutoMirrored.Outlined.ArrowBack
                            false -> Icons.AutoMirrored.Outlined.ArrowForward
                            null -> Icons.Outlined.HorizontalRule
                        },
                        contentDescription = when (card.gridImporting) {
                            true -> "Import"
                            false -> "Export"
                            null -> "Balanced"
                        },
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Icon(
                        Icons.Outlined.ElectricMeter, contentDescription = "Grid",
                        tint = statsWhite, modifier = Modifier.size(statsIcon),
                    )
                    Text(card.gridText, color = statsWhite, fontSize = statsFont, maxLines = 1)
                }
            }
        }
    }
}
