package com.orbitai.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.ui.viewmodels.TermuxViewModel
import rikka.shizuku.Shizuku

private const val TITLE = "Terminal"
private const val CD_BACK = "Back"
private const val CD_EXECUTE = "Execute"
private const val CD_COPY = "Copy"
private const val INPUT_LABEL = "Type a shell command..."
private const val SUDO_LABEL = "Sudo"
private const val COPIED_TOAST = "Copied to clipboard"

private val CARD_BG = Color(0xFF0F172A)
private val CMD_COLOR = Color(0xFF00F2FE)
private val SUCCESS_TEXT = Color(0xFFE2E8F0)
private val ERROR_TEXT = Color(0xFFFCA5A5)
private val DIVIDER_COLOR = Color(0xFF1E293B)

/**
 * Terminal screen — a real Linux terminal connected to the Termux rootfs.
 *
 * No install buttons (node/git/python are pre-bundled).
 * No hardcoded commands.
 * No confirmation dialogs — just type and press Enter, like a real terminal.
 *
 * Commands run inside the Termux rootfs via PRoot, giving the user access to
 * bash, node, npm, git, python, apt, and everything else in the rootfs.
 *
 * The Sudo button routes commands through Shizuku for Android system-level
 * access (settings, input, am, pm, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxScreen(
    onNavigateBack: () -> Unit,
    viewModel: TermuxViewModel = viewModel(factory = TermuxViewModel.Factory)
) {
    val logs by viewModel.logs.collectAsState()
    var commandText by remember { mutableStateOf("") }
    var executeAsSudo by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var isShizukuActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val isInstalled = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        isShizukuActive = isInstalled
                && Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    val submitCommand = {
        if (commandText.isNotBlank()) {
            if (executeAsSudo && isShizukuActive) {
                viewModel.executePrivilegedCommand(commandText.trim())
            } else {
                viewModel.executeCommand(commandText.trim())
            }
            commandText = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TITLE, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CD_BACK)
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
        ) {
            // Log output — newest at the bottom, auto-scrolls
            val reversedLogs = remember(logs) { logs.asReversed() }
            val clipboardManager = LocalClipboardManager.current
            val ctx = LocalContext.current

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                reverseLayout = true
            ) {
                items(reversedLogs, key = { it.id }) { log ->
                    TerminalLogCard(
                        command = log.command,
                        output = log.output,
                        exitCode = log.exitCode,
                        onCopy = {
                            val text = "$ ${log.command}\n${log.output}"
                            clipboardManager.setText(AnnotatedString(text))
                            android.widget.Toast.makeText(ctx, COPIED_TOAST, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // Command input bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        label = { Text(INPUT_LABEL) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { submitCommand() }
                        )
                    )

                    // Sudo toggle — only visible if Shizuku is active
                    if (isShizukuActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = executeAsSudo,
                            onClick = { executeAsSudo = !executeAsSudo },
                            label = {
                                Text(
                                    SUDO_LABEL,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = { submitCommand() },
                        containerColor = if (executeAsSudo && isShizukuActive)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = CD_EXECUTE)
                    }
                }
            }
        }
    }
}

/**
 * One terminal log entry. Shows the command, output, and a copy button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalLogCard(
    command: String,
    output: String,
    exitCode: Int,
    onCopy: () -> Unit
) {
    val isSuccess = exitCode == 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = onCopy
            ),
        colors = CardDefaults.cardColors(containerColor = CARD_BG),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$ $command",
                    color = CMD_COLOR,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = CD_COPY,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = output.ifBlank { "(no output)" },
                color = if (isSuccess) SUCCESS_TEXT else ERROR_TEXT,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 50,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (exitCode != 0 && exitCode != -1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "[exit $exitCode]",
                    color = ERROR_TEXT.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = DIVIDER_COLOR, thickness = 1.dp)
        }
    }
}
