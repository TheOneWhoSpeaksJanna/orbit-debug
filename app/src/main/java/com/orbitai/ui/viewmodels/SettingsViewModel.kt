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
import com.orbitai.ui.theme.CustomTheme
import com.orbitai.ui.theme.ThemeId
import com.orbitai.ui.theme.OrbitThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val THEME_DEFAULT = "system"

class SettingsViewModel(
    private val prefsManager: PreferencesManager,
    private val repository: OrbitAiRepository,
    private val updateManager: com.orbitai.data.local.updater.UpdateManager
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("SettingsViewModel")

    private val _themeMode = MutableStateFlow(THEME_DEFAULT)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _themeId = MutableStateFlow(ThemeId.NORMAL.key)
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    // Custom theme editor state (only meaningful when themeId == "custom")
    private val _custom = MutableStateFlow(CustomTheme())
    val custom: StateFlow<CustomTheme> = _custom.asStateFlow()

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
    val appVersionCode: Int = BuildConfig.VERSION_CODE

    // ── Update system state ──────────────────────────────────────────────
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    private val _updateResult =
        MutableStateFlow<com.orbitai.data.local.updater.UpdateCheckResult?>(null)
    val updateResult: StateFlow<com.orbitai.data.local.updater.UpdateCheckResult?> =
        _updateResult.asStateFlow()

    private val _updateInstalling = MutableStateFlow(false)
    val updateInstalling: StateFlow<Boolean> = _updateInstalling.asStateFlow()

    private val _updateInstallResult =
        MutableStateFlow<com.orbitai.data.local.updater.UpdateInstallResult?>(null)
    val updateInstallResult: StateFlow<com.orbitai.data.local.updater.UpdateInstallResult?> =
        _updateInstallResult.asStateFlow()

    init {
        loadSettings()
        loadSkills()
    }

    fun checkForUpdate() {
        if (_updateChecking.value) return
        _updateChecking.value = true
        _updateResult.value = null
        viewModelScope.launch(exceptionHandler) {
            _updateResult.value = updateManager.checkForUpdate()
            _updateChecking.value = false
        }
    }

    fun installUpdate(apkUrl: String) {
        if (_updateInstalling.value) return
        _updateInstalling.value = true
        _updateInstallResult.value = null
        viewModelScope.launch(exceptionHandler) {
            val res = updateManager.downloadAndInstall(apkUrl)
            _updateInstalling.value = false
            _updateInstallResult.value = res
            if (res is com.orbitai.data.local.updater.UpdateInstallResult.Success) {
                updateManager.restartApp()
            }
        }
    }

    fun clearUpdateState() {
        _updateResult.value = null
        _updateInstallResult.value = null
    }

    private fun loadSettings() {
        viewModelScope.launch(exceptionHandler) {
            _themeMode.value = prefsManager.themeMode.firstOrNull() ?: THEME_DEFAULT
            _themeId.value = prefsManager.themeId.firstOrNull() ?: ThemeId.NORMAL.key
            _custom.value = CustomTheme.fromStored(prefsManager.customTheme.firstOrNull())
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

    fun updateThemeId(id: String) {
        _themeId.value = id
        viewModelScope.launch(exceptionHandler) { prefsManager.setThemeId(id) }
    }

    /**
     * Update a single custom color slot and persist the whole custom theme.
     * slot: "background" | "surface" | "primary" | "onBackground" | "secondary" | "tertiary"
     */
    fun updateCustomColor(slot: String, color: androidx.compose.ui.graphics.Color) {
        val next = when (slot) {
            "background" -> _custom.value.copy(background = color)
            "surface" -> _custom.value.copy(surface = color)
            "primary" -> _custom.value.copy(primary = color)
            "onBackground" -> _custom.value.copy(onBackground = color)
            "secondary" -> _custom.value.copy(secondary = color)
            "tertiary" -> _custom.value.copy(tertiary = color)
            else -> _custom.value
        }
        _custom.value = next
        viewModelScope.launch(exceptionHandler) { prefsManager.setCustomTheme(next.toStored()) }
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

    // ── Gateway connections (Hermes edition only) ──────────────
    val isHermes: Boolean = com.orbitai.core.config.FlavorConfig.isHermes

    private val _gatewayConnections =
        prefsManager.getGatewayConnections()
    val gatewayConnections: Flow<List<com.orbitai.data.local.prefs.PreferencesManager.GatewayConnection>> =
        _gatewayConnections

    fun addGatewayConnection(
        service: String,
        endpoint: String,
        token: String = ""
    ) {
        viewModelScope.launch(exceptionHandler) {
            val id = java.util.UUID.randomUUID().toString()
            prefsManager.addGatewayConnection(
                com.orbitai.data.local.prefs.PreferencesManager.GatewayConnection(
                    id, service, endpoint, token
                )
            )
        }
    }

    fun removeGatewayConnection(id: String) {
        viewModelScope.launch(exceptionHandler) {
            prefsManager.removeGatewayConnection(id)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                val container = application.container
                return SettingsViewModel(
                    container.prefsManager,
                    container.repository,
                    com.orbitai.data.local.updater.UpdateManager(
                        container.appContext,
                        container.okHttpClient,
                        container.silentUpdater
                    )
                ) as T
            }
        }
    }
}
