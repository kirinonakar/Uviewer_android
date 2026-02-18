package com.uviewer_android

import android.content.Context
import com.uviewer_android.data.AppDatabase
import com.uviewer_android.data.repository.CredentialsManager
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository

class AppContainer(private val context: Context) {

    val database by lazy {
        AppDatabase.getDatabase(context)
    }

    val credentialsManager by lazy {
        CredentialsManager(context)
    }

    val fileRepository by lazy {
        FileRepository()
    }

    val webDavRepository by lazy {
        WebDavRepository(database.webDavServerDao(), credentialsManager)
    }

    val userPreferencesRepository by lazy {
        com.uviewer_android.data.repository.UserPreferencesRepository(context)
    }

    val cacheManager by lazy {
        com.uviewer_android.data.utils.CacheManager(context, userPreferencesRepository)
    }

    // ViewModels factories or direct injection
    // ...
}
