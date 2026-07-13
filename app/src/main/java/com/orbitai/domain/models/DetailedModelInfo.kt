package com.orbitai.domain.models

data class DetailedModelInfo(
    val id: String,
    val name: String,
    val promptPrice: String,
    val completionPrice: String,
    val contextLength: Long
) {
    val formattedPromptPrice: String
        get() = if (promptPrice == PRICE_FREE_VALUE || promptPrice.toDoubleOrNull() == 0.0) {
            PRICE_FREE_LABEL
        } else {
            PRICE_PREFIX + promptPrice + PRICE_SUFFIX
        }

    val formattedCompletionPrice: String
        get() = if (completionPrice == PRICE_FREE_VALUE || completionPrice.toDoubleOrNull() == 0.0) {
            PRICE_FREE_LABEL
        } else {
            PRICE_PREFIX + completionPrice + PRICE_SUFFIX
        }

    val contextDisplay: String
        get() = if (contextLength >= CONTEXT_THRESHOLD_K) {
            "${contextLength / 1000}K"
        } else {
            "$contextLength"
        }

    companion object {
        private const val PRICE_FREE_VALUE = "0"
        private const val PRICE_FREE_LABEL = "Free"
        private const val PRICE_PREFIX = "$"
        private const val PRICE_SUFFIX = "/1M tokens"
        private const val CONTEXT_THRESHOLD_K = 1000
    }
}
