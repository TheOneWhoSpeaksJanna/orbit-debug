package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.core.di.AppContainer
import com.orbitai.core.di.ToolCallRecord
import com.orbitai.domain.models.Agent
import com.orbitai.domain.models.ChatSession
import com.orbitai.domain.models.Project
import com.orbitai.domain.models.TermuxLog
import com.orbitai.domain.repository.OrbitAiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val DEFAULT_AGENT = "OpenClaude"
private const val DEFAULT_PROVIDER = "OpenRouter"

class DashboardViewModel(
    private val repository: OrbitAiRepository,
    private val appContainer: AppContainer
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("DashboardViewModel")

    init {
        // Clean up empty sessions left behind when users navigate away
        // without sending a message (e.g. tapping New Session then going back)
        viewModelScope.launch(exceptionHandler) {
            repository.deleteEmptySessions()
        }
    }

    val projects: StateFlow<List<Project>> = repository.getAllProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val sessions: StateFlow<List<ChatSession>> = repository.getAllSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(1000),
            initialValue = emptyList()
        )

    val agents: StateFlow<List<Agent>> = repository.getAllAgents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val termuxLogs: StateFlow<List<TermuxLog>> = repository.getAllTermuxLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeAgent = appContainer.prefsManager.selectedAgent
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_AGENT
        )

    val activeProvider = appContainer.prefsManager.selectedProvider
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_PROVIDER
        )

    val shizukuEnabled = appContainer.prefsManager.shizukuEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val activeSessionToolCalls: StateFlow<List<ToolCallRecord>> = combine(
        sessions, appContainer.toolCallRecorder.records
    ) { sessionList, records ->
        val lastSessionId = sessionList.maxByOrNull { it.updatedAt }?.id ?: return@combine emptyList()
        records.filter { it.sessionId == lastSessionId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun createNewProject(name: String, description: String) {
        viewModelScope.launch(exceptionHandler) {
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            repository.insertProject(project)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return DashboardViewModel(application.container.repository, application.container) as T
            }
        }
    }
}
