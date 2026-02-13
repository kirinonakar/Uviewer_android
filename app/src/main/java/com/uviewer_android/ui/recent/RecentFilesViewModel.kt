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
    private val recentFileDao: RecentFileDao
) : ViewModel() {

    val recentFiles: StateFlow<List<RecentFile>> = recentFileDao.getRecentFiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // Recent files logic usually involves adding items when files are opened.
    // That logic should be in ViewModels related to opening files (ImageViewer, DocumentViewer, MediaPlayer).
    // This ViewModel is primarily for displaying the list.
}
