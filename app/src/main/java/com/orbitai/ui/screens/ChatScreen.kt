package com.orbitai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.core.commands.ChatSlashCommands
import com.orbitai.domain.models.MessageRole
import com.orbitai.ui.components.ModelBrowserSheet
import com.orbitai.ui.theme.staggeredEntrance
import com.orbitai.ui.viewmodels.ChatViewModel

private const val DEFAULT_TITLE = "Chat"
private const val MESSAGE_PLACEHOLDER = "Message..."
private const val WELCOME_TITLE = "What can I help with?"
private const val WELCOME_SUBTITLE = "Ask anything — research, code, writing, or just a question."
private const val NO_AGENT_TITLE = "No Agent Selected"
private const val NO_AGENT_SUBTITLE = "Install an agent from Skills to start chatting."
private const val CD_BACK = "Back"
private const val CD_SELECT_MODEL = "Select model"
private const val CD_SEND = "Send"
private const val CD_ATTACH = "Attach"
private const val ANIMATION_DURATION_MS = 900
private val BUBBLE_MAX_WIDTH = 480.dp

private val FALLBACK_MODELS = listOf(
    "tencent/hy3:free", "gemini-2.0-flash-exp", "gpt-4o", "claude-sonnet-4-20250514"
)
private val ANIMATION_DELAYS = listOf(0, 150, 300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateBack: () -> Unit,
    onSessionIdResolved: (String?) -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
) {
    val currentSession by viewModel.currentSession.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val thinkingModel by viewModel.thinkingModel.collectAsState()
    val useLocalMode by viewModel.useLocalMode.collectAsState()
    val detailedModels by viewModel.detailedModels.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val hasAgent by viewModel.hasAgent.collectAsState()
    val pendingCommand by viewModel.pendingCommand.collectAsState()
    val streamLines by viewModel.streamLines.collectAsState()
    val showTranscript by viewModel.showTranscript.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showModelBrowser by remember { mutableStateOf(false) }
    var showThinkingBrowser by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    var expandedTranscript by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Wire the slash-command /skills navigation.
    LaunchedEffect(Unit) { viewModel.onNavigateToSkills = onNavigateToSkills }

    // Attachment launchers — multi-select so several files/images can be
    // attached to one message (not one at a time).
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<Uri> -> uris.forEach { viewModel.attachFile(it) } }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> uris.forEach { viewModel.attachFile(it) } }

    val attachItems = listOf(
        AttachOption("Images", Icons.Default.Image) {
            imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        AttachOption("Photos", Icons.Default.PhotoLibrary) {
            imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        AttachOption("Files", Icons.Default.AttachFile) { filePicker.launch("*/*") },
        AttachOption("Plugins / Skills", Icons.Default.Extension) { showSkillsSheet = true; showAttachMenu = false }
    )

    // Slash palette: visible when input starts with "/"
    val slashQuery = inputText
    val slashMatches by remember(inputText) {
        derivedStateOf { ChatSlashCommands.filter(inputText) }
    }
    val showSlashPalette = inputText.startsWith("/") && slashMatches.isNotEmpty()

    LaunchedEffect(sessionId) {
        viewModel.loadModelsForCurrentProvider()
        viewModel.fetchDetailedModels()
        if (sessionId.isNullOrEmpty()) {
            if (viewModel.currentSession.value == null) {
                // Resume the most-recent session (or create one) instead of
                // always spawning a new "New Session". This prevents orphan
                // sessions piling up in History every time the Chat tab opens.
                viewModel.resumeOrCreateSession()
            } else {
                viewModel.loadSession(viewModel.currentSession.value!!.id)
            }
        } else {
            viewModel.loadSession(sessionId)
        }
        onSessionIdResolved(viewModel.currentSession.value?.id)
    }

    val lastMessageId = messages.lastOrNull()?.id
    val isNearBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(lastMessageId, streamLines.size) {
        if (lastMessageId != null && isNearBottom) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            currentSession?.title ?: DEFAULT_TITLE,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        SuggestionChip(
                            onClick = { viewModel.toggleLocalMode() },
                            label = {
                                Text(
                                    if (useLocalMode) "Local" else "Cloud",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        val displayModels = availableModels.ifEmpty { FALLBACK_MODELS }
                        Row(
                            modifier = Modifier
                                .clickable {
                                    if (detailedModels.isNotEmpty()) {
                                        showModelBrowser = true
                                    } else {
                                        showModelDropdown = true
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedModel.ifBlank { displayModels.first() }
                                    .substringAfterLast('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 90.dp)
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = CD_SELECT_MODEL,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            if (detailedModels.isEmpty()) {
                                DropdownMenu(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false }
                                ) {
                                    displayModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                viewModel.setSelectedModel(model)
                                                showModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        // Thinking-model bar: pick the reasoning model. Sits right
                        // next to the change-model bar.
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                                .clickable { showThinkingBrowser = true }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = "Thinking model",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = if (thinkingModel.isBlank()) "Thinking"
                                else "💭 " + thinkingModel.substringAfterLast('/').take(12),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CD_BACK)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasAgent) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (messages.isEmpty() && !isLoading && !showTranscript) {
                        item { WelcomePlaceholder() }
                    }
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            content = message.content,
                            isUser = message.role == MessageRole.USER,
                            modifier = Modifier.staggeredEntrance(index = 0, itemId = message.id)
                        )
                    }
                    // Live transcript (transparency): shows the agent's raw
                    // stdout/stderr as it streams, so nothing is hidden.
                    if (showTranscript) {
                        item {
                            TranscriptBlock(
                                lines = streamLines,
                                expanded = expandedTranscript,
                                onToggleExpand = { expandedTranscript = !expandedTranscript }
                            )
                        }
                    }
                    if (isLoading) {
                        item { LoadingBubble() }
                    }
                }

                // Slash-command palette
                AnimatedVisibility(
                    visible = showSlashPalette,
                    enter = fadeIn(spring()) + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut(spring())
                ) {
                    SlashPalette(
                        matches = slashMatches,
                        onSelect = { cmd ->
                            if (cmd.immediate) {
                                viewModel.sendMessage("/${cmd.name}")
                                inputText = ""
                            } else {
                                inputText = "/${cmd.name} "
                            }
                        }
                    )
                }

                // Attachment chips
                val attachments by viewModel.attachments.collectAsState()
                AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    AttachmentChips(
                        attachments = attachments,
                        onRemove = { viewModel.removeAttachment(it) }
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtMost(8.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // + attach button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ) {
                            IconButton(onClick = { showAttachMenu = true }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = CD_ATTACH,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    MESSAGE_PLACEHOLDER,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp)),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            singleLine = false,
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (inputText.isNotBlank() || attachments.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ) {
                            IconButton(
                                onClick = {
                                    if ((inputText.isNotBlank() || attachments.isNotEmpty()) && !isLoading) {
                                        // Send ONLY the user's text. Attachment
                                        // files are copied to the agent's rootfs
                                        // and described to the model inside
                                        // prepareAttachmentsForAgent() — we must
                                        // NOT inject the file name as chat text,
                                        // or the model only "sees" a filename
                                        // (the old fake-attachment bug).
                                        val toSend = inputText.trim()
                                        viewModel.sendMessage(toSend)
                                        inputText = ""
                                        viewModel.clearAttachments()
                                    }
                                },
                                enabled = (inputText.isNotBlank() || attachments.isNotEmpty()) && !isLoading
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = CD_SEND,
                                    tint = if (inputText.isNotBlank() || attachments.isNotEmpty())
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }
                    }
                }
            } else {
                NoAgentPlaceholder()
            }
        }
    }

    if (showModelBrowser) {
        Dialog(
            onDismissRequest = { showModelBrowser = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ModelBrowserSheet(
                models = detailedModels,
                selectedModelId = selectedModel,
                isLoading = isFetchingModels,
                onModelSelected = { modelId ->
                    viewModel.setSelectedModel(modelId)
                    showModelBrowser = false
                },
                onDismiss = { showModelBrowser = false }
            )
        }
    }

    if (showThinkingBrowser) {
        Dialog(
            onDismissRequest = { showThinkingBrowser = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ModelBrowserSheet(
                models = detailedModels,
                selectedModelId = thinkingModel,
                isLoading = isFetchingModels,
                onModelSelected = { modelId ->
                    viewModel.setThinkingModel(modelId)
                    showThinkingBrowser = false
                },
                onDismiss = { showThinkingBrowser = false }
            )
        }
    }

    // Attachment menu (does NOT jump to file picker until an item is chosen)
    if (showAttachMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachMenu = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text(
                "Attach",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            attachItems.forEach { opt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            opt.action()
                            if (opt.label != "Plugins / Skills") showAttachMenu = false
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(opt.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Skills / MCP / Slash-commands sheet
    if (showSkillsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSkillsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            SkillsAndCommandsSheet(
                onDismiss = { showSkillsSheet = false },
                onRunCommand = { viewModel.sendMessage(it) }
            )
        }
    }

    pendingCommand?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.denyPendingCommand() },
            title = { Text("Allow Command?") },
            text = {
                Column {
                    Text("The agent wants to run:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = pending.command,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (pending.isSudo) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This command will run with elevated privileges (sudo).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmPendingCommand() }) { Text("Allow") }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.denyPendingCommand() }) { Text("Deny") }
            }
        )
    }
}

private data class AttachOption(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

@Composable
private fun TranscriptBlock(
    lines: List<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Transcript · agent is working…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        val shown = if (lines.size > 200) lines.takeLast(200) else lines
                        shown.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        // live cursor
                        Text(
                            "▌",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashPalette(
    matches: List<com.orbitai.core.commands.SlashCommand>,
    onSelect: (com.orbitai.core.commands.SlashCommand) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                "Commands",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            matches.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(cmd) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("/${cmd.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChips(
    attachments: List<ChatViewModel.AttachmentItem>,
    onRemove: (ChatViewModel.AttachmentItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { att ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(att.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRemove(att) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(content: String, isUser: Boolean, modifier: Modifier = Modifier) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(content))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                Text(
                    text = content,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                modifier = Modifier
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(content))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                Text(
                    text = content,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun LoadingBubble() {
    val infiniteTransition = rememberInfiniteTransition(label = "loadingDots")
    val baseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = ANIMATION_DURATION_MS
                0.3f at 0
                1.0f at 450
                0.3f at ANIMATION_DURATION_MS
            }
        ),
        label = "dotAlphaBase"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ANIMATION_DELAYS.forEachIndexed { i, _ ->
                    val phase = (i + 1) / 3f
                    val alpha = (baseAlpha - 0.3f) * phase + 0.3f
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = alpha.coerceIn(0.3f, 1f))
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            WELCOME_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            WELCOME_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoAgentPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(NO_AGENT_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(NO_AGENT_SUBTITLE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
