package com.orbitai.data.repository

import com.orbitai.domain.models.AgentCategory
import com.orbitai.domain.models.DownloadableAgent
import com.orbitai.domain.repository.OpenCodeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OpenCodeRepositoryImpl : OpenCodeRepository {

    private val _catalog = MutableStateFlow(defaultCatalog())
    override fun getAvailableAgents(): Flow<List<DownloadableAgent>> = _catalog.asStateFlow()

    override suspend fun refreshCatalog() {
        delay(SIMULATED_FETCH_DELAY_MS)
        _catalog.value = defaultCatalog()
    }

    override suspend fun getAgentById(id: String): DownloadableAgent? {
        return _catalog.value.find { it.id == id }
    }

    private fun defaultCatalog() = listOf(
        DownloadableAgent(
            id = "openclaude",
            name = "OpenClaude",
            description = "Open-source coding agent CLI supporting 200+ LLMs via OpenAI, Gemini, DeepSeek, Ollama, and more",
            category = AgentCategory.DEVELOPER,
            iconName = "code",
            version = "0.19.0",
            installCommand = "npm install -g @gitlawb/openclaude",
            runCommand = "openclaude",
            systemPrompt = "You are OpenClaude, an open-source coding agent that works with any LLM provider. You assist with software engineering tasks including code generation, debugging, refactoring, and testing. You can execute shell commands, read and write files, and manage complex multi-step development workflows. When asked to code, provide complete, working solutions with proper error handling. Prefer simple, maintainable solutions over complex abstractions."
        ),
        DownloadableAgent(
            id = "claude-code",
            name = "Claude Code",
            description = "Anthropic's official CLI coding agent — understands codebases, edits files, runs terminal commands, and handles entire workflows",
            category = AgentCategory.DEVELOPER,
            iconName = "smart_toy",
            version = "2.1.186",
            installCommand = "npm install -g @anthropic-ai/claude-code",
            runCommand = "claude",
            systemPrompt = "You are Claude Code, Anthropic's official CLI coding agent. You understand entire codebases, edit files, run terminal commands, and handle complete development workflows. You think step-by-step before writing code, propose clear plans, and execute them precisely. You are an expert software engineer who ships production-quality code. When the user asks a coding question, you analyze the codebase context, plan your approach, and implement the solution."
        ),
        DownloadableAgent(
            id = "codex-cli",
            name = "Codex CLI",
            description = "OpenAI's coding agent that runs locally on your computer — generates, runs, and debugs code autonomously",
            category = AgentCategory.DEVELOPER,
            iconName = "terminal",
            version = "0.142.0",
            installCommand = "npm install -g @openai/codex",
            runCommand = "codex",
            systemPrompt = "You are Codex CLI, OpenAI's coding agent that runs locally. You generate, run, and debug code autonomously. You have deep knowledge of programming languages, frameworks, and best practices. You write clean code, explain your reasoning, and iterate based on feedback. You can execute commands in the user's terminal and see the results to understand and fix issues. You are practical and focused on shipping working solutions."
        ),
        DownloadableAgent(
            id = "opencode-ai",
            name = "OpenCode AI",
            description = "AI-powered coding assistant that integrates with your development workflow for code generation, review, and automation",
            category = AgentCategory.DEVELOPER,
            iconName = "extension",
            version = "1.17.9",
            installCommand = "npm install -g @opencode-ai/cli",
            runCommand = "lildax",
            systemPrompt = "You are OpenCode AI, a coding agent designed to integrate with development workflows for code generation, review, and automation. You help developers write better code faster by understanding their intent and context. You are proficient in analyzing requirements, suggesting architectures, and implementing clean, maintainable solutions across multiple programming languages and frameworks."
        )
    )

    companion object {
        private const val SIMULATED_FETCH_DELAY_MS = 800L
    }
}
