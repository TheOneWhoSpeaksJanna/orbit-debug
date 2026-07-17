package com.orbitai.core.config

import com.orbitai.BuildConfig

object FlavorConfig {
    val presetAgentId: String get() = BuildConfig.FLAVOR_PRESET_AGENT_ID
    val presetAgentName: String get() = BuildConfig.FLAVOR_PRESET_AGENT_NAME
    val appLabel: String get() = BuildConfig.FLAVOR_APP_LABEL
    val isNormal: Boolean get() = presetAgentId.isEmpty()
    /** Hermes is a cloud-only general AI agent (no local coding CLI). */
    val isHermes: Boolean get() = presetAgentId.equals("hermes", ignoreCase = true)
}
