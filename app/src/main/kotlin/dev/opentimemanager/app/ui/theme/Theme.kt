package dev.opentimemanager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opentimemanager.app.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF6251DA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE9FF),
    onPrimaryContainer = Color(0xFF4431A8),
    secondary = Color(0xFF536378),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0F6),
    onSecondaryContainer = Color(0xFF28394C),
    tertiary = Color(0xFF166B59),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCF3E9),
    onTertiaryContainer = Color(0xFF134E41),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1C1D2B),
    surface = Color.White,
    onSurface = Color(0xFF1C1D2B),
    surfaceVariant = Color(0xFFEFF0F6),
    onSurfaceVariant = Color(0xFF646B7D),
    surfaceDim = Color(0xFFE2E4ED),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFF0F1F7),
    surfaceContainerHigh = Color(0xFFEAECF4),
    surfaceContainerHighest = Color(0xFFE3E6EF),
    outline = Color(0xFF787E90),
    outlineVariant = Color(0xFFE3E5EE),
    inverseSurface = Color(0xFF252638),
    inverseOnSurface = Color(0xFFF5F4FF),
    inversePrimary = Color(0xFFC3B7FF),
    surfaceTint = Color(0xFF6251DA),
    error = Color(0xFFB32635),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9EB),
    onErrorContainer = Color(0xFF851B2A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDB0FF),
    onPrimary = Color(0xFF2D1C72),
    primaryContainer = Color(0xFF39305D),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFB5C4DA),
    onSecondary = Color(0xFF233247),
    secondaryContainer = Color(0xFF293547),
    onSecondaryContainer = Color(0xFFD9E5F7),
    tertiary = Color(0xFF8DD7BD),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF194B3D),
    onTertiaryContainer = Color(0xFFB6EED9),
    background = Color(0xFF10121B),
    onBackground = Color(0xFFECECF5),
    surface = Color(0xFF1A1D2A),
    onSurface = Color(0xFFECECF5),
    surfaceVariant = Color(0xFF2B2F41),
    onSurfaceVariant = Color(0xFFADB3C7),
    surfaceDim = Color(0xFF10121B),
    surfaceBright = Color(0xFF34384A),
    surfaceContainerLowest = Color(0xFF0D0F17),
    surfaceContainerLow = Color(0xFF171A25),
    surfaceContainer = Color(0xFF1D202E),
    surfaceContainerHigh = Color(0xFF262A3A),
    surfaceContainerHighest = Color(0xFF303547),
    outline = Color(0xFF80879D),
    outlineVariant = Color(0xFF303547),
    inverseSurface = Color(0xFFECECF5),
    inverseOnSurface = Color(0xFF222435),
    inversePrimary = Color(0xFF6251DA),
    surfaceTint = Color(0xFFBDB0FF),
    error = Color(0xFFFFB2B9),
    onError = Color(0xFF650B1D),
    errorContainer = Color(0xFF49232E),
    onErrorContainer = Color(0xFFFFDADF),
)

@Immutable
data class AppPalette(
    val hero: Color = Color(0xFF242436),
    val onHero: Color = Color(0xFFF8F7FF),
    val heroMuted: Color = Color(0xFFBCBBD1),
    val heroAccent: Color = Color(0xFFCDC3FF),
    val heroAccentContainer: Color = Color(0xFF3D3659),
)

val LocalAppPalette = staticCompositionLocalOf { AppPalette() }

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
    val palette = if (dynamicColor) {
        val hero = if (dark) colors.surfaceContainerHigh else colors.onSurface
        val accent = if (dark) colors.primary else colors.inversePrimary
        AppPalette(
            hero = hero,
            onHero = if (dark) colors.onSurface else colors.surface,
            heroMuted = if (dark) colors.onSurfaceVariant else colors.surface.copy(alpha = 0.75f),
            heroAccent = accent,
            heroAccentContainer = lerp(hero, accent, 0.15f),
        )
    } else AppPalette()
    CompositionLocalProvider(LocalAppPalette provides palette, LocalContentColor provides colors.onBackground) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
