package com.rar.echodash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ui.model.NotifSeverity
import com.rar.echodash.ui.model.NotificationItem
import com.rar.echodash.ui.model.notifTimestampLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val SWIPE_DISMISS_FRACTION = 0.30f

private fun accentColor(severity: NotifSeverity): Color = when (severity) {
    NotifSeverity.CRITICAL -> Color(0xFFE05555)
    NotifSeverity.WARNING -> Color(0xFFE0A030) // matches GaugeAmber / connection dot
    NotifSeverity.INFO -> Color(0xFF9E9E9E)
}

/**
 * The home-view notification stack. Rows beyond the caller's height cap scroll vertically (the
 * cap + clipToBounds come in via [modifier]). Tapping a row with detail toggles in-place expansion
 * (only one expanded at a time). Swiping a row left past [SWIPE_DISMISS_FRACTION] of its width
 * dismisses it (the horizontal detector and the vertical scroll claim separate axes).
 * The caller anchors/sizes this via [modifier]; empty lists should not be rendered by the caller.
 */
@Composable
fun NotificationArea(
    notifications: List<NotificationItem>,
    nowMs: Long,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notifications.forEach { item ->
            key(item.key) {
                NotificationRow(
                    item = item,
                    nowMs = nowMs,
                    expanded = expandedKey == item.key,
                    onToggle = { expandedKey = if (expandedKey == item.key) null else item.key },
                    onDismiss = { onDismiss(item.key) },
                )
            }
        }
    }
}

/**
 * Mini-player card for the home notification stack — shown while a session is active, playing
 * (or within the post-pause grace window, see [miniPlayerVisible]), and neither the takeover nor
 * a swipe has claimed it. Chrome matches [NotificationRow] (RoundedCornerShape(14) on Black-0.35).
 * Layout is a full-height Row: a square album-art panel on the left ([artThumb] = art.sharp, or a
 * MusicNote fallback) sized to the card height (fillMaxHeight + aspectRatio, so the whole card's
 * height drives the art edge), then a right column with the title/artist lines ([artist] omitted
 * when null/blank) above four 36dp transport chips (back/play-pause/next/stop). Tapping the art or
 * the title/artist column runs [onTap] (restores the takeover); the transport chips consume their
 * own taps. The whole card is wrapped in the exact swipe-to-dismiss mechanics [NotificationRow]
 * uses below — fire-and-forget, local state only, no server op and so no failure path (unlike the
 * MA queue rows' suspend variant elsewhere).
 */
@Composable
fun MiniPlayerCard(
    title: String,
    artist: String?,
    artThumb: ImageBitmap?,
    playing: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = widthPx * SWIPE_DISMISS_FRACTION
                        if (widthPx > 0 && -offsetX.value >= threshold) {
                            scope.launch {
                                offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        }
                    },
                    // A cancelled drag (ancestor claims the gesture, extra pointer) never reaches
                    // onDragEnd — snap back so the card can't be left stranded mid-swipe.
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    // Only left drags move the card; right drags clamp back to 0.
                    scope.launch { offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f)) }
                }
            },
    ) {
        // Full-height Row: the right column's content (title/artist + transport) drives the card
        // height via IntrinsicSize.Min, and the art panel fills that height as a square.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .height(IntrinsicSize.Min),
        ) {
            // Album art spans the whole card height. Tapping it (like the title/artist column)
            // restores the takeover; the transport chips consume their own taps -- normal Compose
            // click handling wins over the swipe detector for a tap below the swipe threshold.
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable { onTap() },
                contentAlignment = Alignment.Center,
            ) {
                if (artThumb != null) {
                    Image(
                        artThumb, contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Outlined.MusicNote, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().clickable { onTap() }) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!artist.isNullOrBlank()) {
                        Text(
                            artist,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniTransportButton(Icons.Outlined.SkipPrevious, onPrev)
                    MiniTransportButton(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, onPlayPause)
                    MiniTransportButton(Icons.Outlined.SkipNext, onNext)
                    MiniTransportButton(Icons.Outlined.Stop, onStop)
                }
            }
        }
    }
}

/** 36dp round transport chip (18dp icon), same #2A2F3C background as the takeover's transport
 *  button — a smaller clone local to this file (that one, `NpTransportButton`, is private to
 *  `NowPlayingHome.kt`). */
@Composable
private fun MiniTransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2F3C))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(item.key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = widthPx * SWIPE_DISMISS_FRACTION
                        if (widthPx > 0 && -offsetX.value >= threshold) {
                            scope.launch {
                                offsetX.animateTo(-widthPx.toFloat(), tween(200))
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        }
                    },
                    // A cancelled drag (ancestor claims the gesture, extra pointer) never reaches
                    // onDragEnd — snap back so the row can't be left stranded mid-swipe.
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    // Only left drags move the row; right drags clamp back to 0.
                    scope.launch { offsetX.snapTo((offsetX.value + dragAmount).coerceAtMost(0f)) }
                }
            },
    ) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .then(if (item.detail != null) Modifier.clickable { onToggle() } else Modifier)
            .height(IntrinsicSize.Min)

        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accentColor(item.severity)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    notifTimestampLabel(item.timestampMs, nowMs)?.let { label ->
                        Text(
                            label,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (expanded && item.detail != null) {
                    Text(
                        item.detail,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
