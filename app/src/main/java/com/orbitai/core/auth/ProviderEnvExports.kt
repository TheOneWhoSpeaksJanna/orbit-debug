package com.orbitai.core.auth

/**
 * Pure, framework-free construction of the environment-variable exports that
 * Orbit-AI passes to a CLI agent (openclaude, claude, codex, opencode) so the
 * agent authenticates against the user's chosen provider.
 *
 * Kept free of Android APIs (no Context, no file I/O) so it can be unit-tested
 * on the JVM without Robolectric — Robolectric can't run on this aarch64 host
 * because the conscrypt native lib only ships an x86-64 build.
 *
 * @return a Pair of (exportShellString, baseUrlWasSet). The caller (the
 *         ViewModel) appends any catalog-based base-URL fallback and the SHELL
 *         export. The returned string always ends with " && " so the caller can
 *         concatenate more exports.
 */
fun buildProviderEnvExports(
    provider: String,
    apiKey: String,
    model: String,
    useSubscription: Boolean = false
): Pair<String, Boolean> {
    if (apiKey.isBlank()) return "" to false

    val exports = StringBuilder()

    // CRITICAL: CLAUDE_CODE_USE_OPENAI=1 tells OpenClaude to use the
    // OpenAI-compatible provider instead of the default Gitlawb Opengateway.
    exports.append("export CLAUDE_CODE_USE_OPENAI=1")

    // Always set OPENAI_API_KEY — OpenClaude reads this when
    // CLAUDE_CODE_USE_OPENAI=1
    exports.append(" && export OPENAI_API_KEY='$apiKey'")

    var baseUrlSet = false
    when {
        provider.contains("OpenRouter", ignoreCase = true) -> {
            exports.append(" && export OPENROUTER_API_KEY='$apiKey'")
            exports.append(" && export OPENAI_BASE_URL='https://openrouter.ai/api/v1'")
            baseUrlSet = true
        }
        provider.contains("Anthropic", ignoreCase = true) ||
            provider.contains("Claude", ignoreCase = true) -> {
            val (envName, envValue) = anthropicAuthEnv(
                provider, if (useSubscription) "subscription" else "api-key", apiKey
            )
            if (envName.isNotBlank()) {
                exports.append(" && export $envName='$envValue'")
            }
        }
        provider.contains("Gemini", ignoreCase = true) -> {
            exports.append(" && export OPENAI_BASE_URL='https://generativelanguage.googleapis.com/v1beta/openai/'")
            exports.append(" && export GOOGLE_API_KEY='$apiKey'")
            exports.append(" && export GEMINI_API_KEY='$apiKey'")
            baseUrlSet = true
        }
        provider.contains("DeepSeek", ignoreCase = true) -> {
            exports.append(" && export OPENAI_BASE_URL='https://api.deepseek.com/v1'")
            baseUrlSet = true
        }
        provider.contains("Groq", ignoreCase = true) -> {
            exports.append(" && export OPENAI_BASE_URL='https://api.groq.com/openai/v1'")
            baseUrlSet = true
        }
        provider.contains("xAI", ignoreCase = true) ||
            provider.contains("Grok", ignoreCase = true) -> {
            exports.append(" && export OPENAI_BASE_URL='https://api.x.ai/v1'")
            baseUrlSet = true
        }
    }

    // Model export (OpenClaude honors ANTHROPIC_MODEL + ANTHROPIC_SMALL_FAST_MODEL;
    // OpenAI-compatible path honors OPENAI_MODEL).
    if (model.isNotBlank() && model != "auto") {
        exports.append(" && export OPENAI_MODEL='$model'")
        exports.append(" && export ANTHROPIC_MODEL='$model'")
        exports.append(" && export ANTHROPIC_SMALL_FAST_MODEL='$model'")
    }

    return exports.toString() to baseUrlSet
}
