package com.uviewer_android.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.uviewer_android.data.llm.LlmProvider

class CredentialsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "uviewer_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(serverId: Int, username: String, password: String) {
        sharedPreferences.edit()
            .putString("username_$serverId", username)
            .putString("password_$serverId", password)
            .apply()
    }

    fun getUsername(serverId: Int): String? {
        return sharedPreferences.getString("username_$serverId", null)
    }

    fun getPassword(serverId: Int): String? {
        return sharedPreferences.getString("password_$serverId", null)
    }

    fun clearCredentials(serverId: Int) {
        sharedPreferences.edit()
            .remove("username_$serverId")
            .remove("password_$serverId")
            .apply()
    }

    /**
     * LLM API keys are kept in the same encrypted preferences file as WebDAV
     * passwords and are never copied into the regular user preferences.
     */
    fun saveLlmApiKey(provider: LlmProvider, apiKey: String) {
        val editor = sharedPreferences.edit()
        if (apiKey.isBlank()) {
            editor.remove(llmApiKeyKey(provider))
        } else {
            editor.putString(llmApiKeyKey(provider), apiKey.trim())
        }
        editor.apply()
    }

    fun getLlmApiKey(provider: LlmProvider): String? {
        return sharedPreferences.getString(llmApiKeyKey(provider), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun hasLlmApiKey(provider: LlmProvider): Boolean {
        return !getLlmApiKey(provider).isNullOrBlank()
    }

    fun clearLlmApiKey(provider: LlmProvider) {
        sharedPreferences.edit().remove(llmApiKeyKey(provider)).apply()
    }

    private fun llmApiKeyKey(provider: LlmProvider): String {
        return "llm_api_key_${provider.storageKey}"
    }
}
