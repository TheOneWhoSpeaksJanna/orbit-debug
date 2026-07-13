package com.orbitai.domain.api

sealed interface AiEvent {
    data class Chunk(val text: String) : AiEvent
    data class Done(val fullText: String) : AiEvent
    data class Error(val message: String) : AiEvent
}
