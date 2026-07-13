package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.domain.models.ChatSession
import com.orbitai.domain.repository.OrbitAiRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    sessionsFlow: Flow<List<ChatSession>>,
    private val repository: OrbitAiRepository
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("HistoryViewModel")

    private val _state = MutableStateFlow<List<ChatSession>>(emptyList())
    val state: StateFlow<List<ChatSession>> = _state.asStateFlow()

    init {
        viewModelScope.launch(exceptionHandler) {
            sessionsFlow.collect { sessions ->
                _state.value = sessions
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(exceptionHandler) {
            repository.deleteSession(sessionId)
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch(exceptionHandler) {
            repository.updateSessionTitle(sessionId, newTitle)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return HistoryViewModel(
                    application.container.repository.getAllSessions(),
                    application.container.repository
                ) as T
            }
        }
    }
}
