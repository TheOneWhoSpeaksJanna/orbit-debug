package com.orbitai.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
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
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
) {
    val currentSession by viewModel.currentSession.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val useLocalMode by viewModel.useLocalMode.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val detailedModels by viewModel.detailedModels.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val hasAgent by viewModel.hasAgent.collectAsState()
    val pendingCommand by viewModel.pendingCommand.collectAsState()
    var showModelBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.loadModelsForCurrentProvider()
        viewModel.fetchDetailedModels()
        if (sessionId.isNullOrEmpty()) {
            viewModel.startNewSession(null)
        } else {
            viewModel.loadSession(sessionId)
        }
    }

    // Stick-to-bottom: only auto-scroll when the user is already near the
    // bottom. Keyed on the LAST message id (not messages.size) so a streamed
    // token that changes content but not id doesn't re-trigger a scroll, and
    // scrolling up to read history is never yanked back down. scrollToItem
    // (not animateScrollToItem) avoids a per-frame spring recomposition
    // while the agent streams — the main source of chat jank.
    val lastMessageId = messages.lastOrNull()?.id
    val isNearBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(lastMessageId) {
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
                            style = MaterialTheme.typography.titleMedium
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
                                text = selectedModel.ifBlank { displayModels.first() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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
                    if (messages.isEmpty() && !isLoading) {
                        item {
                            WelcomePlaceholder()
                        }
                    }
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            content = message.content,
                            isUser = message.role == MessageRole.USER,
                            modifier = Modifier.staggeredEntrance(index = 0, itemId = message.id)
                        )
                    }
                    if (isLoading) {
                        item {
                            LoadingBubble()
                        }
                    }
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
                            singleLine = true,
                            maxLines = 1,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (inputText.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ) {
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank() && !isLoading) {
                                        viewModel.sendMessage(inputText.trim())
                                        inputText = ""
                                    }
                                },
                                enabled = inputText.isNotBlank() && !isLoading
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = CD_SEND,
                                    tint = if (inputText.isNotBlank())
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

    pendingCommand?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.denyPendingCommand() },
            title = { Text("Allow Command?") },
            text = {
                Column {
                    Text(
                        "The agent wants to run:",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                Button(onClick = { viewModel.confirmPendingCommand() }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.denyPendingCommand() }) {
                    Text("Deny")
                }
            }
        )
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
                            android.widget.Toast
                                .makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT)
                                .show()
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
                            android.widget.Toast
                                .makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT)
                                .show()
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
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            NO_AGENT_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            NO_AGENT_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
