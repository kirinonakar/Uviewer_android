package com.uviewer_android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.llm.LlmPromptPreset
import com.uviewer_android.data.llm.LlmClient
import com.uviewer_android.data.llm.LlmModelOption
import com.uviewer_android.data.llm.LlmProvider
import com.uviewer_android.data.llm.LlmThinkingLevel
import com.uviewer_android.data.WebDavServer
import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.repository.CredentialsManager
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SettingsViewModel(
    application: Application,
    private val webDavServerDao: WebDavServerDao,
    private val credentialsManager: CredentialsManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val llmClient: LlmClient
) : AndroidViewModel(application) {

    private val _cacheSize = MutableStateFlow("0 B")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    val llmProvider: StateFlow<LlmProvider> = userPreferencesRepository.llmProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LlmProvider.GOOGLE)

    private val _llmModelName = MutableStateFlow(
        userPreferencesRepository.getLlmModelName(userPreferencesRepository.llmProvider.value)
    )
    val llmModelName: StateFlow<String> = _llmModelName.asStateFlow()

    private val _llmThinkingLevel = MutableStateFlow(
        userPreferencesRepository.getLlmThinkingLevel(userPreferencesRepository.llmProvider.value)
    )
    val llmThinkingLevel: StateFlow<LlmThinkingLevel> = _llmThinkingLevel.asStateFlow()

    private val _llmApiKeyConfigured = MutableStateFlow(
        credentialsManager.hasLlmApiKey(userPreferencesRepository.llmProvider.value)
    )
    val llmApiKeyConfigured: StateFlow<Boolean> = _llmApiKeyConfigured.asStateFlow()

    private val _llmModels = MutableStateFlow<List<LlmModelOption>>(emptyList())
    val llmModels: StateFlow<List<LlmModelOption>> = _llmModels.asStateFlow()

    private val _llmModelsLoading = MutableStateFlow(false)
    val llmModelsLoading: StateFlow<Boolean> = _llmModelsLoading.asStateFlow()

    private val _llmModelsError = MutableStateFlow<String?>(null)
    val llmModelsError: StateFlow<String?> = _llmModelsError.asStateFlow()

    private var llmModelsJob: kotlinx.coroutines.Job? = null

    val llmPromptPresets: StateFlow<List<LlmPromptPreset>> = userPreferencesRepository.llmPromptPresets
    val selectedLlmPromptPresetId: StateFlow<String?> = userPreferencesRepository.selectedLlmPromptPresetId
    val llmSystemPrompt: StateFlow<String> = userPreferencesRepository.llmSystemPrompt

    init {
        updateCacheSize()
        viewModelScope.launch {
            userPreferencesRepository.llmProvider.collect { provider ->
                _llmModelName.value = userPreferencesRepository.getLlmModelName(provider)
                _llmThinkingLevel.value = userPreferencesRepository.getLlmThinkingLevel(provider)
                _llmApiKeyConfigured.value = credentialsManager.hasLlmApiKey(provider)
                clearLlmModels()
            }
        }
    }

    val servers: StateFlow<List<WebDavServer>> = webDavServerDao.getAllServers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val themeMode: StateFlow<String> = userPreferencesRepository.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "system"
        )

    val fontSize: StateFlow<Int> = userPreferencesRepository.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18)

    val fontFamily: StateFlow<String> = userPreferencesRepository.fontFamily
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "serif")

    val docBackgroundColor: StateFlow<String> = userPreferencesRepository.docBackgroundColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.DOC_BG_COMFORT)

    val appLanguage: StateFlow<String> = userPreferencesRepository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.LANG_SYSTEM)

    private val _systemLanguage = MutableStateFlow(getCurrentlySetSystemLanguage())
    val systemLanguage: StateFlow<String> = _systemLanguage.asStateFlow()

    private fun getCurrentlySetSystemLanguage(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) {
            UserPreferencesRepository.LANG_SYSTEM
        } else {
            locales.get(0)?.language ?: UserPreferencesRepository.LANG_SYSTEM
        }
    }

    val docTextColor: StateFlow<String> = userPreferencesRepository.docTextColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "comfort")


    val customDocBackgroundColor: StateFlow<String> = userPreferencesRepository.customDocBackgroundColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "#FFFFFF")

    val customDocTextColor: StateFlow<String> = userPreferencesRepository.customDocTextColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "#000000")


    val invertImageControl: StateFlow<Boolean> = userPreferencesRepository.invertImageControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dualPageOrder: StateFlow<Int> = userPreferencesRepository.dualPageOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val persistZoom: StateFlow<Boolean> = userPreferencesRepository.persistZoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sharpeningAmount: StateFlow<Int> = userPreferencesRepository.sharpeningAmount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val imageViewMode: StateFlow<Int> = userPreferencesRepository.imageViewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val volumeKeyPaging: StateFlow<Boolean> = userPreferencesRepository.volumeKeyPaging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val maxCacheSize: StateFlow<Long> = userPreferencesRepository.maxCacheSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1024 * 1024 * 1024L)



    fun setThemeMode(mode: String) {
        userPreferencesRepository.setThemeMode(mode)
    }

    fun setFontSize(size: Int) {
        userPreferencesRepository.setFontSize(size)
    }

    fun setFontFamily(family: String) {
        userPreferencesRepository.setFontFamily(family)
    }

    fun setDocBackgroundColor(color: String) {
        userPreferencesRepository.setDocBackgroundColor(color)
    }

    fun setAppLanguage(lang: String) {
        userPreferencesRepository.setAppLanguage(lang)
    }

    fun setLlmProvider(provider: LlmProvider) {
        userPreferencesRepository.setLlmProvider(provider)
        _llmModelName.value = userPreferencesRepository.getLlmModelName(provider)
        _llmThinkingLevel.value = userPreferencesRepository.getLlmThinkingLevel(provider)
        _llmApiKeyConfigured.value = credentialsManager.hasLlmApiKey(provider)
        clearLlmModels()
    }

    fun setLlmModelName(modelName: String) {
        val provider = userPreferencesRepository.llmProvider.value
        userPreferencesRepository.setLlmModelName(provider, modelName)
        _llmModelName.value = userPreferencesRepository.getLlmModelName(provider)
    }

    fun loadLlmModels() {
        llmModelsJob?.cancel()
        _llmModelsLoading.value = true
        _llmModelsError.value = null
        llmModelsJob = viewModelScope.launch {
            try {
                val models = llmClient.listModels()
                _llmModels.value = models
                if (models.isEmpty()) {
                    _llmModelsError.value = "No models were returned by the provider."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _llmModels.value = emptyList()
                _llmModelsError.value = error.message ?: "Failed to load models."
            } finally {
                _llmModelsLoading.value = false
            }
        }
    }

    fun clearLlmModels() {
        llmModelsJob?.cancel()
        llmModelsJob = null
        _llmModels.value = emptyList()
        _llmModelsError.value = null
        _llmModelsLoading.value = false
    }

    fun setLlmThinkingLevel(level: LlmThinkingLevel) {
        val provider = userPreferencesRepository.llmProvider.value
        userPreferencesRepository.setLlmThinkingLevel(provider, level)
        _llmThinkingLevel.value = userPreferencesRepository.getLlmThinkingLevel(provider)
    }

    fun saveLlmApiKey(apiKey: String) {
        credentialsManager.saveLlmApiKey(userPreferencesRepository.llmProvider.value, apiKey)
        _llmApiKeyConfigured.value = credentialsManager.hasLlmApiKey(userPreferencesRepository.llmProvider.value)
    }

    fun clearLlmApiKey() {
        credentialsManager.clearLlmApiKey(userPreferencesRepository.llmProvider.value)
        _llmApiKeyConfigured.value = false
    }

    fun setLlmSystemPrompt(prompt: String) {
        userPreferencesRepository.setLlmSystemPrompt(prompt)
    }

    fun selectLlmPromptPreset(presetId: String) {
        userPreferencesRepository.selectLlmPromptPreset(presetId)
    }

    fun saveLlmPromptPreset(name: String, prompt: String) {
        userPreferencesRepository.saveLlmPromptPreset(name, prompt)
    }

    fun deleteLlmPromptPreset(presetId: String) {
        userPreferencesRepository.deleteLlmPromptPreset(presetId)
    }

    fun setSystemLanguage(lang: String) {
        val localeList = if (lang == UserPreferencesRepository.LANG_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
        _systemLanguage.value = lang
    }

    fun setDocTextColor(color: String) {
        userPreferencesRepository.setDocTextColor(color)
    }


    fun setInvertImageControl(invert: Boolean) {
        userPreferencesRepository.setInvertImageControl(invert)
    }

    fun setDualPageOrder(order: Int) {
        userPreferencesRepository.setDualPageOrder(order)
    }

    fun setPersistZoom(persist: Boolean) {
        userPreferencesRepository.setPersistZoom(persist)
    }

    fun setSharpeningAmount(amount: Int) {
        userPreferencesRepository.setSharpeningAmount(amount)
    }

    fun setCustomDocBackgroundColor(color: String) {
        userPreferencesRepository.setCustomDocBackgroundColor(color)
    }

    fun setCustomDocTextColor(color: String) {
        userPreferencesRepository.setCustomDocTextColor(color)
    }

    fun setImageViewMode(mode: Int) {
        userPreferencesRepository.setImageViewMode(mode)
    }

    fun setVolumeKeyPaging(enabled: Boolean) {
        userPreferencesRepository.setVolumeKeyPaging(enabled)
    }

    fun setMaxCacheSize(size: Long) {
        userPreferencesRepository.setMaxCacheSize(size)
    }

    fun updateCacheSize() {
        viewModelScope.launch {
            val size = calculateCacheSize()
            _cacheSize.value = FileRepository.formatFileSize(size)
        }
    }

    private fun calculateCacheSize(): Long {
        val context = getApplication<Application>()
        var size = dirSize(context.cacheDir)
        context.externalCacheDir?.let { size += dirSize(it) }
        context.getExternalFilesDir("cache")?.let { size += dirSize(it) }
        return size
    }

    private fun dirSize(dir: File): Long {
        var size: Long = 0
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    fun clearCache() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()
            context.getExternalFilesDir("cache")?.deleteRecursively()
            updateCacheSize()
        }
    }

    fun addServer(name: String, url: String, username: String, password: String?) {
        viewModelScope.launch {
            val server = WebDavServer(name = name, url = url)
            val id = webDavServerDao.insertServer(server)
            if (password != null) {
                credentialsManager.saveCredentials(id.toInt(), username, password)
            }
        }
    }

    fun deleteServer(server: WebDavServer) {
        viewModelScope.launch {
            webDavServerDao.deleteServer(server)
            credentialsManager.clearCredentials(server.id)
        }
    }
}

