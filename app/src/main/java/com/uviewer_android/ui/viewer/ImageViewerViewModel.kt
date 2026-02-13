package com.uviewer_android.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ImageViewerUiState(
    val images: List<FileEntry> = emptyList(),
    val initialIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val authHeader: String? = null
)

class ImageViewerViewModel(
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageViewerUiState())
    val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

    fun loadImages(filePath: String, isWebDav: Boolean, serverId: Int?) {
        if (_uiState.value.images.isNotEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Add to Recent
            try {
                val title = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = filePath,
                        title = title,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "IMAGE",
                        lastAccessed = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // Ignore
            }

            try {
                val parentPath = if (isWebDav) {
                    if (filePath.endsWith("/")) filePath.dropLast(1).substringBeforeLast("/")
                    else filePath.substringBeforeLast("/")
                } else {
                    File(filePath).parent ?: "/"
                }

                // Get Client first if WebDAV to extract Auth Header?
                // WebDavRepository should expose getAuthHeader or credentials.
                // Let's assume WebDavRepository has a method 'getAuthHeader(serverId)'
                val auth = if (isWebDav && serverId != null) {
                    webDavRepository.getAuthHeader(serverId)
                } else null

                val files = if (isWebDav && serverId != null) {
                    webDavRepository.listFiles(serverId, parentPath)
                } else {
                    fileRepository.listFiles(parentPath)
                }

                val images = files.filter { it.type == FileEntry.FileType.IMAGE }
                val index = images.indexOfFirst { it.path == filePath }
                
                _uiState.value = _uiState.value.copy(
                    images = images,
                    initialIndex = if (index != -1) index else 0,
                    isLoading = false,
                    authHeader = auth
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
