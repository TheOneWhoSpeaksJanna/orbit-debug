package com.orbitai.domain.api

data class ToolResult(
    val output: String,
    val exitCode: Int,
    val command: String
)

data class ParsedToolCall(
    val toolName: String,
    val params: String,
    val rawMatch: String
)

interface Tool {
    val name: String
    val description: String
    suspend fun execute(params: String): ToolResult
}
