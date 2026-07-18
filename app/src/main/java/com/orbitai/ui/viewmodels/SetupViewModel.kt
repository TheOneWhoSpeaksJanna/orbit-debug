package com.orbitai.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.BuildConfig
import com.orbitai.OrbitAiApplication
import com.orbitai.R
import com.orbitai.core.config.FlavorConfig
import com.orbitai.core.logging.FileLogger
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.data.local.prefs.PreferencesManager
import com.orbitai.data.local.runner.LocalCommandRunner
import com.orbitai.data.local.runtime.OrbitAiRuntimeManager
import com.orbitai.domain.models.Agent
import com.orbitai.domain.models.Skill
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.repository.OrbitAiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

private const val DEFAULT_THEME = "System"
private const val DEFAULT_AGENT = "OpenClaude"
private const val DEFAULT_PROVIDER = "OpenRouter"
// Per-edition default provider is resolved by EditionAuth.defaultProviderForAgent
// (Codex -> OpenAI, Claude Code -> Anthropic Claude) so the bundled agent gets a
// usable key. OpenCode/OpenClaude support any provider, so they keep OpenRouter.
// Auth modes for the Anthropic provider — API key OR Claude Max subscription.
// (Values imported from com.orbitai.core.auth.)
private const val AGENT_HERMES = "Hermes"
private const val AGENT_OPENCLAUDE = "OpenClaude"
private const val AGENT_CLAUDE_CODE = "Claude Code"
private const val AGENT_OPENCODE = "OpenCode"
private const val AGENT_CODEX = "Codex"
private const val AGENT_DESC = "Agent provisioned during setup"
private const val STATUS_STARTING = "Starting installation..."
private const val STATUS_CHECKING = "Checking prerequisites..."
private const val STATUS_DOWNLOADING = "Downloading "
private const val STATUS_INSTALLING_DEPS = "Installing dependencies..."
private const val STATUS_BUILDING = "Building "
private const val STATUS_CREATING_SCRIPT = "Creating run script..."
private const val STATUS_INSTALLED = " installed successfully!"
private const val STATUS_FAILED = "Installation failed: "

private const val SHIZUKU_SKILL_ID = "shizuku_phone_control"

private val SHIZUKU_SKILL_CONTENT = """
# Shizuku Phone Control Skill

You have Shizuku root-level access on this Android device. You can execute system commands
that normal apps cannot. This skill documents what commands are available for phone control.

## System Settings (settings command)
Settings are stored in three databases: global, system, secure.

### Dark Mode
  settings put secure ui_night_mode 0  (off / light mode)
  settings put secure ui_night_mode 1  (on / dark mode - battery saver)
  settings put secure ui_night_mode 2  (on / dark mode - always)
  To check current: settings get secure ui_night_mode

### Screen Brightness
  settings put system screen_brightness 0-255
  Auto-brightness: settings put system screen_brightness_mode 0 (manual) / 1 (auto)

### Display
  settings put global window_animation_scale 0.0-1.0
  settings put global transition_animation_scale 0.0-1.0
  settings put global animator_duration_scale 0.0-1.0
  wm density 420  (change DPI)
  wm size 1080x2400  (change resolution)

### Screen Timeout
  settings put system screen_off_timeout 30000  (milliseconds)

### Font Size
  settings put system font_scale 1.0  (default)
  settings put system font_scale 1.15 (large)

## Connectivity (svc command)
  svc wifi enable / svc wifi disable
  svc bluetooth enable / svc bluetooth disable
  svc data enable / svc data disable
  svc nfc enable / svc nfc disable  (if supported)

## Volume Control
  media volume --show --stream 3 --set 10  (media volume 0-15)
  Streams: 0=call, 1=system, 2=ring, 3=media, 4=alarm, 5=notification

## App Management (am / pm commands)
  am start -n com.package.name/.Activity  (open app)
  am start -a android.intent.action.VIEW -d url  (open URL)
  am force-stop com.package.name  (force stop app)
  pm list packages  (list installed packages)
  pm list packages | grep keyword  (search for app)

## Input Simulation (input command)
  input tap x y  (simulate tap)
  input swipe x1 y1 x2 y2  (simulate swipe)
  input keyevent KEYCODE_HOME  (home button)
  input keyevent KEYCODE_BACK  (back button)
  input keyevent KEYCODE_APP_SWITCH  (recent apps)
  input text "hello"  (type text - requires focused field)
  Keycodes: 3=HOME, 4=BACK, 5=CALL, 24=VOLUME_UP, 25=VOLUME_DOWN, 26=POWER, 187=APP_SWITCH

## Screenshot
  screencap /sdcard/Pictures/screenshot.png
  screenrecord /sdcard/Pictures/record.mp4

## Device Info
  getprop  (all properties)
  getprop ro.product.model
  getprop ro.build.version.sdk
  dumpsys battery  (battery status)
  dumpsys window displays  (display info)
  dumpsys connectivity  (network info)

## Date & Time
  settings put global auto_time 0 / 1  (auto time)
  settings put global auto_time_zone 0 / 1  (auto timezone)
  date +%s -s @TIMESTAMP  (set time - requires root)

## Usage Pattern
When the user asks you to control the phone:
1. Determine the appropriate command
2. Use [SUDO: command] to execute it
3. Report the result
""".trimIndent()


