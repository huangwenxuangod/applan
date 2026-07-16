package com.applan.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE63946),
    onPrimary = Color.White,
    secondary = Color(0xFFC1121F),
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF1A1A1A),
    onBackground = Color(0xFFE5E5E5),
    onSurface = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF888888),
    surfaceVariant = Color(0xFF252525),
    errorContainer = Color(0xFF3D1A1A),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC1121F),
    onPrimary = Color.White,
    secondary = Color(0xFFE63946),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF666666),
    surfaceVariant = Color(0xFFF0F0F0),
    errorContainer = Color(0xFFFFE5E5),
    error = Color(0xFFC1121F),
    onError = Color.White,
)

@Composable
fun ApplanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
