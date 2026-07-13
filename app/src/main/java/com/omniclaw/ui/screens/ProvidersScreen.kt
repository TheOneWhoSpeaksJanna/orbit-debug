package com.omniclaw.ui.screens

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniclaw.ui.components.BrandIcons
import com.omniclaw.ui.components.OrbitButton
import com.omniclaw.ui.components.OrbitButtonVariant
import com.omniclaw.ui.components.OrbitCard
import com.omniclaw.ui.theme.MotionTokens
import com.omniclaw.ui.theme.staggeredEntrance
import com.omniclaw.ui.viewmodels.ConnectionState
import com.omniclaw.ui.viewmodels.ProviderConfig
import com.omniclaw.ui.viewmodels.ProvidersViewModel
import com.omniclaw.ui.theme.OrbitSuccess
import com.omniclaw.ui.theme.OrbitWarning

private const val TITLE = "API Providers"
private const val SUBTITLE = "Verify connectivity and manage endpoint configurations"
private const val NO_API_KEY_LABEL = "No API key configured"
private const val API_KEY_SET_LABEL = "API key set"
private const val NO_KEY_REQUIRED_LABEL = "No key required"
private const val CD_VERIFY = "Verify"
private const val CD_TESTING = "Testing"
private const val CD_EDIT = "Edit Key"
private const val STATUS_NOT_VERIFIED = "Not verified"
private const val STATUS_VERIFYING = "Verifying connection..."
private const val STATUS_CONNECTED = "Connected"
private const val STATUS_OFFLINE = "Offline / No connection"

private const val KEY_DIALOG_TITLE = "API Key"
private const val KEY_DIALOG_LABEL = "Paste your API key here"
private const val KEY_DIALOG_SAVE = "Save"
private const val KEY_DIALOG_DELETE = "Delete Key"
private const val KEY_DIALOG_CANCEL = "Cancel"
private const val KEY_DELETE_CONFIRM = "Remove this API key?"

// Brand colors are intentionally fixed - they identify the provider, not the app theme.
private val PROVIDER_COLORS = mapOf(
    "Claude" to Color(0xFFCC7832),
    "Anthropic Claude" to Color(0xFFCC7832),
    "OpenAI" to Color(0xFF10A37F),
    "Gemini" to Color(0xFF4285F4),
    "Google Gemini" to Color(0xFF4285F4),
    "OpenRouter" to Color(0xFFFF6B35),
    "DeepSeek" to Color(0xFF4F6CF7),
    "Groq" to Color(0xFFF97316),
    "Ollama" to Color(0xFF8B5CF6),
    "Ollama (Local)" to Color(0xFF8B5CF6),
    "Z.AI (Free GLM)" to Color(0xFF6366F1),
    "Z.AI" to Color(0xFF6366F1),
    "xAI (Grok)" to Color(0xFF000000),
    "Mistral AI" to Color(0xFFFF7000),
    "Together AI" to Color(0xFF00A170),
    "GitHub Copilot" to Color(0xFF24292E),
    "NVIDIA NIM" to Color(0xFF76B900),
    "LM Studio (Local)" to Color(0xFF6366F1),
    "AWS Bedrock" to Color(0xFFFF9900),
    "Google Vertex AI" to Color(0xFF4285F4),
    "Azure OpenAI" to Color(0xFF0078D4),
    "Venice AI" to Color(0xFFE5B53A),
    "Fireworks AI" to Color(0xFFE25822),
    "Moonshot (Kimi)" to Color(0xFF1A1A1A),
    "MiniMax" to Color(0xFFE60012),
    "Near AI" to Color(0xFF00C896),
    "DashScope (CN)" to Color(0xFFFF6A00),
    "DashScope (Intl)" to Color(0xFFFF6A00)
)

@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel = viewModel(factory = ProvidersViewModel.Factory)
) {
    val providers by viewModel.providers.collectAsState()
    val editingProvider by viewModel.editingProvider.collectAsState()
    val editApiKeyValue by viewModel.editApiKeyValue.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredProviders = remember(providers, searchQuery) {
        if (searchQuery.isBlank()) providers
        else providers.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp).padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = TITLE,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Active provider banner — shows which provider chat uses right now.
            if (selectedProvider.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Active provider: $selectedProvider  ·  tap a card to switch",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search providers...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        itemsIndexed(filteredProviders, key = { _, item -> item.name }) { index, provider ->
            ProviderHealthCard(
                provider = provider,
                isSelected = provider.name.equals(selectedProvider, ignoreCase = true),
                onVerify = { viewModel.verifyConnection(provider.name) },
                onEditKey = { viewModel.startEditApiKey(provider.name) },
                onSetActive = { viewModel.setActiveProvider(provider.name) },
                modifier = Modifier.staggeredEntrance(index, itemId = provider.name)
            )
        }
    }

    // API Key Edit Dialog
    if (editingProvider != null) {
        var showKey by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.cancelEditApiKey() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = "$KEY_DIALOG_TITLE: $editingProvider",
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editApiKeyValue,
                        onValueChange = { viewModel.updateEditApiKey(it) },
                        label = { Text(KEY_DIALOG_LABEL) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showKey) "Hide key" else "Show key"
                                )
                            }
                        }
                    )
                    if (editApiKeyValue.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OrbitButton(
                            onClick = { showDeleteConfirm = true },
                            variant = OrbitButtonVariant.Outlined,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(KEY_DIALOG_DELETE)
                        }
                    }
                }
            },
            confirmButton = {
                OrbitButton(
                    onClick = { viewModel.saveApiKey() },
                    variant = OrbitButtonVariant.Primary
                ) {
                    Text(KEY_DIALOG_SAVE)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelEditApiKey() }) {
                    Text(KEY_DIALOG_CANCEL, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )

        // Delete confirmation dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = {
                    Text(KEY_DELETE_CONFIRM, color = MaterialTheme.colorScheme.onSurface)
                },
                text = {
                    Text("This will remove the saved API key for $editingProvider.")
                },
                confirmButton = {
                    OrbitButton(
                        onClick = {
                            showDeleteConfirm = false
                            viewModel.removeApiKey()
                        },
                        variant = OrbitButtonVariant.Primary
                    ) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(KEY_DIALOG_CANCEL, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    }
}

