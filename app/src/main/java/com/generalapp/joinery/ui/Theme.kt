package com.generalapp.joinery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF17110C)
val Panel = Color(0xFF241A12)
val PanelHigh = Color(0xFF32241A)
val Wood = Color(0xFFC08A57)
val WoodLight = Color(0xFFE0B27E)
val Brass = Color(0xFFD8A84E)
val Paper = Color(0xFFF0E3CE)
val Muted = Color(0xFFA28C74)

private val scheme = darkColorScheme(
    primary = Brass,
    onPrimary = Ink,
    secondary = WoodLight,
    background = Ink,
    onBackground = Paper,
    surface = Panel,
    onSurface = Paper,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
)

@Composable
fun JoineryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
