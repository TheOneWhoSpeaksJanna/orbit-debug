package com.orbitai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.data.local.runtime.SkillCategory
import com.orbitai.data.local.runtime.SkillEntry
import com.orbitai.ui.components.OrbitCard
import com.orbitai.ui.theme.OrbitSuccess
import com.orbitai.ui.theme.OrbitWarning
import com.orbitai.ui.theme.staggeredEntrance
import com.orbitai.ui.viewmodels.SkillsViewModel

private const val SECTION_AGENTS = "Agents"
private const val DEFAULT_AGENT_NAME = "Default Agent"
private const val DEFAULT_AGENT_DESC = "General-purpose AI orchestration agent"
private const val SUBTITLE_CAPABILITIES = "Installed agents, skills, and extension modules"

@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel = viewModel(factory = SkillsViewModel.Factory)
) {
    val agents by viewModel.agents.collectAsState()
    val skillCategories by viewModel.skillCategories.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "Skills & Capabilities",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = SUBTITLE_CAPABILITIES,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Active Agents section
        item {
            Text(
                text = SECTION_AGENTS,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (agents.isEmpty()) {
            item {
                AgentCard(
                    name = DEFAULT_AGENT_NAME,
                    description = DEFAULT_AGENT_DESC,
                    icon = Icons.Default.Memory,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    status = "Ready"
                )
            }
        } else {
            items(agents, key = { it.id }) { agent ->
                AgentCard(
                    name = agent.name,
                    description = agent.systemPrompt?.take(80) ?: "Active agent",
                    icon = Icons.Default.Memory,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    status = "Active"
                )
            }
        }

        // Dynamic skill categories from catalog — each skill is a real
        // LazyColumn item so staggeredEntrance fires per item.
        skillCategories.forEach { category ->
            item(key = "category_header_${category.name}") {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(
                items = category.skills,
                key = { _, skill -> "${category.name}_${skill.id}" }
            ) { index, skill ->
                SkillCard(
                    skill = skill,
                    icon = getSkillIcon(skill.id, category.name),
                    modifier = Modifier.staggeredEntrance(index, itemId = skill.id)
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillEntry,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val statusColor = if (skill.enabled) OrbitSuccess else OrbitWarning
    val statusText = if (skill.builtIn) "Built-in" else if (skill.enabled) "Available" else "Disabled"

    OrbitCard(
        modifier = modifier.fillMaxWidth(),
        tonal = true
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    name: String,
    description: String,
    icon: ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    status: String,
    modifier: Modifier = Modifier
) {
    val statusColor = if (status == "Active") OrbitSuccess else OrbitWarning

    OrbitCard(
        modifier = modifier.fillMaxWidth(),
        tonal = true
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = accentColor
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getSkillIcon(skillId: String, categoryName: String): ImageVector {
    return when {
        skillId.contains("android") || skillId.contains("shizuku") -> Icons.Default.PhoneAndroid
        categoryName.contains("Code") || skillId.contains("commit") || skillId.contains("diff") -> Icons.Default.Code
        categoryName.contains("AI") || skillId.contains("model") -> Icons.Default.SmartToy
        categoryName.contains("Debug") -> Icons.Default.Build
        else -> Icons.Default.Memory
    }
}
