package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.RecentFileDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

data class MediaPlayerUiState(
    val mediaUrl: String? = null,
    val authHeader: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class MediaPlayerViewModel(
    application: Application,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: RecentFileDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MediaPlayerUiState())
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    fun prepareMedia(filePath: String, isWebDav: Boolean, serverId: Int?, fileType: FileEntry.FileType) {
        viewModelScope.launch {
            _uiState.value = MediaPlayerUiState(isLoading = true)
            try {
                // Add to Recent
                try {
                    val title = filePath.substringAfterLast("/")
                    recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = filePath,
                            title = title,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = fileType.name,
                            lastAccessed = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    // Ignore
                }

                if (isWebDav && serverId != null) {
                    val server = webDavRepository.getServer(serverId)
                    val authHeaderValue = webDavRepository.getAuthHeader(serverId)
                    if (server != null && authHeaderValue != null) {
                        val fullUrl = try {
                            val baseHttpUrl = server.url.trimEnd('/').toHttpUrl()
                            val builder = baseHttpUrl.newBuilder()
                            filePath.split("/").filter { it.isNotEmpty() }.forEach {
                                builder.addPathSegment(it)
                            }
                            builder.build().toString()
                        } catch (e: Exception) {
                            server.url.trimEnd('/') + filePath
                        }

                        val headers = mapOf("Authorization" to authHeaderValue)
                        _uiState.value = MediaPlayerUiState(mediaUrl = fullUrl, authHeader = headers, isLoading = false)
                    } else {
                        _uiState.value = MediaPlayerUiState(error = "Can't get WebDAV server details", isLoading = false)
                    }
                } else {
                    // For local files, the path is the URL
                    _uiState.value = MediaPlayerUiState(mediaUrl = filePath, isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = MediaPlayerUiState(error = e.message, isLoading = false)
            }
        }
    }
}
