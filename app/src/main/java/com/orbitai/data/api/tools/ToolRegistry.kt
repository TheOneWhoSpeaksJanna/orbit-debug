package com.orbitai.data.api.tools

import com.orbitai.core.logging.FileLogger
import com.orbitai.domain.api.ParsedToolCall
import com.orbitai.domain.api.Tool
import com.orbitai.domain.api.ToolResult

class ToolRegistry(
    private val tools: List<Tool> = emptyList()
) {
    private val toolCallPattern = "\\[(RUN|SUDO): (.+?)]".toRegex()

    fun parseToolCalls(text: String): List<ParsedToolCall> =
        toolCallPattern.findAll(text).map { match ->
            ParsedToolCall(
                toolName = match.groupValues[1],
                params = match.groupValues[2],
                rawMatch = match.value
            )
        }.toList()

    fun containsToolCall(text: String): Boolean = toolCallPattern.containsMatchIn(text)

    suspend fun execute(parsed: ParsedToolCall): ToolResult {
        FileLogger.i("ToolRegistry", "dispatch", "tool=${parsed.toolName} cmd=${parsed.params.take(200)}")

        val tool = tools.find { it.name.equals(parsed.toolName, ignoreCase = true) }
        if (tool == null) {
            FileLogger.w("ToolRegistry", "No tool registered", "name=${parsed.toolName} available=${tools.map { it.name }}")
            return ToolResult(
                output = "Error: No tool registered for '${parsed.toolName}'",
                exitCode = -1,
                command = parsed.params
            )
        }

        val result = tool.execute(parsed.params)
        FileLogger.i("ToolRegistry", "result", "tool=${parsed.toolName} exitCode=${result.exitCode} output=${result.output.take(200)}")
        return result
    }
}
