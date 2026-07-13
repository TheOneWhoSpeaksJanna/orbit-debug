package com.orbitai.data.local.runtime

import android.content.Context
import com.orbitai.core.logging.FileLogger
import org.json.JSONObject

private const val TAG = "ProviderCatalog"
private const val CATALOG_ASSET = "providers.catalog.json"

/**
 * Dynamic provider catalog — mirrors OpenClaude's integration registry.
 *
 * This replaces the old hardcoded KNOWN_PROVIDERS list in ProvidersViewModel.
 * The catalog is bundled in the APK as assets/providers.catalog.json and
 * contains all 30+ providers that OpenClaude supports (vendors + gateways).
 *
 * At runtime, the app loads this catalog and populates the Providers screen
 * dynamically. If an agent is installed, its own provider configs can be
 * read from the agent's source code to augment this list.
 *
 * Provider fields:
 *  - id: unique identifier (e.g. "anthropic", "openrouter")
 *  - name: display name (e.g. "Anthropic Claude")
 *  - type: "vendor" (direct API) or "gateway" (multi-model)
 *  - transport: API protocol ("anthropic-native", "openai-compatible", etc.)
 *  - baseUrl: default API endpoint
 *  - authMode: "api-key", "oauth", "adc", "token", "none"
 *  - defaultModel: suggested default model
 *  - icon: icon key for BrandIcons (falls back to a generic icon)
 *  - requiresKey: whether the user must configure an API key
 */
data class ProviderEntry(
    val id: String,
    val name: String,
    val type: String,       // "vendor" or "gateway"
    val transport: String,  // "anthropic-native", "openai-compatible", "gemini-native", "local", "bedrock", "vertex"
    val baseUrl: String,
    val authMode: String,   // "api-key", "oauth", "adc", "token", "none"
    val defaultModel: String,
    val icon: String,
    val requiresKey: Boolean
)

object ProviderCatalog {

    private var cachedProviders: List<ProviderEntry>? = null

    /**
     * Load the provider catalog from the bundled JSON asset.
     * Results are cached after first load.
     */
    fun load(context: Context): List<ProviderEntry> {
        cachedProviders?.let { return it }

        val providers = mutableListOf<ProviderEntry>()
        try {
            val text = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val array = json.optJSONArray("providers") ?: return emptyList()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                providers.add(ProviderEntry(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    type = item.optString("type"),
                    transport = item.optString("transport"),
                    baseUrl = item.optString("baseUrl"),
                    authMode = item.optString("authMode"),
                    defaultModel = item.optString("defaultModel"),
                    icon = item.optString("icon"),
                    requiresKey = item.optBoolean("requiresKey", true)
                ))
            }

            FileLogger.i(TAG, "Loaded ${providers.size} providers from catalog")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to load provider catalog: ${e.message}", e)
            // Fallback to a minimal hardcoded list
            return fallbackProviders()
        }

        cachedProviders = providers
        return providers
    }

    /**
     * Get providers grouped by type (vendors first, then gateways).
     */
    fun loadGrouped(context: Context): Pair<List<ProviderEntry>, List<ProviderEntry>> {
        val all = load(context)
        val vendors = all.filter { it.type == "vendor" }
        val gateways = all.filter { it.type == "gateway" }
        return vendors to gateways
    }

    /**
     * Find a provider by ID.
     */
    fun findById(context: Context, id: String): ProviderEntry? {
        return load(context).find { it.id == id }
    }

    private fun fallbackProviders(): List<ProviderEntry> {
        return listOf(
            ProviderEntry("openrouter", "OpenRouter", "gateway", "openai-compatible",
                "https://openrouter.ai/api/v1", "api-key", "tencent/hy3:free", "openrouter", true),
            ProviderEntry("anthropic", "Anthropic Claude", "vendor", "anthropic-native",
                "https://api.anthropic.com", "api-key", "claude-sonnet-4-20250514", "claude", true),
            ProviderEntry("openai", "OpenAI", "vendor", "openai-compatible",
                "https://api.openai.com/v1", "api-key", "gpt-4o", "openai", true),
            ProviderEntry("gemini", "Google Gemini", "vendor", "gemini-native",
                "https://generativelanguage.googleapis.com", "api-key", "gemini-2.0-flash-exp", "gemini", true),
            ProviderEntry("ollama", "Ollama (Local)", "gateway", "local",
                "http://localhost:11434", "none", "llama3.1", "ollama", false)
        )
    }
}
