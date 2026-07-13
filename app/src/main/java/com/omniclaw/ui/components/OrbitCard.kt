package com.omniclaw.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Single, themed surface used everywhere a card is needed (dashboard stat
 * cards, session rows, provider rows, skill rows...). Replaces the mix of
 * GlassCard / AnimatedGlassCard / raw Surface that made the app look
 * inconsistent. One elevation + corner spec = visual coherence.
 *
 * `tonal` cards use the theme's container color so they read as grouped
 * content rather than floating panels. `interactive` cards get native
 * Material 3 press feedback (ripple) via [interactionSource].
 */
@Composable
fun OrbitCard(
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    interactive: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor = if (tonal)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    else MaterialTheme.colorScheme.surface

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.defaultMinSize(minHeight = 1.dp),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (tonal) 0.dp else 1.dp,
        border = if (!tonal) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        interactionSource = interactionSource
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