private val AGENT_INSTALL_DIRS = mapOf(
    AGENT_HERMES to "hermes",
    AGENT_OPENCLAUDE to "openclaude",
    AGENT_CLAUDE_CODE to "claude-code",
    AGENT_OPENCODE to "opencode",
    AGENT_CODEX to "codex"
)

/**
 * NPM package names for each agent. Used to install the agent via npm
 * inside the Termux rootfs under PRoot.
 */
private val NPM_PACKAGES = mapOf(
    AGENT_OPENCLAUDE to "@gitlawb/openclaude",
    AGENT_CLAUDE_CODE to "@anthropic-ai/claude-code",
    AGENT_OPENCODE to "@opencode-ai/cli",
    AGENT_CODEX to "@openai/codex"
)

/**
 * Binary name for each agent after `npm install -g`.
 * npm creates a symlink in $PREFIX/bin/ pointing to the package's bin entry.
 * The runCommand stores just this binary name — it's found via PATH inside
 * the rootfs when executed via termuxRuntime.executeInTermux().
 */
private val AGENT_BINARIES = mapOf(
    AGENT_OPENCLAUDE to "openclaude",
    AGENT_CLAUDE_CODE to "claude",
    AGENT_OPENCODE to "opencode",
    AGENT_CODEX to "codex",
    AGENT_HERMES to "hermes"
)

private val SYSTEM_PROMPTS = mapOf(
    AGENT_HERMES to """You are Hermes, a local execution agent with Shizuku root access. You can control the Android device.

CAPABILITIES:
1. Execute shell commands: [RUN: command] - regular shell access
2. Execute privileged commands: [SUDO: command] - runs via Shizuku (root/system-level access)

PHONE CONTROL EXAMPLES (use [SUDO: ...]):
- Change dark mode: settings put secure ui_night_mode 2 (dark) / 1 (light)
- Set brightness: settings put system screen_brightness 200
- Toggle WiFi: svc wifi enable / svc wifi disable
- Toggle Bluetooth: svc bluetooth enable / svc bluetooth disable
- Toggle mobile data: svc data enable / svc data disable
- Set volume: media volume --stream 3 --set 10
- Open app: am start -n com.package.name/.ActivityName
- Take screenshot: screencap /sdcard/screenshot.png
- List installed apps: pm list packages
- Get device info: getprop ro.product.model
- Get battery: dumpsys battery
- Simulate tap: input tap x y
- Simulate swipe: input swipe x1 y1 x2 y2
- Simulate key: input keyevent KEYCODE_HOME
- Change display density: wm density 420
- Change display size: wm size 1080x2400

RULES:
- Output [RUN: ...] or [SUDO: ...] when you need to execute something
- Do not wrap the tag in markdown, just the raw tag
- For phone control tasks, prefer [SUDO: ...] since they need system privileges
- If you just want to talk, respond normally""",
    AGENT_OPENCLAUDE to "You are OpenClaude, an open-source Claude integration with full tool use.",
    AGENT_CLAUDE_CODE to "You are Claude Code, a specialized coding agent with codebase awareness.",
    AGENT_OPENCODE to "You are OpenCode, an open-source coding agent specialized in automated code generation and local execution.",
    AGENT_CODEX to "You are Codex, an AI coding agent powered by OpenAI with strong instruction-following capabilities."
)

enum class SetupStep(@StringRes val labelResId: Int) {
    Welcome(R.string.step_welcome),
    Theme(R.string.step_theme),
    Agent(R.string.step_agent),
    Provider(R.string.step_provider),
    Shizuku(R.string.step_shizuku),
    Storage(R.string.step_storage),
    Summary(R.string.step_summary);
}

/**
 * High-level state of the post-"Finish Setup" finalization flow.
 *
 * IDLE       — user hasn't clicked Finish Setup yet.
 * FINALIZING — agent install (Termux bootstrap + npm + wrapper) is running.
 * READY      — install completed successfully (or user chose to skip).
 * FAILED     — install threw an exception; user can retry or skip.
 *
 * The UI watches this to decide whether to show the loading overlay.
 */
enum class SetupPhase {
    IDLE,
    FINALIZING,
    READY,
    FAILED
}

