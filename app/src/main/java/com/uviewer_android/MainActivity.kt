package com.uviewer_android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import com.uviewer_android.data.repository.UserPreferencesRepository
import com.uviewer_android.ui.MainScreen
import com.uviewer_android.ui.theme.UviewerTheme
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                 try {
                     val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.addCategory("android.intent.category.DEFAULT")
                     intent.data = Uri.parse("package:$packageName")
                     startActivity(intent)
                 } catch (e: Exception) {
                     val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                     startActivity(intent)
                 }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }
        setContent {
            val appContainer = (application as UviewerApplication).container
            val themeMode by appContainer.userPreferencesRepository.themeMode.collectAsState()
            val language by appContainer.userPreferencesRepository.language.collectAsState()

            LaunchedEffect(language) {
                val localeList = if (language == UserPreferencesRepository.LANG_SYSTEM) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(language)
                }
                if (AppCompatDelegate.getApplicationLocales() != localeList) {
                    AppCompatDelegate.setApplicationLocales(localeList)
                }
            }

            val darkTheme = when(themeMode) {
                UserPreferencesRepository.THEME_LIGHT -> false
                UserPreferencesRepository.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }

            UviewerTheme(darkTheme = darkTheme) {
                MainScreen()
            }
        }
    }
}
