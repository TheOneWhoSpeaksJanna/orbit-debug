package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.data.local.prefs.PreferencesManager
import com.orbitai.domain.models.Skill
import com.orbitai.BuildConfig
import com.orbitai.domain.repository.OrbitAiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

private const val THEME_DEFAULT = "system"

class SettingsViewModel(
    private val prefsManager: PreferencesManager,
    private val repository: OrbitAiRepository
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("SettingsViewModel")

    private val _themeMode = MutableStateFlow(THEME_DEFAULT)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _agentPermissionLevel = MutableStateFlow("NORMAL")
    val agentPermissionLevel: StateFlow<String> = _agentPermissionLevel.asStateFlow()

    private val _agentRulesAllowed = MutableStateFlow("")
    val agentRulesAllowed: StateFlow<String> = _agentRulesAllowed.asStateFlow()

    private val _agentRulesAsk = MutableStateFlow("")
    val agentRulesAsk: StateFlow<String> = _agentRulesAsk.asStateFlow()

    private val _agentRulesDenied = MutableStateFlow("")
    val agentRulesDenied: StateFlow<String> = _agentRulesDenied.asStateFlow()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    val appVersion: String = BuildConfig.VERSION_NAME.substringBeforeLast('-')

    init {
        loadSettings()
        loadSkills()
    }

    private fun loadSettings() {
        viewModelScope.launch(exceptionHandler) {
            _themeMode.value = prefsManager.themeMode.firstOrNull() ?: THEME_DEFAULT
            _agentPermissionLevel.value = prefsManager.agentPermissionLevel.firstOrNull() ?: "NORMAL"
            _agentRulesAllowed.value = prefsManager.agentRulesAllowed.firstOrNull() ?: ""
            _agentRulesAsk.value = prefsManager.agentRulesAsk.firstOrNull() ?: ""
            _agentRulesDenied.value = prefsManager.agentRulesDenied.firstOrNull() ?: ""
        }
    }

    fun updateThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch(exceptionHandler) { prefsManager.setThemeMode(mode) }
    }

    fun updateAgentPermissionLevel(level: String) {
        _agentPermissionLevel.value = level
        viewModelScope.launch(exceptionHandler) { prefsManager.setAgentPermissionLevel(level) }
    }

    fun updateAgentRulesAllowed(rules: String) {
        _agentRulesAllowed.value = rules
        viewModelScope.launch(exceptionHandler) { prefsManager.setAgentRulesAllowed(rules) }
    }

    fun updateAgentRulesAsk(rules: String) {
        _agentRulesAsk.value = rules
        viewModelScope.launch(exceptionHandler) { prefsManager.setAgentRulesAsk(rules) }
    }

    fun updateAgentRulesDenied(rules: String) {
        _agentRulesDenied.value = rules
        viewModelScope.launch(exceptionHandler) { prefsManager.setAgentRulesDenied(rules) }
    }

    fun loadSkills() {
        viewModelScope.launch(exceptionHandler) {
            repository.getAllSkills().collect { list ->
                _skills.value = list
            }
        }
    }

    fun toggleSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            repository.setSkillEnabled(skillId, enabled)
        }
    }

    fun updateSkillContent(skillId: String, content: String) {
        viewModelScope.launch(exceptionHandler) {
            repository.updateSkillContent(skillId, content)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return SettingsViewModel(
                    application.container.prefsManager,
                    application.container.repository
                ) as T
            }
        }
    }
}
