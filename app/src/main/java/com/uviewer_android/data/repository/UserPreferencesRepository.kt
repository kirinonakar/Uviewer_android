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

    private val _docBackgroundColor = MutableStateFlow(
        sharedPreferences.getString("doc_background_color", "white") ?: "white"
    )
    val docBackgroundColor: StateFlow<String> = _docBackgroundColor.asStateFlow()

    private val _language = MutableStateFlow(
        sharedPreferences.getString("language", "system") ?: "system"
    )
    val language: StateFlow<String> = _language.asStateFlow()

    private val _invertImageControl = MutableStateFlow(
        sharedPreferences.getBoolean("invert_image_control", false)
    )
    val invertImageControl: StateFlow<Boolean> = _invertImageControl.asStateFlow()

    private val _dualPageOrder = MutableStateFlow(
        sharedPreferences.getInt("dual_page_order", 0)
    )
    val dualPageOrder: StateFlow<Int> = _dualPageOrder.asStateFlow()

    private val _persistZoom = MutableStateFlow(
        sharedPreferences.getBoolean("persist_zoom", false)
    )
    val persistZoom: StateFlow<Boolean> = _persistZoom.asStateFlow()

    private val _upscaleFilter = MutableStateFlow(
        sharedPreferences.getBoolean("upscale_filter", false)
    )
    val upscaleFilter: StateFlow<Boolean> = _upscaleFilter.asStateFlow()

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

    fun setDocBackgroundColor(color: String) {
        sharedPreferences.edit {
            putString("doc_background_color", color)
        }
        _docBackgroundColor.value = color
    }

    fun setLanguage(lang: String) {
        sharedPreferences.edit {
            putString("language", lang)
        }
        _language.value = lang
    }

    fun setInvertImageControl(invert: Boolean) {
        sharedPreferences.edit {
            putBoolean("invert_image_control", invert)
        }
        _invertImageControl.value = invert
    }

    fun setDualPageOrder(order: Int) {
        sharedPreferences.edit {
            putInt("dual_page_order", order)
        }
        _dualPageOrder.value = order
    }

    fun setPersistZoom(persist: Boolean) {
        sharedPreferences.edit {
            putBoolean("persist_zoom", persist)
        }
        _persistZoom.value = persist
    }

    fun setUpscaleFilter(upscale: Boolean) {
        sharedPreferences.edit {
            putBoolean("upscale_filter", upscale)
        }
        _upscaleFilter.value = upscale
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val DOC_BG_WHITE = "white"
        const val DOC_BG_SEPIA = "sepia"
        const val DOC_BG_DARK = "dark"

        const val LANG_SYSTEM = "system"
        const val LANG_EN = "en"
        const val LANG_KO = "ko"
        const val LANG_JA = "ja"
    }
}
