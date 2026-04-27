package com.robotx.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BrandBlueLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    background = SurfaceLight,
    surface = androidx.compose.ui.graphics.Color.White,
    error = RecordingRed,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    background = SurfaceDark,
    surface = androidx.compose.ui.graphics.Color(0xFF161B21),
    error = RecordingRed,
)

@Composable
fun RobotXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = RobotXTypography,
        content = content,
    )
}
