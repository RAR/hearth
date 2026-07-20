package com.rar.hearth.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** App-wide dark theme with Nunito typography. */
@Composable
fun HearthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography = HearthTypography,
        content = content,
    )
}
