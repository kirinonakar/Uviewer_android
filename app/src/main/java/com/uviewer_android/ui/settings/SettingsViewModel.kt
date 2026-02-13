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
