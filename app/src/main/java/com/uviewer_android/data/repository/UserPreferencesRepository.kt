package com.uviewer_android.data.repository

import android.content.Context
import androidx.core.content.edit
import com.uviewer_android.data.llm.LlmPromptPreset
import com.uviewer_android.data.llm.LlmProvider
import com.uviewer_android.data.llm.LlmThinkingLevel
import com.uviewer_android.data.llm.isAllowedFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
        sharedPreferences.getString("doc_background_color", DOC_BG_COMFORT) ?: DOC_BG_COMFORT
    )
    val docBackgroundColor: StateFlow<String> = _docBackgroundColor.asStateFlow()

    private val _docTextColor = MutableStateFlow(
        sharedPreferences.getString("doc_text_color", "comfort") ?: "comfort"
    )
    val docTextColor: StateFlow<String> = _docTextColor.asStateFlow()

    private val _appLanguage = MutableStateFlow(
        sharedPreferences.getString("language", "system") ?: "system"
    )
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _llmProvider = MutableStateFlow(
        LlmProvider.fromStorageKey(sharedPreferences.getString("llm_provider", null))
    )
    val llmProvider: StateFlow<LlmProvider> = _llmProvider.asStateFlow()

    private val llmModelNames: Map<LlmProvider, MutableStateFlow<String>> =
        LlmProvider.entries.associateWith { provider ->
            MutableStateFlow(
                sharedPreferences.getString("llm_model_${provider.storageKey}", provider.defaultModel)
                    ?.ifBlank { provider.defaultModel }
                    ?: provider.defaultModel
            )
        }

    private val llmThinkingLevels: Map<LlmProvider, MutableStateFlow<LlmThinkingLevel>> =
        LlmProvider.entries.associateWith { provider ->
            val saved = LlmThinkingLevel.fromStorageKey(
                sharedPreferences.getString("llm_thinking_${provider.storageKey}", null)
            )
            MutableStateFlow(if (saved.isAllowedFor(provider)) saved else LlmThinkingLevel.DEFAULT)
        }

    private val _llmPromptPresets = MutableStateFlow(loadLlmPromptPresets())
    val llmPromptPresets: StateFlow<List<LlmPromptPreset>> = _llmPromptPresets.asStateFlow()

    private val _llmSystemPrompt = MutableStateFlow(
        sharedPreferences.getString("llm_system_prompt", DEFAULT_LLM_SYSTEM_PROMPT)
            ?: DEFAULT_LLM_SYSTEM_PROMPT
    )
    val llmSystemPrompt: StateFlow<String> = _llmSystemPrompt.asStateFlow()

    private val _selectedLlmPromptPresetId = MutableStateFlow(
        sharedPreferences.getString("llm_selected_prompt_preset", null)
            ?.takeIf { id -> _llmPromptPresets.value.any { it.id == id } }
            ?: _llmPromptPresets.value.firstOrNull { it.prompt == _llmSystemPrompt.value }?.id
    )
    val selectedLlmPromptPresetId: StateFlow<String?> = _selectedLlmPromptPresetId.asStateFlow()

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

    private val _volumeKeyPaging = MutableStateFlow(
        getSafeBoolean("volume_key_paging", true)
    )
    val volumeKeyPaging: StateFlow<Boolean> = _volumeKeyPaging.asStateFlow()

    fun setVolumeKeyPaging(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean("volume_key_paging", enabled)
        }
        _volumeKeyPaging.value = enabled
    }

    private val _maxCacheSize = MutableStateFlow(
        sharedPreferences.getLong("max_cache_size", 1024 * 1024 * 1024L)
    )
    val maxCacheSize: StateFlow<Long> = _maxCacheSize.asStateFlow()

    fun setMaxCacheSize(size: Long) {
        sharedPreferences.edit {
            putLong("max_cache_size", size)
        }
        _maxCacheSize.value = size
    }

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

    fun setAppLanguage(lang: String) {
        sharedPreferences.edit {
            putString("language", lang)
        }
        _appLanguage.value = lang
    }

    fun setLlmProvider(provider: LlmProvider) {
        sharedPreferences.edit {
            putString("llm_provider", provider.storageKey)
        }
        _llmProvider.value = provider

        val level = llmThinkingLevels.getValue(provider).value
        if (!level.isAllowedFor(provider)) {
            setLlmThinkingLevel(provider, LlmThinkingLevel.DEFAULT)
        }
    }

    fun llmModelName(provider: LlmProvider): StateFlow<String> {
        return llmModelNames.getValue(provider).asStateFlow()
    }

    fun getLlmModelName(provider: LlmProvider): String {
        return llmModelNames.getValue(provider).value
    }

    fun setLlmModelName(provider: LlmProvider, modelName: String) {
        val value = modelName.trim()
        val finalValue = value.ifBlank { provider.defaultModel }
        sharedPreferences.edit {
            putString("llm_model_${provider.storageKey}", finalValue)
        }
        llmModelNames.getValue(provider).value = finalValue
    }

    fun llmThinkingLevel(provider: LlmProvider): StateFlow<LlmThinkingLevel> {
        return llmThinkingLevels.getValue(provider).asStateFlow()
    }

    fun getLlmThinkingLevel(provider: LlmProvider): LlmThinkingLevel {
        return llmThinkingLevels.getValue(provider).value
    }

    fun setLlmThinkingLevel(provider: LlmProvider, level: LlmThinkingLevel) {
        val finalLevel = if (level.isAllowedFor(provider)) level else LlmThinkingLevel.DEFAULT
        sharedPreferences.edit {
            putString("llm_thinking_${provider.storageKey}", finalLevel.storageKey)
        }
        llmThinkingLevels.getValue(provider).value = finalLevel
    }

    fun setLlmSystemPrompt(prompt: String) {
        val matchingPresetId = _llmPromptPresets.value.firstOrNull { it.prompt == prompt }?.id
        sharedPreferences.edit {
            putString("llm_system_prompt", prompt)
            if (matchingPresetId == null) {
                remove("llm_selected_prompt_preset")
            } else {
                putString("llm_selected_prompt_preset", matchingPresetId)
            }
        }
        _llmSystemPrompt.value = prompt
        _selectedLlmPromptPresetId.value = matchingPresetId
    }

    fun selectLlmPromptPreset(presetId: String) {
        val preset = _llmPromptPresets.value.firstOrNull { it.id == presetId } ?: return
        sharedPreferences.edit {
            putString("llm_system_prompt", preset.prompt)
            putString("llm_selected_prompt_preset", preset.id)
        }
        _llmSystemPrompt.value = preset.prompt
        _selectedLlmPromptPresetId.value = preset.id
    }

    fun saveLlmPromptPreset(name: String, prompt: String, presetId: String? = null) {
        val cleanName = name.trim()
        val cleanPrompt = prompt.trim()
        if (cleanName.isBlank() || cleanPrompt.isBlank()) return

        val current = _llmPromptPresets.value
        val existing = presetId?.let { id -> current.firstOrNull { it.id == id && !it.isBuiltIn } }
        val savedPreset = (existing ?: LlmPromptPreset(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            prompt = cleanPrompt
        )).copy(name = cleanName, prompt = cleanPrompt)
        val updated = if (existing == null) {
            current + savedPreset
        } else {
            current.map { if (it.id == existing.id) savedPreset else it }
        }
        persistLlmPromptPresets(updated)
        sharedPreferences.edit {
            putString("llm_system_prompt", savedPreset.prompt)
            putString("llm_selected_prompt_preset", savedPreset.id)
        }
        _llmPromptPresets.value = updated
        _llmSystemPrompt.value = savedPreset.prompt
        _selectedLlmPromptPresetId.value = savedPreset.id
    }

    fun deleteLlmPromptPreset(presetId: String) {
        val preset = _llmPromptPresets.value.firstOrNull { it.id == presetId } ?: return
        if (preset.isBuiltIn) return

        val updated = _llmPromptPresets.value.filterNot { it.id == presetId }
        val fallback = updated.firstOrNull { it.isBuiltIn } ?: updated.firstOrNull() ?: return
        persistLlmPromptPresets(updated)
        _llmPromptPresets.value = updated
        selectLlmPromptPreset(fallback.id)
    }

    private fun loadLlmPromptPresets(): List<LlmPromptPreset> {
        val builtIn = LlmPromptPreset(
            id = DEFAULT_LLM_PROMPT_PRESET_ID,
            name = "Explain this word",
            prompt = DEFAULT_LLM_SYSTEM_PROMPT,
            isBuiltIn = true
        )
        val raw = sharedPreferences.getString("llm_prompt_presets", null)
            ?: return listOf(builtIn)
        return try {
            val array = JSONArray(raw)
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val prompt = item.optString("prompt")
                    if (id.isNotBlank() && name.isNotBlank() && prompt.isNotBlank()) {
                        add(
                            LlmPromptPreset(
                                id = id,
                                name = name,
                                prompt = prompt,
                                isBuiltIn = id == DEFAULT_LLM_PROMPT_PRESET_ID
                            )
                        )
                    }
                }
            }
            if (parsed.any { it.id == builtIn.id }) parsed else listOf(builtIn) + parsed
        } catch (_: Exception) {
            listOf(builtIn)
        }
    }

    private fun persistLlmPromptPresets(presets: List<LlmPromptPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("prompt", preset.prompt)
            )
        }
        sharedPreferences.edit {
            putString("llm_prompt_presets", array.toString())
        }
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

    fun getLibrarySortOption(): String {
        return sharedPreferences.getString("library_sort_option", "NAME") ?: "NAME"
    }

    fun setLibrarySortOption(option: String) {
        sharedPreferences.edit().putString("library_sort_option", option).apply()
    }

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
        const val DEFAULT_LLM_SYSTEM_PROMPT = "explain this word"
        const val DEFAULT_LLM_PROMPT_PRESET_ID = "default-explain-this-word"

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
