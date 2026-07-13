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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TermuxViewModel"
private const val SUDO_PREFIX = "sudo "

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
     */
    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        FileLogger.i(TAG, "executeCommand: '$trimmed'")

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

            val executionResult = termuxRuntime.executeInTermux(trimmed, "")
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
     * Execute a privileged command via Shizuku (Android system-level access).
     */
    fun executePrivilegedCommand(command: String) {
        FileLogger.i(TAG, "executePrivilegedCommand: '$command'")
        viewModelScope.launch(Dispatchers.IO) {
            val executionResult = appContainer.localCommandRunner.executePrivilegedCommand(command)
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
