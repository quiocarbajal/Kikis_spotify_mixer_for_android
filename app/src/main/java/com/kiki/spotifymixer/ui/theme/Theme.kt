package com.kiki.spotifymixer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = SelectedBg,
    onPrimaryContainer = SpotifyGreenHover,
    secondary = SpotifyGreenHover,
    onSecondary = Color.Black,
    background = BgMain,
    onBackground = TextPrimary,
    surface = BgSurface1,
    onSurface = TextPrimary,
    surfaceVariant = BgSurface2,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = StatusError,
    onError = Color.White
)

@Composable
fun KikisSpotifyMixerTheme(
    // Kiki's Spotify Mixer is strictly dark-themed matching Spotify's native desktop & mobile design
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BgMain.toArgb()
            window.navigationBarColor = BgMain.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
