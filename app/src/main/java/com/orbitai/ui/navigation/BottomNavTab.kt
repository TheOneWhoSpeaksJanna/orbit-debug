package com.orbitai.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavTab(
    val label: String,
    val icon: ImageVector
) {
    HOME("Home", Icons.Default.Home),
    CHAT("Chat", Icons.Default.Chat),
    HISTORY("History", Icons.Default.History),
    SKILLS("Skills", Icons.Default.Extension),
    PROVIDERS("Provider", Icons.Default.Cloud),
    SETTINGS("Settings", Icons.Default.Settings)
}
