package dev.opentimemanager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.opentimemanager.app.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1EC),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF8B5000),
    secondaryContainer = Color(0xFFFFDDB6),
    surface = Color(0xFFF7FAF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D0),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF9CF1EC),
    secondary = Color(0xFFFFB95F),
    secondaryContainer = Color(0xFF693C00),
    surface = Color(0xFF101413),
)

@Composable
fun OpenTimeManagerTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (dynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
