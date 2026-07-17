package com.orbitai.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.R
import com.orbitai.ui.components.AnimatedGlassCard
import com.orbitai.ui.viewmodels.SetupPhase
import com.orbitai.ui.viewmodels.SetupStep
import com.orbitai.ui.viewmodels.SetupViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import rikka.shizuku.Shizuku

private val THEME_OPTIONS = listOf("System", "Dark", "Light")
private val AGENT_OPTIONS = listOf("OpenClaude", "Claude Code", "OpenCode", "Codex", "Default")

// Providers are now loaded dynamically from ProviderCatalog.
// This is a fallback list used before the catalog loads.
private val FALLBACK_PROVIDER_OPTIONS = listOf(
    "Anthropic Claude", "OpenAI", "Google Gemini", "OpenRouter",
    "DeepSeek", "Groq", "Ollama"
)
private const val OLLAMA_HINT = "Local Ollama — leave blank for http://localhost:11434 or enter a custom URL"

private const val AGENT_INSTALLED_FORMAT = "%s Installed"
private const val AGENT_READY_DESC = "Ready to use with this device."
private const val AGENT_INSTALL_PREFIX = "Install "
private const val AGENT_INSTALL_DESC_FORMAT = "Downloads and installs %s on this device"

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onFinishSetup: () -> Unit,
    viewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory)
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val hasFlavorPreset by viewModel.hasFlavorPreset.collectAsState()
    val filteredSteps = remember(hasFlavorPreset) {
        if (hasFlavorPreset) SetupStep.entries.filter { it != SetupStep.Agent }
        else SetupStep.entries
    }
    val currentStepDef = filteredSteps.getOrNull(currentStep) ?: SetupStep.Welcome
    val scope = rememberCoroutineScope()

    // Finalization overlay state — when non-IDLE, shows full-screen loading
    // that blocks navigation until the agent install actually completes.
    val setupPhase by viewModel.setupPhase.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val agentInstallStates by viewModel.agentInstallStates.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.step_n_of_m, currentStep + 1, filteredSteps.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(id = currentStepDef.labelResId),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    if (currentStep > 0) {
                        TextButton(onClick = { viewModel.previousStep() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.back))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (currentStep < filteredSteps.lastIndex) {
                        Button(
                            onClick = { viewModel.nextStep() },
                            enabled = viewModel.canAdvance
                        ) {
                            Text(stringResource(R.string.next))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.next))
                        }
                    } else {
                        Button(onClick = {
                            // Trigger setup + agent install. The FinalizingOverlay
                            // will appear and block navigation until install completes.
                            viewModel.completeSetup()
                        }) {
                            Text(stringResource(R.string.finish_setup))
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)).togetherWith(
                                fadeOut(animationSpec = tween(150))
                            )
                        },
                        label = "SetupWizardTransition"
                    ) { step ->
                        val stepDef = if (hasFlavorPreset) SetupStep.entries.filter { it != SetupStep.Agent }.getOrNull(step) else SetupStep.entries.getOrNull(step)
                        when (stepDef) {
                            SetupStep.Welcome -> WelcomeStep()
                            SetupStep.Theme -> ThemeSelectionStep(viewModel)
                            SetupStep.Agent -> AgentSelectionStep(viewModel)
                            SetupStep.Provider -> ProviderSelectionStep(viewModel)
                            SetupStep.Shizuku -> ShizukuStep(viewModel)
                            SetupStep.Storage -> StoragePermissionStep(viewModel)
                            SetupStep.Summary -> SummaryStep()
                            null -> Unit
                        }
                    }
                }
                GlassPageIndicator(
                    totalSteps = filteredSteps.size,
                    currentStep = currentStep
                )
            }
        }

        // Full-screen loading overlay — appears above the wizard content when
        // setupPhase != IDLE. Blocks the back button and all underlying UI
        // taps so the user can't accidentally navigate to the dashboard with
        // an uninstalled agent.
        if (setupPhase != SetupPhase.IDLE) {
            val agentState = agentInstallStates[selectedAgent] ?: SetupViewModel.AgentInstallState()
            // Collect SharedFlow into a list for display. SharedFlow doesn't
            // have collectAsState(initialValue), so we use produceState.
            val liveLogs = produceState(initialValue = emptyList<String>(), viewModel.liveLogs) {
                viewModel.liveLogs.collect { value = value + it }
            }.value
            FinalizingOverlay(
                phase = setupPhase,
                agentName = selectedAgent,
                agentState = agentState,
                liveLogs = liveLogs,
                onSkip = { viewModel.skipFinalization() },
                onRetry = { viewModel.retryInstall() },
                onEnterApp = {
                    viewModel.finishOnboarding()
                    onFinishSetup()
                }
            )
        }
    }
}

