package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.data.local.prefs.PreferencesManager
import com.orbitai.ui.theme.OrbitThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETUP = "setup"
private const val THEME_LIGHT = "LIGHT"
private const val THEME_DARK = "DARK"

class MainViewModel(
    private val prefsManager: PreferencesManager
) : ViewModel() {

    val startDestination: StateFlow<String?> = prefsManager.isOnboardingComplete
        .map { isComplete ->
            if (isComplete) ROUTE_DASHBOARD else ROUTE_SETUP
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val themeMode: StateFlow<OrbitThemeMode> = prefsManager.themeMode
        .map { raw ->
            when {
                raw.equals(THEME_DARK, ignoreCase = true) -> OrbitThemeMode.DARK
                raw.equals(THEME_LIGHT, ignoreCase = true) -> OrbitThemeMode.LIGHT
                else -> OrbitThemeMode.SYSTEM
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = OrbitThemeMode.SYSTEM
        )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return MainViewModel(application.container.prefsManager) as T
            }
        }
    }
}
