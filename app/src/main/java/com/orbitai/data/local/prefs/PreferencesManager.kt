package com.orbitai.data.local.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.orbitai.ui.theme.ThemeId
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DownloadProgress(
    val url: String,
    val filePath: String,
    val bytesDownloaded: Long,
    val version: String
)

// A corruption handler makes the app self-heal if the prefs file ever becomes
// unreadable (e.g. interrupted write, disk issue): instead of crash-looping with
// CorruptionException, DataStore resets to empty prefs (onboarding re-runs) and
// the app stays usable.
val Context.dataStore by preferencesDataStore(
    name = "orbit_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class PreferencesManager(private val context: Context) {

    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_ID = stringPreferencesKey("theme_id")
        val THEME_CUSTOM = stringPreferencesKey("theme_custom")
        val IS_ONBOARDING_COMPLETE = booleanPreferencesKey("is_onboarding_complete")
        val SHIZUKU_ENABLED = booleanPreferencesKey("shizuku_enabled")
        val SELECTED_AGENT = stringPreferencesKey("selected_agent")
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val THINKING_MODEL = stringPreferencesKey("thinking_model")

        val AGENT_PERMISSION_LEVEL = stringPreferencesKey("agent_permission_level")
        val AGENT_RULES_ALLOWED = stringPreferencesKey("agent_rules_allowed")
        val AGENT_RULES_ASK = stringPreferencesKey("agent_rules_ask")
        val AGENT_RULES_DENIED = stringPreferencesKey("agent_rules_denied")

        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val CLAUDE_AUTH_MODE = stringPreferencesKey("claude_auth_mode")
        val CLAUDE_SUBSCRIPTION_TOKEN = stringPreferencesKey("claude_subscription_token")
        val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        // For Ollama, the "key" slot stores the base URL of the Ollama server
        // (default http://localhost:11434). Ollama does not require auth.
        val OLLAMA_BASE_URL = stringPreferencesKey("ollama_base_url")
        // For Z.AI, the "key" slot stores the session token from chat.z.ai.
        // The actual API key is "Z.ai" (public/free), but the session token
        // identifies the user. Get it from the Z.AI web interface.
        val ZAI_TOKEN = stringPreferencesKey("zai_token")

        // Generic per-provider API key storage. Each provider gets its own
        // DataStore key: "provider_key_<id>". This avoids the old bug where
        // all unknown providers aliased to the Gemini key slot.
        val DOWNLOAD_URL = stringPreferencesKey("download_url")
        val DOWNLOAD_FILE = stringPreferencesKey("download_file")
        val DOWNLOAD_BYTES = longPreferencesKey("download_bytes")
        val DOWNLOAD_VERSION = stringPreferencesKey("download_version")

        private const val PROVIDER_GEMINI = "gemini"
        private const val PROVIDER_OPENAI = "openai"
        private const val PROVIDER_CLAUDE = "anthropic"
        private const val PROVIDER_OPENROUTER = "openrouter"
        private const val PROVIDER_DEEPSEEK = "deepseek"
        private const val PROVIDER_GROQ = "groq"
        private const val PROVIDER_OLLAMA = "ollama"
        private const val PROVIDER_ZAI = "z.ai"
        private const val PROVIDER_ZAI_FREE_GLM = "z.ai (free glm)"
        private const val PROVIDER_XAI = "xai"
        private const val PROVIDER_MISTRAL = "mistral"
        private const val PROVIDER_TOGETHER = "together"
        private const val PROVIDER_NVIDIA = "nvidia"
        private const val PROVIDER_AWS = "aws"
        private const val PROVIDER_AZURE = "azure"
        private const val PROVIDER_VERTEX = "vertex"
        private const val PROVIDER_LMSTUDIO = "lmstudio"
        private const val PROVIDER_DASHSCOPE = "dashscope"
        private const val PROVIDER_MOONSHOT = "moonshot"
        private const val PROVIDER_VENICE = "venice"
        private const val PROVIDER_FIREWORKS = "fireworks"
        private const val PROVIDER_MINIMAX = "minimax"
        private const val PROVIDER_NEARAI = "nearai"
        private const val PROVIDER_XIAOMI = "xiaomi"
        private const val PROVIDER_ATLAS = "atlas"
        private const val PROVIDER_KIMI = "kimi"
        private const val PROVIDER_OPENCODE_GATEWAY = "opencode_gateway"
        private const val PROVIDER_OPENGATEWAY = "opengateway"
        private const val PROVIDER_GITHUB = "github"
        private const val PROVIDER_CUSTOM = "custom"
    }

    val themeMode: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE]
    }

    val themeId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_ID] ?: ThemeId.NORMAL.key
    }

    val customTheme: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[THEME_CUSTOM]
    }

    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[IS_ONBOARDING_COMPLETE] ?: false
    }

    val shizukuEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHIZUKU_ENABLED] ?: false
    }

    val selectedAgent: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_AGENT]
    }

    val selectedProvider: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_PROVIDER]
    }

    val selectedModel: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_MODEL]
    }

    val thinkingModel: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[THINKING_MODEL]
    }

    val agentPermissionLevel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AGENT_PERMISSION_LEVEL] ?: "NORMAL"
    }

    val agentRulesAllowed: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AGENT_RULES_ALLOWED] ?: ""
    }

    val agentRulesAsk: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AGENT_RULES_ASK] ?: ""
    }

    val agentRulesDenied: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AGENT_RULES_DENIED] ?: ""
    }

    val geminiApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[GEMINI_API_KEY]
    }

    val openAiApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OPENAI_API_KEY]
    }

    val claudeApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[CLAUDE_API_KEY]
    }

    val openRouterApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OPENROUTER_API_KEY]
    }

    val deepSeekApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[DEEPSEEK_API_KEY]
    }

    val groqApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[GROQ_API_KEY]
    }

    /**
     * Ollama base URL (no auth required). Returns null when unset, in which
     * case the Ollama provider falls back to `http://localhost:11434`.
     */
    val ollamaBaseUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OLLAMA_BASE_URL]
    }

    val zaiToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ZAI_TOKEN]
    }

    /**
     * Get the API key for a provider.
     *
     * Uses a generic per-provider DataStore key ("provider_key_<id>") for ALL
     * providers. This replaces the old when() that aliased unknown providers
     * to the Gemini key slot.
     *
     * The old named keys (gemini_api_key, openai_api_key, etc.) are kept for
     * backward compatibility — if a generic key is not set, we fall back to
     * the old named key so users don't lose their existing keys.
     */
    fun getApiKeyForProvider(provider: String): Flow<String?> {
        val providerId = provider.lowercase().trim()
        val genericKey = stringPreferencesKey("provider_key_$providerId")
        return context.dataStore.data.map { prefs ->
            // Try generic key first, then fall back to legacy named keys
            prefs[genericKey] ?: when (providerId) {
                PROVIDER_GEMINI -> prefs[GEMINI_API_KEY]
                PROVIDER_OPENAI -> prefs[OPENAI_API_KEY]
                PROVIDER_CLAUDE -> prefs[CLAUDE_API_KEY]
                PROVIDER_OPENROUTER -> prefs[OPENROUTER_API_KEY]
                PROVIDER_DEEPSEEK -> prefs[DEEPSEEK_API_KEY]
                PROVIDER_GROQ -> prefs[GROQ_API_KEY]
                PROVIDER_OLLAMA -> prefs[OLLAMA_BASE_URL]
                PROVIDER_ZAI, PROVIDER_ZAI_FREE_GLM -> prefs[ZAI_TOKEN]
                else -> null
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE] = mode }
    }

    suspend fun setThemeId(id: String) {
        context.dataStore.edit { prefs -> prefs[THEME_ID] = id }
    }

    suspend fun setCustomTheme(stored: String) {
        context.dataStore.edit { prefs -> prefs[THEME_CUSTOM] = stored }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs -> prefs[IS_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setShizukuEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHIZUKU_ENABLED] = enabled }
    }

    suspend fun setSelectedAgent(agentId: String) {
        context.dataStore.edit { prefs -> prefs[SELECTED_AGENT] = agentId }
    }

    suspend fun setSelectedProvider(provider: String) {
        context.dataStore.edit { prefs -> prefs[SELECTED_PROVIDER] = provider }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { prefs -> prefs[SELECTED_MODEL] = model }
    }

    suspend fun setThinkingModel(model: String) {
        context.dataStore.edit { prefs -> prefs[THINKING_MODEL] = model }
    }

    suspend fun setAgentPermissionLevel(level: String) {
        context.dataStore.edit { prefs -> prefs[AGENT_PERMISSION_LEVEL] = level }
    }

    suspend fun setAgentRulesAllowed(rules: String) {
        context.dataStore.edit { prefs -> prefs[AGENT_RULES_ALLOWED] = rules }
    }

    suspend fun setAgentRulesAsk(rules: String) {
        context.dataStore.edit { prefs -> prefs[AGENT_RULES_ASK] = rules }
    }

    suspend fun setAgentRulesDenied(rules: String) {
        context.dataStore.edit { prefs -> prefs[AGENT_RULES_DENIED] = rules }
    }

    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[GEMINI_API_KEY] = key }
    }

    suspend fun setOpenAiApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[OPENAI_API_KEY] = key }
    }

    suspend fun setClaudeApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[CLAUDE_API_KEY] = key }
    }

    // Claude auth mode: "api-key" or "subscription" (Claude Max). The
    // subscription path uses ANTHROPIC_AUTH_TOKEN instead of ANTHROPIC_API_KEY.
    suspend fun setClaudeAuthMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[CLAUDE_AUTH_MODE] = mode }
    }

    fun getClaudeAuthMode(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[CLAUDE_AUTH_MODE] ?: "api-key" }

    suspend fun setClaudeSubscriptionToken(token: String) {
        context.dataStore.edit { prefs -> prefs[CLAUDE_SUBSCRIPTION_TOKEN] = token }
    }

    fun getClaudeSubscriptionToken(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[CLAUDE_SUBSCRIPTION_TOKEN] ?: "" }

    suspend fun setOpenRouterApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[OPENROUTER_API_KEY] = key }
    }

    suspend fun setDeepSeekApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[DEEPSEEK_API_KEY] = key }
    }

    suspend fun setGroqApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[GROQ_API_KEY] = key }
    }

    suspend fun setOllamaBaseUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[OLLAMA_BASE_URL] = url }
    }

    suspend fun setZaiToken(token: String) {
        context.dataStore.edit { prefs -> prefs[ZAI_TOKEN] = token }
    }

    /**
     * Set the API key for a provider.
     * Uses the generic per-provider key ("provider_key_<id>").
     * Also writes to the legacy named key for backward compatibility.
     */
    suspend fun setApiKeyForProvider(provider: String, key: String) {
        val providerId = provider.lowercase().trim()
        val genericKey = stringPreferencesKey("provider_key_$providerId")
        context.dataStore.edit { prefs ->
            prefs[genericKey] = key
            // Also write to legacy named key for backward compat
            when (providerId) {
                PROVIDER_GEMINI -> prefs[GEMINI_API_KEY] = key
                PROVIDER_OPENAI -> prefs[OPENAI_API_KEY] = key
                PROVIDER_CLAUDE -> prefs[CLAUDE_API_KEY] = key
                PROVIDER_OPENROUTER -> prefs[OPENROUTER_API_KEY] = key
                PROVIDER_DEEPSEEK -> prefs[DEEPSEEK_API_KEY] = key
                PROVIDER_GROQ -> prefs[GROQ_API_KEY] = key
                PROVIDER_OLLAMA -> prefs[OLLAMA_BASE_URL] = key
                PROVIDER_ZAI, PROVIDER_ZAI_FREE_GLM -> prefs[ZAI_TOKEN] = key
            }
        }
    }

    suspend fun removeApiKeyForProvider(provider: String) {
        setApiKeyForProvider(provider, "")
    }

    fun getDownloadProgress(): Flow<DownloadProgress?> = context.dataStore.data.map { prefs ->
        val url = prefs[DOWNLOAD_URL] ?: return@map null
        val file = prefs[DOWNLOAD_FILE] ?: return@map null
        val bytes = prefs[DOWNLOAD_BYTES] ?: 0L
        val version = prefs[DOWNLOAD_VERSION] ?: ""
        DownloadProgress(url, file, bytes, version)
    }

    suspend fun setDownloadProgress(url: String, filePath: String, bytes: Long, version: String) {
        context.dataStore.edit { prefs ->
            prefs[DOWNLOAD_URL] = url
            prefs[DOWNLOAD_FILE] = filePath
            prefs[DOWNLOAD_BYTES] = bytes
            prefs[DOWNLOAD_VERSION] = version
        }
    }

    suspend fun clearDownloadProgress() {
        context.dataStore.edit { prefs ->
            prefs.remove(DOWNLOAD_URL)
            prefs.remove(DOWNLOAD_FILE)
            prefs.remove(DOWNLOAD_BYTES)
            prefs.remove(DOWNLOAD_VERSION)
        }
    }

    // ── Gateway connections (Hermes edition) ───────────────────────────
    // Lets the user wire external chat gateways (WhatsApp, Discord, Telegram,
    // or a custom webhook) so Hermes can be reached from outside the app.
    // Stored as a JSON array under a single DataStore key.
    val GATEWAY_CONNECTIONS = stringPreferencesKey("gateway_connections")

    data class GatewayConnection(
        val id: String,
        val service: String,   // WhatsApp | Discord | Telegram | Custom
        val endpoint: String,  // phone / server URL / webhook
        val token: String = ""
    ) {
        fun toJson(): String =
            """{"id":"$id","service":"$service","endpoint":"$endpoint","token":"$token"}"""
    }

    fun getGatewayConnections(): Flow<List<GatewayConnection>> =
        context.dataStore.data.map { prefs ->
            prefs[GATEWAY_CONNECTIONS]?.let { parseGateways(it) } ?: emptyList()
        }

    suspend fun addGatewayConnection(c: GatewayConnection) {
        context.dataStore.edit { prefs ->
            val list = prefs[GATEWAY_CONNECTIONS]?.let { parseGateways(it) } ?: emptyList()
            prefs[GATEWAY_CONNECTIONS] = (list + c).joinToString(",") { it.toJson() }
        }
    }

    suspend fun removeGatewayConnection(id: String) {
        context.dataStore.edit { prefs ->
            val list = prefs[GATEWAY_CONNECTIONS]?.let { parseGateways(it) } ?: emptyList()
            prefs[GATEWAY_CONNECTIONS] = list.filter { it.id != id }
                .joinToString(",") { it.toJson() }
        }
    }

    private fun parseGateways(raw: String): List<GatewayConnection> {
        if (raw.isBlank()) return emptyList()
        return raw.split("},").mapNotNull { part ->
            val seg = if (part.endsWith("}")) part else "$part}"
            runCatching {
                val id = seg.substringAfter("\"id\":\"").substringBefore("\"")
                val service = seg.substringAfter("\"service\":\"").substringBefore("\"")
                val endpoint = seg.substringAfter("\"endpoint\":\"").substringBefore("\"")
                val token = seg.substringAfter("\"token\":\"").substringBefore("\"")
                GatewayConnection(id, service, endpoint, token)
            }.getOrNull()
        }
    }
}
