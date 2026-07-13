package com.orbitai.domain.api

data class ProviderMetadata(
    val name: String,
    val displayName: String,
    val models: List<String>,
    val supportsStreaming: Boolean = true,
    val requiresApiKey: Boolean = true,
    val defaultModel: String = ""
)
