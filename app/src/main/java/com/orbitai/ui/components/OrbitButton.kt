package com.orbitai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Single, themed button used across every screen — replaces the ad-hoc
 * `.background(MaterialTheme.colorScheme.primary)` Box+clickable blocks that
 * were duplicated 3x in the setup wizard alone (and in several other screens).
 *
 * Using one component guarantees consistent shape, elevation, press feedback and
 * disabled state everywhere, which is half the "ugly/inconsistent" problem.
 *
 * Press feedback comes from Material 3's native Surface indication
 * (ripple) driven by [interactionSource] — no custom draw-phase hack needed.
 */
@Composable
fun OrbitButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: OrbitButtonVariant = OrbitButtonVariant.Primary,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val (containerColor, contentColor) = when (variant) {
        OrbitButtonVariant.Primary ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        OrbitButtonVariant.Tonal ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        OrbitButtonVariant.Outlined ->
            Color.Transparent to MaterialTheme.colorScheme.primary
    }
    val border = if (variant == OrbitButtonVariant.Outlined)
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        interactionSource = interactionSource
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            content = content
        )
    }
}

enum class OrbitButtonVariant { Primary, Tonal, Outlined }

/** Convenience full-width primary button (the most common call site). */
@Composable
fun OrbitPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    OrbitButton(onClick = onClick, modifier = modifier, enabled = enabled,
        variant = OrbitButtonVariant.Primary) {
        androidx.compose.material3.Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
