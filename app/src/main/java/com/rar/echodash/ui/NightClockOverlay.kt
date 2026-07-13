package com.rar.echodash.ui

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Minute ticker matching HomeView's rememberMinuteTicker: updates on the wall-clock minute edge. */
@Composable
private fun rememberNightMinuteTicker(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000 - now.longValue % 60_000)
        }
    }
    return now
}

/**
 * The night clock: a huge dim-gray time on pure black, faded in/out on [active]. The overlay
 * consumes ALL touches — the waking tap fires [onWake] and must NOT reach the panels underneath.
 * Same 12/24-hour format as HomeView's clock (via clockIs24), AM/PM suffix smaller and dimmer.
 */
@Composable
fun NightClockOverlay(
    active: Boolean,
    clockFormat: ClockFormat,
    onWake: () -> Unit,
) {
    Crossfade(targetState = active, animationSpec = tween(600), label = "night") { on ->
        if (on) {
            val context = LocalContext.current
            val now by rememberNightMinuteTicker()
            val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.type == PointerEventType.Press) onWake()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row {
                    Text(
                        SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)),
                        color = Color(0xFF777777), fontSize = 120.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (!is24) {
                        Text(
                            SimpleDateFormat("a", Locale.getDefault()).format(Date(now)),
                            color = Color(0xFF555555), fontSize = 28.sp,
                            modifier = Modifier.alignByBaseline().padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
