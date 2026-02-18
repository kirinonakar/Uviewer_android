package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class PdfViewerUiState(
    val localFilePath: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val initialPage: Int = 0
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

    fun loadPdf(filePath: String, isWebDav: Boolean, serverId: Int?, initialPage: Int? = null) {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState(isLoading = true)

            // Add to Recent (and retrieve saved position)
            var savedIndex = initialPage ?: try {
                val existing = recentFileDao.getFile(filePath)
                existing?.pageIndex ?: 0
            } catch (e: Exception) {
                0
            }
            if (savedIndex < 0) savedIndex = 0

            try {
                val title = filePath.substringAfterLast("/")
                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = filePath,
                        title = title,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "PDF",
                        lastAccessed = System.currentTimeMillis(),
                        pageIndex = savedIndex
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
                    _uiState.value = PdfViewerUiState(
                        localFilePath = localFile.absolutePath, 
                        isLoading = false,
                        initialPage = savedIndex
                    )
                } else {
                    _uiState.value = PdfViewerUiState(error = "File not found", isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = PdfViewerUiState(error = e.message, isLoading = false)
            }
        }
    }

    fun toggleBookmark(path: String, page: Int, totalPages: Int, isWebDav: Boolean, serverId: Int?) {
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

            // Add to Favorites (File) with new rules
            val favorites = favoriteDao.getAllFavorites().first()
            val existing = favorites.find { it.path == path && it.position == page }
            
            // Check if any favorite with the same document is already pinned
            val pinnedItem = favorites.find { 
                (it.path == path || it.title == fileName || it.title.startsWith("$fileName - ")) && it.isPinned 
            }
            val wasPinned = pinnedItem != null

            if (existing != null) {
                // Rule 1: Same file and same page -> Move to top
                favoriteDao.updateFavorite(existing.copy(
                    timestamp = System.currentTimeMillis(),
                    isPinned = wasPinned || existing.isPinned
                ))
                // If a DIFFERENT item was pinned, unpin it
                if (wasPinned && pinnedItem?.id != existing.id) {
                    favoriteDao.updateFavorite(pinnedItem!!.copy(isPinned = false))
                }
            } else {
                // Rule 2: Same document, different location/page -> Max 3
                val sameDocFavorites = favorites.filter { 
                    it.path == path || it.title == fileName || it.title.startsWith("$fileName - ") 
                }
                if (sameDocFavorites.size >= 3) {
                    val oldest = sameDocFavorites.minByOrNull { it.timestamp }
                    if (oldest != null) {
                        favoriteDao.deleteFavorite(oldest)
                    }
                }
                favoriteDao.insertFavorite(
                    com.uviewer_android.data.FavoriteItem(
                        title = bookmarkTitle,
                        path = path,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "PDF",
                        position = page,
                        isPinned = wasPinned, // Transfer pin status
                        progress = if (totalPages > 1) (page.toFloat() / (totalPages - 1)) else 0f,
                        timestamp = System.currentTimeMillis()
                    )
                )
                // Unpin the old one if it was different
                if (wasPinned) {
                    favoriteDao.updateFavorite(pinnedItem!!.copy(isPinned = false))
                }
            }
        }
    }

    fun updateProgress(path: String, page: Int, totalPages: Int, isWebDav: Boolean, serverId: Int?) {
        viewModelScope.launch {
            try {
                val fileName = File(path).name
                val progress = if (totalPages > 1) {
                    (page.toFloat() / (totalPages - 1))
                } else if (page > 0) {
                    0.5f 
                } else 0f

                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = path,
                        title = fileName,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "PDF",
                        lastAccessed = System.currentTimeMillis(),
                        pageIndex = page,
                        progress = progress
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
