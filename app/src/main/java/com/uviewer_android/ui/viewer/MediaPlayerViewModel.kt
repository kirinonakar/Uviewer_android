package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi // Needed for DefaultHttpDataSource
class MediaPlayerViewModel(
    application: Application,
    private val webDavRepository: WebDavRepository,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao
) : AndroidViewModel(application) {

    private val _playerState = MutableStateFlow<ExoPlayer?>(null)
    val playerState: StateFlow<ExoPlayer?> = _playerState.asStateFlow()

    private var player: ExoPlayer? = null

    fun initializePlayer(filePath: String, isWebDav: Boolean, serverId: Int?) {
        if (player != null) return // Already initialized

        viewModelScope.launch {
            // Add to Recent
            try {
                // Determine title
                val title = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                recentFileDao.insertRecent(
                    com.uviewer_android.data.RecentFile(
                        path = filePath,
                        title = title,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = "MEDIA",
                        lastAccessed = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // Ignore
            }

            val context = getApplication<Application>()
            
            // WebDAV Headers
            val authHeader = if (isWebDav && serverId != null) {
                webDavRepository.getAuthHeader(serverId)
            } else null

            // Build DataSource Factory
            val dataSourceFactory = if (authHeader != null) {
                val factory = DefaultHttpDataSource.Factory()
                factory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
                factory
            } else {
                DefaultHttpDataSource.Factory() // Or DefaultDataSource.Factory for mixed local/remote
            }
            
            // Build Player
            // For local files, we need DefaultDataSourceFactory to handle file://
            // Creating a generic DefaultDataSource.Factory might be better, but lets start simple.
            // Actually DefaultMediaSourceFactory handles most URIs automatically if no custom factory provided, 
            // EXCEPT headers.
            
            val newPlayer = if (authHeader != null) {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .build()
            } else {
                ExoPlayer.Builder(context).build()
            }

            val mediaItem = if (isWebDav) {
                MediaItem.fromUri(filePath) // filePath should be full URL ideally, but let's assume UI/Nav passes URL or path
                // Wait, ViewerScreen receives `filePath`. If WebDav, is it full URL?
                // In LibraryScreen, file path is used. WebDavRepository logic suggests path relative to server?
                // We need full URL for ExoPlayer if it's WebDAV.
                // WebDavRepository doesn't expose full URL generator easily yet without server details.
                // Let's assume we can fetch server URL or reconstruct it.
                // For now, let's look at how LibraryViewModel passes it. It passes `entry.path`.
                // If it's WebDAV, `entry.path` is likely just the path on server.
                // We need the server Base URL.
                // Let's fetch server details inside ViewModel.
            } else {
                MediaItem.fromUri(File(filePath).toURI().toString())
            }

            // Correction: For WebDAV we need server URL.
            val finalMediaItem = if (isWebDav && serverId != null) {
                val server = webDavRepository.getServer(serverId) // Need this method
                if (server != null) {
                    val fullUrl = server.url.trimEnd('/') + filePath
                    MediaItem.fromUri(fullUrl)
                } else null
            } else {
                MediaItem.fromUri(File(filePath).toURI().toString())
            }

            if (finalMediaItem != null) {
                newPlayer.setMediaItem(finalMediaItem)
                newPlayer.prepare()
                newPlayer.playWhenReady = true
                
                player = newPlayer
                _playerState.value = newPlayer
            }
        }
    }

    fun releasePlayer() {
        player?.release()
        player = null
        _playerState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }
}
