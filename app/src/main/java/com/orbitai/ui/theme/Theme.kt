package com.orbitai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shape configuration per theme — controls how user/assistant chat bubbles look.
 */
data class BubbleShapes(
    val user: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
    val assistant: RoundedCornerShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
)

val LocalBubbleShapes = staticCompositionLocalOf { BubbleShapes() }
/**
 * Theme presets the user can pick in Settings.
 *  - NORMAL  : the app's "famous AI" orange look
 *  - CHATGPT : replicates the ChatGPT UI (white/green)
 *  - CLAUDE  : replicates the Claude UI (warm ivory/clay)
 *  - CUSTOM  : user-defined colors
 */
enum class ThemeId(val key: String, val label: String) {
    NORMAL("normal", "Normal"),
    CHATGPT("chatgpt", "ChatGPT"),
    CLAUDE("claude", "Claude"),
    CUSTOM("custom", "Custom");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: NORMAL
    }
}

/** Light/Dark preference, independent of the preset. */
enum class OrbitThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-defined theme colors. Stored as a compact string in DataStore:
 *   "bg,surface,primary,onBg,secondary,tertiary"
 * Any empty/blank field falls back to the Normal palette so a half-finished
 * custom theme never renders unreadable.
 */
data class CustomTheme(
    val background: Color = Color.Unspecified,
    val surface: Color = Color.Unspecified,
    val primary: Color = Color.Unspecified,
    val onBackground: Color = Color.Unspecified,
    val secondary: Color = Color.Unspecified,
    val tertiary: Color = Color.Unspecified
) {
    fun toStored(): String = listOf(
        background.toHex(), surface.toHex(), primary.toHex(),
        onBackground.toHex(), secondary.toHex(), tertiary.toHex()
    ).joinToString(",")

    companion object {
        fun fromStored(s: String?): CustomTheme {
            val parts = (s ?: "").split(",")
            fun at(i: Int) = parts.getOrNull(i)?.let { Color.fromHex(it) } ?: Color.Unspecified
            return CustomTheme(at(0), at(1), at(2), at(3), at(4), at(5))
        }
    }
}

private fun Color.toHex(): String =
    if (this == Color.Unspecified) "" else "#%08X".format(0xFFFFFFFF and this.value.toLong())
private fun Color.Companion.fromHex(hex: String): Color? {
    if (hex.length != 7 || hex[0] != '#') return null
    return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { null }
}

// ── Normal bubble shapes ──────────────────────────────────────
private val NormalBubbles = BubbleShapes(
    user = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
    assistant = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
)

// ── ChatGPT bubble shapes (flatter bottom on user, rounder overall) ─
private val ChatGptBubbles = BubbleShapes(
    user = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
    assistant = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
)

