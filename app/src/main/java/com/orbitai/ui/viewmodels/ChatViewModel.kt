package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.core.config.FlavorConfig
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
import kotlin.text.RegexOption
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
    private val termuxRuntime: TermuxRuntime,
    private val hermesRuntime: com.orbitai.data.local.runtime.HermesRuntime,
    private val silentUpdater: com.orbitai.data.local.updater.SilentUpdater
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("ChatViewModel")

    // Live "transcript" of the agent's raw stdout/stderr as it streams in.
    // This is the transparency feature: instead of only seeing the final
    // answer (and discovering problems after the fact), the user watches
    // exactly what the agent does — tool calls, reasoning, errors — live.
    private val _streamLines = MutableStateFlow<List<String>>(emptyList())
    val streamLines: StateFlow<List<String>> = _streamLines.asStateFlow()
    private val _showTranscript = MutableStateFlow(false)
    val showTranscript: StateFlow<Boolean> = _showTranscript.asStateFlow()

    // Set by the host screen so slash commands can navigate (e.g. /skills).
    var onNavigateToSkills: (() -> Unit)? = null

    // Attachments queued for the next message (images / files). The agent
    // receives their display names; deep multimodal ingestion is a follow-up.
    private val _attachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    val attachments: StateFlow<List<AttachmentItem>> = _attachments.asStateFlow()

    fun attachFile(uri: android.net.Uri) {
        val ctx = termuxRuntime.appContext
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment ?: "file"
        } catch (_: Exception) { uri.lastPathSegment ?: "file" }
        _attachments.value = _attachments.value + AttachmentItem(uri = uri.toString(), displayName = name)
    }

    fun removeAttachment(item: AttachmentItem) {
        _attachments.value = _attachments.value.filter { it != item }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    /**
     * Copy the queued attachments into a PRoot-visible directory
     * (HOME/attachments) so the CLI agent can actually open and read them,
     * and return a prompt suffix that tells the agent where they are + their
     * type. This is the real multimodal ingestion path: the agent reads the
     * file bytes (images, text, PDFs) from disk itself.
     *
     * Returns "" when there are no attachments.
     */
    private fun prepareAttachmentsForAgent(): String {
        val items = _attachments.value
        if (items.isEmpty()) return ""
        val ctx = termuxRuntime.appContext
        // Copy attachments into the rootfs the ACTIVE agent actually sees.
        // openclaude/opencode/claudecode/codex run under TermuxRuntime's rootfs;
        // Hermes runs under its own glibc rootfs at a different path. The
        // hardcoded /data/data/com.termux/... path was wrong for Hermes, so
        // the agent could never find the file (the "fake attachment" bug).
        val hostDir: java.io.File
        val insideBase: String
        if (com.orbitai.core.config.FlavorConfig.isHermes) {
            val hr = com.orbitai.data.local.runtime.HermesRuntime(ctx)
            hostDir = java.io.File(hr.rootfsDir, "home/attachments")
            insideBase = "/home/attachments"
        } else {
            hostDir = java.io.File(termuxRuntime.homeDir, "attachments")
            insideBase = "/data/data/com.termux/files/home/attachments"
        }
        hostDir.mkdirs()
        val lines = StringBuilder("\n\n--- Attached files (read them from disk) ---\n")
        var copyFailed = false
        for ((idx, item) in items.withIndex()) {
            try {
                val uri = android.net.Uri.parse(item.uri)
                val safe = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outName = "${idx}_$safe"
                val outFile = java.io.File(hostDir, outName)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.setReadable(true, false)
                val kind = when {
                    safe.matches(Regex(".*\\.(png|jpe?g|webp|gif|bmp)$", RegexOption.IGNORE_CASE)) -> "image"
                    safe.matches(Regex(".*\\.(pdf)$", RegexOption.IGNORE_CASE)) -> "PDF"
                    else -> "file"
                }
                // Tell the agent HOW to consume each kind so it actually uses
                // the bytes (e.g. route images through vision, not text).
                val guidance = when (kind) {
                    "image" -> "This is an IMAGE — use your vision/multimodal capability to look at it and answer about its contents. Do NOT describe it from the filename; read the actual pixels from disk."
                    "PDF" -> "This is a PDF document — read and analyze its text/contents from disk."
                    else -> "This is a file — read its contents from disk."
                }
                lines.append("- $kind: $insideBase/$outName\n  $guidance\n")
            } catch (e: Exception) {
                copyFailed = true
                com.orbitai.core.logging.FileLogger.w(
                    "ChatViewModel", "Attachment copy failed",
                    "name=${item.displayName} err=${e.message}"
                )
            }
        }
        lines.append("--- end attachments ---\n")
        if (copyFailed) {
            lines.append("(Note: one or more attachments could not be read from the picker.)\n")
        }
        return lines.toString()
    }

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    // All editions (including Hermes) run their agent LOCALLY via PRoot.
    // Hermes runs the NousResearch hermes-agent binary the same way
    // openclaude runs openclaude — its LLM backend is supplied via the
    // OPENROUTER_API_KEY env var (set from the provider key in Settings).
    // There is no separate "cloud mode": the app is for interacting with
    // the on-device agent directly (per product direction). Hermes's
    // only network use is fetching its LLM completions from OpenRouter.

    /** Hermes default model — a free OpenRouter model that returns HTTP 200 on
     *  both streaming and non-streaming requests with the user's key. Used as
     *  Hermes's LLM backend. */
    private val HERMES_DEFAULT_MODEL = "tencent/hy3:free"

    data class PendingCommand(
        val command: String,
        val isSudo: Boolean
    )

    data class AttachmentItem(
        val uri: String,
        val displayName: String
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

    // Thinking LEVEL (auto/low/medium/high/xhigh) — not a model id. The user
    // picks how hard the agent reasons; we map it per flavor to the CLI's
    // reasoning flag (Claude Code -> --effort, Codex -> --reasoning-effort,
    // OpenRouter-backed flavors -> reasoning.effort). Stored in the same
    // DataStore key as before (thinking_model) but now holds a level string.
    val thinkingLevel: StateFlow<String> = prefsManager.thinkingModel
        .map { it ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

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

    fun setThinkingLevel(level: String) {
        viewModelScope.launch(exceptionHandler) {
            prefsManager.setThinkingModel(level)
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
            // Persist immediately so re-entering the Chat tab resumes THIS
            // session instead of spawning a fresh "New Session" every time.
            repository.insertSession(session)
            _currentSession.value = session
            _messages.value = emptyList()
            loadSession(session.id)
        }
    }

    /**
     * Resume the most-recent existing session, or create one if there are
     * none. This is what the Chat tab calls on open so we never multiply
     * empty sessions in the background (the old bug: every open called
     * startNewSession, so History filled with orphan "New Session" rows).
     */
    fun resumeOrCreateSession() {
        viewModelScope.launch(exceptionHandler) {
            val existing = repository.getAllSessions().firstOrNull()?.firstOrNull()
            if (existing != null) {
                _currentSession.value = existing
                loadSession(existing.id)
            } else {
                startNewSession(null)
            }
        }
    }

    // ── Test/debug hook ────────────────────────────────────────────────
    // Lets a harness drive a real chat send without relying on soft-keyboard
    // text entry (which is unreliable on some emulators).Send:
    //   am broadcast -a com.orbitai.TEST_SEND --es prompt "your message"
    // This calls the exact same sendMessage() path the UI uses, so it fully
    // exercises ChatViewModel -> HermesRuntime.runAgent -> UI render.
    private val testReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.orbitai.TEST_SEND") {
                val p = intent.getStringExtra("prompt") ?: return
                com.orbitai.core.logging.FileLogger.i("ChatViewModel", "testReceiver got prompt", "len=${p.length}")
                if (com.orbitai.core.config.FlavorConfig.isHermes) {
                    viewModelScope.launch(exceptionHandler) { sendMessage(p) }
                }
            }
        }
    }

    init {
        val c = termuxRuntime.appContext
        val filter = android.content.IntentFilter("com.orbitai.TEST_SEND")
        c.registerReceiver(testReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        com.orbitai.core.logging.FileLogger.i("ChatViewModel", "testReceiver registered")
    }

    fun sendMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return

        // Slash commands: route before any agent execution.
        if (text.startsWith("/")) {
            handleSlashCommand(text)
            return
        }

        val session = _currentSession.value ?: return
        viewModelScope.launch(exceptionHandler) {
            // Session is already persisted (startNewSession / resumeOrCreateSession
            // inserts it up-front). Do NOT re-insert here — re-inserting with
            // REPLACE is harmless for the row but was the source of confusion;
            // we skip it so we never create duplicate session entities.

            val userMsg = Message(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                role = MessageRole.USER,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            repository.insertMessage(userMsg)

            // Multimodal / attachments: copy queued files into a PRoot-visible
            // dir and build a suffix telling the agent where to read them.
            val attachSuffix = prepareAttachmentsForAgent()
            val agentContent = if (attachSuffix.isBlank()) content else content + attachSuffix
            clearAttachments()

            // ── Hermes edition: run the REAL local hermes-agent on-device ──
            // The agent (LLM reasoning + tool calls + shell) runs inside the
            // glibc Debian PRoot rootfs. OpenRouter is only the LLM backend.
            // This is NOT an OpenRouter chat shim — the agent process is real.
            if (com.orbitai.core.config.FlavorConfig.isHermes) {
                _isLoading.value = true
                _streamLines.value = emptyList()
                _showTranscript.value = true
                val apiKey = prefsManager.getApiKeyForProvider("OpenRouter").firstOrNull() ?: ""
                val model = HERMES_DEFAULT_MODEL
                if (apiKey.isBlank()) {
                    val errMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.MODEL,
                        content = "No OpenRouter API key configured. Open the Provider tab, tap the pencil on OpenRouter, paste your key, and tap Save.",
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(errMsg)
                    _isLoading.value = false
                    _showTranscript.value = false
                    return@launch
                }
                if (!hermesRuntime.isInstalled) {
                    val errMsg = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.MODEL,
                        content = "The Hermes local runtime is not installed yet. Go to Setup Wizard and complete setup first.",
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(errMsg)
                    _isLoading.value = false
                    _showTranscript.value = false
                    return@launch
                }
                val output = hermesRuntime.runAgent(agentContent, apiKey, model) { line ->
                    _streamLines.value = _streamLines.value + line
                }
                com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Hermes agent run done", "outLen=${output.length}")
                val modelMsg = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    role = MessageRole.MODEL,
                    content = if (output.isNotBlank()) output else "(Hermes returned no output)",
                    timestamp = System.currentTimeMillis()
                )
                repository.insertMessage(modelMsg)
                _showTranscript.value = false
                _streamLines.value = emptyList()
                _isLoading.value = false
                return@launch
            }

            // Hermes edition: its AI is always backed by OpenRouter (the user's
            // provider key), regardless of any stale selected-provider value.
            val activeProvider = if (com.orbitai.core.config.FlavorConfig.isHermes) {
                "OpenRouter"
            } else {
                prefsManager.selectedProvider.firstOrNull() ?: DEFAULT_PROVIDER
            }
            // Hermes edition: always use a proven-working free OpenRouter model
            // as its LLM backend. The dropdown may show a stale local-model name,
            // but Hermes's AI is backed by OpenRouter, so we pin a valid model.
            val activeModelName = if (com.orbitai.core.config.FlavorConfig.isHermes) {
                HERMES_DEFAULT_MODEL
            } else {
                prefsManager.selectedModel.firstOrNull() ?: ""
            }
            val activeAgentId = prefsManager.selectedAgent.firstOrNull()
                ?.lowercase()?.replace(" ", "-")
                ?: return@launch

            val thinkingLevel = prefsManager.thinkingModel.firstOrNull() ?: "auto"

            _isLoading.value = true

            // Hermes flavor has no local CLI binary in this environment (the
            // Python hermes-agent requires Python <3.14 but Termux ships 3.14,
            // and glibc toolchains won't run under the bionic PRoot). So Hermes
            // routes its AI through the configured provider (OpenRouter) as its
            // LLM backend — this is the working "local orchestrator + OpenRouter
            // brain" path. All other editions run their local agent binary.
            // All non-Hermes editions run the local agent binary (no cloud mode).
            if (!com.orbitai.core.config.FlavorConfig.isHermes) {
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

                    // Permission gating: the Settings permission level controls
                    // whether the agent may run shell/tool commands WITHOUT an
                    // interactive approval prompt.
                    //   FULL_ACCESS -> --dangerously-skip-permissions (auto-run all)
                    //   NORMAL / RULES -> permissions are enforced by the agent;
                    //     the user is prompted per action instead of everything
                    //     running blindly. This makes the Settings control real.
                    val permLevel = AgentPermissionLevel.fromValue(
                        prefsManager.agentPermissionLevel.firstOrNull() ?: "NORMAL"
                    )
                    val dangerouslySkip = if (permLevel == AgentPermissionLevel.FULL_ACCESS) {
                        " --dangerously-skip-permissions"
                    } else {
                        ""
                    }

                    // Thinking level → per-flavor CLI reasoning flag.
                    // Providers name the knob differently (research-backed):
                    //  - Claude Code (Anthropic): --effort {low|medium|high|max}
                    //    (xhigh maps to "max"; "auto" = don't pass -> model default)
                    //  - Codex (OpenAI):         --reasoning-effort {low|medium|high}
                    //    (xhigh maps to "high")
                    //  - OpenClaude/OpenCode via OpenRouter: reasoning.effort
                    //    {low|medium|high|xhigh} (OpenRouter's unified knob),
                    //    exported as OPENROUTER_REASONING_EFFORT + --thinking enabled.
                    val thinkingFlag = buildThinkingFlag(activeAgentId, thinkingLevel)

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

                    com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec start (PRoot, streamed)", "cmd=$runCmd content=${content.take(80)}")
                    _streamLines.value = emptyList()
                    _showTranscript.value = true
                    val escaped = agentContent.replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")

                    // Accumulates the final assistant answer parsed out of the
                    // stream-json events (so the answer bubble shows clean prose,
                    // while the transcript shows the live tool/thinking events).
                    val answerAccumulator = StringBuilder()
                    // Whether the CLI supports the Claude-Code stream-json protocol.
                    // If the first attempt yields parseable events we trust it;
                    // otherwise we fall back to plain text streaming below.
                    var sawStreamEvent = false

                    val onStreamLine: (String) -> Unit = { raw ->
                        val parsed = parseAgentStreamLine(raw, answerAccumulator)
                        if (parsed != null) {
                            sawStreamEvent = true
                            if (parsed.isNotBlank()) _streamLines.value = _streamLines.value + parsed
                        } else {
                            // Not JSON — show the raw line so plain-text CLIs are
                            // still transparent.
                            _streamLines.value = _streamLines.value + raw
                        }
                    }

                    // Method 1: stream-json (real transparency — one event per line).
                    // openclaude / claude / opencode-compatible CLIs emit assistant
                    // text, tool_use and tool_result events as they work.
                    var fullCmd = "$envExports $runCmd -p \"$escaped\" --output-format stream-json --verbose$thinkingFlag$dangerouslySkip"
                    var result = termuxRuntime.executeInTermuxStreamed(fullCmd, "", onStreamLine)
                    com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (stream-json)", "exit=${result.exitCode} events=$sawStreamEvent output=${result.output.take(500)}")

                    // If stream-json wasn't supported (no events parsed) or it
                    // failed, fall back to plain -p flag.
                    if (!sawStreamEvent && (result.exitCode != 0 || result.output.isBlank())) {
                        answerAccumulator.clear()
                        _streamLines.value = emptyList()
                        fullCmd = "$envExports $runCmd -p \"$escaped\"$dangerouslySkip"
                        result = termuxRuntime.executeInTermuxStreamed(fullCmd, "") { line ->
                            _streamLines.value = _streamLines.value + line
                        }
                        com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (-p flag)", "exit=${result.exitCode} output=${result.output.take(2000)}")
                    }

                    // If -p also failed, try stdin pipe
                    if (!sawStreamEvent && (result.exitCode != 0 || result.output.isBlank())) {
                        fullCmd = "$envExports echo \"$escaped\" | $runCmd -p$dangerouslySkip"
                        result = termuxRuntime.executeInTermuxStreamed(fullCmd, "") { line ->
                            _streamLines.value = _streamLines.value + line
                        }
                        com.orbitai.core.logging.FileLogger.i("ChatViewModel", "Agent exec (stdin+pipe)", "exit=${result.exitCode} output=${result.output.take(2000)}")
                    }

                    // Decide what to show the user:
                    // - success (exit 0) with output -> show it
                    // - all attempts failed -> show the most informative error we
                    //   captured (prefer the first attempt's non-blank output),
                    //   clearly marked as a failure instead of a bare "(no output)".
                    val allFailed = result.exitCode != 0 || result.output.isBlank()
                    val parsedAnswer = answerAccumulator.toString().trim()
                    val displayContent = if (sawStreamEvent && parsedAnswer.isNotBlank()) {
                        // stream-json path: show the clean assistant answer we
                        // accumulated from the events.
                        parsedAnswer
                    } else if (!allFailed) {
                        result.output
                    } else {
                        val bestError = listOf(result.output)
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
                    _showTranscript.value = false
                    _streamLines.value = emptyList()
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
            promptBuilder.append("USER: $agentContent\nMODEL: ")

            loopPromptBuilder = promptBuilder
            loopContinueLooping = true
            loopSessionId = session.id
            loopActiveProvider = activeProvider
            loopActiveModelName = activeModelName
            loopApiKey = apiKey

            continueAiLoop()
        }
    }

    private val slashHandler = object : com.orbitai.core.commands.SlashCommandHandler {
        override fun postSystemMessage(text: String) {
            val session = _currentSession.value ?: return
            viewModelScope.launch(exceptionHandler) {
                repository.insertMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        role = MessageRole.TOOL,
                        content = text,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        override fun triggerUpdate() {
            viewModelScope.launch(exceptionHandler) {
                runUpdateCheck()
            }
        }

        override fun clearSession() {
            val session = _currentSession.value ?: return
            viewModelScope.launch(exceptionHandler) {
                repository.deleteMessagesForSession(session.id)
                _messages.value = emptyList()
                postSystemMessage("Session cleared.")
            }
        }

        override fun openSkills() {
            onNavigateToSkills?.invoke()
        }
    }

    private fun handleSlashCommand(raw: String) {
        val parts = raw.trim().removePrefix("/").split(" ", limit = 2)
        val name = parts[0].lowercase()
        val arg = parts.getOrNull(1) ?: ""
        val cmd = com.orbitai.core.commands.ChatSlashCommands.ALL.find { it.name == name }
        if (cmd == null) {
            slashHandler.postSystemMessage("Unknown command: /$name. Type / for a list.")
            return
        }
        if (cmd.immediate) {
            val handled = com.orbitai.core.commands.ChatSlashCommands.execute(cmd, arg, slashHandler)
            if (!handled) {
                slashHandler.postSystemMessage("Command /$name is not executable here.")
            }
        } else {
            // Non-immediate: insert into the conversation as a user note so the
            // agent sees it (e.g. /btw use the style guide).
            val session = _currentSession.value
            if (session != null) {
                viewModelScope.launch(exceptionHandler) {
                    repository.insertMessage(
                        Message(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.id,
                            role = MessageRole.USER,
                            content = raw,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    /** Minimal update check used by the /update slash command. Delegates to
     *  the shared UpdateManager so the "already up to date" gate and the
     *  install path are identical to Settings/Dashboard. */
    private suspend fun runUpdateCheck() {
        val context = termuxRuntime.appContext
        val container = (context.applicationContext as com.orbitai.OrbitAiApplication).container
        val manager = com.orbitai.data.local.updater.UpdateManager(
            container.appContext,
            container.okHttpClient,
            container.silentUpdater
        )
        try {
            val check = manager.checkForUpdate()
            if (!check.available || check.apkUrl == null) {
                slashHandler.postSystemMessage(check.message.ifBlank { "Already on the latest version." })
                return
            }
            slashHandler.postSystemMessage("Downloading update ${check.tag}…")
            when (val res = manager.downloadAndInstall(check.apkUrl)) {
                is com.orbitai.data.local.updater.UpdateInstallResult.Success -> {
                    slashHandler.postSystemMessage("Updated successfully — restarting…")
                    manager.restartApp()
                }
                is com.orbitai.data.local.updater.UpdateInstallResult.Failure ->
                    slashHandler.postSystemMessage("Update failed: ${res.reason}")
                is com.orbitai.data.local.updater.UpdateInstallResult.NeedsManualInstall -> {
                    slashHandler.postSystemMessage("New version downloaded — tap Install to update.")
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(res.apkUri, "application/vnd.android.package-archive")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        slashHandler.postSystemMessage("Couldn't open installer: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "network error (couldn't reach GitHub)"
            slashHandler.postSystemMessage("Couldn't check for updates — $reason")
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
     * Parse one line of a CLI agent's `--output-format stream-json` output
     * (the Claude-Code streaming protocol shared by openclaude / claude /
     * opencode). Returns a short human-readable transcript line describing the
     * event, and appends any assistant text to [answerAcc] so the final answer
     * bubble can show clean prose.
     *
     * Returns null when the line is not valid stream-json (so the caller can
     * fall back to showing the raw line for plain-text CLIs).
     */
    private fun parseAgentStreamLine(raw: String, answerAcc: StringBuilder): String? {
        val line = raw.trim()
        if (line.isEmpty() || !line.startsWith("{")) return null
        return try {
            val obj = org.json.JSONObject(line)
            when (obj.optString("type")) {
                "system" -> {
                    val sub = obj.optString("subtype")
                    if (sub == "init") "• session started" else "• system: $sub"
                }
                "assistant" -> {
                    val msg = obj.optJSONObject("message")
                    val blocks = msg?.optJSONArray("content")
                    val sb = StringBuilder()
                    if (blocks != null) {
                        for (i in 0 until blocks.length()) {
                            val b = blocks.optJSONObject(i) ?: continue
                            when (b.optString("type")) {
                                "text" -> {
                                    val t = b.optString("text")
                                    if (t.isNotBlank()) {
                                        answerAcc.append(t)
                                        sb.append(t.trim())
                                    }
                                }
                                "tool_use" -> {
                                    val name = b.optString("name")
                                    val input = b.optJSONObject("input")?.toString()?.take(120) ?: ""
                                    sb.append("🔧 $name $input")
                                }
                                "thinking" -> {
                                    val th = b.optString("thinking").take(200)
                                    if (th.isNotBlank()) sb.append("💭 $th")
                                }
                            }
                            if (i < blocks.length() - 1 && sb.isNotEmpty()) sb.append("\n")
                        }
                    }
                    sb.toString()
                }
                "user" -> {
                    // tool_result events come back wrapped as a user message
                    val msg = obj.optJSONObject("message")
                    val blocks = msg?.optJSONArray("content")
                    val sb = StringBuilder()
                    if (blocks != null) {
                        for (i in 0 until blocks.length()) {
                            val b = blocks.optJSONObject(i) ?: continue
                            if (b.optString("type") == "tool_result") {
                                val c = b.opt("content")
                                val text = when (c) {
                                    is org.json.JSONArray -> {
                                        val t = StringBuilder()
                                        for (j in 0 until c.length()) {
                                            t.append(c.optJSONObject(j)?.optString("text") ?: "")
                                        }
                                        t.toString()
                                    }
                                    else -> c?.toString() ?: ""
                                }
                                sb.append("↳ ${text.trim().take(160)}")
                            }
                        }
                    }
                    sb.toString()
                }
                "result" -> {
                    // Final event. If we never captured assistant text, use the
                    // result field as the answer.
                    val res = obj.optString("result")
                    if (answerAcc.isBlank() && res.isNotBlank()) answerAcc.append(res)
                    "" // don't add a transcript line; the answer bubble covers it
                }
                "error" -> "⚠️ ${obj.optString("error", obj.optString("message", "error"))}"
                else -> "" // known-but-uninteresting event: consume silently
            }
        } catch (e: Exception) {
            null // not JSON — let caller show the raw line
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
    /**
     * Map the UI thinking LEVEL (auto/low/medium/high/xhigh) to the agent CLI's
     * reasoning flag, per flavor (each provider names the knob differently):
     *  - claude-code : --effort {low|medium|high|max}  (Anthropic API effort)
     *  - codex       : --reasoning-effort {low|medium|high}  (OpenAI)
     *  - openclaude/opencode (OpenRouter-backed): --thinking enabled + env
     *    OPENROUTER_REASONING_EFFORT={low|medium|high|xhigh} (OpenRouter unified)
     * "auto" returns "" (let the model/CLI use its default reasoning).
     */
    private fun buildThinkingFlag(agentId: String, level: String): String {
        if (level.isBlank() || level == "auto") return ""
        val a = agentId.lowercase()
        return when {
            a.contains("claude code") -> {
                val mapped = if (level == "xhigh") "max" else level
                " --effort $mapped"
            }
            a.contains("codex") -> {
                val mapped = if (level == "xhigh") "high" else level
                " --reasoning-effort $mapped"
            }
            else -> {
                // OpenRouter-backed flavors (openclaude, opencode, and any
                // openai-compatible agent routed via OpenRouter).
                " --thinking enabled && export OPENROUTER_REASONING_EFFORT='$level'"
            }
        }
    }

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
                val activeAgent = if (com.orbitai.core.config.FlavorConfig.presetAgentName.isNotBlank())
                    com.orbitai.core.config.FlavorConfig.presetAgentName
                else selectedAgent.value.orEmpty()
                val catalog = com.orbitai.data.local.runtime.ProviderCatalog
                    .loadForAgent(termuxRuntime.appContext, activeAgent)
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
                    application.container.termuxRuntime,
                    application.container.hermesRuntime,
                    application.container.silentUpdater
                ) as T
            }
        }
    }
}
