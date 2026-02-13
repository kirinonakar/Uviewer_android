package com.uviewer_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.uviewer_android.ui.MainScreen
import com.uviewer_android.ui.theme.UviewerTheme

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import com.uviewer_android.data.repository.UserPreferencesRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appContainer = (application as UviewerApplication).container
            val themeMode by appContainer.userPreferencesRepository.themeMode.collectAsState()
            
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
