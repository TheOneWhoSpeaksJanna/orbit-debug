package com.orbitai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class OrbitThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * Clean Material 3 theme — no glassmorphism, no glow effects, no extended
 * color tokens. Just a standard Material 3 color scheme with one accent
 * color (indigo). Optimized for readability and performance.
 *
 * The old "cosmic glassmorphism" theme had:
 *  - Expensive blur/glass overlays that caused jank on low-end devices
 *  - Extended color tokens (OrbitAiExtendedColors) that added complexity
 *  - Glow effects that were invisible in light mode
 *
 * This theme uses only standard Material 3 color roles, so all components
 * get proper colors automatically with zero custom draw code.
 */
private val DarkColorScheme = darkColorScheme(
    primary = OrbitPrimary,
    onPrimary = OrbitOnPrimary,
    primaryContainer = OrbitPrimaryContainer,
    onPrimaryContainer = OrbitOnPrimaryContainer,
    secondary = OrbitSecondary,
    onSecondary = OrbitOnSecondary,
    secondaryContainer = OrbitSecondaryContainer,
    onSecondaryContainer = OrbitOnSecondaryContainer,
    tertiary = OrbitTertiary,
    onTertiary = OrbitOnTertiary,
    tertiaryContainer = OrbitTertiaryContainer,
    onTertiaryContainer = OrbitOnTertiaryContainer,
    error = OrbitError,
    onError = OrbitOnError,
    errorContainer = OrbitErrorContainer,
    onErrorContainer = OrbitOnErrorContainer,
    background = OrbitDarkBackground,
    onBackground = OrbitDarkOnSurface,
    surface = OrbitDarkSurface,
    onSurface = OrbitDarkOnSurface,
    surfaceVariant = OrbitDarkSurfaceVariant,
    onSurfaceVariant = OrbitDarkOnSurfaceVariant,
    outline = OrbitDarkOutline,
    outlineVariant = OrbitDarkSurfaceVariant,
    scrim = Color.Black,
)

private val LightColorScheme = lightColorScheme(
    primary = OrbitPrimary,
    onPrimary = OrbitOnPrimary,
    primaryContainer = OrbitPrimaryContainer,
    onPrimaryContainer = OrbitOnPrimaryContainer,
    secondary = OrbitSecondary,
    onSecondary = OrbitOnSecondary,
    secondaryContainer = OrbitSecondaryContainer,
    onSecondaryContainer = OrbitOnSecondaryContainer,
    tertiary = OrbitTertiary,
    onTertiary = OrbitOnTertiary,
    tertiaryContainer = OrbitTertiaryContainer,
    onTertiaryContainer = OrbitOnTertiaryContainer,
    error = OrbitError,
    onError = OrbitOnError,
    errorContainer = OrbitErrorContainer,
    onErrorContainer = OrbitOnErrorContainer,
    background = OrbitLightBackground,
    onBackground = OrbitLightOnSurface,
    surface = OrbitLightSurface,
    onSurface = OrbitLightOnSurface,
    surfaceVariant = OrbitLightSurfaceVariant,
    onSurfaceVariant = OrbitLightOnSurfaceVariant,
    outline = OrbitLightOutline,
    outlineVariant = OrbitLightSurfaceVariant,
    scrim = Color.Black,
)

@Composable
fun OrbitTheme(
    themeMode: OrbitThemeMode = OrbitThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        OrbitThemeMode.SYSTEM -> isSystemInDarkTheme()
        OrbitThemeMode.LIGHT -> false
        OrbitThemeMode.DARK -> true
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/**
 * Compatibility alias — old code calls OrbitAiTheme. This redirects
 * to the new OrbitTheme so existing code works without changes.
 */
@Composable
fun OrbitAiTheme(
    themeMode: OrbitThemeMode = OrbitThemeMode.SYSTEM,
    content: @Composable () -> Unit
) = OrbitTheme(themeMode, content)
