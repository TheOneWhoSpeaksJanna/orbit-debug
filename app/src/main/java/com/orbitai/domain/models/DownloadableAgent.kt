package com.orbitai.domain.models

data class DownloadableAgent(
    val id: String,
    val name: String,
    val description: String,
    val category: AgentCategory,
    val downloadUrl: String = "",
    val source: DownloadSource = DownloadSource.OPENCODE,
    val iconName: String = "default",
    val version: String = "1.0.0",
    val fileSize: Long = 0L,
    val systemPrompt: String = "",
    val installCommand: String = "",
    val runCommand: String = ""
)

fun DownloadableAgent.toAgent() = Agent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    runCommand = runCommand
)

enum class AgentCategory(val displayName: String) {
    UTILITY("Utility Package"),
    AUTOMATION("System Automation"),
    CUSTOM_LOGIC("Custom Logic"),
    DEVELOPER("Developer Tool"),
    ANALYTICS("Data & Analytics"),
    SECURITY("Security & Audit")
}

enum class DownloadSource(val displayName: String) {
    OPENCODE("OpenCode Registry"),
    TERMUX("Termux Package"),
    CUSTOM("Custom URL")
}
