package com.orbitai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.orbitai.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.orbitai.core.logging.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.viewmodel.compose.viewModel
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

    /**
     * DEBUG-ONLY test hook (guarded by BuildConfig.DEBUG so it never ships in
     * release). Lets a headless harness set the OpenRouter API key + provider/
     * agent/model via an ordered broadcast, since AndroidX DataStore refuses a
     * prefs file written by an external process:
     *   adb shell am broadcast -a com.orbitai.DEBUG_SET_PREFS \
     *     --es key "sk-or-..." --es provider OpenRouter \
     *     --es agent OpenClaude --es model "openai/gpt-oss-20b:free"
     */
    private fun registerDebugPrefsReceiver() {
        val filter = IntentFilter("com.orbitai.DEBUG_SET_PREFS")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                FileLogger.i(TAG, "DEBUG_SET_PREFS onReceive | keyLen=${(intent.getStringExtra("key") ?: "").length}")
                try {
                    val key = intent.getStringExtra("key") ?: return
                    val provider = intent.getStringExtra("provider") ?: "OpenRouter"
                    val agent = intent.getStringExtra("agent") ?: "OpenClaude"
                    val model = intent.getStringExtra("model") ?: "openai/gpt-oss-20b:free"
                    FileLogger.i(TAG, "DEBUG_SET_PREFS resolving pm")
                    val pm = (application as com.orbitai.OrbitAiApplication).container.prefsManager
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            pm.setApiKeyForProvider(provider.lowercase(), key)
                            pm.setOpenRouterApiKey(key)
                            pm.setSelectedProvider(provider)
                            pm.setSelectedAgent(agent)
                            pm.setSelectedModel(model)
                            pm.setOnboardingComplete(true)
                            FileLogger.i(TAG, "DEBUG_SET_PREFS applied | provider=$provider agent=$agent")
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "DEBUG_SET_PREFS edit failed: ${e.message}")
                        }
                    }
                    setResultCode(android.app.Activity.RESULT_OK)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "DEBUG_SET_PREFS outer failed: ${e.message}")
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        FileLogger.i(TAG, "debug prefs receiver registered")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "onCreate start")
        requestStoragePermissionIfNeeded()
        if (BuildConfig.DEBUG) registerDebugPrefsReceiver()
        try {
            enableEdgeToEdge()
            setContent {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
                val themeMode by viewModel.themeMode.collectAsState()
                val themeId by viewModel.themeId.collectAsState()
                val customTheme by viewModel.customTheme.collectAsState()
                OrbitAiTheme(themeId = themeId, themeMode = themeMode, custom = customTheme) {
                    // Keep the status/nav bar colors in sync with the theme so
                    // there is no visible gap or seam between the app content
                    // and the system notification bar (edge-to-edge mode).
                    val scheme = MaterialTheme.colorScheme
                    val window = window
                    val statusColor = scheme.background
                    val navColor = scheme.background
                    window.statusBarColor = statusColor.toArgb()
                    window.navigationBarColor = navColor.toArgb()
                    val insetsCtrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    insetsCtrl.isAppearanceLightStatusBars = scheme.background.luminance() > 0.5f
                    insetsCtrl.isAppearanceLightNavigationBars = scheme.background.luminance() > 0.5f

                    var destination by remember { mutableStateOf(viewModel.startDestination.value) }
                    // Keep local navigation state in sync with the store-driven
                    // start destination (covers cold launch + later flag flips).
                    LaunchedEffect(Unit) {
                        viewModel.startDestination.collect { destination = it }
                    }
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
                            SetupWizardScreen(onFinishSetup = {
                                // Honor the contract: when onboarding finishes,
                                // jump straight to the Dashboard. This is robust
                                // even if AppShell stops observing the store flag.
                                destination = Routes.DASHBOARD
                            })
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
