package com.btmicfix.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple40,
    onPrimary = Purple80,
    secondary = PurpleGrey40,
    onSecondary = PurpleGrey80,
    tertiary = Pink40,
    onTertiary = Pink80,

    background = SurfaceDark,
    onBackground = Purple80,
    surface = SurfaceCard,
    onSurface = Purple80,
    surfaceVariant = SurfaceCardHigh,
    onSurfaceVariant = PurpleGrey80,

    error = StatusFailed,
    onError = Purple80,
)

/**
 * BTMicFix theme — always dark mode for a premium, focused look.
 * The app is meant to be a utility that runs quietly in the background;
 * dark theme reduces visual interruption and looks great on OLED screens.
 */
@Composable
fun BTMicFixTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
