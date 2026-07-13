package com.orbitai.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(providerName: String): String? =
        prefs.getString(apiKeyKey(providerName), null)

    fun setApiKey(providerName: String, key: String) {
        prefs.edit().putString(apiKeyKey(providerName), key).apply()
    }

    fun clearApiKey(providerName: String) {
        prefs.edit().remove(apiKeyKey(providerName)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREF_NAME = "omniclaw_secure_prefs"
        private const val API_KEY_SUFFIX = "_api_key"

        private fun apiKeyKey(providerName: String) = "${providerName.lowercase()}$API_KEY_SUFFIX"
    }
}
