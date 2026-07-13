package com.rar.echodash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.voice.TimerAlert
import com.rar.echodash.voice.TimersUiState
import com.rar.echodash.voice.VoiceOverlayPhase
import com.rar.echodash.voice.VoiceOverlayState

/**
 * Full screen-edge glow shown while the satellite is listening (wake word heard, speech not
 * yet captured). Four thin gradient strips hugging the edges — cheap fill for this GPU; the
 * pulse animation only runs while the glow is composed.
 */
@Composable
fun WakeGlow(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(400)), modifier = modifier) {
        val pulse = rememberInfiniteTransition(label = "wakeGlow")
        val alpha by pulse.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "wakeGlowAlpha",
        )
        val color = Color(0xFF4FC3F7).copy(alpha = alpha)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(color, Color.Transparent))),
            )
            Box(
                Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, color))),
            )
            Box(
                Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterStart)
                    .background(Brush.horizontalGradient(listOf(color, Color.Transparent))),
            )
            Box(
                Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, color))),
            )
        }
    }
}

/**
 * Small bottom-center voice pill. Lighter than the doorbell popup: it does not cover the
 * screen and passes touches through the surrounding area. Renders nothing when hidden.
 */
@Composable
fun VoiceOverlay(state: VoiceOverlayState, modifier: Modifier = Modifier) {
    if (state.phase == VoiceOverlayPhase.HIDDEN) return
    val label = when (state.phase) {
        VoiceOverlayPhase.LISTENING -> "Listening…"
        VoiceOverlayPhase.TRANSCRIPT -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.RESPONSE -> state.text.ifBlank { "…" }
        VoiceOverlayPhase.HIDDEN -> ""
    }
    Box(modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xE6101218)) {
            Text(
                label,
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}

private fun formatTimer(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** Top-center stack of live countdown chips. Renders nothing when there are no timers. */
@Composable
fun TimerChips(state: TimersUiState, modifier: Modifier = Modifier) {
    if (state.chips.isEmpty()) return
    Box(modifier.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            state.chips.forEach { chip ->
                val text = buildString {
                    if (chip.name.isNotBlank()) append(chip.name).append("  ")
                    append(formatTimer(chip.remainingSec))
                    if (!chip.active) append("  ⏸")
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xE61B1E27),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(text, color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** Full-attention "Timer done" overlay. Tap anywhere to dismiss. */
@Composable
fun TimerFinishedOverlay(alert: TimerAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF2A2340)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Timer done", color = Color.White, fontSize = 30.sp)
                if (alert.label.isNotBlank() && alert.label != "Timer") {
                    Text(alert.label, color = Color(0xFFCFC6F0), fontSize = 20.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
                Text("Tap to dismiss", color = Color(0x99FFFFFF), fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
