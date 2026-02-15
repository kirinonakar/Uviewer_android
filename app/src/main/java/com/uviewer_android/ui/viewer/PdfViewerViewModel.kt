package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PdfViewerUiState(
    val localFilePath: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class PdfViewerViewModel(
    application: Application,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val bookmarkDao: com.uviewer_android.data.BookmarkDao,
    private val favoriteDao: com.uviewer_android.data.FavoriteDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    fun loadPdf(filePath: String, isWebDav: Boolean, serverId: Int?) {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState(isLoading = true)

            // Add to Recent
            try {
                val title = filePath.substringAfterLast("/")
                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = filePath,
                        title = title,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "PDF",
                        lastAccessed = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {}

            try {
                val localFile = if (isWebDav && serverId != null) {
                    val cacheDir = getApplication<Application>().cacheDir
                    val tempFile = File(cacheDir, "temp_" + File(filePath).name)
                    webDavRepository.downloadFile(serverId, filePath, tempFile)
                    tempFile
                } else {
                    File(filePath)
                }

                if (localFile.exists()) {
                    _uiState.value = PdfViewerUiState(localFilePath = localFile.absolutePath, isLoading = false)
                } else {
                    _uiState.value = PdfViewerUiState(error = "File not found", isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = PdfViewerUiState(error = e.message, isLoading = false)
            }
        }
    }

    fun toggleBookmark(path: String, page: Int, isWebDav: Boolean, serverId: Int?) {
        viewModelScope.launch {
            val fileName = File(path).name
            val bookmarkTitle = "$fileName - pg ${page + 1}"
            
            // Bookmark (Position)
            bookmarkDao.insertBookmark(
                com.uviewer_android.data.Bookmark(
                    title = bookmarkTitle,
                    path = path,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    type = "PDF",
                    position = page,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Favorite (File)
            favoriteDao.insertFavorite(
                com.uviewer_android.data.FavoriteItem(
                    title = bookmarkTitle,
                    path = path,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    type = "PDF",
                    position = page,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }
}
