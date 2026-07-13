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

/**
 * Ollama provider — runs models locally on the device or on a reachable server.
 *
 * The Ollama "API key" slot is re-purposed to hold the base URL of the Ollama
 * server (default `http://localhost:11434`). This lets users point at a remote
 * machine running Ollama without needing a separate setting screen.
 *
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md
 */
class OllamaProvider(private val httpClient: OkHttpClient) : AiProvider {
    private val TAG = "OllamaProvider"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Resolve the Ollama base URL. The apiKey parameter is re-used as a custom
     * base URL (falls back to the local default when blank).
     */
    private fun baseUrl(apiKey: String): String {
        val raw = apiKey.trim().trimEnd('/')
        return if (raw.isNotBlank()) raw else ApiConfig.OLLAMA_DEFAULT_BASE_URL
    }

    override fun generateContentStream(
        sessionId: String?,
        prompt: String,
        apiKey: String,
        provider: String,
        model: String
    ): Flow<AiEvent> = flow {
        val result = generateContent(prompt, apiKey, provider, model)
        when (result) {
            is AiResult.Success -> emit(AiEvent.Done(result.text))
            is AiResult.Error -> emit(AiEvent.Error(result.message))
        }
    }

    override suspend fun generateContent(
        prompt: String, apiKey: String, provider: String, model: String
    ): AiResult = withContext(Dispatchers.IO) {
        try {
            val requestModel = if (model.isNotBlank()) model else DEFAULT_MODEL

            val jsonBody = JSONObject().apply {
                put("model", requestModel)
                put("prompt", prompt)
                put("stream", false)
            }

            val request = Request.Builder()
                .url("${baseUrl(apiKey)}${ApiConfig.OLLAMA_CHAT_PATH}")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val text = jsonResponse.optJSONObject("message")?.optString("content")
                    ?: jsonResponse.optString("response", "")
                if (text.isNotBlank()) {
                    AiResult.Success(text)
                } else {
                    AiResult.Error("Empty response from Ollama.")
                }
            } else {
                AiResult.Error("HTTP Error ${response.code}: ${responseBody ?: response.message}")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "generateContent failed", e)
            AiResult.Error("Network Error: ${e.message}")
        }
    }

    override suspend fun createSession(sessionId: String, systemPrompt: String?) { }
    override suspend fun deleteSession(sessionId: String) { }

    override fun getModels(providerName: String): List<String> = metadata.models

    override val metadata: ProviderMetadata = ProviderMetadata(
        name = "Ollama",
        displayName = "Ollama (Local)",
        models = listOf(DEFAULT_MODEL, "llama3", "mistral", "phi3"),
        supportsStreaming = true,
        requiresApiKey = false,
        defaultModel = DEFAULT_MODEL
    )

    override suspend fun fetchDetailedModels(providerName: String, apiKey: String): List<DetailedModelInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl(apiKey)}${ApiConfig.OLLAMA_TAGS_PATH}")
                    .get()
                    .build()
                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext emptyList()

                val json = JSONObject(body)
                val models = json.optJSONArray("models") ?: JSONArray()
                buildList {
                    for (i in 0 until models.length()) {
                        val m = models.getJSONObject(i)
                        add(DetailedModelInfo(
                            id = m.optString("name", ""),
                            name = m.optString("name", ""),
                            promptPrice = "0",
                            completionPrice = "0",
                            contextLength = m.optJSONObject("parameters")?.optLong("context_length", 0L) ?: 0L
                        ))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl(apiKey)}${ApiConfig.OLLAMA_TAGS_PATH}")
                    .get()
                    .build()
                val response = httpClient.newCall(request).execute()
                FileLogger.d(TAG, "HTTP response", "code=${response.code}")
                response.isSuccessful.also { response.close() }
            } catch (_: Exception) {
                false
            }
        }

    companion object {
        private const val DEFAULT_MODEL = "llama3.1"
    }
}
