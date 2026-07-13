package com.orbitai.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-only tests for per-edition auth decisions. These run without Robolectric
 * (which can't execute on this aarch64 host because conscrypt ships only an
 * x86-64 native lib), so they give real, headless verification of the edition
 * requirements that don't need an Android device.
 */
class EditionAuthTest {

    // --- Requirement 1: Codex Edition uses OpenAI auth ----------------------
    @Test
    fun codexEditionDefaultsToOpenAI() {
        assertEquals("OpenAI", defaultProviderForAgent("Codex"))
    }

    @Test
    fun claudeCodeEditionDefaultsToAnthropicClaude() {
        assertEquals("Anthropic Claude", defaultProviderForAgent("Claude Code"))
    }

    @Test
    fun openCodeAndOpenClaudeHaveNoForcedProvider() {
        assertEquals(null, defaultProviderForAgent("OpenCode"))
        assertEquals(null, defaultProviderForAgent("OpenClaude"))
    }

    @Test
    fun normalEditionHasNoForcedProvider() {
        assertEquals(null, defaultProviderForAgent(""))
    }

    // --- Requirement 2: Claude Code supports API key AND subscription -------
    @Test
    fun anthropicApiKeyModeExportsAnthropicApiKey() {
        val (name, value) = anthropicAuthEnv("Anthropic Claude", CLAUDE_AUTH_API_KEY, "sk-ant-123")
        assertEquals("ANTHROPIC_API_KEY", name)
        assertEquals("sk-ant-123", value)
    }

    @Test
    fun anthropicSubscriptionModeExportsAnthropicAuthToken() {
        val (name, value) = anthropicAuthEnv("Anthropic Claude", CLAUDE_AUTH_SUBSCRIPTION, "tok-abc")
        assertEquals("ANTHROPIC_AUTH_TOKEN", name)
        assertEquals("tok-abc", value)
    }

    @Test
    fun claudeProviderNameAlsoResolvesSubscription() {
        val (name, _) = anthropicAuthEnv("Claude", CLAUDE_AUTH_SUBSCRIPTION, "tok")
        assertEquals("ANTHROPIC_AUTH_TOKEN", name)
    }

    @Test
    fun nonAnthropicProviderYieldsNoEnvVar() {
        val (name, _) = anthropicAuthEnv("OpenAI", CLAUDE_AUTH_SUBSCRIPTION, "k")
        assertEquals("", name)
    }
}
