package com.uviewer_android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.WebDavServer
import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.repository.CredentialsManager
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val webDavServerDao: WebDavServerDao,
    private val credentialsManager: CredentialsManager,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.DOC_BG_WHITE)

    val language: StateFlow<String> = userPreferencesRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.LANG_SYSTEM)

    val docTextColor: StateFlow<String> = userPreferencesRepository.docTextColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "black")

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

    fun setLanguage(lang: String) {
        userPreferencesRepository.setLanguage(lang)
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