@Composable
private fun ProviderHealthCard(
    provider: ProviderConfig,
    isSelected: Boolean = false,
    onVerify: () -> Unit,
    onEditKey: () -> Unit,
    onSetActive: () -> Unit = {},
    modifier: Modifier = Modifier
) {


    val accentColor = MaterialTheme.colorScheme.secondary
    val errorColor = MaterialTheme.colorScheme.error
    val statusColor by animateColorAsState(
        targetValue = provider.connectionState.statusColor(accentColor, errorColor),
        animationSpec = MotionTokens.TweenNormalColor,
        label = "statusColor"
    )

    val statusText = provider.connectionState.statusText()
    val isVerifying = provider.connectionState is ConnectionState.Verifying

    OrbitCard(
        interactive = true,
        onClick = if (isSelected) null else onSetActive,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(providerAccent(provider.name).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = providerIcon(provider.name),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = providerAccent(provider.name)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active provider",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        provider.apiKeyConfigured -> API_KEY_SET_LABEL
                        !provider.requiresKey -> NO_KEY_REQUIRED_LABEL
                        else -> NO_API_KEY_LABEL
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        provider.apiKeyConfigured -> OrbitSuccess
                        !provider.requiresKey -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> OrbitWarning
                    }
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Edit key button
            IconButton(
                onClick = onEditKey,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = CD_EDIT,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Verify button
            OrbitButton(
                onClick = onVerify,
                enabled = !isVerifying,
                variant = OrbitButtonVariant.Tonal
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (isVerifying) CD_TESTING else CD_VERIFY,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun providerAccent(name: String): Color =
    PROVIDER_COLORS[name] ?: Color(0xFF6366F1)

@Composable
private fun providerIcon(name: String): Painter = when (name) {
    "Claude", "Anthropic Claude" -> BrandIcons.Claude
    "OpenAI" -> BrandIcons.OpenAI
    "Gemini", "Google Gemini" -> BrandIcons.Gemini
    "OpenRouter" -> BrandIcons.OpenRouter
    "DeepSeek" -> BrandIcons.DeepSeek
    "Groq" -> BrandIcons.Groq
    "Ollama", "Ollama (Local)" -> BrandIcons.Ollama
    "Z.AI (Free GLM)", "Z.AI" -> BrandIcons.ZAI
    "xAI (Grok)" -> BrandIcons.XAI
    "Mistral AI" -> BrandIcons.Mistral
    "Together AI" -> BrandIcons.Together
    "GitHub Copilot" -> BrandIcons.GitHub
    "NVIDIA NIM" -> BrandIcons.NVIDIA
    "AWS Bedrock" -> BrandIcons.AWS
    "Google Vertex AI" -> BrandIcons.Vertex
    "Azure OpenAI" -> BrandIcons.Azure
    "LM Studio (Local)" -> BrandIcons.LMStudio
    "DashScope (CN)", "DashScope (Intl)" -> BrandIcons.DashScope
    "Moonshot (Kimi)" -> BrandIcons.Moonshot
    "Venice AI" -> BrandIcons.Venice
    "Fireworks AI" -> BrandIcons.Fireworks
    "MiniMax" -> BrandIcons.MiniMax
    "Near AI" -> BrandIcons.NearAI
    "Xiaomi MiMo" -> BrandIcons.Xiaomi
    "Atlas Cloud" -> BrandIcons.Atlas
    "Kimi Code" -> BrandIcons.Kimi
    "OpenCode Gateway" -> BrandIcons.OpenCodeGateway
    "Gitlawb OpenGateway" -> BrandIcons.OpenGateway
    "Custom OpenAI-compatible" -> BrandIcons.Custom
    else -> BrandIcons.Custom
}

@Composable
private fun ConnectionState.statusColor(
    accentColor: Color,
    errorColor: Color
): Color = when (this) {
    is ConnectionState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    is ConnectionState.Verifying -> accentColor
    is ConnectionState.Connected -> OrbitSuccess
    is ConnectionState.Unauthorized -> OrbitWarning
    is ConnectionState.Offline -> errorColor
    is ConnectionState.Error -> errorColor
}

private fun ConnectionState.statusText(): String = when (this) {
    is ConnectionState.Idle -> STATUS_NOT_VERIFIED
    is ConnectionState.Verifying -> STATUS_VERIFYING
    is ConnectionState.Connected -> STATUS_CONNECTED
    is ConnectionState.Unauthorized -> this.message
    is ConnectionState.Offline -> STATUS_OFFLINE
    is ConnectionState.Error -> this.message
}
