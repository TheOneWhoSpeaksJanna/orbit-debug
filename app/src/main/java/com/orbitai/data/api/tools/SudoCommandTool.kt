package com.orbitai.data.api.tools

import com.orbitai.data.local.runner.LocalCommandRunner
import com.orbitai.domain.api.Tool
import com.orbitai.domain.api.ToolResult

class SudoCommandTool(
    private val runner: LocalCommandRunner
) : Tool {
    override val name = "SUDO"
    override val description = "Execute a privileged shell command via Shizuku and return its output"
    override suspend fun execute(params: String): ToolResult {
        val result = runner.executePrivilegedCommand(params)
        return ToolResult(
            output = result.output,
            exitCode = result.exitCode,
            command = params
        )
    }
}