class SetupViewModel(
    private val prefsManager: PreferencesManager,
    private val repository: OrbitAiRepository,
    private val aiProvider: AiProvider,
    private val runtimeManager: OrbitAiRuntimeManager,
    private val appContainer: com.orbitai.core.di.AppContainer
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("SetupViewModel")

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _theme = MutableStateFlow(DEFAULT_THEME)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _shizukuEnabled = MutableStateFlow(false)
    val shizukuEnabled: StateFlow<Boolean> = _shizukuEnabled.asStateFlow()

    private val _storagePermissionGranted = MutableStateFlow(false)
    val storagePermissionGranted: StateFlow<Boolean> = _storagePermissionGranted.asStateFlow()

    fun setStoragePermissionGranted(granted: Boolean) {
        _storagePermissionGranted.value = granted
    }

    private val _selectedAgent = MutableStateFlow(
        if (FlavorConfig.presetAgentName.isNotBlank()) FlavorConfig.presetAgentName
        else DEFAULT_AGENT
    )
    val selectedAgent: StateFlow<String> = _selectedAgent.asStateFlow()

    private val _hasFlavorPreset = MutableStateFlow(FlavorConfig.presetAgentName.isNotBlank())
    val hasFlavorPreset: StateFlow<Boolean> = _hasFlavorPreset.asStateFlow()

    val filteredSteps: List<SetupStep>
        get() = if (_hasFlavorPreset.value) SetupStep.entries.filter { it != SetupStep.Agent }
        else SetupStep.entries

    private val _selectedProvider = MutableStateFlow(
        com.orbitai.core.auth.defaultProviderForAgent(FlavorConfig.presetAgentName) ?: DEFAULT_PROVIDER
    )
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    // Claude (Anthropic) auth mode: API key OR Claude Max subscription token.
    // Only meaningful when selectedProvider resolves to Anthropic Claude.
    private val _claudeAuthMode = MutableStateFlow(com.orbitai.core.auth.CLAUDE_AUTH_API_KEY)
    val claudeAuthMode: StateFlow<String> = _claudeAuthMode.asStateFlow()

    // Hermes (cloud general agent) ships with a proven-working free
    // OpenRouter model so chat responds out of the box once a key is set.
    private val _selectedModel = MutableStateFlow(
        if (com.orbitai.core.config.FlavorConfig.isHermes) "google/gemma-4-26b-a4b-it:free" else ""
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    // Holds either the API key (api-key mode) or the subscription token
    // (subscription mode). completeSetup() routes it to the right store.
    val claudeSubscriptionToken: StateFlow<String> = _apiKey.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _testConnectionSuccess = MutableStateFlow<Boolean?>(null)
    val testConnectionSuccess: StateFlow<Boolean?> = _testConnectionSuccess.asStateFlow()

    private val _testConnectionError = MutableStateFlow<String?>(null)
    val testConnectionError: StateFlow<String?> = _testConnectionError.asStateFlow()

    data class AgentInstallState(
        val isInstalling: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val isInstalled: Boolean = false
    )

    private val _agentInstallStates = MutableStateFlow(
        mapOf(
            AGENT_HERMES to AgentInstallState(),
            AGENT_OPENCLAUDE to AgentInstallState(),
            AGENT_CLAUDE_CODE to AgentInstallState(),
            AGENT_OPENCODE to AgentInstallState(),
            AGENT_CODEX to AgentInstallState()
        )
    )
    val agentInstallStates: StateFlow<Map<String, AgentInstallState>> = _agentInstallStates.asStateFlow()

    /**
     * Tracks the post-"Finish Setup" finalization phase. Drives the
     * FinalizingOverlay UI so the user sees real progress before being
     * dropped into the dashboard with an uninstalled agent.
     */
    private val _setupPhase = MutableStateFlow(SetupPhase.IDLE)
    val setupPhase: StateFlow<SetupPhase> = _setupPhase.asStateFlow()

    /**
     * Live log stream for the FinalizingOverlay. Each log entry is a
     * timestamped string that the UI displays in a scrolling list so
     * the user can see exactly what's happening during installation.
     */
    private val _liveLogs = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    val liveLogs: SharedFlow<String> = _liveLogs.asSharedFlow()

    /** Emit a log to both FileLogger and the live UI stream. */
    private fun emitLog(tag: String, event: String, details: String = "") {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = if (details.isNotBlank()) "$timestamp | $event | $details" else "$timestamp | $event"
        FileLogger.i(tag, event, details)
        _liveLogs.tryEmit(line)
    }

    val canAdvance: Boolean
        get() {
            val step = filteredSteps.getOrNull(_currentStep.value) ?: return false
            return when (step) {
                SetupStep.Welcome -> true
                SetupStep.Theme -> _theme.value.isNotBlank()
                SetupStep.Agent -> _selectedAgent.value.isNotBlank()
                // Ollama doesn't require an API key — its key slot is an optional base URL.
                // Provider step never blocks advancing — the user can skip the API key /
                // "Test Connection" step and configure the provider later in the Providers tab.
                SetupStep.Provider -> true
                SetupStep.Shizuku -> true
                SetupStep.Storage -> true
                SetupStep.Summary -> true
            }
        }

    fun nextStep() { _currentStep.value += 1 }

    fun previousStep() {
        if (_currentStep.value > 0) _currentStep.value -= 1
    }

    fun setTheme(mode: String) {
        _theme.value = mode
        viewModelScope.launch(exceptionHandler) { prefsManager.setThemeMode(mode) }
    }
    fun setShizukuEnabled(enabled: Boolean) { _shizukuEnabled.value = enabled }
    fun setSelectedAgent(agent: String) { _selectedAgent.value = agent }
    fun setSelectedProvider(provider: String) {
        _selectedProvider.value = provider
        // Leaving Anthropic resets the auth mode to API key.
        if (!provider.contains("Anthropic", ignoreCase = true) &&
            !provider.contains("Claude", ignoreCase = true)
        ) {
            _claudeAuthMode.value = com.orbitai.core.auth.CLAUDE_AUTH_API_KEY
        }
    }
    fun setClaudeAuthMode(mode: String) { _claudeAuthMode.value = mode }
    fun setSelectedModel(model: String) { _selectedModel.value = model }
    fun setApiKey(key: String) { _apiKey.value = key }

    fun testConnection() {
        viewModelScope.launch(exceptionHandler) {
            _isTestingConnection.value = true
            _testConnectionSuccess.value = null
            _testConnectionError.value = null

            val model = _selectedModel.value.ifBlank {
                aiProvider.getModels(_selectedProvider.value).firstOrNull() ?: ""
            }

            if (_apiKey.value.isBlank()) {
                _testConnectionSuccess.value = false
                _testConnectionError.value = "API key is blank"
                _isTestingConnection.value = false
                return@launch
            }

            val result = aiProvider.generateContent(
                prompt = "Reply with exactly: ok",
                apiKey = _apiKey.value,
                provider = _selectedProvider.value,
                model = model
            )

            when (result) {
                is AiResult.Success -> {
                    _testConnectionSuccess.value = true
                    _testConnectionError.value = null
                }
                is AiResult.Error -> {
                    _testConnectionSuccess.value = false
                    _testConnectionError.value = result.message
                }
            }
            _isTestingConnection.value = false
        }
    }

    fun installOpenClaude() = installAgent(AGENT_OPENCLAUDE)
    fun installHermes() = installAgent(AGENT_HERMES)
    fun installClaudeCode() = installAgent(AGENT_CLAUDE_CODE)
    fun installOpenCode() = installAgent(AGENT_OPENCODE)
    fun installCodex() = installAgent(AGENT_CODEX)

    // Track in-flight install jobs per agent to prevent duplicate concurrent installs.
    // A second installAgent() call while one is running is a no-op.
    private val installJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Install an agent. The guard prevents duplicate concurrent installs
     * from rapid double-taps on the Install button.
     *
     * When called from completeSetup()/retryInstall(), pass skipGuard=true
     * because those functions already have their own _setupPhase guard.
     */
    fun installAgent(agentName: String, skipGuard: Boolean = false) {
        // Synchronous guard — check BEFORE launching so a rapid double-tap
        // sees isInstalling=true and bails out. Without this, two coroutines
        // could both run npm install concurrently and corrupt the global
        // package metadata lockfile.
        // Skip the guard when called from completeSetup/retryInstall (they
        // have their own _setupPhase guard and need to call installAgent
        // even if isInstalling was pre-set).
        if (!skipGuard) {
            val currentState = _agentInstallStates.value[agentName]
            if (currentState?.isInstalling == true) {
                FileLogger.w("SetupViewModel", "installAgent already in progress, ignoring", "agent=$agentName")
                return
            }
        }

        // Cancel any previous job (shouldn't exist due to guard above, but
        // defensive in case state was reset externally)
        installJobs[agentName]?.cancel()

        val targetDirName = AGENT_INSTALL_DIRS[agentName] ?: agentName.lowercase().replace(" ", "-")

        // Set isInstalling=true SYNCHRONOUSLY before launch so the UI
        // can disable the button on the next frame before a second tap.
        _agentInstallStates.value = _agentInstallStates.value + (agentName to AgentInstallState(
            isInstalling = true,
            progress = 0f,
            status = STATUS_STARTING,
            isInstalled = false
        ))

        installJobs[agentName] = viewModelScope.launch(exceptionHandler) {

            try {
                emitLog("SetupViewModel", "Install start", "agent=$agentName")
                updateInstallState(agentName, status = STATUS_CHECKING)

                val termuxRuntime = appContainer.termuxRuntime
                val hermesRuntime = appContainer.hermesRuntime

                val isHermes = com.orbitai.core.config.FlavorConfig.isHermes
                val npmPackage = NPM_PACKAGES[agentName]

                // Hermes runs the REAL NousResearch hermes-agent locally inside a
                // glibc Debian aarch64 PRoot rootfs (bundled asset). Extract it
                // (the bionic Termux rootfs is NOT used for Hermes). The agent's
                // LLM backend is OpenRouter, supplied via OPENROUTER_API_KEY.
                if (isHermes) {
                    if (!hermesRuntime.isInstalled) {
                        emitLog("SetupViewModel", "Extracting Hermes local agent runtime...")
                        updateInstallState(agentName, progress = 0.5f, status = "Installing Hermes runtime...")
                        val ok = hermesRuntime.install { progress, status ->
                            emitLog("SetupViewModel", status, "progress=${(progress * 100).toInt()}%")
                            updateInstallState(agentName, progress = 0.5f + progress * 0.45f, status = status)
                        }
                        if (!ok) {
                            throw IllegalStateException("Failed to install Hermes local runtime")
                        }
                    }
                    emitLog("SetupViewModel", "Hermes local agent ready — runs on-device via PRoot")
                    updateInstallState(agentName, progress = 1f, status = "Hermes ready (local agent)", isInstalled = true)
                } else {
                // Install the agent. Try local tarball first (pre-bundled in APK
                // assets), fall back to npm registry download if not available.
                if (npmPackage != null) {
                    updateInstallState(agentName, progress = 0.6f, status = STATUS_INSTALLING_DEPS)

                    // Try installing from pre-bundled tarball (offline, no network needed)
                    //
                    // NOTE: the tarball is copied into runtimeDir/tmp/ which is
                    // bind-mounted INSIDE PRoot at /orbit/tmp (see the
                    // "-b $runtimeDir:/orbit" flag in executeInTermux). It must
                    // NOT be referenced as /tmp/openclaude.tgz — executeInTermux
                    // also binds /data/local/tmp to /tmp, which would shadow the
                    // copied file and make npm fail with ENOENT ("tarball data
                    // for file:/tmp/openclaude.tgz seems to be corrupted").
                    val tarballCopied = copyAssetToRootfs(termuxRuntime, "openclaude.tgz", "/orbit/tmp/openclaude.tgz")
                    if (tarballCopied) {
                        emitLog("SetupViewModel", "Installing agent from bundled tarball", "tarball=/orbit/tmp/openclaude.tgz")
                        val installResult = termuxRuntime.executeInTermux(
                            "npm install -g /orbit/tmp/openclaude.tgz 2>&1",
                            ""
                        )
                        emitLog("SetupViewModel", "npm install (tarball) result", "exit=${installResult.exitCode} output=${installResult.output.take(500)}")
                        if (installResult.exitCode != 0) {
                            FileLogger.w("SetupViewModel", "npm install from tarball failed, trying registry", "exit=${installResult.exitCode}")
                            // Fall back to registry download
                            emitLog("SetupViewModel", "Falling back to npm registry", "package=$npmPackage")
                            val registryResult = termuxRuntime.executeInTermux(
                                "npm install -g $npmPackage 2>&1",
                                ""
                            )
                            emitLog("SetupViewModel", "npm install (registry) result", "exit=${registryResult.exitCode} output=${registryResult.output.take(500)}")
                        }
                    } else {
                        // No tarball in assets — download from npm registry
                        emitLog("SetupViewModel", "Installing agent from npm registry", "package=$npmPackage")
                        val installResult = termuxRuntime.executeInTermux(
                            "npm install -g $npmPackage 2>&1",
                            ""
                        )
                        emitLog("SetupViewModel", "npm install result", "exit=${installResult.exitCode} output=${installResult.output.take(500)}")
                    }
                } else {
                    emitLog("SetupViewModel", "No npm package for agent, skipping", "agent=$agentName")
                }
                } // end else (non-Hermes npm install branch)

                emitLog("SetupViewModel", "Determining agent entry point...")
                updateInstallState(agentName, progress = 0.9f, status = STATUS_CREATING_SCRIPT)

                val binaryName = AGENT_BINARIES[agentName] ?: agentName.lowercase().replace(" ", "-")
                val agentEntry = determineAgentEntryPoint(termuxRuntime, binaryName, npmPackage)
                emitLog("SetupViewModel", "Agent runCommand set", "cmd=$agentEntry")

                // Persist the runCommand to the agent entity immediately
                // so ChatViewModel can find it. completeSetup() also sets
                // this, but setting it here ensures it's correct even if
                // completeSetup's insertAgent already ran with a stale value.
                try {
                    val existingAgent = repository.getAllAgents().firstOrNull()
                        ?.find { it.id == agentName.lowercase().replace(" ", "-") }
                    if (existingAgent != null) {
                        repository.insertAgent(existingAgent.copy(runCommand = agentEntry))
                    }
                } catch (e: Exception) {
                    FileLogger.w("SetupViewModel", "Could not update agent runCommand", "reason=${e.message}")
                }

                emitLog("SetupViewModel", "Agent installed", "$agentName ready")
                updateInstallState(agentName, progress = 1f, status = "$agentName$STATUS_INSTALLED", isInstalled = true)

            } catch (e: Exception) {
                emitLog("SetupViewModel", "Install FAILED", e.message ?: "unknown error")
                FileLogger.e("SetupViewModel", "Agent install failed", e, "reason=${e.message}")
                updateInstallState(agentName, status = "$STATUS_FAILED${e.message}", isInstalled = false)
            } finally {
                // Safe access — the entry might not exist if the map was
                // reset externally. Use ?: return@launch to avoid NPE.
                val current = _agentInstallStates.value[agentName]
                if (current != null) {
                    _agentInstallStates.value = _agentInstallStates.value +
                        (agentName to current.copy(isInstalling = false))
                }
                installJobs.remove(agentName)
            }
        }
    }

    /**
     * Determine the correct runCommand for the agent.
     *
     * The problem: `npm install -g` creates a symlink in $PREFIX/bin/ that
     * points to a JS file with shebang `#!/usr/bin/env node`. But `/usr/bin/env`
     * doesn't exist in Termux (Termux uses $PREFIX/bin/env). So the shell
     * reports "openclaude: not found" even though the file exists.
     *
     * Solution: Instead of using the binary name directly, resolve the symlink
     * to find the actual JS file and run it with `node <path>`. This bypasses
     * the broken shebang entirely.
     */
    private suspend fun determineAgentEntryPoint(
        termuxRuntime: com.orbitai.data.local.runtime.TermuxRuntime,
        binaryName: String,
        npmPackage: String?
    ): String {
        // Helper: extract first non-empty line from command output, stripping
        // linker warnings that pollute PRoot output.
        fun cleanOutput(raw: String): String {
            return raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { !it.startsWith("WARNING: linker:") }
                .firstOrNull() ?: ""
        }

        // Strategy 1: Try to actually execute the binary. If it works, use it.
        if (npmPackage != null) {
            val testExec = termuxRuntime.executeInTermux(
                "$binaryName --version 2>&1 || $binaryName --help 2>&1 || echo EXEC_FAILED",
                ""
            )
            val execOutput = cleanOutput(testExec.output)
            emitLog("SetupViewModel", "Binary exec test", "exit=${testExec.exitCode} output=${execOutput.take(200)}")

            if (testExec.exitCode == 0 && execOutput != "EXEC_FAILED" && !execOutput.contains("not found")) {
                emitLog("SetupViewModel", "Binary executes correctly", "name=$binaryName")
                return binaryName
            }
            emitLog("SetupViewModel", "Binary can't execute (shebang issue)", "name=$binaryName")
        }

        // Strategy 2: Resolve the symlink to find the actual JS file.
        // $PREFIX/bin/openclaude -> ../lib/node_modules/@gitlawb/openclaude/bin/openclaude
        // The file may not end in .js — it could be 'openclaude', 'cli.js', etc.
        // So we accept any path that doesn't contain SYMLINK_FAILED.
        if (npmPackage != null) {
            val resolveSymlink = termuxRuntime.executeInTermux(
                "readlink -f \$PREFIX/bin/$binaryName 2>/dev/null || echo SYMLINK_FAILED",
                ""
            )
            val resolvedPath = cleanOutput(resolveSymlink.output)
            emitLog("SetupViewModel", "Symlink resolve", "path=$resolvedPath")

            if (resolvedPath.isNotEmpty() && !resolvedPath.contains("SYMLINK_FAILED")) {
                emitLog("SetupViewModel", "Using node with resolved path", "path=$resolvedPath")
                return "node \"$resolvedPath\""
            }
        }

        // Strategy 3: Find the package's main entry point via node require.resolve
        if (npmPackage != null) {
            val resolveResult = termuxRuntime.executeInTermux(
                "node -e \"try{console.log(require.resolve('$npmPackage'))}catch(e){console.log('RESOLVE_FAILED')}\" 2>/dev/null",
                ""
            )
            val resolvedPath = cleanOutput(resolveResult.output)
            emitLog("SetupViewModel", "Node resolve result", "path=$resolvedPath")
            if (resolvedPath.isNotEmpty() && !resolvedPath.contains("RESOLVE_FAILED") && !resolvedPath.startsWith("Error")) {
                emitLog("SetupViewModel", "Using node with resolved path", "path=$resolvedPath")
                return "node \"$resolvedPath\""
            }
        }

        // Strategy 4: Find via filesystem search
        if (npmPackage != null) {
            val findEntry = termuxRuntime.executeInTermux(
                "find \$PREFIX/lib/node_modules/$npmPackage -maxdepth 2 \\( -name 'cli.js' -o -name 'index.js' -o -name 'main.js' \\) 2>/dev/null | head -5",
                ""
            )
            val foundFiles = cleanOutput(findEntry.output)
            emitLog("SetupViewModel", "Find entry points", "files=$foundFiles")
            if (foundFiles.isNotEmpty()) {
                val entry = foundFiles.split("\n").firstOrNull { it.contains("cli.js") }
                    ?: foundFiles.split("\n").firstOrNull { it.contains("index.js") }
                    ?: foundFiles.split("\n").firstOrNull { it.contains("main.js") }
                    ?: foundFiles.split("\n").firstOrNull()
                if (entry != null && entry.isNotEmpty()) {
                    emitLog("SetupViewModel", "Using node with found entry", "path=$entry")
                    return "node \"$entry\""
                }
            }
        }

        // Strategy 5: Fall back to npx (always works for npm packages)
        if (npmPackage != null) {
            emitLog("SetupViewModel", "Falling back to npx", "package=$npmPackage")
            return "npx $npmPackage"
        }

        // Last resort: just use the binary name
        emitLog("SetupViewModel", "Could not determine entry point, using binary name", "name=$binaryName")
        return binaryName
    }

    /**
     * Copy an asset file from the APK into the rootfs so PRoot can access it.
     * Used to copy the pre-bundled openclaude.tgz into /tmp for offline npm install.
     * Returns true if the file was copied successfully, false if the asset doesn't exist.
     */
    private suspend fun copyAssetToRootfs(
        termuxRuntime: com.orbitai.data.local.runtime.TermuxRuntime,
        assetName: String,
        destPath: String
    ): Boolean {
        return try {
            val inputStream = runtimeManager.context.assets.open(assetName)
            // Write to the rootfs's /tmp directory (bind-mounted from runtimeDir/tmp)
            val destFile = File(termuxRuntime.runtimeDir, "tmp/${assetName}")
            destFile.parentFile?.mkdirs()
            inputStream.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            FileLogger.i("SetupViewModel", "Asset copied to rootfs", "asset=$assetName size=${destFile.length()} dest=$destFile")
            true
        } catch (e: Exception) {
            FileLogger.d("SetupViewModel", "Asset not found or copy failed", "asset=$assetName reason=${e.message}")
            false
        }
    }

    private fun updateInstallState(agentName: String, progress: Float? = null, status: String? = null, isInstalled: Boolean? = null) {
        val current = _agentInstallStates.value[agentName] ?: AgentInstallState()
        _agentInstallStates.value = _agentInstallStates.value + (agentName to current.copy(
            progress = progress ?: current.progress,
            status = status ?: current.status,
            isInstalled = isInstalled ?: current.isInstalled
        ))
    }

    fun completeSetup() {
        // Synchronous guard — prevent double-fire from rapid taps on
        // the "Finish Setup" button. Without this, two coroutines could
        // both run installAgent() and corrupt the npm global lockfile.
        if (_setupPhase.value == SetupPhase.FINALIZING) {
            FileLogger.w("SetupViewModel", "completeSetup already in progress, ignoring")
            return
        }
        _setupPhase.value = SetupPhase.FINALIZING

        viewModelScope.launch(exceptionHandler) {
            FileLogger.i("SetupViewModel", "completeSetup start", "agent=${_selectedAgent.value}")

            prefsManager.setThemeMode(_theme.value)
            prefsManager.setShizukuEnabled(_shizukuEnabled.value)
            prefsManager.setSelectedAgent(_selectedAgent.value)
            // Never blank out a provider that was already chosen/valid — only
            // write when we actually have a value. This keeps an externally
            // configured key (or a previously selected provider) intact across
            // re-runs of setup (e.g. after a force-stop re-triggers onboarding).
            if (_selectedProvider.value.isNotBlank()) {
                prefsManager.setSelectedProvider(_selectedProvider.value)
            }
            if (_selectedModel.value.isNotBlank()) {
                prefsManager.setSelectedModel(_selectedModel.value)
            }

            // Claude (Anthropic) auth: persist API key OR subscription token
            // based on the chosen auth mode. ChatViewModel reads both to
            // export the correct env var (ANTHROPIC_API_KEY vs ANTHROPIC_AUTH_TOKEN).
            val isAnthropic = _selectedProvider.value.contains("Anthropic", ignoreCase = true) ||
                _selectedProvider.value.contains("Claude", ignoreCase = true)
            if (isAnthropic) {
                prefsManager.setClaudeAuthMode(_claudeAuthMode.value)
                if (_claudeAuthMode.value == com.orbitai.core.auth.CLAUDE_AUTH_SUBSCRIPTION) {
                    prefsManager.setClaudeSubscriptionToken(_apiKey.value)
                } else {
                    prefsManager.setClaudeSubscriptionToken("")
                }
            }

            // Only persist a provider key when the user actually entered one.
            // If _apiKey is blank we MUST NOT overwrite an existing (non-blank)
            // key — otherwise re-running setup would wipe a key the user already
            // configured (critical for cloud agents like Hermes/OpenRouter).
            if (_apiKey.value.isNotBlank()) {
                prefsManager.setApiKeyForProvider(_selectedProvider.value, _apiKey.value)
            }

            val agentName = _selectedAgent.value
            val sysPrompt = SYSTEM_PROMPTS[agentName] ?: "You are an expert AI assistant."
            val binaryName = AGENT_BINARIES[agentName] ?: agentName.lowercase().replace(" ", "-")

            // runCommand is the agent's binary name (e.g. "openclaude").
            // ChatViewModel runs it via termuxRuntime.executeInTermux(runCommand)
            // which finds it in $PREFIX/bin via PATH inside the rootfs.
            val runCommand = binaryName

            val agent = Agent(
                id = agentName.lowercase().replace(" ", "-"),
                name = agentName,
                description = AGENT_DESC,
                systemPrompt = sysPrompt,
                runCommand = runCommand
            )
            repository.insertAgent(agent)

            // Install the agent. skipGuard=true because we already have the
            // _setupPhase guard above, and installAgent() needs to run even
            // though we haven't pre-set isInstalling (installAgent sets it
            // itself inside the coroutine).
            installAgent(agentName, skipGuard = true)
            val installSuccess = waitForInstallComplete(agentName)
            FileLogger.i("SetupViewModel", "completeSetup install finished", "success=$installSuccess")

            // Seed default Shizuku skill if not already present
            val existingSkills = repository.getAllSkills().firstOrNull().orEmpty()
            if (existingSkills.none { it.id == SHIZUKU_SKILL_ID }) {
                repository.insertSkill(Skill(
                    id = SHIZUKU_SKILL_ID,
                    name = "Shizuku Phone Control",
                    content = SHIZUKU_SKILL_CONTENT,
                    enabled = _shizukuEnabled.value
                ))
            }

            _setupPhase.value = if (installSuccess) SetupPhase.READY else SetupPhase.FAILED
        }
    }

    /**
     * User chose to skip waiting. Mark as READY so the overlay's
     * "Enter Orbit-AI" button appears. The background install continues
     * running — if it fails later, the user can retry from the dashboard.
     */
    fun skipFinalization() {
        FileLogger.w("SetupViewModel", "User skipped finalization")
        _setupPhase.value = SetupPhase.READY
    }

    /**
     * User clicked Retry on the failure state. Re-runs the install.
     */
    fun retryInstall() {
        // Synchronous guard — prevent double-fire from rapid taps on the
        // Retry button in the FinalizingOverlay's FAILED state.
        if (_setupPhase.value == SetupPhase.FINALIZING) {
            FileLogger.w("SetupViewModel", "retryInstall already in progress, ignoring")
            return
        }
        _setupPhase.value = SetupPhase.FINALIZING

        viewModelScope.launch(exceptionHandler) {
            val agentName = _selectedAgent.value
            FileLogger.i("SetupViewModel", "retryInstall start", "agent=$agentName")
            // Reset state to allow re-install. Don't pre-set isInstalling=true
            // here — installAgent() sets it itself, and pre-setting would
            // trigger the guard inside installAgent().
            _agentInstallStates.value = _agentInstallStates.value + (agentName to
                (_agentInstallStates.value[agentName] ?: AgentInstallState()).copy(
                    isInstalling = false,
                    progress = 0f,
                    status = "Retrying install...",
                    isInstalled = false
                ))
            installAgent(agentName, skipGuard = true)
            val installSuccess = waitForInstallComplete(agentName)
            FileLogger.i("SetupViewModel", "retryInstall finished", "success=$installSuccess")
            _setupPhase.value = if (installSuccess) SetupPhase.READY else SetupPhase.FAILED
        }
    }

    /**
     * Wait until the install actually starts (isInstalling becomes true),
     * then wait for it to finish (isInstalling becomes false), then return
     * the final isInstalled value.
     *
     * Two-phase wait is needed because installAgent() sets isInstalling=true
     * asynchronously inside a viewModelScope.launch. If we only waited for
     * isInstalling==false, we'd match the initial false state immediately.
     */
    private suspend fun waitForInstallComplete(agentName: String): Boolean {
        // Phase 1: Wait for install to START (isInstalling becomes true)
        // Timeout after 10 seconds in case installAgent() failed to launch
        kotlinx.coroutines.withTimeoutOrNull(10_000) {
            _agentInstallStates.firstOrNull { states ->
                val state = states[agentName]
                state?.isInstalling == true
            }
        } ?: run {
            FileLogger.w("SetupViewModel", "waitForInstallComplete: install never started", "agent=$agentName")
            return false
        }

        // Phase 2: Wait for install to FINISH (isInstalling becomes false)
        val finalStates = _agentInstallStates.firstOrNull { states ->
            val state = states[agentName] ?: return@firstOrNull false
            !state.isInstalling
        } ?: return false

        return finalStates[agentName]?.isInstalled ?: false
    }

    fun finishOnboarding() {
        viewModelScope.launch(exceptionHandler) {
            prefsManager.setOnboardingComplete(true)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return SetupViewModel(
                    application.container.prefsManager,
                    application.container.repository,
                    application.container.aiProvider,
                    application.container.runtimeManager,
                    application.container
                ) as T
            }
        }
    }
}
