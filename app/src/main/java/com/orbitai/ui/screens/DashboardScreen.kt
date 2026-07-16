package com.orbitai.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbitai.core.di.AppContainer
import com.orbitai.BuildConfig
import com.orbitai.ui.components.OrbitButton
import com.orbitai.ui.components.OrbitCard
import com.orbitai.ui.theme.staggeredEntrance
import com.orbitai.ui.viewmodels.DashboardViewModel
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import android.widget.Toast
import com.orbitai.data.local.updater.SilentUpdater

private val DATE_SESSION_FORMAT = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

private const val APP_NAME = "Orbit"
private const val SHIZUKU_LABEL = "Shizuku"
private const val STATUS_ACTIVE = "Active"
private const val STATUS_INACTIVE = "Inactive"
private const val ACTIVE_AGENT_DEFAULT = "OpenClaude"
private const val SECTION_RECENT_SESSIONS = "Recent Sessions"
private const val EMPTY_SESSIONS_TITLE = "No sessions yet"
private const val EMPTY_SESSIONS_DESC = "Start a new session to begin interacting with your AI agent."
private const val CD_NEW_SESSION = "New Session"
private const val CD_LOCAL_TOOLS = "Local Tools"
private const val CD_SETTINGS = "Settings"
private const val CD_SESSION_ICON = "Session"
private const val CD_UPDATE = "Silent update"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSession: (String) -> Unit,
    onNavigateToNewSession: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTermux: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
) {
    val projects by viewModel.projects.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val activeAgent by viewModel.activeAgent.collectAsState()
    val shizukuEnabled by viewModel.shizukuEnabled.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()

    val context = LocalContext.current
    var isShizukuActive by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as com.orbitai.OrbitAiApplication).container

    LaunchedEffect(Unit) {
        val isInstalled = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        isShizukuActive = isInstalled
                && Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(APP_NAME, fontWeight = FontWeight.Bold)
                        activeProvider?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToTermux) {
                        Icon(Icons.Default.Terminal, contentDescription = CD_LOCAL_TOOLS)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = CD_SETTINGS)
                    }
                    // Always show the update button. When Shizuku is active the
                    // install is silent; when it is not, runSilentUpdate falls
                    // back to NeedsManualInstall and opens the system installer.
                    // (Previously this was gated behind isShizukuActive, which
                    // hid the button entirely for the common case of Shizuku
                    // not installed — leaving users with no way to update.)
                    IconButton(
                        enabled = !isUpdating,
                        onClick = {
                                scope.launch {
                                    isUpdating = true
                                    try {
                                        val result = runSilentUpdate(context, container)
                                        val msg = when (result) {
                                            is SilentUpdater.UpdateResult.Success ->
                                                "Updated successfully (silent install)."
                                            is SilentUpdater.UpdateResult.Failure ->
                                                result.reason
                                            is SilentUpdater.UpdateResult.NeedsManualInstall -> {
                                                // No Shizuku: open the system installer.
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(result.apkUri, "application/vnd.android.package-archive")
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                    "New version downloaded — tap Install to update."
                                                } catch (e: Exception) {
                                                    "Could not open installer: ${e.message}"
                                                }
                                            }
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    } finally {
                                        isUpdating = false
                                    }
                                }
                            }
                        ) {
                            if (isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.SystemUpdate, contentDescription = CD_UPDATE)
                            }
                        }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToNewSession,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = CD_NEW_SESSION)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OrbitCard(
                        modifier = Modifier.weight(1f),
                        tonal = true
                    ) {
                        StatusDot(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Active Agent",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            activeAgent ?: ACTIVE_AGENT_DEFAULT,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        activeProvider?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OrbitCard(
                        modifier = Modifier.weight(1f),
                        tonal = true
                    ) {
                        StatusDot(
                            color = if (isShizukuActive)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            SHIZUKU_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isShizukuActive) STATUS_ACTIVE else STATUS_INACTIVE,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = SECTION_RECENT_SESSIONS,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "${sessions.size} total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (sessions.isEmpty()) {
                item {
                    EmptySessionsPlaceholder(onNewSession = onNavigateToNewSession)
                }
            } else {
                itemsIndexed(sessions, key = { _, item -> item.id }) { index, session ->
                    SessionCard(
                        title = session.title,
                        updatedAt = session.updatedAt,
                        onClick = { onNavigateToSession(session.id) },
                        itemId = session.id,
                        modifier = Modifier.staggeredEntrance(index, itemId = session.id)
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun EmptySessionsPlaceholder(onNewSession: () -> Unit) {
    OrbitCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                EMPTY_SESSIONS_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                EMPTY_SESSIONS_DESC,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(20.dp))
            OrbitButton(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(CD_NEW_SESSION)
            }
        }
    }
}

@Composable
private fun SessionCard(
    title: String,
    updatedAt: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    itemId: Any? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dateStr = remember(updatedAt) {
        DATE_SESSION_FORMAT.format(Date(updatedAt))
    }
    OrbitCard(
        modifier = modifier.fillMaxWidth(),
        interactive = true,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Fetch the latest GitHub release APK for THIS app's flavor, download it, and
 * install it. Uses Shizuku for a silent install when available; otherwise
 * falls back to the system installer (a normal "install?" prompt) so the
 * update still works without Shizuku — no more cryptic failures.
 *
 * Release artifacts are named orbit-ai-<flavor>-debug-<run>.apk (see build.yml).
 */
private suspend fun runSilentUpdate(
    context: android.content.Context,
    container: AppContainer
): SilentUpdater.UpdateResult {
    val flavor = when {
        BuildConfig.APPLICATION_ID.endsWith(".openclaude") -> "openclaude"
        BuildConfig.APPLICATION_ID.endsWith(".opencode") -> "opencode"
        BuildConfig.APPLICATION_ID.endsWith(".claudecode") -> "claudecode"
        BuildConfig.APPLICATION_ID.endsWith(".codex") -> "codex"
        else -> "normal"
    }
    val client = container.okHttpClient
    return try {
        val apiReq = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/TheOneWhoSpeaksJanna/Orbit-AI/releases/latest")
            .build()
        val apiResp = client.newCall(apiReq).execute()
        if (!apiResp.isSuccessful) {
            return SilentUpdater.UpdateResult.Failure("Couldn't reach the update server (HTTP ${apiResp.code}).")
        }
        val bodyStr = apiResp.body?.string().orEmpty()

        // Parse the JSON properly (was a brittle regex before).
        val json = org.json.JSONObject(bodyStr)
        val tag = json.optString("tag_name", "")
        val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.contains("orbit-ai-$flavor-debug", ignoreCase = true) &&
                name.endsWith(".apk", ignoreCase = true)
            ) {
                apkUrl = a.optString("browser_download_url", "")
                break
            }
        }
        apkUrl = apkUrl?.takeIf { it.isNotBlank() }
            ?: return SilentUpdater.UpdateResult.Failure("No APK asset found for flavor '$flavor'.")

        // Already on the latest published version? Compare run-number tag.
        val currentRun = BuildConfig.VERSION_NAME.substringAfterLast('-').toIntOrNull()
        val latestRun = tag.substringAfter('v').toIntOrNull()
        if (latestRun != null && currentRun != null && latestRun <= currentRun) {
            return SilentUpdater.UpdateResult.Failure("Already on the latest version ($tag).")
        }

        val dlReq = okhttp3.Request.Builder().url(apkUrl).build()
        val dlResp = client.newCall(dlReq).execute()
        if (!dlResp.isSuccessful) {
            return SilentUpdater.UpdateResult.Failure("Download failed: ${dlResp.code}")
        }

        // Stage in the FileProvider-shared cache dir (file_paths.xml: updates/).
        val outDir = java.io.File(context.cacheDir, "updates")
        outDir.mkdirs()
        val apkFile = java.io.File(outDir, "orbit_update.apk")
        dlResp.body?.byteStream()?.use { input ->
            apkFile.outputStream().use { out -> input.copyTo(out) }
        }

        if (!container.silentUpdater.canSilentInstall()) {
            // No Shizuku — hand off to the system installer via FileProvider.
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            return SilentUpdater.UpdateResult.NeedsManualInstall(uri)
        }
        container.silentUpdater.installApk(apkFile)
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() }
            ?: "network error (couldn't reach GitHub)"
        SilentUpdater.UpdateResult.Failure("Couldn't check for updates — $reason")
    }
}
