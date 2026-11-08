package com.liquid.lfmomni.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF001E2B),
    primaryContainer = Color(0xFF004D65),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFB2CAD6),
    background = Color(0xFF0E1418),
    onBackground = Color(0xFFE0E3E5),
    surface = Color(0xFF121A1F),
    onSurface = Color(0xFFE0E3E5),
    surfaceVariant = Color(0xFF1B252B),
    onSurfaceVariant = Color(0xFFBFC8CD),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006783),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDE9FF),
    onPrimaryContainer = Color(0xFF001E2B),
    background = Color(0xFFFBFCFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF6FAFC),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDDE3E7),
    onSurfaceVariant = Color(0xFF41484C),
)

@Composable
fun LfmOmniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
            }
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
