package com.orbitai.core.config

import com.orbitai.BuildConfig

/**
 * Central registry of all external API endpoints, default models, and app-level
 * constants used across the app.
 *
 * Why this exists:
 *  - Replaces URL strings previously duplicated across [ClaudeProvider],
 *    [OpenAIProvider], [GeminiProvider], [OpenRouterProvider] and
 *    [com.orbitai.ui.viewmodels.ProvidersViewModel].
 *  - Replaces the per-provider hardcoded health-check URLs.
 *  - Exposes the OpenRouter referrer / app title via BuildConfig so the
 *    fork-friendly values can be overridden per-flavor without code edits.
 *
 * When adding a new provider, add an entry to [ProviderEndpoint] and reference
 * it from your provider + the health-check builder. Do NOT inline URL strings.
 */
object ApiConfig {

    /** Anthropic Claude Messages API. */
    const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
    const val CLAUDE_API_VERSION = "2023-06-01"
    const val CLAUDE_DEFAULT_MODEL = "claude-sonnet-4-20250514"
    const val CLAUDE_HEALTH_CHECK_MODEL = "claude-sonnet-4-20250514"

    /** OpenAI Chat Completions + Models listing. */
    const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
    const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
    const val OPENAI_DEFAULT_MODEL = "gpt-4o"

    /** Google Gemini generateContent + streamGenerateContent. */
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"
    const val GEMINI_DEFAULT_MODEL = "gemini-2.0-flash-exp"

    /** OpenRouter chat completions + models listing + key check. */
    const val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
    const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"
    const val OPENROUTER_KEY_CHECK_URL = "https://openrouter.ai/api/v1/auth/key"
    const val OPENROUTER_DEFAULT_MODEL = "tencent/hy3:free"

    /**
     * Referrer + app title sent with every OpenRouter request.
     * Default values come from BuildConfig so forks can override them
     * via `buildConfigField` in `app/build.gradle.kts` per flavor.
     */
    val OPENROUTER_REFERRER_URL: String
        get() = BuildConfig.OPENROUTER_REFERRER_URL.ifBlank { "https://github.com/TheOneWhoSpeaksJanna/Orbit-AI" }
    val OPENROUTER_APP_TITLE: String
        get() = BuildConfig.OPENROUTER_APP_TITLE.ifBlank { "Orbit AI" }

    /** DeepSeek (OpenAI-compatible). */
    const val DEEPSEEK_MODELS_URL = "https://api.deepseek.com/v1/models"
    const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/v1/chat/completions"
    const val DEEPSEEK_DEFAULT_MODEL = "deepseek-chat"

    /** Groq (OpenAI-compatible, very fast). */
    const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
    const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_DEFAULT_MODEL = "llama-3.3-70b-versatile"

    /**
     * Ollama local endpoint. Default is localhost:11434, but users can override
     * via the Ollama provider's API-key slot (which we re-purpose as a base URL).
     */
    const val OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434"
    const val OLLAMA_TAGS_PATH = "/api/tags"
    const val OLLAMA_CHAT_PATH = "/api/chat"

    /** HTTP client tuning. */
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L
    const val POOL_SIZE = 5
    const val POOL_KEEPALIVE_SECONDS = 30L

    /** Provider health-check timeout for the Providers screen. */
    const val HEALTH_CHECK_TIMEOUT_SECONDS = 10L

    /**
     * Fallback GitHub repo used by the SetupWizard when an agent's pre-bundled
     * `agent.tar.gz` is missing from APK assets. Per-flavor override is wired
     * through BuildConfig.AGENT_FALLBACK_REPO_URL so each flavor can point to
     * its own upstream repo.
     */
    val AGENT_FALLBACK_REPO_URL: String
        get() = BuildConfig.AGENT_FALLBACK_REPO_URL

    /** Network timeouts for asset download fallback in SetupWizard. */
    const val AGENT_DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
    const val AGENT_DOWNLOAD_READ_TIMEOUT_MS = 60_000
}
