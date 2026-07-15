package com.orbitai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    // On Android < 11 the app needs the legacy storage permission so the
    // emulated volume is visible inside the Termux PRoot (the headless
    // equivalent of `termux-setup-storage`). On Android 11+ scoped storage
    // means we instead request all-files access via the wizard's settings
    // intent, so this launcher is a no-op there.
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> FileLogger.i(TAG, "storage permission result received") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "onCreate start")
        requestStoragePermissionIfNeeded()
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

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Scoped storage: need all-files access so /storage/emulated/0
            // is visible. The wizard also offers this, but we request it
            // headlessly here so the AI can write outside home with no taps.
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    ).apply { data = android.net.Uri.parse("package:$packageName") }
                    startActivity(intent)
                    FileLogger.i(TAG, "requesting all-files access")
                } catch (_: Exception) {
                    FileLogger.w(TAG, "cannot launch all-files settings intent")
                }
            }
            return
        }
        val needed = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            storagePermissionLauncher.launch(needed.toTypedArray())
            FileLogger.i(TAG, "requesting storage permission")
        }
    }
}