// ── Claude bubble shapes (warmer, more rounded, paper-like) ─────────
private val ClaudeBubbles = BubbleShapes(
    user = RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp),
    assistant = RoundedCornerShape(8.dp, 22.dp, 22.dp, 22.dp)
)
private val NormalDark = darkColorScheme(
    primary = OrbitPrimary, onPrimary = OrbitOnPrimary,
    primaryContainer = OrbitPrimaryContainer, onPrimaryContainer = OrbitOnPrimaryContainer,
    secondary = OrbitSecondary, onSecondary = OrbitOnSecondary,
    secondaryContainer = OrbitSecondaryContainer, onSecondaryContainer = OrbitOnSecondaryContainer,
    tertiary = OrbitTertiary, onTertiary = OrbitOnTertiary,
    tertiaryContainer = OrbitTertiaryContainer, onTertiaryContainer = OrbitOnTertiaryContainer,
    error = OrbitError, onError = OrbitOnError, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = OrbitDarkBackground, onBackground = OrbitDarkOnSurface,
    surface = OrbitDarkSurface, onSurface = OrbitDarkOnSurface,
    surfaceVariant = OrbitDarkSurfaceVariant, onSurfaceVariant = OrbitDarkOnSurfaceVariant,
    outline = OrbitDarkOutline, outlineVariant = OrbitDarkSurfaceVariant, scrim = Color.Black
)
private val NormalLight = lightColorScheme(
    primary = OrbitPrimary, onPrimary = OrbitOnPrimary,
    primaryContainer = OrbitPrimaryContainer, onPrimaryContainer = OrbitOnPrimaryContainer,
    secondary = OrbitSecondary, onSecondary = OrbitOnSecondary,
    secondaryContainer = OrbitSecondaryContainer, onSecondaryContainer = OrbitOnSecondaryContainer,
    tertiary = OrbitTertiary, onTertiary = OrbitOnTertiary,
    tertiaryContainer = OrbitTertiaryContainer, onTertiaryContainer = OrbitOnTertiaryContainer,
    error = OrbitError, onError = OrbitOnError, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = OrbitLightBackground, onBackground = OrbitLightOnSurface,
    surface = OrbitLightSurface, onSurface = OrbitLightOnSurface,
    surfaceVariant = OrbitLightSurfaceVariant, onSurfaceVariant = OrbitLightOnSurfaceVariant,
    outline = OrbitLightOutline, outlineVariant = OrbitLightSurfaceVariant, scrim = Color.Black
)

// ── CHATGPT ─────────────────────────────────────────────────
private val ChatGptDark = darkColorScheme(
    primary = ChatGptAccentDark, onPrimary = Color.White,
    primaryContainer = ChatGptAccentDark.copy(alpha = 0.25f), onPrimaryContainer = ChatGptAccentDark,
    secondary = ChatGptDarkOnSurfaceVariant, onSecondary = ChatGptDarkBackground,
    secondaryContainer = ChatGptDarkSurfaceVariant, onSecondaryContainer = ChatGptDarkOnSurface,
    tertiary = ChatGptAccentDark, onTertiary = Color.White,
    tertiaryContainer = ChatGptAccentDark.copy(alpha = 0.2f), onTertiaryContainer = ChatGptAccentDark,
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = ChatGptDarkBackground, onBackground = ChatGptDarkOnSurface,
    surface = ChatGptDarkSurface, onSurface = ChatGptDarkOnSurface,
    surfaceVariant = ChatGptDarkSurfaceVariant, onSurfaceVariant = ChatGptDarkOnSurfaceVariant,
    outline = ChatGptDarkOutline, outlineVariant = ChatGptDarkSurfaceVariant, scrim = Color.Black
)
private val ChatGptLight = lightColorScheme(
    primary = ChatGptAccent, onPrimary = Color.White,
    primaryContainer = ChatGptAccent.copy(alpha = 0.16f), onPrimaryContainer = ChatGptAccent,
    secondary = ChatGptLightOnSurfaceVariant, onSecondary = ChatGptLightBackground,
    secondaryContainer = ChatGptLightSurfaceVariant, onSecondaryContainer = ChatGptLightOnSurface,
    tertiary = ChatGptAccent, onTertiary = Color.White,
    tertiaryContainer = ChatGptAccent.copy(alpha = 0.16f), onTertiaryContainer = ChatGptAccent,
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = ChatGptLightBackground, onBackground = ChatGptLightOnSurface,
    surface = ChatGptLightSurface, onSurface = ChatGptLightOnSurface,
    surfaceVariant = ChatGptLightSurfaceVariant, onSurfaceVariant = ChatGptLightOnSurfaceVariant,
    outline = ChatGptLightOutline, outlineVariant = ChatGptLightSurfaceVariant, scrim = Color.Black
)

