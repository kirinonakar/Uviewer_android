package com.uviewer_android.data.backup

import com.uviewer_android.data.llm.LlmProvider
import com.uviewer_android.data.llm.LlmThinkingLevel
import com.uviewer_android.data.llm.isAllowedFor
import com.uviewer_android.data.model.LibraryViewMode
import com.uviewer_android.data.model.SortOption
import com.uviewer_android.data.repository.UserPreferencesRepository
import org.json.JSONArray
import org.json.JSONObject

/** Only portable user settings belong here. Credentials and navigation/session state never do. */
object BackupPreferences {
    val defaults: Map<String, Any> = buildMap {
        put("theme_mode", "system")
        put("font_size", 18)
        put("font_family", "serif")
        put("doc_background_color", "comfort")
        put("doc_text_color", "comfort")
        put("language", "system")
        put("custom_doc_background_color", "#FFFFFF")
        put("custom_doc_text_color", "#000000")
        put("invert_image_control", false)
        put("dual_page_order", 0)
        put("persist_zoom", false)
        put("sharpening_amount", 0)
        put("image_view_mode", 0)
        put("side_margin", 8)
        put("subtitle_enabled", true)
        put("is_vertical_reading", false)
        put("volume_key_paging", true)
        put("max_cache_size", 1024 * 1024 * 1024L)
        put("library_view_layout", LibraryViewMode.LIST.name)
        put("library_sort_option", SortOption.NAME.name)
        put("llm_provider", LlmProvider.GOOGLE.storageKey)
        put("llm_system_prompt", UserPreferencesRepository.DEFAULT_LLM_SYSTEM_PROMPT)
        put("llm_prompt_presets", "[]")
        put("llm_selected_prompt_preset", "")
        LlmProvider.entries.forEach {
            put("llm_model_${it.storageKey}", it.defaultModel)
            put("llm_thinking_${it.storageKey}", LlmThinkingLevel.DEFAULT.storageKey)
        }
    }

    fun validate(json: JSONObject): Map<String, Any> {
        val values = defaults.mapValues { (key, default) ->
            val value = if (json.has(key)) json.get(key) else default
            when (default) {
                is String -> (value as? String)?.also { require(it.length <= 1_000_000) }
                is Boolean -> value as? Boolean
                is Int -> (value as? Number)?.let {
                    it.toLong().takeIf { n -> n in Int.MIN_VALUE..Int.MAX_VALUE && it.toDouble() == n.toDouble() }?.toInt()
                }
                is Long -> (value as? Number)?.let {
                    it.toLong().takeIf { n -> it.toDouble() == n.toDouble() }
                }
                else -> null
            } ?: throw IllegalArgumentException("Invalid setting: $key")
        }
        fun oneOf(key: String, options: Collection<String>) = require(values[key] in options) { "Invalid setting: $key" }
        fun range(key: String, range: IntRange) = require(values[key] as Int in range) { "Invalid setting: $key" }
        oneOf("theme_mode", listOf("system", "light", "dark"))
        oneOf("language", listOf("system", "en", "ko", "ja"))
        oneOf("font_family", listOf("serif", "sans-serif", "monospace"))
        oneOf("doc_background_color", listOf("white", "sepia", "dark", "comfort", "custom"))
        oneOf("doc_text_color", listOf("black", "white", "comfort", "custom"))
        oneOf("library_view_layout", LibraryViewMode.entries.map { it.name })
        oneOf("library_sort_option", SortOption.entries.map { it.name })
        oneOf("llm_provider", LlmProvider.entries.map { it.storageKey })
        range("font_size", 12..36)
        range("side_margin", 0..40)
        range("sharpening_amount", 0..10)
        range("dual_page_order", 0..1)
        range("image_view_mode", 0..2)
        require(values["max_cache_size"] as Long > 0)
        for (key in listOf("custom_doc_background_color", "custom_doc_text_color")) {
            require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(values[key] as String))
        }
        LlmProvider.entries.forEach { provider ->
            val thinking = values["llm_thinking_${provider.storageKey}"]
            require(LlmThinkingLevel.entries.any { it.storageKey == thinking && it.isAllowedFor(provider) })
            require((values["llm_model_${provider.storageKey}"] as String).isNotBlank())
        }
        val presets = JSONArray(values.getValue("llm_prompt_presets") as String)
        val ids = mutableSetOf<String>()
        for (index in 0 until presets.length()) {
            val preset = presets.getJSONObject(index)
            for (key in listOf("id", "name", "prompt")) {
                require((preset.get(key) as? String)?.isNotBlank() == true)
            }
            require(ids.add(preset.getString("id")))
        }
        val selected = values["llm_selected_prompt_preset"] as String
        require(selected.isEmpty() || selected == UserPreferencesRepository.DEFAULT_LLM_PROMPT_PRESET_ID || selected in ids)
        return values
    }
}
