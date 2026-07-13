package com.orbitai.domain.api

import com.orbitai.domain.models.DetailedModelInfo
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    fun generateContentStream(
        sessionId: String? = null,
        prompt: String,
        apiKey: String,
        provider: String = DEFAULT_PROVIDER,
        model: String = ""
    ): Flow<AiEvent>

    suspend fun generateContent(
        prompt: String,
        apiKey: String,
        provider: String = DEFAULT_PROVIDER,
        model: String = ""
    ): AiResult

    suspend fun testConnection(provider: String, apiKey: String, model: String): Boolean

    suspend fun createSession(sessionId: String, systemPrompt: String? = null)
    suspend fun deleteSession(sessionId: String)

    val metadata: ProviderMetadata

    fun getModels(providerName: String): List<String>

    suspend fun fetchDetailedModels(providerName: String, apiKey: String): List<DetailedModelInfo> = emptyList()

    companion object {
        const val DEFAULT_PROVIDER = "OpenRouter"
    }
}

sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Error(val message: String) : AiResult()
}
