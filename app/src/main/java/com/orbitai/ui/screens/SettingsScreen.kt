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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.orbitai.ui.theme.ThemeId
import com.orbitai.ui.theme.CustomTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Palette
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    val updateChecking by viewModel.updateChecking.collectAsState()
    val updateResult by viewModel.updateResult.collectAsState()
    val updateInstalling by viewModel.updateInstalling.collectAsState()
    val updateInstallResult by viewModel.updateInstallResult.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

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
            // ── Appearance card ─────────────────────────────────────────
            OrbitCard(
                tonal = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Theme preset selector (Normal / ChatGPT / Claude / Custom)
                    val themeOptions = ThemeId.entries.toList()
                    val currentThemeId = viewModel.themeId.collectAsState().value
                    themeOptions.forEach { tid ->
                        val selected = currentThemeId == tid.key
                        ThemePreviewRow(
                            themeId = tid,
                            selected = selected,
                            onClick = { viewModel.updateThemeId(tid.key) }
                        )
                        if (tid != themeOptions.last()) Spacer(Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Light/Dark mode
                    var modeExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = modeExpanded,
                        onExpandedChange = { modeExpanded = !modeExpanded }
                    ) {
                        OutlinedTextField(
                            value = themeMode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.theme_mode)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        )
                        ExposedDropdownMenu(
                            expanded = modeExpanded,
                            onDismissRequest = { modeExpanded = false }
                        ) {
                            THEME_OPTIONS.forEach { selection ->
                                DropdownMenuItem(
                                    text = { Text(selection) },
                                    onClick = {
                                        viewModel.updateThemeMode(selection)
                                        modeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Custom theme editor
                    if (currentThemeId == ThemeId.CUSTOM.key) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Custom colors", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        val custom = viewModel.custom.collectAsState().value
                        CustomColorEditor(custom = custom) { slot, color ->
                            viewModel.updateCustomColor(slot, color)
                        }
                    }
                }
            }

            // ── Update system card ──────────────────────────────────────
            OrbitCard(
                tonal = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "App Update",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Version ${viewModel.appVersion}  (build ${viewModel.appVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OrbitButton(
                        onClick = { viewModel.checkForUpdate() },
                        enabled = !updateChecking && !updateInstalling,
                        variant = OrbitButtonVariant.Primary
                    ) {
                        Text(if (updateChecking) "Checking…" else "Check for updates")
                    }

                    // Result of the check
                    updateResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            res.message.ifBlank { (if (res.available) "Update available" else "Up to date") },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (res.available) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (res.available && res.apkUrl != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OrbitButton(
                                onClick = { viewModel.installUpdate(res.apkUrl) },
                                enabled = !updateInstalling,
                                variant = OrbitButtonVariant.Primary
                            ) {
                                Text(if (updateInstalling) "Downloading & installing…" else "Download & install ${res.tag}")
                            }
                        }
                    }

                    // Result of the install
                    updateInstallResult?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        when (res) {
                            is com.orbitai.data.local.updater.UpdateInstallResult.Success ->
                                Text("Updated successfully — restarting…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            is com.orbitai.data.local.updater.UpdateInstallResult.Failure ->
                                Text("Update failed: ${res.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            is com.orbitai.data.local.updater.UpdateInstallResult.NeedsManualInstall -> {
                                Text("Tap to finish installing in the system installer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                OrbitButton(
                                    onClick = {
                                        try {
                                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(res.apkUri, "application/vnd.android.package-archive")
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(ctx, "Couldn't open installer: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    variant = OrbitButtonVariant.Primary
                                ) { Text("Open installer") }
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

/** A selectable row showing a theme name + a 3-swatch preview of its palette. */
@Composable
private fun ThemePreviewRow(
    themeId: ThemeId,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (bg, surface, accent) = when (themeId) {
        ThemeId.NORMAL -> listOf(Color(0xFF16110F), Color(0xFF241C19), Color(0xFFD97757))
        ThemeId.CHATGPT -> listOf(Color(0xFFFFFFFF), Color(0xFFF9F9F9), Color(0xFF10A37F))
        ThemeId.CLAUDE -> listOf(Color(0xFFF5F1EA), Color(0xFFFBF8F4), Color(0xFFCC785C))
        ThemeId.CUSTOM -> listOf(Color(0xFF202020), Color(0xFF2A2A2A), Color(0xFF9C6ADE))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview swatches
        Row {
            listOf(bg, surface, accent).forEach { c ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(c)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            themeId.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Color picker for a custom theme: one row of preset swatches per slot. */
@Composable
private fun CustomColorEditor(
    custom: CustomTheme,
    onPick: (String, Color) -> Unit
) {
    val slots = listOf(
        "background" to "Background",
        "surface" to "Surface",
        "primary" to "Accent",
        "onBackground" to "Text",
        "secondary" to "Secondary",
        "tertiary" to "Tertiary"
    )
    val palette = listOf(
        0xFF16110F, 0xFF241C19, 0xFFFFFFFF, 0xFFF9F9F9, 0xFFF5F1EA, 0xFFFBF8F4,
        0xFFD97757, 0xFF10A37F, 0xFFCC785C, 0xFF9C6ADE, 0xFF3B82F6, 0xFFEF4444,
        0xFF22C55E, 0xFFEAB308, 0xFF0EA5E9, 0xFF000000, 0xFF6B7280, 0xFFFFFFFF
    ).map { Color(it.toLong()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { (slot, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    userScrollEnabled = false
                ) {
                    items(palette) { c ->
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(c)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .clickable { onPick(slot, c) }
                        )
                    }
                }
            }
        }
    }
}
