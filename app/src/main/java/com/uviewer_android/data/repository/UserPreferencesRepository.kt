package com.uviewer_android.data.repository

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferencesRepository(context: Context) {
    private val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        sharedPreferences.getString("theme_mode", "system") ?: "system"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _fontSize = MutableStateFlow(
        sharedPreferences.getInt("font_size", 18)
    )
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _fontFamily = MutableStateFlow(
        sharedPreferences.getString("font_family", "serif") ?: "serif"
    )
    val fontFamily: StateFlow<String> = _fontFamily.asStateFlow()

    fun setThemeMode(mode: String) {
        sharedPreferences.edit {
            putString("theme_mode", mode)
        }
        _themeMode.value = mode
    }

    fun setFontSize(size: Int) {
        sharedPreferences.edit {
            putInt("font_size", size)
        }
        _fontSize.value = size
    }

    fun setFontFamily(family: String) {
        sharedPreferences.edit {
            putString("font_family", family)
        }
        _fontFamily.value = family
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
