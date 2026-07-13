package com.orbitai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.core.logging.FileLogger
import com.orbitai.ui.navigation.AppShell
import com.orbitai.ui.navigation.Routes
import com.orbitai.ui.screens.SetupWizardScreen
import com.orbitai.ui.viewmodels.MainViewModel
import com.orbitai.ui.theme.OrbitAiTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "onCreate start")
        try {
            enableEdgeToEdge()
            setContent {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
                val themeMode by viewModel.themeMode.collectAsState()
                OrbitAiTheme(themeMode = themeMode) {
                    val destination by viewModel.startDestination.collectAsState()
                    // Show a loading spinner while the start destination is
                    // being computed (first ~100-300ms on cold launch).
                    // Without this, the screen is blank until the Flow emits.
                    if (destination == null) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        FileLogger.d(TAG, "Navigation", "destination=$destination")
                        if (destination == Routes.SETUP) {
                            SetupWizardScreen(onFinishSetup = { })
                        } else {
                            AppShell()
                        }
                    }
                }
            }
            FileLogger.i(TAG, "onCreate success")
        } catch (e: Exception) {
            FileLogger.e(TAG, "onCreate failed", e)
            throw e
        }
    }
}
