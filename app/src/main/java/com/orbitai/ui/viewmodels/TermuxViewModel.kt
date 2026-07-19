package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.di.AppContainer
import com.orbitai.core.logging.FileLogger
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.domain.models.TermuxLog
import com.orbitai.domain.repository.OrbitAiRepository
import com.orbitai.core.storage.StorageSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TermuxViewModel"
// Reuse the canonical `sudo ` prefix from LocalCommandRunner so the Terminal
// and Chat privileged paths stay in sync. Termux/PRoot has no real sudo, so
// any `sudo` command is routed through Shizuku (rish) — never the PRoot shell.
private val SUDO_PREFIX = com.orbitai.data.local.runner.SUDO_PREFIX

/**
 * Terminal ViewModel — pure pass-through to the Termux rootfs via PRoot.
 *
 * No hardcoded commands. No custom command interpreter. No install buttons.
 * Every command the user types is sent directly to the Termux shell inside
 * the rootfs via termuxRuntime.executeInTermux(). This gives the user a REAL
 * Linux terminal with bash, node, npm, git, python, apt, etc.
 *
 * The only special case is 'sudo' which routes through Shizuku for elevated
 * (system-level) access on the Android host.
 */
class TermuxViewModel(
    private val repository: OrbitAiRepository,
    private val appContainer: AppContainer
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("TermuxViewModel")

    val logs: StateFlow<List<TermuxLog>> = repository.getAllTermuxLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Execute a command in the Termux rootfs. No interception, no built-in
     * commands — just pass it straight through to the shell.
     *
     * Exception: a `sudo ` prefix is intercepted and routed to Shizuku
     * (ristilled `rish` under the hood) for true system-level (uid 0) access
     * on the Android host. Termux/PRoot has NO real `sudo` binary, so letting
     * "sudo id" reach the PRoot shell would just fail with
     * "sudo: command not found" (exit 127). We never do that — instead we
     * either run it elevated via Shizuku or return a clear "Shizuku not
     * available" message.
     */
    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        FileLogger.i(TAG, "executeCommand: '$trimmed'")

        // `sudo` prefix → Shizuku privileged path (no real sudo in Termux).
        if (trimmed.startsWith(SUDO_PREFIX)) {
            val privileged = trimmed.removePrefix(SUDO_PREFIX).trim()
            executePrivilegedCommand(privileged)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val termuxRuntime = appContainer.termuxRuntime

            // Ensure rootfs is installed (first launch)
            if (!termuxRuntime.isInstalled) {
                repository.insertTermuxLog(
                    TermuxLog(
                        id = java.util.UUID.randomUUID().toString(),
                        command = trimmed,
                        output = "Initializing Linux environment (first launch, ~30s)...",
                        exitCode = -1,
                        timestamp = System.currentTimeMillis()
                    )
                )
                termuxRuntime.install { progress, status ->
                    FileLogger.d(TAG, "Rootfs install: $progress — $status")
                }
            }

            val executionResult = if (com.orbitai.core.config.FlavorConfig.isHermes) {
                val hermesRuntime = appContainer.hermesRuntime
                if (!hermesRuntime.isInstalled) {
                    hermesRuntime.install { _, _ -> }
                }
                StorageSetup.createStorageSymlinks(hermesRuntime.rootfsDir)
                hermesRuntime.execute(trimmed, "")
            } else {
                StorageSetup.createStorageSymlinks(termuxRuntime.homeDir)
                termuxRuntime.executeInTermux(trimmed, "")
            }
            val log = TermuxLog(
                id = java.util.UUID.randomUUID().toString(),
                command = trimmed,
                output = executionResult.output,
                exitCode = executionResult.exitCode,
                timestamp = System.currentTimeMillis()
            )
            repository.insertTermuxLog(log)
        }
    }

    /**
     * Execute a privileged command via Shizuku (Android system-level access,
     * ristilled `rish` under the hood). Termux/PRoot does NOT have a real
     * `sudo`, so this is the ONLY path that yields elevated (uid 0) access on
     * the host. If Shizuku isn't running, we return a clear error instead of
     * silently dropping to the unprivileged PRoot shell.
     */
    fun executePrivilegedCommand(command: String) {
        FileLogger.i(TAG, "executePrivilegedCommand: '$command'")
        viewModelScope.launch(Dispatchers.IO) {
            val executionResult = appContainer.localCommandRunner.executePrivilegedCommand(command)
            if (executionResult.exitCode == -1 && executionResult.output.contains("Shizuku")) {
                // Surface the "Shizuku not available" state clearly in the terminal.
                val log = TermuxLog(
                    id = java.util.UUID.randomUUID().toString(),
                    command = "$SUDO_PREFIX$command",
                    output = executionResult.output,
                    exitCode = executionResult.exitCode,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertTermuxLog(log)
                return@launch
            }
            val log = TermuxLog(
                id = java.util.UUID.randomUUID().toString(),
                command = "$SUDO_PREFIX$command",
                output = executionResult.output,
                exitCode = executionResult.exitCode,
                timestamp = System.currentTimeMillis()
            )
            repository.insertTermuxLog(log)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return TermuxViewModel(application.container.repository, application.container) as T
            }
        }
    }
}
