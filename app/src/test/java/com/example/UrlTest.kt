package com.orbitai

import com.orbitai.core.config.ApiConfig
import org.junit.Test

/**
 * Verifies that every provider name listed in the UI also has a working
 * entry in [ApiConfig]. This catches the historical bug where the UI
 * listed 7 providers but only 4 were wired into the rest of the stack.
 *
 * Note: this test runs as a unit test (no Android framework needed) because
 * ApiConfig only depends on BuildConfig, which Roborazzi/Robolectric
 * generates for unit-test variants.
 */
class UrlTest {

    @Test
    fun checkAllProviderUrlsResolve() {
        // Every provider the UI exposes must have at least one URL constant in ApiConfig.
        val providers = listOf("Claude", "OpenAI", "Gemini", "OpenRouter", "DeepSeek", "Groq", "Ollama")

        for (provider in providers) {
            val url = when (provider) {
                "Claude" -> ApiConfig.CLAUDE_API_URL
                "OpenAI" -> ApiConfig.OPENAI_CHAT_URL
                "Gemini" -> ApiConfig.GEMINI_BASE_URL
                "OpenRouter" -> ApiConfig.OPENROUTER_CHAT_URL
                "DeepSeek" -> ApiConfig.DEEPSEEK_CHAT_URL
                "Groq" -> ApiConfig.GROQ_CHAT_URL
                "Ollama" -> ApiConfig.OLLAMA_DEFAULT_BASE_URL
                else -> error("Unknown provider: $provider")
            }
            check(url.startsWith("http://") || url.startsWith("https://")) {
                "$provider URL is not a valid HTTP(S) URL: $url"
            }
        }
    }

    @Test
    fun checkDefaultModelsAreNonEmpty() {
        check(ApiConfig.CLAUDE_DEFAULT_MODEL.isNotBlank())
        check(ApiConfig.OPENAI_DEFAULT_MODEL.isNotBlank())
        check(ApiConfig.GEMINI_DEFAULT_MODEL.isNotBlank())
        check(ApiConfig.OPENROUTER_DEFAULT_MODEL.isNotBlank())
        check(ApiConfig.DEEPSEEK_DEFAULT_MODEL.isNotBlank())
        check(ApiConfig.GROQ_DEFAULT_MODEL.isNotBlank())
    }
}
