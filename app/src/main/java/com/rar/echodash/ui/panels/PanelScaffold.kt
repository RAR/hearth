package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen dark panel background; content is inset from the right so the rail never overlaps it. */
@Composable
fun PanelSurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF12141C))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 96.dp),
    ) { content() }
}

/** Centered hint shown when a panel's labels match nothing. */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
