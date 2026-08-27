package com.imogen.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * imogen's colours: warm paper, near-black ink, and a safelight orange borrowed from a
 * darkroom. Dynamic colour wins where the platform offers it — a photo app that ignores
 * the wallpaper somebody chose is a photo app with opinions about colour that are not
 * about photographs.
 */
private val Safelight = Color(0xFFE39B5C)
private val Ink = Color(0xFF17191C)
private val Paper = Color(0xFFFBF7F2)
private val Sunken = Color(0xFFF1EBE3)
private val DarkPaper = Color(0xFF121110)
private val DarkSunken = Color(0xFF1D1B19)

private val LightScheme = lightColorScheme(
    primary = Safelight,
    onPrimary = Color(0xFF241505),
    primaryContainer = Color(0xFFF6DEC6),
    onPrimaryContainer = Color(0xFF2C1A07),
    secondary = Color(0xFF6F5B47),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Sunken,
    onSurfaceVariant = Color(0xFF534A40),
    outline = Color(0xFFCFC4B6),
)

private val DarkScheme = darkColorScheme(
    primary = Safelight,
    onPrimary = Color(0xFF241505),
    primaryContainer = Color(0xFF5B3A18),
    onPrimaryContainer = Color(0xFFF6DEC6),
    secondary = Color(0xFFD6C3AE),
    background = DarkPaper,
    onBackground = Color(0xFFEDE6DD),
    surface = DarkPaper,
    onSurface = Color(0xFFEDE6DD),
    surfaceVariant = DarkSunken,
    onSurfaceVariant = Color(0xFFBFB4A6),
    outline = Color(0xFF4A443C),
)

@Composable
fun ImogenTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
