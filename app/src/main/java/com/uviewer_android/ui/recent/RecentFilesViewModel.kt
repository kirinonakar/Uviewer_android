package com.uviewer_android.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.RecentFile
import com.uviewer_android.data.RecentFileDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecentFilesViewModel(
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository
) : ViewModel() {

    val viewMode: StateFlow<Int> = userPreferencesRepository.libraryViewMode

    val recentFiles: StateFlow<List<RecentFile>> = recentFileDao.getRecentFiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    init {
        viewModelScope.launch {
            recentFileDao.deleteExcess()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            recentFileDao.deleteAll()
        }
    }

    fun toggleViewMode() {
        val newMode = if (viewMode.value == 0) 1 else 0
        viewModelScope.launch {
            userPreferencesRepository.setLibraryViewMode(newMode)
        }
    }
}
