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

    private val _docTextColor = MutableStateFlow(
        sharedPreferences.getString("doc_text_color", "black") ?: "black"
    )
    val docTextColor: StateFlow<String> = _docTextColor.asStateFlow()

    private val _language = MutableStateFlow(
        sharedPreferences.getString("language", "system") ?: "system"
    )
    val language: StateFlow<String> = _language.asStateFlow()
    private val _customDocBackgroundColor = MutableStateFlow(
        sharedPreferences.getString("custom_doc_background_color", "#FFFFFF") ?: "#FFFFFF"
    )
    val customDocBackgroundColor: StateFlow<String> = _customDocBackgroundColor.asStateFlow()

    private val _customDocTextColor = MutableStateFlow(
        sharedPreferences.getString("custom_doc_text_color", "#000000") ?: "#000000"
    )
    val customDocTextColor: StateFlow<String> = _customDocTextColor.asStateFlow()


    private val _invertImageControl = MutableStateFlow(
        getSafeBoolean("invert_image_control", false)
    )
    val invertImageControl: StateFlow<Boolean> = _invertImageControl.asStateFlow()

    private val _dualPageOrder = MutableStateFlow(
        sharedPreferences.getInt("dual_page_order", 0)
    )
    val dualPageOrder: StateFlow<Int> = _dualPageOrder.asStateFlow()

    private val _persistZoom = MutableStateFlow(
        getSafeBoolean("persist_zoom", false)
    )
    val persistZoom: StateFlow<Boolean> = _persistZoom.asStateFlow()

    private val _sharpeningAmount = MutableStateFlow(
        sharedPreferences.getInt("sharpening_amount", 0)
    )
    val sharpeningAmount: StateFlow<Int> = _sharpeningAmount.asStateFlow()

    private val _imageViewMode = MutableStateFlow(
        sharedPreferences.getInt("image_view_mode", 0)
    )
    val imageViewMode: StateFlow<Int> = _imageViewMode.asStateFlow()


    private val _sideMargin = MutableStateFlow(
        sharedPreferences.getInt("side_margin", 8)
    )
    val sideMargin: StateFlow<Int> = _sideMargin.asStateFlow()

    private val _subtitleEnabled = MutableStateFlow(
        getSafeBoolean("subtitle_enabled", true)
    )
    val subtitleEnabled: StateFlow<Boolean> = _subtitleEnabled.asStateFlow()

    private val _isVerticalReading = MutableStateFlow(
        getSafeBoolean("is_vertical_reading", false)
    )
    val isVerticalReading: StateFlow<Boolean> = _isVerticalReading.asStateFlow()

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

    fun setDocTextColor(color: String) {
        sharedPreferences.edit {
            putString("doc_text_color", color)
        }
        _docTextColor.value = color
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

    fun setSharpeningAmount(amount: Int) {
        sharedPreferences.edit {
            putInt("sharpening_amount", amount)
        }
        _sharpeningAmount.value = amount
    }


    fun setSideMargin(margin: Int) {
        sharedPreferences.edit {
            putInt("side_margin", margin)
        }
        _sideMargin.value = margin
    }

    fun setSubtitleEnabled(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean("subtitle_enabled", enabled)
        }
        _subtitleEnabled.value = enabled
    }

    fun setIsVerticalReading(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean("is_vertical_reading", enabled)
        }
        _isVerticalReading.value = enabled
    }

    fun setCustomDocBackgroundColor(color: String) {
        sharedPreferences.edit {
            putString("custom_doc_background_color", color)
        }
        _customDocBackgroundColor.value = color
    }

    fun setCustomDocTextColor(color: String) {
        sharedPreferences.edit {
            putString("custom_doc_text_color", color)
        }
        _customDocTextColor.value = color
    }

    fun setImageViewMode(mode: Int) {
        sharedPreferences.edit {
            putInt("image_view_mode", mode)
        }
        _imageViewMode.value = mode
    }



    fun setLastLibraryPath(path: String) {
        sharedPreferences.edit().putString("last_library_path", path).apply()
    }
    fun getLastLibraryPath(): String? = sharedPreferences.getString("last_library_path", null)

    fun setLastLocalPath(path: String) {
        sharedPreferences.edit().putString("last_local_path", path).apply()
    }
    fun getLastLocalPath(): String? = sharedPreferences.getString("last_local_path", null)

    fun setLastWebDavPath(path: String) {
        sharedPreferences.edit().putString("last_webdav_path", path).apply()
    }
    fun getLastWebDavPath(): String? = sharedPreferences.getString("last_webdav_path", null)

    fun setLastLibraryTab(tab: Int) {
        sharedPreferences.edit().putInt("last_library_tab", tab).apply()
    }
    fun getLastLibraryTab(): Int = sharedPreferences.getInt("last_library_tab", 0)

    fun setLastServerId(id: Int) {
        sharedPreferences.edit().putInt("last_server_id", id).apply()
    }
    fun getLastServerId(): Int = sharedPreferences.getInt("last_server_id", -1)

    fun setLibraryViewMode(isGrid: Boolean) {
        sharedPreferences.edit().putBoolean("library_view_mode", isGrid).apply()
    }
    fun getLibraryViewMode(): Boolean = getSafeBoolean("library_view_mode", false)

    private fun getSafeBoolean(key: String, defaultValue: Boolean): Boolean {
        return try {
            sharedPreferences.getBoolean(key, defaultValue)
        } catch (e: Exception) {
            val value = sharedPreferences.all[key]
            if (value is Int) {
                val boolValue = value != 0
                sharedPreferences.edit().putBoolean(key, boolValue).apply()
                boolValue
            } else {
                defaultValue
            }
        }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val DOC_BG_WHITE = "white"
        const val DOC_BG_SEPIA = "sepia"
        const val DOC_BG_DARK = "dark"
        const val DOC_BG_COMFORT = "comfort"
        const val DOC_BG_CUSTOM = "custom"


        const val LANG_SYSTEM = "system"
        const val LANG_EN = "en"
        const val LANG_KO = "ko"
        const val LANG_JA = "ja"
    }
}
