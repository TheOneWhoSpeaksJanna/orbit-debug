package com.orbitai.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the agent env-var exports. These run without Robolectric
 * (which can't execute on this aarch64 host because conscrypt ships only an
 * x86-64 native lib), so they give real, headless verification of the exact
 * credentials Orbit-AI hands to each bundled agent — the crux of the
 * per-edition requirements.
 */
class ProviderEnvExportsTest {

    // --- Requirement 1: Codex Edition -> OpenAI auth -----------------------
    @Test
    fun codexUsesOpenAIKeyAndBaseUrl() {
        val (exports, baseUrlSet) = buildProviderEnvExports("OpenAI", "sk-openai-1", "gpt-4o")
        assertTrue(exports.contains("export CLAUDE_CODE_USE_OPENAI=1"))
        assertTrue(exports.contains("export OPENAI_API_KEY='sk-openai-1'"))
        // OpenAI isn't in the special-cased list, so the ViewModel's catalog
        // fallback would supply api.openai.com; core does NOT set a base URL.
        assertFalse(exports.contains("OPENAI_BASE_URL"))
        assertFalse(baseUrlSet)
        assertTrue(exports.contains("OPENAI_MODEL='gpt-4o'"))
    }

    // --- Requirement 2: Claude Code -> API key AND subscription -----------
    @Test
    fun claudeCodeApiKeyMode() {
        val (exports, _) = buildProviderEnvExports("Anthropic Claude", "sk-ant-1", "claude-sonnet-4-20250514", useSubscription = false)
        assertTrue(exports.contains("export ANTHROPIC_API_KEY='sk-ant-1'"))
        assertFalse(exports.contains("ANTHROPIC_AUTH_TOKEN"))
    }

    @Test
    fun claudeCodeSubscriptionMode() {
        val (exports, _) = buildProviderEnvExports("Anthropic Claude", "tok-max-1", "claude-sonnet-4-20250514", useSubscription = true)
        assertTrue(exports.contains("export ANTHROPIC_AUTH_TOKEN='tok-max-1'"))
        assertFalse(exports.contains("ANTHROPIC_API_KEY"))
    }

    @Test
    fun claudeProviderNameAlsoResolves() {
        val (exports, _) = buildProviderEnvExports("Claude", "sk-ant-2", "auto", useSubscription = false)
        assertTrue(exports.contains("export ANTHROPIC_API_KEY='sk-ant-2'"))
    }

    // --- Requirement 3: OpenClaude -> any provider (OpenRouter e.g.) ------
    @Test
    fun openclaudeOpenRouterProvider() {
        val (exports, baseUrlSet) = buildProviderEnvExports("OpenRouter", "sk-or-1", "tencent/hy3:free")
        assertTrue(exports.contains("export OPENROUTER_API_KEY='sk-or-1'"))
        assertTrue(exports.contains("export OPENAI_BASE_URL='https://openrouter.ai/api/v1'"))
        assertTrue(baseUrlSet)
        assertTrue(exports.contains("OPENAI_MODEL='tencent/hy3:free'"))
    }

    @Test
    fun openclaudeGeminiProvider() {
        val (exports, baseUrlSet) = buildProviderEnvExports("Google Gemini", "gem-key", "gemini-2.0-flash-exp")
        assertTrue(exports.contains("export GOOGLE_API_KEY='gem-key'"))
        assertTrue(exports.contains("export GEMINI_API_KEY='gem-key'"))
        assertTrue(exports.contains("export OPENAI_BASE_URL='https://generativelanguage.googleapis.com/v1beta/openai/'"))
        assertTrue(baseUrlSet)
    }

    // --- Requirement 4: OpenCode -> OpenRouter + others (any provider) ---
    @Test
    fun opencodeOpenRouterProvider() {
        val (exports, baseUrlSet) = buildProviderEnvExports("OpenRouter", "sk-or-2", "tencent/hy3:free")
        assertTrue(exports.contains("export OPENROUTER_API_KEY='sk-or-2'"))
        assertTrue(exports.contains("export OPENAI_BASE_URL='https://openrouter.ai/api/v1'"))
        assertTrue(baseUrlSet)
    }

    @Test
    fun opencodeDeepSeekProvider() {
        val (_, baseUrlSet) = buildProviderEnvExports("DeepSeek", "ds-key", "deepseek-chat")
        assertTrue(baseUrlSet) // DeepSeek is special-cased with its own base URL
    }

    @Test
    fun opencodeGroqProvider() {
        val (_, baseUrlSet) = buildProviderEnvExports("Groq", "groq-key", "llama-3.3-70b-versatile")
        assertTrue(baseUrlSet)
    }

    // --- Blank key short-circuits (no accidental empty export) -----------
    @Test
    fun blankKeyReturnsEmpty() {
        val (exports, _) = buildProviderEnvExports("OpenAI", "", "gpt-4o")
        assertEquals("", exports)
    }
}
