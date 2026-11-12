package com.wifigatekeeper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deep-sea palette, matching the guide cover.
val Navy = Color(0xFF0B1C2C)
val NavyLight = Color(0xFF14293D)
val Accent = Color(0xFF529286)
val AccentBlue = Color(0xFF2E6DA4)
val Danger = Color(0xFFB3474A)
val SurfaceSoft = Color(0xFFF4F8FC)

private val ColorScheme = darkColorScheme(
    primary = Accent,
    secondary = AccentBlue,
    background = Navy,
    surface = NavyLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    error = Danger,
)

@Composable
fun WifiGatekeeperTheme(content: @Composable () -> Unit) {
    // The app ships one deliberate dark theme regardless of system setting.
    isSystemInDarkTheme() // observed for future light-theme work; not used yet
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
