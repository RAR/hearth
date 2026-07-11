package com.rar.echodash.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.TempReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

fun isStale(nowMs: Long, updatedAtMs: Long?): Boolean =
    updatedAtMs != null && nowMs - updatedAtMs > 15 * 60_000L

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
private fun DuskBackground() {
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

@Composable
fun DashboardScreen(
    reading: TempReading?,
    connState: ConnState,
    onChangeSensor: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberMinuteTicker()

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
    ) {
        DuskBackground()

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            Text(
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
            )
            val stale = isStale(now, reading?.updatedAtMs)
            Text(
                reading?.let { "${it.value}${it.unit ?: "°"}" } ?: "--",
                color = Color.White.copy(alpha = if (stale) 0.4f else 0.85f),
                fontSize = 40.sp,
            )
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
                text = { Text("Change sensor") },
                onClick = { menuOpen = false; onChangeSensor() },
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
