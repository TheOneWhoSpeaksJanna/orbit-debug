package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.data.local.runner.LocalCommandRunner
import com.orbitai.data.local.prefs.PreferencesManager
import com.orbitai.data.local.runtime.TermuxRuntime
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.models.ChatSession
import com.orbitai.domain.models.DetailedModelInfo
import com.orbitai.domain.models.Message
import com.orbitai.domain.models.MessageRole
import com.orbitai.domain.models.TermuxLog
import com.orbitai.domain.repository.OrbitAiRepository
import com.orbitai.domain.models.AgentPermissionLevel
import com.orbitai.domain.repository.OpenCodeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val DEFAULT_PROVIDER = "OpenRouter"
private const val NEW_SESSION_TITLE = "New Session"
private const val DEFAULT_SYSTEM_PROMPT = "System: You are an expert AI assistant."
private const val NO_OUTPUT = "(no output)"
private const val AGENT_FAILED_PREFIX = "⚠️ The agent could not complete this request. Details:"
private const val ERROR_RUNNING_AGENT = "Error running agent: "
private const val UNKNOWN_ERROR = "Unknown error"
private const val ACTION_BLOCKED = "Action blocked by permission rules: "

enum class PermissionResult { ALLOWED, ASK, BLOCKED }

class ChatViewModel(
    private val repository: OrbitAiRepository,
    private val aiProvider: AiProvider,
    private val localCommandRunner: LocalCommandRunner,
    private val prefsManager: PreferencesManager,
    private val openCodeRepository: OpenCodeRepository,
    private val termuxRuntime: TermuxRuntime
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("ChatViewModel")

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    // Local mode is always on — the app only uses the local OpenClaude agent.
    // Cloud mode (direct API calls without the agent) is removed.
    private val _useLocalMode = MutableStateFlow(true)
    val useLocalMode: StateFlow<Boolean> = _useLocalMode.asStateFlow()

    fun toggleLocalMode() {
        // No-op — local mode is always on
    }

    data class PendingCommand(
        val command: String,
        val isSudo: Boolean
    )

    // AI loop state
    private var loopPromptBuilder = StringBuilder()
    private var loopContinueLooping = false
    private var loopSessionId = ""
    private var loopActiveProvider = ""
    private var loopActiveModelName = ""
    private var loopApiKey = ""

    // Track the in-flight AI loop coroutine so we can cancel it before
    // relaunching. Without this, two rapid sendMessage() calls would
    // launch two loop coroutines that both read/write the loop fields
    // concurrently, corrupting the chat history and doubling API calls.
    private var loopJob: kotlinx.coroutines.Job? = null

    private val _pendingCommand = MutableStateFlow<PendingCommand?>(null)
    val pendingCommand: StateFlow<PendingCommand?> = _pendingCommand.asStateFlow()

    fun confirmPendingCommand() {
        val pending = _pendingCommand.value ?: return
        _pendingCommand.value = null
        viewModelScope.launch(exceptionHandler) {
            executeCommandAndContinue(pending.command, pending.isSudo)
        }
    }

    fun denyPendingCommand() {
        val pending = _pendingCommand.value ?: return
        _pendingCommand.value = null
        viewModelScope.launch(exceptionHandler) {
            val blockMsg = Message(
                id = UUID.randomUUID().toString(),
                sessionId = loopSessionId,
                role = MessageRole.TOOL,
                content = "$ACTION_BLOCKED${pending.command}",
                timestamp = System.currentTimeMillis()
            )
            repository.insertMessage(blockMsg)
            loopPromptBuilder.append("TOOL: $ACTION_BLOCKED${pending.command}\nMODEL: ")
            loopContinueLooping = true
            continueAiLoop()
        }
    }

    private suspend fun isCommandAllowed(cmd: String, isSudo: Boolean): PermissionResult {
        val level = AgentPermissionLevel.fromValue(prefsManager.agentPermissionLevel.firstOrNull() ?: "NORMAL")
        return when (level) {
            AgentPermissionLevel.FULL_ACCESS -> PermissionResult.ALLOWED
            AgentPermissionLevel.NORMAL -> PermissionResult.ASK
            AgentPermissionLevel.RULES -> {
                val lower = cmd.lowercase()
                val allowedRules = prefsManager.agentRulesAllowed.firstOrNull() ?: ""
                val askRules = prefsManager.agentRulesAsk.firstOrNull() ?: ""
                val deniedRules = prefsManager.agentRulesDenied.firstOrNull() ?: ""

                val allowed = allowedRules.lines().filter { it.isNotBlank() }
                val ask = askRules.lines().filter { it.isNotBlank() }
                val denied = deniedRules.lines().filter { it.isNotBlank() }

                if (denied.any { lower.contains(it.lowercase()) }) return PermissionResult.BLOCKED
                if (ask.any { lower.contains(it.lowercase()) }) return PermissionResult.ASK
                if (allowed.isEmpty()) return PermissionResult.ASK
                if (allowed.any { lower.contains(it.lowercase()) }) return PermissionResult.ALLOWED
                PermissionResult.ASK
            }
        }
    }

    private val _detailedModels = MutableStateFlow<List<DetailedModelInfo>>(emptyList())
    val detailedModels: StateFlow<List<DetailedModelInfo>> = _detailedModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val selectedModel: StateFlow<String> = prefsManager.selectedModel
        .map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedAgent: StateFlow<String?> = prefsManager.selectedAgent
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasAgent: StateFlow<Boolean> = prefsManager.selectedAgent
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadModelsForCurrentProvider() {
        viewModelScope.launch(exceptionHandler) {
            val provider = prefsManager.selectedProvider.firstOrNull() ?: DEFAULT_PROVIDER
            val models = aiProvider.getModels(provider)
            _availableModels.value = if (models.isNotEmpty()) models else DEFAULT_MODELS
        }
    }

    fun fetchDetailedModels() {
        viewModelScope.launch(exceptionHandler) {
            val provider = prefsManager.selectedProvider.firstOrNull() ?: DEFAULT_PROVIDER
            if (provider != "OpenRouter") return@launch
            _isFetchingModels.value = true
            val apiKey = prefsManager.getApiKeyForProvider(provider).firstOrNull() ?: ""
            if (apiKey.isBlank()) {
                _isFetchingModels.value = false
                return@launch
            }
            try {
                val models = aiProvider.fetchDetailedModels(provider, apiKey)
                _detailedModels.value = models
                _availableModels.value = models.map { it.id }
            } catch (_: Exception) {
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun setSelectedModel(model: String) {
        viewModelScope.launch(exceptionHandler) {
            prefsManager.setSelectedModel(model)
        }
    }

    private var loadSessionJob: Job? = null

    fun loadSession(sessionId: String) {
        loadSessionJob?.cancel()
        loadSessionJob = viewModelScope.launch(exceptionHandler) {
            repository.getAllSessions().firstOrNull()?.find { it.id == sessionId }?.let {
                _currentSession.value = it
            }
            repository.getMessagesForSession(sessionId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun startNewSession(projectId: String?) {
        viewModelScope.launch(exceptionHandler) {
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = NEW_SESSION_TITLE,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            _currentSession.value = session
            _messages.value = emptyList()
            loadSession(session.id)
        }
    }

    fun sendMessage(content: String) {
        val session = _currentSession.value ?: return
        viewModelScope.launch(exceptionHandler) {
            // Persist session on first message (blank sessions never hit the DB)
            repository.insertSession(session)

            val userMsg = Message(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                role = MessageRole.USER,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertMessage(userMsg)

            val activeProvider = prefsManager.selectedProvider.firstOrNull() ?: DEFAULT_PROVIDER
            val activeModelName = prefsManager.selectedModel.firstOrNull() ?: ""
            val activeAgentId = prefsManager.selectedAgent.firstOrNull()
                ?.lowercase()?.replace(" ", "-")
                ?: return@launch

            _isLoading.value = true

            if (_useLocalMode.value) {
                var runCmd: String? = null
                val agentEntity = repository.getAllAgents().firstOrNull()?.find { it.id == activeAgentId }
                runCmd = agentEntity?.runCommand

                if (runCmd.isNullOrBlank()) {
                    val catalogAgent = openCodeRepository.getAgentById(activeAgentId)
                    runCmd = catalogAgent?.runCommand
                }

                if (runCmd.isNullOrBlank()) {
                    // No runCommand stored — fall back to the agent's binary name.
                    // npm install -g puts it in $PREFIX/bin/, found via PATH.
                    runCmd = activeAgentId
                }

                // Guard: if the Termux rootfs isn't installed yet, fail with
                // a clear user-facing error instead of a confusing PRoot error.
                if (!termuxRuntime.isInstalled) {
                    val errMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.MODEL,
                        content = "The Linux runtime is not installed yet. Go to Setup Wizard and complete setup first.",
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(errMsg)
                    _isLoading.value = false
                    return@launch
                }

                // Run agent via PRoot. Try multiple methods to pass the prompt:
                // 1. stdin piping (echo "prompt" | agent) — works for most CLI agents
                // 2. -p flag (agent -p "prompt") — common flag for prompt input
                // 3. direct argument (agent "prompt") — some agents accept this
                try {
                    // Get the API key from the app's provider config and pass it
                    // to the agent via environment variables. Most CLI agents
                    // (openclaude, claude, codex, opencode) read API keys from
                    // env vars, not from the app's DataStore.
                    val isAnthropic = activeProvider.contains("Anthropic", ignoreCase = true) ||
                        activeProvider.contains("Claude", ignoreCase = true)
                    val claudeAuthMode = if (isAnthropic)
                        prefsManager.getClaudeAuthMode().firstOrNull() ?: "api-key" else "api-key"
                    val claudeSubscriptionToken = if (isAnthropic)
                        prefsManager.getClaudeSubscriptionToken().firstOrNull() ?: "" else ""
                    // In subscription mode the "key" the agent needs is the
                    // Claude Max token (ANTHROPIC_AUTH_TOKEN), not ANTHROPIC_API_KEY.
                    val useSubscription = isAnthropic && claudeAuthMode == "subscription"
                    val apiKey = if (useSubscription) claudeSubscriptionToken
                        else prefsManager.getApiKeyForProvider(activeProvider).firstOrNull() ?: ""
                    val model = activeModelName.ifBlank { "auto" }

                    // Guard: if no API key is configured for the selected
                    // provider, the agent would silently fall back to its own
                    // (often broken) default gateway and emit a confusing
                    // upstream error. Fail fast with a clear, actionable
                    // message so the user knows to add their key in Settings.
                    if (apiKey.isBlank()) {
                        val errMsg = Message(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.id,
                            role = MessageRole.MODEL,
                            content = "No API key configured for provider \"$activeProvider\". " +
                                "Open the Provider tab, tap the pencil on $activeProvider, paste your key, and tap Save.",
                            timestamp = System.currentTimeMillis()
                        )
                        repository.insertMessage(errMsg)
                        _isLoading.value = false
                        com.orbitai.core.logging.FileLogger.w(
                            "ChatViewModel", "Agent exec skipped",
                            "reason=no API key for provider=$activeProvider"
                        )
                        return@launch
                    }

                    // Build env var exports based on the provider
                    // Most OpenAI-compatible agents accept OPENAI_API_KEY + OPENAI_BASE_URL
                    // OpenRouter specifically uses OPENROUTER_API_KEY
                    val envExports = buildEnvExports(activeProvider, apiKey, model, useSubscription)
                    com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent env", "provider=$activeProvider model=$model keyLen=${apiKey.length}")

                    com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec start (PRoot)", "cmd=$runCmd content=${content.take(80)}")
                    val escaped = content.replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")

                    // Method 1: -p flag (OpenClaude's non-interactive mode)
                    // openclaude -p "prompt" is the correct way to send a one-shot prompt
                    var fullCmd = "$envExports $runCmd -p \"$escaped\" --dangerously-skip-permissions"
                    var result = termuxRuntime.executeInTermux(fullCmd, "")
                    com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (-p flag)", "exit=${result.exitCode} output=${result.output.take(2000)}")

                    // Remember the first attempt's output so a later, weaker
                    // fallback that fails with a less useful message doesn't
                    // clobber the original (often more informative) error.
                    val firstResult = result

                    // If -p flag failed, try stdin pipe
                    if (result.exitCode != 0 || result.output.isBlank()) {
                        fullCmd = "$envExports echo \"$escaped\" | $runCmd -p --dangerously-skip-permissions"
                        result = termuxRuntime.executeInTermux(fullCmd, "")
                        com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (stdin+pipe)", "exit=${result.exitCode} output=${result.output.take(2000)}")
                    }

                    // If stdin also failed, try direct argument (no -p)
                    if (result.exitCode != 0 || result.output.isBlank()) {
                        fullCmd = "$envExports $runCmd \"$escaped\""
                        result = termuxRuntime.executeInTermux(fullCmd, "")
                        com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (direct arg)", "exit=${result.exitCode} output=${result.output.take(2000)}")
                    }

                    // Decide what to show the user:
                    // - success (exit 0) with output -> show it
                    // - all attempts failed -> show the most informative error we
                    //   captured (prefer the first attempt's non-blank output),
                    //   clearly marked as a failure instead of a bare "(no output)".
                    val allFailed = result.exitCode != 0 || result.output.isBlank()
                    val displayContent = if (!allFailed) {
                        result.output
                    } else {
                        val bestError = listOf(result.output, firstResult.output)
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                        if (bestError != null) {
                            "$AGENT_FAILED_PREFIX\n\n$bestError"
                        } else {
                            "$AGENT_FAILED_PREFIX $NO_OUTPUT"
                        }
                    }

                    val modelMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.MODEL,
                        content = displayContent,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(modelMsg)
                } catch (e: Exception) {
                    com.orbitai.core.logging.FileLogger.e("ChatViewModel", "Agent exec failed", e, "reason=${e.message}")
                    val errMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.MODEL,
                        content = "$ERROR_RUNNING_AGENT${e.message ?: UNKNOWN_ERROR}",
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(errMsg)
                }
                _isLoading.value = false
                return@launch
            }

            val apiKey = prefsManager.getApiKeyForProvider(activeProvider).firstOrNull() ?: ""

            val agentEntity = repository.getAllAgents().firstOrNull()?.find { it.id == activeAgentId }
            val systemPrompt = agentEntity?.systemPrompt ?: DEFAULT_SYSTEM_PROMPT

            val promptBuilder = StringBuilder()
            promptBuilder.append("$systemPrompt\n\n")

            // Append enabled skills
            val skills = repository.getAllSkills().firstOrNull().orEmpty()
            val enabledSkills = skills.filter { it.enabled && it.content.isNotBlank() }
            if (enabledSkills.isNotEmpty()) {
                promptBuilder.append("## Active Skills\n\n")
                for (skill in enabledSkills) {
                    promptBuilder.append("### ${skill.name}\n")
                    promptBuilder.append("${skill.content}\n\n")
                }
            }

            _messages.value.forEach { msg ->
                promptBuilder.append("${msg.role.name}: ${msg.content}\n")
            }
            promptBuilder.append("USER: $content\nMODEL: ")

            loopPromptBuilder = promptBuilder
            loopContinueLooping = true
            loopSessionId = session.id
            loopActiveProvider = activeProvider
            loopActiveModelName = activeModelName
            loopApiKey = apiKey

            continueAiLoop()
        }
    }

    private suspend fun executeCommandAndContinue(cmd: String, isSudo: Boolean) {
        loopContinueLooping = true
        val execResult = if (isSudo) {
            localCommandRunner.executePrivilegedCommand(cmd)
        } else {
            localCommandRunner.executeCommand(cmd)
        }
        val toolOutput = "Output: ${execResult.output}\nExitCode: ${execResult.exitCode}"

        repository.insertTermuxLog(
            TermuxLog(
                UUID.randomUUID().toString(),
                if (isSudo) "sudo $cmd" else cmd,
                execResult.output,
                execResult.exitCode,
                System.currentTimeMillis()
            )
        )

        val toolMsg = Message(
            id = UUID.randomUUID().toString(),
            sessionId = loopSessionId,
            role = MessageRole.TOOL,
            content = toolOutput,
            timestamp = System.currentTimeMillis()
        )
        repository.insertMessage(toolMsg)
        loopPromptBuilder.append("TOOL: $toolOutput\nMODEL: ")
        continueAiLoop()
    }

    private fun continueAiLoop() {
        // Cancel any previous loop coroutine before launching a new one.
        // This prevents two loop coroutines from running concurrently and
        // corrupting the shared loopPromptBuilder / loopSessionId state.
        loopJob?.cancel()
        loopJob = viewModelScope.launch(exceptionHandler) {
            while (loopContinueLooping) {
                val result = aiProvider.generateContent(
                    loopPromptBuilder.toString(), loopApiKey, loopActiveProvider, loopActiveModelName
                )

                val modelText = when (result) {
                    is AiResult.Success -> result.text
                    is AiResult.Error -> "Error: ${result.message}"
                }

                if (modelText.contains("[RUN: ") || modelText.contains("[SUDO: ")) {
                    val runMatch = RUN_COMMAND_REGEX.find(modelText)
                    val sudoMatch = SUDO_COMMAND_REGEX.find(modelText)

                    val actionModelMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = loopSessionId,
                        role = MessageRole.MODEL,
                        content = modelText,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(actionModelMsg)

                    val pair = when {
                        runMatch != null -> runMatch.groupValues[1] to false
                        sudoMatch != null -> sudoMatch.groupValues[1] to true
                        else -> null
                    } ?: continue
                    val (cmd, isSudo) = pair

                    when (isCommandAllowed(cmd, isSudo)) {
                        PermissionResult.ALLOWED -> {
                            executeCommandAndContinue(cmd, isSudo)
                            return@launch
                        }
                        PermissionResult.ASK -> {
                            _pendingCommand.value = PendingCommand(cmd, isSudo)
                            loopContinueLooping = false
                            _isLoading.value = false
                            return@launch
                        }
                        PermissionResult.BLOCKED -> {
                            val blockMsg = Message(
                                id = UUID.randomUUID().toString(),
                                sessionId = loopSessionId,
                                role = MessageRole.TOOL,
                                content = "$ACTION_BLOCKED$cmd",
                                timestamp = System.currentTimeMillis()
                            )
                            repository.insertMessage(blockMsg)
                            loopPromptBuilder.append("TOOL: $ACTION_BLOCKED$cmd\nMODEL: ")
                        }
                    }
                } else {
                    val modelMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = loopSessionId,
                        role = MessageRole.MODEL,
                        content = modelText,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(modelMsg)
                    loopContinueLooping = false
                }
            }
            _isLoading.value = false
        }
    }

    /**
     * Build environment variable exports for the agent based on the app's
     * selected provider. This passes the API key from the app's DataStore
     * to the CLI agent running inside PRoot.
     *
     * Most CLI agents (openclaude, claude, codex, opencode) read API keys
     * from environment variables. Without this, the agent doesn't know
     * which provider/API key to use and asks the user to log in.
     *
     * The provider→env-var mapping lives in the pure, testable helper
     * [com.orbitai.core.auth.buildProviderEnvExports]; this method adds the
     * catalog base-URL fallback (which needs an Android Context) and the
     * PRoot SHELL export.
     */
    private fun buildEnvExports(provider: String, apiKey: String, model: String, useSubscription: Boolean = false): String {
        if (apiKey.isBlank()) return ""

        val (coreExports, baseUrlSetByCore) = com.orbitai.core.auth.buildProviderEnvExports(
            provider, apiKey, model, useSubscription
        )
        val exports = StringBuilder(coreExports)
        var baseUrlSet = baseUrlSetByCore

        // Fallback: for any other catalog provider (Venice, Fireworks, Mistral,
        // NVIDIA, Together, MiniMax, etc.) look up its baseUrl from the provider
        // catalog. Without this the agent would default to api.openai.com and
        // send the wrong provider's key there, failing every chat.
        if (!baseUrlSet) {
            try {
                val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(termuxRuntime.appContext)
                val entry = catalog.find { it.name.equals(provider, ignoreCase = true) }
                val base = entry?.baseUrl?.trim()?.takeIf { it.isNotBlank() }
                if (base != null) {
                    val normalized = if (base.trimEnd('/').endsWith("/v1")) base.trimEnd('/') else base.trimEnd('/') + "/v1"
                    exports.append(" && export OPENAI_BASE_URL='$normalized'")
                    baseUrlSet = true
                }
            } catch (e: Exception) {
                com.orbitai.core.logging.FileLogger.w(
                    "ChatViewModel", "baseUrl catalog lookup failed",
                    "provider=$provider reason=${e.message}"
                )
            }
        }

        // Ensure the agent's Bash tool can find a POSIX shell inside PRoot. The
        // termux rootfs has usr/bin/{sh,bash} but no /bin/sh and no /etc/passwd
        // entry, so without SHELL set OpenClaude reports "No suitable shell found"
        // and every shell command fails. Point it at the guest-relative bash.
        exports.append(" && export SHELL='/data/data/com.termux/files/usr/bin/bash'")

        exports.append(" && ")
        return exports.toString()
    }

    companion object {
        private val RUN_COMMAND_REGEX = "\\[RUN: (.+?)]".toRegex()
        private val SUDO_COMMAND_REGEX = "\\[SUDO: (.+?)]".toRegex()
        val DEFAULT_MODELS = listOf("tencent/hy3:free", "gemini-2.0-flash-exp", "gpt-4o", "claude-sonnet-4-20250514", "glm-5.2", "glm-4.6")

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return ChatViewModel(
                    application.container.repository,
                    application.container.aiProvider,
                    application.container.localCommandRunner,
                    application.container.prefsManager,
                    application.container.openCodeRepository,
                    application.container.termuxRuntime
                ) as T
            }
        }
    }
}
