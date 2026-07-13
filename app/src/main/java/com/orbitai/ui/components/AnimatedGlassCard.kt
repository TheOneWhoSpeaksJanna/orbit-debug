package com.orbitai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbitai.ui.theme.pressScale

private const val DEFAULT_RADIUS = 16

/**
 * Clean Material 3 card — replaces the old AnimatedGlassCard.
 *
 * No glassmorphism, no blur, no custom draw code. Just a standard
 * Material 3 Surface with the theme's surface color and a subtle
 * press-scale animation. Fast, clean, works on all devices.
 */
@Composable
fun AnimatedGlassCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    radius: Int = DEFAULT_RADIUS,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = remember(radius) { RoundedCornerShape(radius.dp) }

    val surfaceModifier = modifier
        .pressScale(interactionSource)

    Surface(
        modifier = if (onClick != null) {
            surfaceModifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
        } else {
            surfaceModifier
        },
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Box(content = content)
    }
}
