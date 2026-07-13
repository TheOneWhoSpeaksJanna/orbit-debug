package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.data.local.runtime.SkillCategory
import com.orbitai.data.local.runtime.SkillsCatalog
import com.orbitai.domain.models.Agent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillsViewModel(
    agentsFlow: Flow<List<Agent>>,
    private val context: android.content.Context
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("SkillsViewModel")

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _skillCategories = MutableStateFlow<List<SkillCategory>>(emptyList())
    val skillCategories: StateFlow<List<SkillCategory>> = _skillCategories.asStateFlow()

    init {
        viewModelScope.launch(exceptionHandler) {
            agentsFlow.collect { agentList ->
                _agents.value = agentList
            }
        }
        // Load skills dynamically from the bundled catalog
        _skillCategories.value = SkillsCatalog.load(context)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return SkillsViewModel(application.container.repository.getAllAgents(), application) as T
            }
        }
    }
}