// ── CLAUDE ──────────────────────────────────────────────────
private val ClaudeDark = darkColorScheme(
    primary = ClaudeAccentDark, onPrimary = Color.White,
    primaryContainer = ClaudeAccentDark.copy(alpha = 0.25f), onPrimaryContainer = ClaudeAccentDark,
    secondary = ClaudeDarkOnSurfaceVariant, onSecondary = ClaudeDarkBackground,
    secondaryContainer = ClaudeDarkSurfaceVariant, onSecondaryContainer = ClaudeDarkOnSurface,
    tertiary = ClaudeAccentDark, onTertiary = Color.White,
    tertiaryContainer = ClaudeAccentDark.copy(alpha = 0.2f), onTertiaryContainer = ClaudeAccentDark,
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = ClaudeDarkBackground, onBackground = ClaudeDarkOnSurface,
    surface = ClaudeDarkSurface, onSurface = ClaudeDarkOnSurface,
    surfaceVariant = ClaudeDarkSurfaceVariant, onSurfaceVariant = ClaudeDarkOnSurfaceVariant,
    outline = ClaudeDarkOutline, outlineVariant = ClaudeDarkSurfaceVariant, scrim = Color.Black
)
private val ClaudeLight = lightColorScheme(
    primary = ClaudeAccent, onPrimary = Color.White,
    primaryContainer = ClaudeAccent.copy(alpha = 0.18f), onPrimaryContainer = ClaudeAccent,
    secondary = ClaudeLightOnSurfaceVariant, onSecondary = ClaudeLightBackground,
    secondaryContainer = ClaudeLightSurfaceVariant, onSecondaryContainer = ClaudeLightOnSurface,
    tertiary = ClaudeAccent, onTertiary = Color.White,
    tertiaryContainer = ClaudeAccent.copy(alpha = 0.18f), onTertiaryContainer = ClaudeAccent,
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = ClaudeLightBackground, onBackground = ClaudeLightOnSurface,
    surface = ClaudeLightSurface, onSurface = ClaudeLightOnSurface,
    surfaceVariant = ClaudeLightSurfaceVariant, onSurfaceVariant = ClaudeLightOnSurfaceVariant,
    outline = ClaudeLightOutline, outlineVariant = ClaudeLightSurfaceVariant, scrim = Color.Black
)

