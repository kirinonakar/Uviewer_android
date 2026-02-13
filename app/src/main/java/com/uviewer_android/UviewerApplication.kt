package com.uviewer_android

import android.app.Application
import com.uviewer_android.data.AppDatabase
import com.uviewer_android.data.repository.CredentialsManager

class UviewerApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
