package com.uviewer_android.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
}