// ── CUSTOM ──────────────────────────────────────────────────
private fun customScheme(dark: Boolean, c: CustomTheme) = if (dark) darkColorScheme(
    primary = c.primary.takeOr(OrbitPrimary), onPrimary = c.onBackground.takeOr(Color.White),
    primaryContainer = c.primary.takeOr(OrbitPrimaryContainer), onPrimaryContainer = c.onBackground.takeOr(OrbitOnPrimaryContainer),
    secondary = c.secondary.takeOr(OrbitSecondary), onSecondary = c.onBackground.takeOr(Color.White),
    secondaryContainer = c.surface.takeOr(OrbitSecondaryContainer), onSecondaryContainer = c.onBackground.takeOr(OrbitOnSecondaryContainer),
    tertiary = c.tertiary.takeOr(OrbitTertiary), onTertiary = c.onBackground.takeOr(Color.White),
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = c.background.takeOr(if (dark) OrbitDarkBackground else OrbitLightBackground),
    onBackground = c.onBackground.takeOr(if (dark) OrbitDarkOnSurface else OrbitLightOnSurface),
    surface = c.surface.takeOr(if (dark) OrbitDarkSurface else OrbitLightSurface),
    onSurface = c.onBackground.takeOr(if (dark) OrbitDarkOnSurface else OrbitLightOnSurface),
    surfaceVariant = c.surface.takeOr(if (dark) OrbitDarkSurfaceVariant else OrbitLightSurfaceVariant),
    onSurfaceVariant = c.onBackground.takeOr(if (dark) OrbitDarkOnSurfaceVariant else OrbitLightOnSurfaceVariant),
    outline = c.surface.takeOr(if (dark) OrbitDarkOutline else OrbitLightOutline),
    outlineVariant = c.surface.takeOr(if (dark) OrbitDarkSurfaceVariant else OrbitLightSurfaceVariant),
    scrim = Color.Black
) else lightColorScheme(
    primary = c.primary.takeOr(OrbitPrimary), onPrimary = c.onBackground.takeOr(Color.White),
    primaryContainer = c.primary.takeOr(OrbitPrimaryContainer), onPrimaryContainer = c.onBackground.takeOr(OrbitOnPrimaryContainer),
    secondary = c.secondary.takeOr(OrbitSecondary), onSecondary = c.onBackground.takeOr(Color.White),
    secondaryContainer = c.surface.takeOr(OrbitSecondaryContainer), onSecondaryContainer = c.onBackground.takeOr(OrbitOnSecondaryContainer),
    tertiary = c.tertiary.takeOr(OrbitTertiary), onTertiary = c.onBackground.takeOr(Color.White),
    error = OrbitError, onError = Color.White, errorContainer = OrbitErrorContainer, onErrorContainer = OrbitOnErrorContainer,
    background = c.background.takeOr(if (dark) OrbitDarkBackground else OrbitLightBackground),
    onBackground = c.onBackground.takeOr(if (dark) OrbitDarkOnSurface else OrbitLightOnSurface),
    surface = c.surface.takeOr(if (dark) OrbitDarkSurface else OrbitLightSurface),
    onSurface = c.onBackground.takeOr(if (dark) OrbitDarkOnSurface else OrbitLightOnSurface),
    surfaceVariant = c.surface.takeOr(if (dark) OrbitDarkSurfaceVariant else OrbitLightSurfaceVariant),
    onSurfaceVariant = c.onBackground.takeOr(if (dark) OrbitDarkOnSurfaceVariant else OrbitLightOnSurfaceVariant),
    outline = c.surface.takeOr(if (dark) OrbitDarkOutline else OrbitLightOutline),
    outlineVariant = c.surface.takeOr(if (dark) OrbitDarkSurfaceVariant else OrbitLightSurfaceVariant),
    scrim = Color.Black
)
private fun Color.takeOr(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

@Composable
fun OrbitTheme(
    themeId: ThemeId = ThemeId.NORMAL,
    darkMode: OrbitThemeMode = OrbitThemeMode.SYSTEM,
    custom: CustomTheme = CustomTheme(),
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (darkMode) {
        OrbitThemeMode.SYSTEM -> isSystemInDarkTheme()
        OrbitThemeMode.LIGHT -> false
        OrbitThemeMode.DARK -> true
    }
    val scheme = when (themeId) {
        ThemeId.NORMAL -> if (useDarkTheme) NormalDark else NormalLight
        ThemeId.CHATGPT -> if (useDarkTheme) ChatGptDark else ChatGptLight
        ThemeId.CLAUDE -> if (useDarkTheme) ClaudeDark else ClaudeLight
        ThemeId.CUSTOM -> customScheme(useDarkTheme, custom)
    }
    val bubbleShapes = when (themeId) {
        ThemeId.CHATGPT -> ChatGptBubbles
        ThemeId.CLAUDE -> ClaudeBubbles
        else -> NormalBubbles
    }
    CompositionLocalProvider(LocalBubbleShapes provides bubbleShapes) {
        MaterialTheme(colorScheme = scheme, typography = Typography, shapes = Shapes, content = content)
    }
}

/** Compatibility alias — old code passes an OrbitThemeMode only. */
@Composable
fun OrbitAiTheme(
    themeMode: OrbitThemeMode = OrbitThemeMode.SYSTEM,
    content: @Composable () -> Unit
) = OrbitTheme(ThemeId.NORMAL, themeMode, CustomTheme(), content)

/** Full form used by MainActivity with a stored theme id. */
@Composable
fun OrbitAiTheme(
    themeId: ThemeId = ThemeId.NORMAL,
    themeMode: OrbitThemeMode = OrbitThemeMode.SYSTEM,
    custom: CustomTheme = CustomTheme(),
    content: @Composable () -> Unit
) = OrbitTheme(themeId, themeMode, custom, content)
