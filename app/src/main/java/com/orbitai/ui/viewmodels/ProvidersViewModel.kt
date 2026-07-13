package com.orbitai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.orbitai.OrbitAiApplication
import com.orbitai.core.config.ApiConfig
import com.orbitai.core.logging.FileLogger
import com.orbitai.core.logging.CoroutineExceptionHandlerFactory
import com.orbitai.data.local.prefs.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
private const val NO_API_KEY_MSG = "No API key configured"
private const val INVALID_API_KEY = "Invalid API key"
private const val CLIENT_ERROR = "Client error"
private const val SERVER_ERROR = "Server error"
private const val UNEXPECTED_CODE = "Unexpected"
private const val OLLAMA_PROVIDER = "Ollama"

data class ProviderConfig(
    val name: String,
    val apiKeyConfigured: Boolean,
    val requiresKey: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.Idle
)
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Verifying : ConnectionState()
    data object Connected : ConnectionState()
    data class Unauthorized(val message: String) : ConnectionState()
    data object Offline : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class ProvidersViewModel(
    private val prefsManager: PreferencesManager,
    private val context: android.content.Context
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandlerFactory.create("ProvidersViewModel")

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    // The provider currently active for chat (display name), read from DataStore.
    private val _selectedProvider = MutableStateFlow("")
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    private val _editingProvider = MutableStateFlow<String?>(null)
    val editingProvider: StateFlow<String?> = _editingProvider.asStateFlow()

    private val _editApiKeyValue = MutableStateFlow("")
    val editApiKeyValue: StateFlow<String> = _editApiKeyValue.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiConfig.HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = CONTENT_TYPE_JSON.toMediaType()

    init {
        loadProviders()
    }

    /**
     * Load providers dynamically from the bundled providers.catalog.json.
     * This replaces the old hardcoded KNOWN_PROVIDERS list and shows all
     * 30+ providers that OpenClaude supports.
     */
    private fun loadProviders() {
        viewModelScope.launch(exceptionHandler) {
            // Read the active provider BEFORE publishing the list, so it is stable
            // by the time the list items compose/animate (avoids a second emission
            // mid-animation that could interrupt item entrance animations).
            _selectedProvider.value = prefsManager.selectedProvider.firstOrNull().orEmpty()
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val configs = catalog.map { entry ->
                val key = prefsManager.getApiKeyForProvider(entry.id).firstOrNull()
                ProviderConfig(
                    name = entry.name,
                    apiKeyConfigured = !key.isNullOrBlank(),
                    requiresKey = entry.requiresKey
                )
            }
            _providers.value = configs
            FileLogger.i("ProvidersViewModel", "Loaded ${configs.size} providers from catalog")
        }
    }

    /**
     * Set [providerName] as the active provider for chat and persist to DataStore.
     * Lets the user switch providers (e.g. Gemini -> OpenRouter) without re-running
     * the setup wizard.
     *
     * Also resets the selected model to the new provider's catalog default, because
     * a model name valid for the old provider (e.g. a Gemini model) is usually
     * rejected by a different provider (e.g. OpenRouter). Without this reset the
     * first chat after a switch would fail with an upstream "unknown model" error.
     */
    fun setActiveProvider(providerName: String) {
        viewModelScope.launch(exceptionHandler) {
            prefsManager.setSelectedProvider(providerName)
            _selectedProvider.value = providerName
            // Reset model to the new provider's default so chat doesn't send a
            // stale, incompatible model name to the newly-selected provider.
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val entry = catalog.find { it.name == providerName }
            val defaultModel = entry?.defaultModel?.takeIf { it.isNotBlank() }
            if (defaultModel != null) {
                prefsManager.setSelectedModel(defaultModel)
                FileLogger.i("ProvidersViewModel", "Active provider set", "provider=$providerName model=$defaultModel")
            } else {
                FileLogger.i("ProvidersViewModel", "Active provider set", "provider=$providerName model=unchanged")
            }
        }
    }

    private fun loadApiKeyStatus() {
        viewModelScope.launch(exceptionHandler) {
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val updated = _providers.value.map { provider ->
                val entry = catalog.find { it.name == provider.name }
                val key = if (entry != null) {
                    prefsManager.getApiKeyForProvider(entry.id).firstOrNull()
                } else null
                provider.copy(apiKeyConfigured = !key.isNullOrBlank())
            }
            _providers.value = updated
        }
    }

    fun verifyConnection(providerName: String) {
        viewModelScope.launch(exceptionHandler) {
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val entry = catalog.find { it.name == providerName }
            val providerId = entry?.id ?: providerName

            if (providerId == "ollama") {
                updateProviderState(providerName, ConnectionState.Verifying)
                val baseUrl = prefsManager.getApiKeyForProvider(providerId).firstOrNull().orEmpty()
                val result = withContext(Dispatchers.IO) {
                    performHealthCheck(providerName, baseUrl)
                }
                updateProviderState(providerName, result)
                return@launch
            }

            val keyFlow = prefsManager.getApiKeyForProvider(providerId)
            val key = keyFlow.firstOrNull()

            if (key.isNullOrBlank()) {
                updateProviderState(providerName, ConnectionState.Unauthorized(NO_API_KEY_MSG))
                return@launch
            }

            updateProviderState(providerName, ConnectionState.Verifying)

            val result = withContext(Dispatchers.IO) {
                performHealthCheck(providerName, key)
            }

            updateProviderState(providerName, result)
        }
    }

    fun startEditApiKey(providerName: String) {
        viewModelScope.launch(exceptionHandler) {
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val entry = catalog.find { it.name == providerName }
            val providerId = entry?.id ?: providerName
            val currentKey = prefsManager.getApiKeyForProvider(providerId).firstOrNull() ?: ""
            _editApiKeyValue.value = currentKey
            _editingProvider.value = providerName
        }
    }

    fun saveApiKey() {
        val providerName = _editingProvider.value ?: return
        viewModelScope.launch(exceptionHandler) {
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val entry = catalog.find { it.name == providerName }
            val providerId = entry?.id ?: providerName
            prefsManager.setApiKeyForProvider(providerId, _editApiKeyValue.value)
            _editingProvider.value = null
            _editApiKeyValue.value = ""
            loadApiKeyStatus()
        }
    }

    fun removeApiKey() {
        val providerName = _editingProvider.value ?: return
        viewModelScope.launch(exceptionHandler) {
            val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
            val entry = catalog.find { it.name == providerName }
            val providerId = entry?.id ?: providerName
            prefsManager.removeApiKeyForProvider(providerId)
            _editingProvider.value = null
            _editApiKeyValue.value = ""
            loadApiKeyStatus()
        }
    }

    fun cancelEditApiKey() {
        _editingProvider.value = null
        _editApiKeyValue.value = ""
    }

    fun updateEditApiKey(value: String) {
        _editApiKeyValue.value = value
    }

    private suspend fun performHealthCheck(name: String, apiKey: String): ConnectionState {
        return try {
            val request = buildHealthCheckRequest(name, apiKey)
            val response = httpClient.newCall(request).execute()

            return when (response.code) {
                in 200..299 -> {
                    response.close()
                    ConnectionState.Connected
                }
                401, 403 -> {
                    response.close()
                    ConnectionState.Unauthorized("$INVALID_API_KEY (${response.code})")
                }
                in 400..499 -> {
                    response.close()
                    ConnectionState.Error("$CLIENT_ERROR: ${response.code}")
                }
                in 500..599 -> {
                    response.close()
                    ConnectionState.Error("$SERVER_ERROR: ${response.code}")
                }
                else -> {
                    response.close()
                    ConnectionState.Error("$UNEXPECTED_CODE: ${response.code}")
                }
            }
        } catch (e: SocketTimeoutException) {
            ConnectionState.Offline
        } catch (e: ConnectException) {
            ConnectionState.Offline
        } catch (e: UnknownHostException) {
            ConnectionState.Offline
        } catch (e: Exception) {
            ConnectionState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Build a health-check request per provider. All URLs come from [ApiConfig]
     * so adding/changing a provider only needs an [ApiConfig] entry + a branch
     * here — no scattered string literals anywhere else in the codebase.
     */
    private fun buildHealthCheckRequest(name: String, apiKey: String): Request {
        return when (name) {
            "Claude" -> Request.Builder()
                .url(ApiConfig.CLAUDE_API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ApiConfig.CLAUDE_API_VERSION)
                .header("content-type", "application/json")
                .post(CLAUDE_HEALTH_CHECK_BODY.toRequestBody(jsonMediaType))
                .build()

            "OpenAI" -> Request.Builder()
                .url(ApiConfig.OPENAI_MODELS_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            "Gemini" -> Request.Builder()
                .url("${ApiConfig.GEMINI_BASE_URL}v1beta/models?key=$apiKey")
                .get()
                .build()

            "OpenRouter" -> Request.Builder()
                .url(ApiConfig.OPENROUTER_KEY_CHECK_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            "DeepSeek" -> Request.Builder()
                .url(ApiConfig.DEEPSEEK_MODELS_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            "Groq" -> Request.Builder()
                .url(ApiConfig.GROQ_MODELS_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            OLLAMA_PROVIDER, "Ollama (Local)" -> {
                val base = apiKey.trim().trimEnd('/').ifBlank { ApiConfig.OLLAMA_DEFAULT_BASE_URL }
                Request.Builder()
                    .url("$base${ApiConfig.OLLAMA_TAGS_PATH}")
                    .get()
                    .build()
            }

            "Z.AI (Free GLM)", "Z.AI" -> {
                // Z.AI health check: send a minimal chat completion request.
                // The API key is "Z.ai" (public), the user's token goes in X-Token.
                Request.Builder()
                    .url("https://internal-api.z.ai/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer Z.ai")
                    .header("X-Z-AI-From", "Z")
                    .header("X-Token", apiKey)
                    .post("""{"model":"glm-4.6","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"thinking":{"type":"disabled"}}""".toRequestBody(jsonMediaType))
                    .build()
            }

            else -> {
                // For dynamic catalog providers, look up the baseUrl and do
                // a generic OpenAI-compatible /models GET with Bearer auth.
                // This works for xAI, Venice, Fireworks, Moonshot, Together,
                // Mistral, NVIDIA, DashScope, Kimi, GitHub Models, etc.
                val catalog = com.orbitai.data.local.runtime.ProviderCatalog.load(context)
                val entry = catalog.find { it.name == name }
                if (entry != null && entry.baseUrl.isNotBlank()) {
                    var base = entry.baseUrl.trimEnd('/')
                    if (!base.endsWith("/v1")) base = "$base/v1"
                    Request.Builder()
                        .url("$base/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                } else {
                    throw IllegalArgumentException("Unknown provider: $name")
                }
            }
        }
    }

    private fun updateProviderState(providerName: String, state: ConnectionState) {
        _providers.value = _providers.value.map {
            if (it.name == providerName) it.copy(connectionState = state) else it
        }
    }

    companion object {
        private const val CLAUDE_HEALTH_CHECK_BODY =
            """{"model":"${ApiConfig.CLAUDE_HEALTH_CHECK_MODEL}","max_tokens":1,"messages":[{"role":"user","content":"."}]}"""

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[APPLICATION_KEY]) as OrbitAiApplication
                return ProvidersViewModel(application.container.prefsManager, application) as T
            }
        }
    }
}
