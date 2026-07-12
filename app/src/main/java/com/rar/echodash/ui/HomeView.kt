package com.rar.echodash.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.rar.echodash.ui.model.WeatherPill
import com.rar.echodash.photos.PhotoConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/** Photo slideshow backdrop; cycles shuffled cached photos every 5 min with a crossfade, else dusk. */
@Composable
private fun PhotoBackdrop(photos: List<File>) {
    if (photos.isEmpty()) { DuskBackground(); return }
    val order = remember(photos) { photos.shuffled() }
    var index by remember(order) { mutableIntStateOf(0) }
    LaunchedEffect(order) {
        while (true) {
            delay(PhotoConfig.CYCLE_MS)
            index = (index + 1) % order.size
        }
    }
    Crossfade(targetState = order[index % order.size], animationSpec = tween(1000), label = "photo") { file ->
        val bitmap = remember(file) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
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
    pill: WeatherPill?,
    clockFormat: ClockFormat,
    connState: ConnState,
    configUrl: String,
    configPin: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberMinuteTicker()

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
    ) {
        PhotoBackdrop(photos)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val pattern = clockPattern(clockFormat, DateFormat.is24HourFormat(context))
            Text(
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
                color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light,
            )
            Text(
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)),
                color = Color.White.copy(alpha = 0.9f), fontSize = 24.sp,
            )
            if (pill != null) {
                Row(
                    Modifier
                        .padding(top = 12.dp)
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
