package com.dkraft.powhvm6

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    secondary = Color(0xFF1565C0),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1F24)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFF90CAF9),
    background = Color(0xFF101418),
    surface = Color(0xFF1A2026),
    onSurface = Color(0xFFE6EDF3)
)

@Composable
fun PowHvmTheme(
    nightMode: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (nightMode) DarkColors else LightColors,
        content = content
    )
}
