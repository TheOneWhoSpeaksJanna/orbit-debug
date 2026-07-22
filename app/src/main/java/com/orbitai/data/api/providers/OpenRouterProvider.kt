package com.orbitai.data.api.providers
import com.orbitai.core.logging.FileLogger

import com.orbitai.core.config.ApiConfig
import com.orbitai.domain.api.AiEvent
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.api.ProviderMetadata
import com.orbitai.domain.models.DetailedModelInfo
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

class OpenRouterProvider(private val httpClient: OkHttpClient) : AiProvider {
    private val TAG = "OpenRouterProvider"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var cachedModels: List<DetailedModelInfo>? = null
    private var lastFetchTime: Long = 0

    override fun getModels(providerName: String): List<String> = metadata.models

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

                val requestModel = if (model.isNotBlank()) model else ApiConfig.OPENROUTER_DEFAULT_MODEL

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
                    .url(ApiConfig.OPENROUTER_CHAT_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", ApiConfig.OPENROUTER_REFERRER_URL)
                    .addHeader("X-Title", ApiConfig.OPENROUTER_APP_TITLE)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message", "") ?: ""
                    } catch (_: Exception) { "" }
                    return@withContext AiResult.Error("OpenRouter API error ${response.code}: ${errorMsg.ifBlank { response.message }}")
                }

                val json = JSONObject(body)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    val text = message?.optString("content", "") ?: ""
                    return@withContext AiResult.Success(text)
                }
                AiResult.Error("No response from OpenRouter")
            } catch (e: Exception) {
            FileLogger.e(TAG, "generateContent failed", e)
                AiResult.Error("OpenRouter error: ${e.message}")
            }
        }
    }

    override suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestModel = if (model.isNotBlank()) model else ApiConfig.OPENROUTER_DEFAULT_MODEL
                val jsonBody = JSONObject().apply {
                    put("model", requestModel)
                    put("max_tokens", 1)
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "hi")
                        })
                    }
                    put("messages", messages)
                }
                val request = Request.Builder()
                    .url(ApiConfig.OPENROUTER_CHAT_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", ApiConfig.OPENROUTER_REFERRER_URL)
                    .addHeader("X-Title", ApiConfig.OPENROUTER_APP_TITLE)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()
                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
                response.isSuccessful
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun fetchDetailedModels(providerName: String, apiKey: String): List<DetailedModelInfo> {
        cachedModels?.let {
            if (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS) return it
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ApiConfig.OPENROUTER_MODELS_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", ApiConfig.OPENROUTER_REFERRER_URL)
                    .addHeader("X-Title", ApiConfig.OPENROUTER_APP_TITLE)
                    .build()

                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext cachedModels ?: emptyList()
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()

                val models = buildList {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val pricing = item.optJSONObject("pricing") ?: JSONObject()
                        add(DetailedModelInfo(
                            id = item.optString("id", ""),
                            name = item.optString("name", item.optString("id", "")),
                            promptPrice = pricing.optString("prompt", "0"),
                            completionPrice = pricing.optString("completion", "0"),
                            contextLength = item.optLong("context_length", 0)
                        ))
                    }
                }.sortedBy { it.id }

                cachedModels = models
                lastFetchTime = System.currentTimeMillis()
                models
            } catch (_: Exception) {
                cachedModels ?: emptyList()
            }
        }
    }

    override suspend fun createSession(sessionId: String, systemPrompt: String?) { }
    override suspend fun deleteSession(sessionId: String) { }

    override val metadata: ProviderMetadata = ProviderMetadata(
        name = "OpenRouter",
        displayName = "OpenRouter",
        models = listOf(
            ApiConfig.OPENROUTER_DEFAULT_MODEL,
            "google/gemma-4-31b-it:free",
            "anthropic/claude-sonnet-4-20250514",
            "google/gemini-2.0-flash-exp"
        ),
        supportsStreaming = true,
        requiresApiKey = true
    )

    companion object {
        private const val CACHE_DURATION_MS = 300_000L
    }
}
