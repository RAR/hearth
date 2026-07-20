package com.rar.hearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.hearth.ui.model.TakeoverTimer
import com.rar.hearth.ui.model.formatTimer

private val TIMER_NAME_PRESETS = listOf("Pasta", "Eggs", "Tea", "Oven", "Laundry")

/**
 * Full-screen kitchen timer takeover: every running timer shown big with a live countdown. One
 * timer fills the screen; 2–4 tile into a 2-column grid; 5+ scroll. Dark surface in the NowPlaying
 * takeover family. ✕ dismisses (back to the dashboard); tapping a name opens the rename dialog.
 */
@Composable
fun TimersTakeoverView(
    timers: List<TakeoverTimer>,
    onDismiss: () -> Unit,
    onRename: (id: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        if (timers.size == 1) {
            SingleTimer(
                timer = timers[0],
                onRename = { renamingId = timers[0].id; renameText = "" },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 72.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                timers.chunked(2).forEach { rowTimers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        rowTimers.forEach { t ->
                            TimerCard(
                                timer = t,
                                onRename = { renamingId = t.id; renameText = "" },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowTimers.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp)
                .clip(CircleShape).background(Color(0x33FFFFFF)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss timers",
                tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }

    renamingId?.let { id ->
        RenameDialog(
            text = renameText,
            onText = { renameText = it },
            onPreset = { renameText = it },
            onSave = { onRename(id, renameText); renamingId = null },
            onCancel = { renamingId = null },
        )
    }
}

@Composable
private fun SingleTimer(timer: TakeoverTimer, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val dim = if (timer.active) 1f else 0.5f
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            timer.label,
            color = Color.White.copy(alpha = dim),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onRename() },
        )
        Text(
            formatTimer(timer.remainingSec),
            color = Color.White.copy(alpha = dim),
            fontSize = 120.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (!timer.active) PausedTag()
    }
}

@Composable
private fun TimerCard(timer: TakeoverTimer, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val dim = if (timer.active) 1f else 0.5f
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF11151F), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                timer.label,
                color = Color.White.copy(alpha = dim),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onRename() },
            )
            Text(
                formatTimer(timer.remainingSec),
                color = Color.White.copy(alpha = dim),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (!timer.active) PausedTag()
        }
    }
}

@Composable
private fun PausedTag() {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0x33FFFFFF)) {
        Text("paused", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun RenameDialog(
    text: String,
    onText: (String) -> Unit,
    onPreset: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name this timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TIMER_NAME_PRESETS.forEach { preset ->
                        SuggestionChip(onClick = { onPreset(preset) }, label = { Text(preset) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
