package com.rar.hearth.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rar.hearth.R

/**
 * Brand splash shown over the app on launch. Renders the same baked tile + "Hearth" wordmark
 * lockup as the OS window-background splash (ic_splash_lockup / splash_background.xml), at the
 * same size and centering — so the wordmark is present from the very first frame through to
 * dismissal, with no jump when Compose takes over from the window background.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_lockup),
            contentDescription = "Hearth",
            modifier = Modifier.size(width = 200.dp, height = 150.dp),
        )
    }
}

/**
 * Whether the startup splash has served its purpose: shown for at least [minMs], and either the
 * app has connected to HA or the [maxMs] cap has elapsed (so it never sticks on a slow or failed
 * connect — e.g. when the first screen is Setup and no connection is coming).
 */
fun splashDone(
    elapsedMs: Long,
    connected: Boolean,
    minMs: Long = 700,
    maxMs: Long = 2000,
): Boolean = elapsedMs >= maxMs || (elapsedMs >= minMs && connected)
