package com.orbitai.ui.screens

import com.orbitai.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.ui.viewmodels.SettingsViewModel
import com.orbitai.domain.models.AgentPermissionLevel
import com.orbitai.domain.models.Skill
import com.orbitai.ui.components.OrbitCard
import com.orbitai.ui.components.OrbitButton
import com.orbitai.ui.components.OrbitButtonVariant
import com.orbitai.ui.theme.staggeredEntrance

private const val THEME_SYSTEM = "System"
private const val THEME_DARK = "Dark"
private const val THEME_LIGHT = "Light"
private const val SECTION_PERMISSION = "Agent Permissions"
private const val PERMISSION_LABEL = "Permission Level"
private const val RULES_LABEL = "Agent Rules"
private const val RULES_ALLOWED_LABEL = "Allowed"
private const val RULES_ASK_LABEL = "Ask"
private const val RULES_DENIED_LABEL = "Not Allowed"
private const val RULES_PLACEHOLDER = "One rule per line..."
private const val NORMAL_DESC = "Always asks before taking actions"
private const val RULES_DESC = "Follows defined rules"
private const val FULL_ACCESS_DESC = "Never asks, full autonomy"
private const val SECTION_SKILLS = "Skills"
private const val EDIT_SKILL = "Edit"
private const val SAVE = "Save"
private const val CANCEL = "Cancel"
private const val SKILL_CONTENT_HINT = "Enter skill instructions..."
private const val ENABLED = "Enabled"
private const val DISABLED = "Disabled"

private val THEME_OPTIONS = listOf(THEME_SYSTEM, THEME_DARK, THEME_LIGHT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val agentPermissionLevel by viewModel.agentPermissionLevel.collectAsState()
    val agentRulesAllowed by viewModel.agentRulesAllowed.collectAsState()
    val agentRulesAsk by viewModel.agentRulesAsk.collectAsState()
    val agentRulesDenied by viewModel.agentRulesDenied.collectAsState()
    val skills by viewModel.skills.collectAsState()

    var editSkill by remember { mutableStateOf<Skill?>(null) }
    var editContent by remember { mutableStateOf("") }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OrbitCard(
                tonal = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = themeMode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.theme_mode)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            THEME_OPTIONS.forEach { selection ->
                                DropdownMenuItem(
                                    text = { Text(selection) },
                                    onClick = {
                                        viewModel.updateThemeMode(selection)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            OrbitCard(
                tonal = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(SECTION_PERMISSION, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    var levelExpanded by remember { mutableStateOf(false) }
                    val permissionOptions = AgentPermissionLevel.entries.toList()
                    val currentLevel = AgentPermissionLevel.fromValue(agentPermissionLevel)

                    ExposedDropdownMenuBox(
                        expanded = levelExpanded,
                        onExpandedChange = { levelExpanded = !levelExpanded }
                    ) {
                        OutlinedTextField(
                            value = currentLevel.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(PERMISSION_LABEL) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = levelExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        )
                        ExposedDropdownMenu(
                            expanded = levelExpanded,
                            onDismissRequest = { levelExpanded = false }
                        ) {
                            permissionOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        viewModel.updateAgentPermissionLevel(option.name)
                                        levelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val desc = when (currentLevel) {
                        AgentPermissionLevel.NORMAL -> NORMAL_DESC
                        AgentPermissionLevel.RULES -> RULES_DESC
                        AgentPermissionLevel.FULL_ACCESS -> FULL_ACCESS_DESC
                    }
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (currentLevel == AgentPermissionLevel.RULES) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = agentRulesAllowed,
                            onValueChange = { viewModel.updateAgentRulesAllowed(it) },
                            label = { Text(RULES_ALLOWED_LABEL) },
                            placeholder = { Text(RULES_PLACEHOLDER) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            shape = MaterialTheme.shapes.medium,
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = agentRulesAsk,
                            onValueChange = { viewModel.updateAgentRulesAsk(it) },
                            label = { Text(RULES_ASK_LABEL) },
                            placeholder = { Text(RULES_PLACEHOLDER) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            shape = MaterialTheme.shapes.medium,
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = agentRulesDenied,
                            onValueChange = { viewModel.updateAgentRulesDenied(it) },
                            label = { Text(RULES_DENIED_LABEL) },
                            placeholder = { Text(RULES_PLACEHOLDER) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            shape = MaterialTheme.shapes.medium,
                            maxLines = 6
                        )
                    }
                }
            }

            // Skills Card
            if (skills.isNotEmpty()) {
                OrbitCard(
                    tonal = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(SECTION_SKILLS, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        skills.forEachIndexed { index, skill ->
                            SkillCard(
                                skill = skill,
                                isExpanded = expandedSkillId == skill.id,
                                onToggleExpanded = {
                                    expandedSkillId = if (expandedSkillId == skill.id) null else skill.id
                                },
                                onToggleEnabled = { enabled ->
                                    viewModel.toggleSkillEnabled(skill.id, enabled)
                                },
                                onEdit = {
                                    editSkill = skill
                                    editContent = skill.content
                                },
                                modifier = Modifier.staggeredEntrance(index, itemId = skill.id)
                            )
                            if (skill != skills.last()) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }

            // ── Diagnostics ──────────────────────────────────────────────
            // Shows the active log directory path so users can find their logs
            // (previously, logs went to an app-private dir that was effectively
            // invisible without root — users reported "the log system doesn't
            // work"). The path is also copyable to clipboard.
            OrbitCard(
                tonal = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                val logPath = remember { com.orbitai.core.logging.FileLogger.getLogDirPath() }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Log directory:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = logPath ?: "(not initialized)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tip: run 'adb logcat -s Orbit AI' to see logs in real time. " +
                        "File logs are written to the path above — tap the button to copy it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OrbitButton(
                        onClick = {
                            logPath?.let {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                                android.widget.Toast.makeText(ctx, "Log path copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = logPath != null,
                        variant = OrbitButtonVariant.Outlined
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Copy log path")
                    }
                }
            }

        }

        // Skill edit dialog
        if (editSkill != null) {
            AlertDialog(
                onDismissRequest = { editSkill = null },
                title = { Text("Edit: ${editSkill?.name}") },
                text = {
                    Column {
                        Text("Skill content defines what the AI knows about this capability.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editContent,
                            onValueChange = { editContent = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                            shape = MaterialTheme.shapes.medium,
                            placeholder = { Text(SKILL_CONTENT_HINT) },
                            maxLines = 30
                        )
                    }
                },
                confirmButton = {
                    OrbitButton(
                        onClick = {
                            editSkill?.let { viewModel.updateSkillContent(it.id, editContent) }
                            editSkill = null
                        },
                        variant = OrbitButtonVariant.Primary
                    ) {
                        Text(SAVE)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editSkill = null }) { Text(CANCEL) }
                }
            )
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    OrbitCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    if (skill.enabled) ENABLED else DISABLED,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (skill.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggleEnabled
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onEdit) { Text(EDIT_SKILL) }
            TextButton(onClick = onToggleExpanded) {
                Text(if (isExpanded) "Hide Content" else "View Content")
            }
        }
        if (isExpanded) {
            Spacer(Modifier.height(8.dp))
            OrbitCard(tonal = true) {
                Text(
                    text = skill.content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
