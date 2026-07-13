package com.orbitai.data.api.providers

import android.content.Context
import com.orbitai.core.logging.FileLogger
import com.orbitai.data.local.runtime.ProviderCatalog
import com.orbitai.data.local.runtime.ProviderEntry
import com.orbitai.domain.api.AiEvent
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.api.ProviderMetadata
import com.orbitai.domain.models.DetailedModelInfo
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * Routes calls to the active provider implementation.
 *
 * DYNAMIC ROUTING (v195+):
 * Previously, this class had a hardcoded map of 9 providers and silently
 * fell back to Gemini for any unknown provider. This caused "API key invalid"
 * errors when users selected providers like xAI, Mistral, Together, etc.
 *
 * Now it loads the provider catalog dynamically and creates the appropriate
 * provider based on the catalog entry's "transport" field:
 *  - "openai-compatible" → GenericOpenAIProvider (handles 20+ providers)
 *  - "anthropic-native"  → ClaudeProvider
 *  - "gemini-native"     → GeminiProvider
 *  - "local"             → OllamaProvider
 *  - "zai-free"          → ZAIProvider
 *
 * The catalog is loaded lazily on first use and cached.
 */
class AiProviderSelector(
    private val okHttpClient: OkHttpClient,
    private val context: Context? = null
) : AiProvider {

    private val hardcodedProviders: Map<String, AiProvider> = mapOf(
        "Gemini" to GeminiProvider(okHttpClient),
        "OpenAI" to OpenAIProvider(okHttpClient),
        "Claude" to ClaudeProvider(okHttpClient),
        "OpenRouter" to OpenRouterProvider(okHttpClient),
        "DeepSeek" to DeepSeekProvider(okHttpClient),
        "Groq" to GroqProvider(okHttpClient),
        "Ollama" to OllamaProvider(okHttpClient),
        "Z.AI (Free GLM)" to ZAIProvider(okHttpClient),
        "Z.AI" to ZAIProvider(okHttpClient)
    )

    // Cache of dynamically-created providers (keyed by provider display name)
    private val dynamicProviders = mutableMapOf<String, AiProvider>()

    // Lazy-loaded catalog
    private val catalog: List<ProviderEntry>? by lazy {
        try {
            context?.let { ProviderCatalog.load(it) }
        } catch (e: Exception) {
            FileLogger.w("AiProviderSelector", "Failed to load provider catalog", "reason=${e.message}")
            null
        }
    }

    private fun getProvider(name: String): AiProvider {
        // 1. Check hardcoded providers first (fast path for the 9 legacy providers)
        hardcodedProviders[name]?.let { return it }

        // 2. Check dynamic cache
        dynamicProviders[name]?.let { return it }

        // 3. Look up in catalog and create dynamically
        if (catalog != null) {
            for (entry in catalog!!) {
                if (entry.name == name) {
                    val provider = createProviderForEntry(entry)
                    dynamicProviders[name] = provider
                    return provider
                }
            }
        }

        // 4. Last resort: return Gemini (shouldn't happen with a valid catalog)
        FileLogger.w("AiProviderSelector", "Unknown provider, falling back to Gemini", "name=$name")
        return hardcodedProviders["Gemini"]!!
    }

    private fun createProviderForEntry(entry: ProviderEntry): AiProvider {
        val baseUrl = entry.baseUrl
        val transport = entry.transport
        val defaultModel = entry.defaultModel

        FileLogger.i("AiProviderSelector", "Creating dynamic provider",
            "name=${entry.name} transport=$transport baseUrl=$baseUrl")

        return when (transport) {
            "anthropic-native" -> ClaudeProvider(okHttpClient)
            "gemini-native" -> GeminiProvider(okHttpClient)
            "local" -> OllamaProvider(okHttpClient)
            "zai-free" -> ZAIProvider(okHttpClient)
            else -> GenericOpenAIProvider(
                httpClient = okHttpClient,
                baseUrl = baseUrl,
                providerName = entry.name,
                defaultModel = defaultModel
            )
        }
    }

    override fun generateContentStream(sessionId: String?, prompt: String, apiKey: String, provider: String, model: String): Flow<AiEvent> {
        return getProvider(provider).generateContentStream(sessionId, prompt, apiKey, provider, model)
    }

    override suspend fun generateContent(prompt: String, apiKey: String, provider: String, model: String): AiResult {
        return getProvider(provider).generateContent(prompt, apiKey, provider, model)
    }

    override suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean {
        return getProvider(provider).testConnection(provider, apiKey, model)
    }

    override suspend fun createSession(sessionId: String, systemPrompt: String?) {
        hardcodedProviders.values.forEach { it.createSession(sessionId, systemPrompt) }
    }

    override suspend fun deleteSession(sessionId: String) {
        hardcodedProviders.values.forEach { it.deleteSession(sessionId) }
    }

    override fun getModels(providerName: String): List<String> = getProvider(providerName).getModels(providerName)

    override suspend fun fetchDetailedModels(providerName: String, apiKey: String): List<DetailedModelInfo> =
        getProvider(providerName).fetchDetailedModels(providerName, apiKey)

    override val metadata: ProviderMetadata = ProviderMetadata(
        name = "All",
        displayName = "All Providers",
        models = emptyList(),
        supportsStreaming = true,
        requiresApiKey = true
    )
}
