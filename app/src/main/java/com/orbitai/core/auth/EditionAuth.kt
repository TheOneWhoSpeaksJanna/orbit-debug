package com.orbitai.core.auth

/**
 * Pure, framework-free helpers for per-edition auth decisions.
 *
 * Kept in a standalone object (rather than private to a ViewModel) so the
 * rules can be unit-tested on the JVM without Robolectric. Robolectric tests
 * can't run on this aarch64 host because the conscrypt native lib only ships
 * an x86-64 build, so any logic that must be verifiable headlessly belongs
 * here.
 */

/** Auth mode constants for the Anthropic (Claude) provider. */
const val CLAUDE_AUTH_API_KEY = "api-key"
const val CLAUDE_AUTH_SUBSCRIPTION = "subscription"

/**
 * Default provider for an edition's bundled agent.
 *
 * - Codex needs an OpenAI key (the @openai/codex agent reads OPENAI_API_KEY),
 *   so the Codex edition defaults to "OpenAI".
 * - Claude Code's agent uses Anthropic credentials, so it defaults to
 *   "Anthropic Claude".
 * - OpenCode / OpenClaude support any provider, so they keep the caller's
 *   normal default (return null here so callers fall back to OpenRouter).
 *
 * @return the provider name to preselect, or null if the caller should use
 *         its own default (e.g. OpenRouter for "any provider" editions).
 */
fun defaultProviderForAgent(agentName: String): String? = when (agentName) {
    "Codex" -> "OpenAI"
    "Claude Code" -> "Anthropic Claude"
    else -> null
}

/**
 * Resolve the Anthropic env var + value pair for a given auth mode.
 *
 * - api-key:      export ANTHROPIC_API_KEY=<key>
 * - subscription: export ANTHROPIC_AUTH_TOKEN=<token>   (Claude Max)
 *
 * @return a Pair of (ENV_VAR_NAME, ENV_VAR_VALUE)
 */
fun anthropicAuthEnv(provider: String, mode: String, key: String): Pair<String, String> {
    val isAnthropic = provider.contains("Anthropic", ignoreCase = true) ||
        provider.contains("Claude", ignoreCase = true)
    if (!isAnthropic) return "" to ""
    return if (mode == CLAUDE_AUTH_SUBSCRIPTION) "ANTHROPIC_AUTH_TOKEN" to key
    else "ANTHROPIC_API_KEY" to key
}
