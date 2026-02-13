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

data class SubtitleTrack(
    val id: String,
    val label: String,
    val language: String?,
    val isSelected: Boolean
)

data class MediaPlayerUiState(
    val mediaUrl: String? = null,
    val currentPath: String? = null,
    val playlist: List<FileEntry> = emptyList(),
    val currentIndex: Int = -1,
    val authHeader: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val rotation: Float = 0f,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

class MediaPlayerViewModel(
    application: Application,
    private val fileRepository: com.uviewer_android.data.repository.FileRepository,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: RecentFileDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MediaPlayerUiState())
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    fun prepareMedia(filePath: String, isWebDav: Boolean, serverId: Int?, fileType: FileEntry.FileType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, currentPath = filePath)
            try {
                // List files in same directory for playlist
                val parentPath = if (isWebDav) {
                    if (filePath.endsWith("/")) filePath.dropLast(1).substringBeforeLast("/")
                    else filePath.substringBeforeLast("/")
                } else {
                    java.io.File(filePath).parent ?: "/"
                }

                val allFiles = if (isWebDav && serverId != null) {
                    webDavRepository.listFiles(serverId, parentPath)
                } else {
                    fileRepository.listFiles(parentPath)
                }
                
                val playlist = allFiles
                    .filter { it.type == FileEntry.FileType.AUDIO || it.type == FileEntry.FileType.VIDEO }
                    .sortedBy { it.name.lowercase() }
                val index = playlist.indexOfFirst { it.path == filePath }

                _uiState.value = _uiState.value.copy(
                    playlist = playlist,
                    currentIndex = index
                )

                loadMedia(filePath, isWebDav, serverId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun next(isWebDav: Boolean, serverId: Int?) {
        val nextIndex = _uiState.value.currentIndex + 1
        if (nextIndex < _uiState.value.playlist.size) {
            val nextFile = _uiState.value.playlist[nextIndex]
            _uiState.value = _uiState.value.copy(currentIndex = nextIndex, isLoading = true)
            viewModelScope.launch {
                loadMedia(nextFile.path, isWebDav, serverId)
            }
        }
    }

    fun prev(isWebDav: Boolean, serverId: Int?) {
        val prevIndex = _uiState.value.currentIndex - 1
        if (prevIndex >= 0) {
            val prevFile = _uiState.value.playlist[prevIndex]
            _uiState.value = _uiState.value.copy(currentIndex = prevIndex, isLoading = true)
            viewModelScope.launch {
                loadMedia(prevFile.path, isWebDav, serverId)
            }
        }
    }

    private suspend fun loadMedia(filePath: String, isWebDav: Boolean, serverId: Int?) {
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
                _uiState.value = _uiState.value.copy(mediaUrl = fullUrl, authHeader = headers, isLoading = false, currentPath = filePath)
            } else {
                _uiState.value = _uiState.value.copy(error = "Can't get WebDAV server details", isLoading = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(mediaUrl = filePath, isLoading = false, currentPath = filePath)
        }
    }

    fun rotate() {
        _uiState.value = _uiState.value.copy(rotation = (_uiState.value.rotation + 90f) % 360f)
    }

    fun setVideoSize(width: Int, height: Int) {
        _uiState.value = _uiState.value.copy(videoWidth = width, videoHeight = height)
    }

    fun updateSubtitleTracks(tracks: List<SubtitleTrack>) {
        _uiState.value = _uiState.value.copy(subtitleTracks = tracks)
    }
}
