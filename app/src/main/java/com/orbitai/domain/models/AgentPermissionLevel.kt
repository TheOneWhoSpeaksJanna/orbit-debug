package com.orbitai.domain.models

enum class AgentPermissionLevel(val label: String) {
    NORMAL("Normal"),
    RULES("Rules"),
    FULL_ACCESS("Full Access");

    val allowsAutoExecute: Boolean get() = this == RULES || this == FULL_ACCESS
    val requiresConfirmationForSensitive: Boolean get() = this == NORMAL
    val allowsAllWithoutAsk: Boolean get() = this == FULL_ACCESS

    companion object {
        fun fromValue(value: String): AgentPermissionLevel =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
