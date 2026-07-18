package com.orbitai.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.orbitai.ui.screens.DashboardScreen
import com.orbitai.ui.screens.ChatScreen
import com.orbitai.ui.screens.HistoryScreen
import com.orbitai.ui.screens.ProvidersScreen
import com.orbitai.ui.screens.SettingsScreen
import com.orbitai.ui.screens.SkillsScreen
import com.orbitai.ui.screens.TermuxScreen
import com.orbitai.ui.theme.MotionTokens

sealed class ChatViewState {
    object SessionList : ChatViewState()
    data class ActiveChat(val sessionId: String) : ChatViewState()
}

private const val TAB_INDICATOR_ALPHA = 0.15f

/**
 * Tab transition duration. Kept deliberately short (150ms = DURATION_FAST) —
 * a long tab-content animation is the #1 cause of perceived "laggy" nav in
 * Material 3 apps because both the outgoing and incoming screen trees are
 * mounted + measured + drawn simultaneously for the duration of the fade.
 *
 * 150ms is below the threshold where the eye perceives a delay but still
 * gives a subtle fade so the swap doesn't feel jarring. Gmail / Google
 * Photos / Twitter all use ~100-200ms for the same reason.
 */
private const val TAB_CROSSFADE_MS = MotionTokens.DURATION_FAST

@Composable
fun AppShell() {
    var selectedTab by remember { mutableStateOf(BottomNavTab.HOME) }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var showTermux by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(enabled = true) {
        if (showTermux) {
            showTermux = false
        } else if (selectedTab == BottomNavTab.HOME) {
            (context as? Activity)?.finish()
        } else {
            selectedTab = BottomNavTab.HOME
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showTermux) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    BottomNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label, fontSize = 10.sp, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.secondary,
                                selectedTextColor = MaterialTheme.colorScheme.secondary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = TAB_INDICATOR_ALPHA)
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (showTermux) {
                TermuxScreen(onNavigateBack = { showTermux = false })
            } else {
                Box(modifier = Modifier.padding(paddingValues)) {
                    // Crossfade is cheaper than AnimatedContent for tab swaps:
                    // it doesn't run a layout pass on both children simultaneously,
                    // it just animates the alpha of the in/out content. Combined
                    // with the short duration above, tab switches feel instant.
                    Crossfade(
                        targetState = selectedTab,
                        animationSpec = tween(durationMillis = TAB_CROSSFADE_MS),
                        label = "TabContent"
                    ) { tab ->
                        when (tab) {
                            BottomNavTab.HOME -> DashboardScreen(
                                onNavigateToSession = { id ->
                                    targetSessionId = id
                                    selectedTab = BottomNavTab.CHAT
                                },
                                onNavigateToNewSession = {
                                    targetSessionId = null
                                    selectedTab = BottomNavTab.CHAT
                                },
                                onNavigateToTermux = { showTermux = true },
                                onNavigateToSettings = { selectedTab = BottomNavTab.SETTINGS }
                            )
                            BottomNavTab.CHAT -> ChatScreen(
                                sessionId = targetSessionId,
                                onNavigateBack = {
                                    // Keep the active session id so returning to
                                    // Chat resumes it (don't null it — that was
                                    // what spawned a fresh session every visit).
                                    selectedTab = BottomNavTab.HOME
                                },
                                onSessionIdResolved = { id ->
                                    // Remember the session ChatScreen is actually
                                    // using so re-entering the Chat tab resumes it
                                    // instead of spawning a new chat (the previous
                                    // bug: targetSessionId stayed null, so each
                                    // re-entry called startNewSession again).
                                    if (id != null) targetSessionId = id
                                }
                            )
                            BottomNavTab.HISTORY -> HistoryScreen(
                                onOpenSession = { id ->
                                    targetSessionId = id
                                    selectedTab = BottomNavTab.CHAT
                                }
                            )
                            BottomNavTab.SKILLS -> SkillsScreen()
                            BottomNavTab.PROVIDERS -> ProvidersScreen()
                            BottomNavTab.SETTINGS -> SettingsScreen(onNavigateBack = { selectedTab = BottomNavTab.HOME })
                        }
                    }
                }
            }
        }
    }
}
