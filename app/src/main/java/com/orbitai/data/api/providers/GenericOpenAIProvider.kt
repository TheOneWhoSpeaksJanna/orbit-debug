package com.orbitai.data.api.providers
import com.orbitai.core.logging.FileLogger

import com.orbitai.domain.api.AiEvent
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.api.ProviderMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic OpenAI-compatible provider.
 *
 * Works with ANY provider that uses the standard OpenAI Chat Completions API
 * format: POST to {baseUrl}/chat/completions with a JSON body containing
 * "model" and "messages", and Authorization: Bearer {apiKey}.
 *
 * This handles 20+ providers from the catalog that are marked
 * transport="openai-compatible": xAI, Venice, Fireworks, Moonshot, MiniMax,
 * Near AI, Xiaomi, Together, Mistral, NVIDIA, DashScope, Kimi, etc.
 *
 * Also handles GitHub Models (models.inference.ai.azure.com) which uses
 * the same API format with a GitHub PAT as the bearer token.
 */
class GenericOpenAIProvider(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val providerName: String,
    private val defaultModel: String = ""
) : AiProvider {

    private val TAG = "GenericOpenAIProvider"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Normalize the base URL: ensure it ends with /v1 for the chat endpoint. */
    private val chatUrl: String = run {
        var base = baseUrl.trimEnd('/')
        // If the base URL doesn't end with /v1, add it (most OpenAI-compatible APIs use /v1)
        if (!base.endsWith("/v1")) {
            base = "$base/v1"
        }
        "$base/chat/completions"
    }

    override fun generateContentStream(sessionId: String?, prompt: String, apiKey: String, provider: String, model: String): Flow<AiEvent> = flow {
        val result = generateContent(prompt, apiKey, provider, model)
        when (result) {
            is AiResult.Success -> emit(AiEvent.Done(result.text))
            is AiResult.Error -> emit(AiEvent.Error(result.message))
        }
    }

    override suspend fun generateContent(prompt: String, apiKey: String, provider: String, model: String): AiResult {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) return@withContext AiResult.Error("API Key is missing.")

                val requestModel = if (model.isNotBlank()) model else defaultModel.ifBlank { "gpt-4o" }

                val jsonBody = JSONObject().apply {
                    put("model", requestModel)
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    }
                    put("messages", messages)
                }

                val request = Request.Builder()
                    .url(chatUrl)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code} model=$model")
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.optJSONArray("choices")
                    val text = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")

                    if (!text.isNullOrBlank()) {
                        AiResult.Success(text)
                    } else {
                        AiResult.Error("Empty response from $providerName.")
                    }
                } else {
                    AiResult.Error("HTTP ${response.code} from $providerName: ${responseBody ?: response.message}")
                }
            } catch (e: Exception) {
            FileLogger.e(TAG, "generateContent failed", e)
                AiResult.Error("Network Error ($providerName): ${e.message}")
            }
        }
    }

    override suspend fun createSession(sessionId: String, systemPrompt: String?) { }
    override suspend fun deleteSession(sessionId: String) { }

    // Expose the catalog's default model so the model picker shows a valid
    // option for this provider instead of falling back to unrelated defaults
    // (e.g. Gemini/Claude names). Providers with a richer live list still get
    // it via fetchDetailedModels; this is the sensible offline fallback.
    override fun getModels(providerName: String): List<String> =
        if (defaultModel.isNotBlank()) listOf(defaultModel) else emptyList()

    override val metadata: ProviderMetadata = ProviderMetadata(
        name = providerName,
        displayName = providerName,
        models = if (defaultModel.isNotBlank()) listOf(defaultModel) else emptyList(),
        supportsStreaming = true,
        requiresApiKey = true,
        defaultModel = defaultModel
    )

    override suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean {
        return withContext(Dispatchers.IO) {
            val result = generateContent("Hi", apiKey, provider, model)
            result is AiResult.Success
        }
    }
}
