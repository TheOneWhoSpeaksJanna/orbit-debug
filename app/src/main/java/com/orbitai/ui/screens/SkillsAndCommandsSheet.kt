package com.orbitai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitai.core.commands.ChatSlashCommands
import com.orbitai.data.local.runtime.SkillsCatalog

@Composable
fun SkillsAndCommandsSheet(
    onDismiss: () -> Unit,
    onRunCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val categories by remember { mutableStateOf(SkillsCatalog.load(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            "Plugins / Skills",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Run an original command or open a skill. MCP servers are listed below when configured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Original slash commands
            item {
                SectionHeader("Commands")
            }
            items(ChatSlashCommands.ALL) { cmd ->
                SheetRow(
                    icon = Icons.Default.Terminal,
                    title = "/${cmd.name}",
                    subtitle = cmd.description
                ) {
                    onRunCommand("/${cmd.name}")
                    onDismiss()
                }
            }

            // Skills from the catalog
            categories.forEach { category ->
                item { SectionHeader(category.name) }
                items(category.skills.take(8)) { skill ->
                    SheetRow(
                        icon = Icons.Default.Extension,
                        title = skill.name,
                        subtitle = skill.description
                    ) {
                        onRunCommand("/skills")
                        onDismiss()
                    }
                }
            }

            // MCP placeholder
            item { SectionHeader("MCP Servers") }
            item {
                SheetRow(
                    icon = Icons.Default.Extension,
                    title = "No MCP servers configured",
                    subtitle = "Add MCP servers in Settings to use them here."
                ) { onDismiss() }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