@Composable
fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.omniclaw_ai_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            stringResource(R.string.omniclaw_ai_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ThemeSelectionStep(viewModel: SetupViewModel) {
    val theme by viewModel.theme.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.appearance),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        THEME_OPTIONS.forEach { text ->
            val isSelected = text == theme
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedGlassCard(
                onClick = { viewModel.setTheme(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentSelectionStep(viewModel: SetupViewModel) {
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val agentInstallStates by viewModel.agentInstallStates.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.select_agent),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(20.dp))

        AGENT_OPTIONS.forEach { agent ->
            val isSelected = agent == selectedAgent
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedGlassCard(
                onClick = { viewModel.setSelectedAgent(agent) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val desc = when (agent) {
                        "Default" -> stringResource(R.string.agent_default)
                        "OpenClaude" -> stringResource(R.string.agent_openclaude)
                        "Claude Code" -> stringResource(R.string.agent_claude_code)
                        "OpenCode" -> stringResource(R.string.agent_opencode)
                        "Codex" -> stringResource(R.string.agent_codex)
                        else -> stringResource(R.string.agent_default)
                    }
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val agentState = agentInstallStates[selectedAgent] ?: SetupViewModel.AgentInstallState()
        Spacer(modifier = Modifier.height(24.dp))
        when {
            agentState.isInstalling -> {
                LinearProgressIndicator(
                    progress = { agentState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = agentState.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            agentState.isInstalled -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = AGENT_INSTALLED_FORMAT.format(selectedAgent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = AGENT_READY_DESC,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Button(
                    onClick = { viewModel.installAgent(selectedAgent) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        "$AGENT_INSTALL_PREFIX$selectedAgent",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = AGENT_INSTALL_DESC_FORMAT.format(selectedAgent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ProviderSelectionStep(viewModel: SetupViewModel) {
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val agent by viewModel.selectedAgent.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val claudeAuthMode by viewModel.claudeAuthMode.collectAsState()
    val isTesting by viewModel.isTestingConnection.collectAsState()
    val success by viewModel.testConnectionSuccess.collectAsState()
    val testError by viewModel.testConnectionError.collectAsState()

    // Claude (Anthropic) supports BOTH an API key and a Claude Max subscription.
    // Surface a toggle so the user can pick which auth method to use.
    val isAnthropic = selectedProvider.contains("Anthropic", ignoreCase = true) ||
        selectedProvider.contains("Claude", ignoreCase = true)
    val useSubscription = isAnthropic && claudeAuthMode == "subscription"

    // Load providers dynamically, filtered to what the active agent supports.
    val context = androidx.compose.ui.platform.LocalContext.current
    val providerOptions = remember(agent) {
        val activeAgent = if (com.orbitai.core.config.FlavorConfig.presetAgentName.isNotBlank())
            com.orbitai.core.config.FlavorConfig.presetAgentName else agent
        val all = com.orbitai.data.local.runtime.ProviderCatalog.loadForAgent(context, activeAgent).map { it.name }
        // OpenRouter is a universal gateway that is NOT an official provider for
        // Claude Code or Codex. Only surface it first on the broad agents
        // (OpenClaude / OpenCode / Hermes / normal-default); for Claude Code /
        // Codex we respect the agent's own (official) provider list and do not
        // inject it. Hermes is a cloud-only agent that talks to OpenRouter, so
        // OpenRouter must be selectable (and shown first) for it.
        val forceOpenRouter = com.orbitai.core.config.FlavorConfig.presetAgentName.isBlank() ||
            activeAgent.equals("OpenClaude", ignoreCase = true) ||
            activeAgent.equals("OpenCode", ignoreCase = true) ||
            activeAgent.equals("Hermes", ignoreCase = true)
        if (forceOpenRouter) listOf("OpenRouter") + all.filter { it != "OpenRouter" }
        else all
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Top
        ) {
        Text(
            stringResource(R.string.select_provider_for, agent),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        providerOptions.forEach { provider ->
            val isSelected = provider == selectedProvider
            Spacer(modifier = Modifier.height(10.dp))
            AnimatedGlassCard(
                onClick = { viewModel.setSelectedProvider(provider) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Claude (Anthropic) auth mode toggle: API key vs Claude Max subscription.
        if (isAnthropic) {
            Text(
                stringResource(R.string.claude_auth_mode),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !useSubscription,
                    onClick = { viewModel.setClaudeAuthMode("api-key") },
                    label = { Text(stringResource(R.string.claude_auth_api_key)) }
                )
                FilterChip(
                    selected = useSubscription,
                    onClick = { viewModel.setClaudeAuthMode("subscription") },
                    label = { Text(stringResource(R.string.claude_auth_subscription)) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            stringResource(R.string.api_key),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { viewModel.setApiKey(it) },
            label = {
                Text(
                    if (selectedProvider == "Ollama") "Ollama server URL (optional)"
                    else if (useSubscription) stringResource(R.string.claude_subscription_token)
                    else stringResource(R.string.api_key)
                )
            },
            placeholder = {
                if (selectedProvider == "Ollama") {
                    Text(
                        text = OLLAMA_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (useSubscription) {
                    Text(
                        text = stringResource(R.string.claude_subscription_token_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.testConnection() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.test_connection))
            }
        }

        if (success != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (success == true) stringResource(R.string.connection_successful)
                else stringResource(R.string.connection_failed),
                color = if (success == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            if (success == false && testError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = testError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        }
    }
}

@Composable
fun ShizukuStep(viewModel: SetupViewModel) {
    val shizukuEnabled by viewModel.shizukuEnabled.collectAsState()
    val context = LocalContext.current
    var shizukuStatus by remember { mutableStateOf(context.getString(R.string.shizuku_status_checking)) }
    var hasPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val isInstalled = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        if (isInstalled) {
            if (Shizuku.pingBinder()) {
                hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                shizukuStatus = if (hasPermission) context.getString(R.string.shizuku_status_running)
                else context.getString(R.string.shizuku_status_no_permission)
            } else {
                shizukuStatus = context.getString(R.string.shizuku_status_not_running)
            }
        } else {
            shizukuStatus = context.getString(R.string.shizuku_status_not_installed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.elevated_permissions),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.shizuku_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        AnimatedGlassCard(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.shizuku_status_format, shizukuStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (!hasPermission && Shizuku.pingBinder()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        try { Shizuku.requestPermission(1000) }
                        catch (e: Exception) { e.printStackTrace() }
                    }) {
                        Text(stringResource(R.string.request_permission))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        AnimatedGlassCard(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.enable_shizuku),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(R.string.shizuku_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = shizukuEnabled,
                    onCheckedChange = { viewModel.setShizukuEnabled(it) },
                    enabled = hasPermission
                )
            }
        }
    }
}

@Composable
fun StoragePermissionStep(viewModel: SetupViewModel) {
    val storagePermissionGranted by viewModel.storagePermissionGranted.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.storage_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.storage_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (storagePermissionGranted) {
            Text(
                stringResource(R.string.storage_granted),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                    // Re-check after returning from settings
                    viewModel.setStoragePermissionGranted(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Environment.isExternalStorageManager()
                        } else {
                            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
            ) {
                Text(stringResource(R.string.storage_grant_button))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.storage_skip),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SummaryStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.step_summary),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.runtime_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        AnimatedGlassCard(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.runtime_active),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.runtime_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            stringResource(R.string.summary),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.summary_ui_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.summary_agent_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.summary_api_keys_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.summary_command_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.ready_enter_workspace),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GlassPageIndicator(totalSteps: Int, currentStep: Int) {
    // Resolve all theme colors once outside the dot loop so they don't
    // get re-resolved on every recomposition of the indicator row.
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val background = MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val isActive = index == currentStep
            val isCompleted = index < currentStep

            // Animate scale + alpha together. Both values are read inside a
            // single graphicsLayer lambda below so the dot's size animation
            // runs entirely in the draw phase — the previous version used
            // Modifier.size(animatedSize.dp) which forced a full recomposition
            // + layout pass on every frame of the size animation.
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.5f else 1f,
                animationSpec = spring(dampingRatio = 0.5f),
                label = "DotScale"
            )
            val alpha by animateFloatAsState(
                targetValue = when {
                    isActive -> 1f
                    isCompleted -> 0.6f
                    else -> 0.25f
                },
                animationSpec = tween(300),
                label = "DotAlpha"
            )

            val dotColor = when {
                isActive -> primary
                isCompleted -> primary.copy(alpha = 0.5f)
                else -> outline
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        this.scaleX = scale
                        this.scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

/**
 * Full-screen loading overlay shown after the user clicks "Finish Setup".
 *
 * WHY THIS EXISTS:
 * The agent install (Termux bootstrap extraction + npm install + wrapper
 * creation) takes 2–5 minutes on first run. Previously the wizard navigated
 * to the dashboard immediately after kicking off the install, so users would
 * land on the chat screen and get an "Agent not installed" error. This overlay
 * blocks navigation until the install actually completes — or the user
 * explicitly chooses to skip.
 *
 * Three visual states driven by [phase]:
 *  - FINALIZING: spinner + progress bar + live status text + elapsed timer + Skip link
 *  - READY:      green check + "Enter Orbit-AI" button
 *  - FAILED:     red error icon + Retry button + "Skip anyway" button
 *
 * The overlay also intercepts the system back button so the user can't back
 * out of the install.
 */
@Composable
private fun FinalizingOverlay(
    phase: SetupPhase,
    agentName: String,
    agentState: SetupViewModel.AgentInstallState,
    liveLogs: List<String>,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onEnterApp: () -> Unit
) {
    // Intercept system back so the user can't accidentally exit mid-install.
    androidx.activity.compose.BackHandler(enabled = true) {
        // During FINALIZING, back is a no-op (don't dismiss the overlay).
        // During READY/FAILED, back also does nothing — user must tap a button.
    }

    // Elapsed-time counter — reassures the user that the install is actually
    // progressing, not stuck. Ticks every second.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        if (phase == SetupPhase.FINALIZING) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val elapsedText = "%d:%02d".format(minutes, seconds)

    val animatedProgress by animateFloatAsState(
        targetValue = agentState.progress,
        animationSpec = tween(durationMillis = 300),
        label = "FinalizeProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (phase) {
                    SetupPhase.FINALIZING -> {
                        // Pulsing hourglass icon
                        val infiniteTransition = rememberInfiniteTransition(label = "FinalizePulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.92f,
                            targetValue = 1.08f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "PulseScale"
                        )
                        Icon(
                            imageVector = Icons.Filled.HourglassEmpty,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                },
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = stringResource(R.string.finalizing_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.finalizing_subtitle, agentName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { animatedProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(animatedProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.finalizing_elapsed, elapsedText),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Live status message from the installer
                        val statusText = agentState.status.ifBlank { stringResource(R.string.finalizing_starting) }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Live log feed — shows real-time progress messages
                        // so the user knows exactly what's happening.
                        if (liveLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            val logScrollState = rememberScrollState()
                            LaunchedEffect(liveLogs.size) {
                                logScrollState.animateScrollTo(logScrollState.maxValue)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(8.dp)
                                    .verticalScroll(logScrollState)
                            ) {
                                Column {
                                    liveLogs.forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Skip link — proceeds to dashboard even if install isn't done
                        TextButton(onClick = onSkip) {
                            Text(
                                text = stringResource(R.string.finalizing_skip),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    SetupPhase.READY -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.finalizing_success_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.finalizing_success_subtitle, agentName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = onEnterApp,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.finalizing_enter_app),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    SetupPhase.FAILED -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.finalizing_failed_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val errorDetail = agentState.status.ifBlank { stringResource(R.string.finalizing_failed_unknown) }
                        Text(
                            text = errorDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSkip,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.finalizing_skip_anyway))
                            }
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(stringResource(R.string.finalizing_retry))
                            }
                        }
                    }

                    SetupPhase.IDLE -> Unit // overlay not shown
                }
            }
        }
    }
}
