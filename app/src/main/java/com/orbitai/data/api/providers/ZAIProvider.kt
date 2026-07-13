package com.orbitai.data.api.providers

import com.orbitai.core.logging.FileLogger
import com.orbitai.domain.api.AiEvent
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.api.AiResult
import com.orbitai.domain.api.ProviderMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ZAIProvider"

/**
 * Z.AI free provider — uses the same API that powers chat.z.ai.
 *
 * HOW IT WORKS:
 *  - API endpoint: https://internal-api.z.ai/v1/chat/completions
 *  - API key: "Z.ai" (literal string, public/free)
 *  - Session token: required, obtained from the Z.AI web interface
 *  - The token is a JWT that identifies the user's session
 *  - No payment or API key purchase needed — completely free
 *
 * AUTO-RETRY:
 *  - When the API returns 429 (rate limit) or 500/502/503 (server error),
 *    the provider automatically retries with exponential backoff.
 *  - Initial delay: 2 seconds, doubling each retry, max 30 seconds.
 *  - Max retries: 10 (configurable).
 *  - This solves the "try again" popup problem the user reported.
 *
 * GETTING A TOKEN:
 *  1. Go to https://chat.z.ai (or z.ai) in a browser
 *  2. Open Developer Tools → Application → Local Storage
 *  3. Look for a token or session value
 *  OR:
 *  1. Open Developer Tools → Network tab
 *  2. Send a message in the chat
 *  3. Find the request to /v1/chat/completions
 *  4. Copy the X-Token header value
 *
 * The token is stored in the app's encrypted preferences as the "Z.AI" provider key.
 */
class ZAIProvider(
    private val httpClient: OkHttpClient
) : AiProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
        prompt: String,
        apiKey: String,
        provider: String,
        model: String
    ): AiResult = withContext(Dispatchers.IO) {
        // apiKey for Z.AI is the session token (pasted by the user).
        // The actual API key is always "Z.ai" (hardcoded, public).
        val token = apiKey.trim()
        if (token.isBlank()) {
            return@withContext AiResult.Error(
                "Z.AI session token required. Get it from chat.z.ai — open Developer Tools → Network, " +
                "send a message, find the /v1/chat/completions request, and copy the X-Token header value."
            )
        }

        val requestModel = if (model.isNotBlank()) model else DEFAULT_MODEL

        // Build the request body (standard OpenAI chat completions format + thinking field)
        val jsonBody = JSONObject().apply {
            put("model", requestModel)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messages)
            put("thinking", JSONObject().apply { put("type", "disabled") })
        }

        // ── Auto-retry with exponential backoff ──────────────────────
        // When the API returns 429 (too many requests — the "try again" popup
        // the user sees on the web), we automatically wait and retry.
        // This makes the app much more reliable than the web interface.
        var lastError: String? = null
        var retryDelayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("X-Z-AI-From", "Z")
                    .addHeader("X-Token", token)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                FileLogger.d(TAG, "Z.AI attempt $attempt/$MAX_RETRIES, model=$requestModel")
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        val json = JSONObject(body)
                        val choices = json.optJSONArray("choices")
                        val text = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                        if (text.isNotBlank()) {
                            FileLogger.i(TAG, "Z.AI success on attempt $attempt (${text.length} chars)")
                            return@withContext AiResult.Success(text)
                        } else {
                            lastError = "Empty response from Z.AI"
                            FileLogger.w(TAG, "Z.AI empty response on attempt $attempt")
                        }
                    }

                    response.code == 429 -> {
                        // Rate limited — this is the "try again" popup.
                        // Auto-retry with exponential backoff.
                        lastError = "Rate limited (429). Retrying in ${retryDelayMs / 1000}s..."
                        FileLogger.w(TAG, "Z.AI rate limited on attempt $attempt, retrying in ${retryDelayMs}ms")
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }

                    response.code in 500..599 -> {
                        // Server error — retry
                        lastError = "Server error (${response.code}). Retrying..."
                        FileLogger.w(TAG, "Z.AI server error ${response.code} on attempt $attempt, retrying")
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }

                    response.code == 401 || response.code == 403 -> {
                        // Auth error — don't retry, token is invalid
                        val errorMsg = try {
                            JSONObject(body).optString("error", "Authentication failed")
                        } catch (_: Exception) { "Authentication failed (token may be expired)" }
                        FileLogger.e(TAG, "Z.AI auth error: $errorMsg")
                        return@withContext AiResult.Error("Z.AI token error: $errorMsg. Get a fresh token from chat.z.ai")
                    }

                    else -> {
                        lastError = "HTTP ${response.code}: ${body.take(200)}"
                        FileLogger.w(TAG, "Z.AI HTTP ${response.code} on attempt $attempt: ${body.take(200)}")
                        // For other errors, try once more
                        if (attempt < MAX_RETRIES) {
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = "Network error: ${e.message}"
                FileLogger.w(TAG, "Z.AI network error on attempt $attempt: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    continue
                }
            }
        }

        AiResult.Error(lastError ?: "Z.AI request failed after $MAX_RETRIES attempts")
    }

    override suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = generateContent("Say 'ok'", apiKey, provider, model)
            result is AiResult.Success
        }

    override fun getModels(providerName: String): List<String> = metadata.models

    override val metadata: ProviderMetadata = ProviderMetadata(
        name = "Z.AI",
        displayName = "Z.AI (Free GLM)",
        models = listOf("glm-5.2", "glm-4.6", "glm-4-plus", "glm-4-flash", "glm-4-air"),
        supportsStreaming = true,
        requiresApiKey = true,  // Requires the session token
        defaultModel = DEFAULT_MODEL
    )

    override suspend fun createSession(sessionId: String, systemPrompt: String?) { }
    override suspend fun deleteSession(sessionId: String) { }

    companion object {
        private const val API_URL = "https://internal-api.z.ai/v1/chat/completions"
        private const val API_KEY = "Z.ai"  // Public, free API key
        private const val DEFAULT_MODEL = "glm-5.2"

        // Auto-retry settings
        private const val MAX_RETRIES = 10
        private const val INITIAL_RETRY_DELAY_MS = 2000L  // 2 seconds
        private const val MAX_RETRY_DELAY_MS = 30000L     // 30 seconds max
    }
}
