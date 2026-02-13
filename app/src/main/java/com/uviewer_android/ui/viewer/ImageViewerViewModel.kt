package com.uviewer_android.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.parser.EpubParser
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
    val authHeader: String? = null,
    val serverUrl: String? = null,
    val isContentLoadedFromWebDav: Boolean = false,
    val containerName: String? = null,
    val persistZoom: Boolean = false,
    val upscaleFilter: Boolean = false
)

    class ImageViewerViewModel(
        application: Application,
        private val fileRepository: FileRepository,
        private val webDavRepository: WebDavRepository,
        private val recentFileDao: com.uviewer_android.data.RecentFileDao,
        private val bookmarkDao: com.uviewer_android.data.BookmarkDao,
        private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository
    ) : AndroidViewModel(application) {

        private val _uiState = MutableStateFlow(ImageViewerUiState())
        val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

        val invertImageControl: StateFlow<Boolean> = userPreferencesRepository.invertImageControl
        val dualPageOrder: StateFlow<Int> = userPreferencesRepository.dualPageOrder

        fun loadImages(filePath: String, isWebDav: Boolean, serverId: Int?) {
            if (_uiState.value.images.isNotEmpty()) return

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)

                // Add to Recent
                try {
                    val title = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                    val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") }
                    
                    recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = filePath,
                            title = title,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = if (isZip) "ZIP" else "IMAGE",
                            lastAccessed = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    // Ignore
                }

                try {
                    val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") }
                    val contentIsWebDav = isWebDav && !isZip

                    val images = if (isZip) {
                        val context = getApplication<Application>()
                        val cacheDir = context.cacheDir
                        
                        val zipFile = if (isWebDav && serverId != null) {
                            val fileName = File(filePath).name
                            val tempFile = File(cacheDir, "temp_$fileName")
                            webDavRepository.downloadFile(serverId, filePath, tempFile)
                            tempFile
                        } else {
                            File(filePath)
                        }

                        val unzipDir = File(cacheDir, "zip_${zipFile.name}_unzipped")
                        if (unzipDir.exists()) unzipDir.deleteRecursively()
                        unzipDir.mkdirs()
                        
                        // EpubParser has a static unzip method we can use
                        EpubParser.unzip(zipFile, unzipDir)
                        
                        // List all files in unzipDir recursively and filter for images
                        val zipImages = unzipDir.walkTopDown()
                            .filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") }
                            .map { file ->
                                FileEntry(
                                    name = file.name,
                                    path = file.absolutePath,
                                    isDirectory = false,
                                    type = FileEntry.FileType.IMAGE,
                                    lastModified = file.lastModified(),
                                    size = file.length()
                                )
                            }
                            .sortedBy { it.name.lowercase() }
                            .toList()

                        if (zipImages.isEmpty()) {
                            throw Exception("No images found in the zip file.")
                        }
                        zipImages
                    } else {
                        val parentPath = if (isWebDav) {
                            if (filePath.endsWith("/")) filePath.dropLast(1).substringBeforeLast("/")
                            else filePath.substringBeforeLast("/")
                        } else {
                            File(filePath).parent ?: "/"
                        }

                        val files = if (isWebDav && serverId != null) {
                            webDavRepository.listFiles(serverId, parentPath)
                        } else {
                            fileRepository.listFiles(parentPath)
                        }
                        files.filter { it.type == FileEntry.FileType.IMAGE }
                            .sortedBy { it.name.lowercase() }
                    }

                    val index = if (isZip) 0 else images.indexOfFirst { it.path == filePath }
                    
                    val auth = if (contentIsWebDav && serverId != null) {
                        webDavRepository.getAuthHeader(serverId)
                    } else null

                    val serverUrl = if (isWebDav && serverId != null) {
                        webDavRepository.getServer(serverId)?.url
                    } else null

                _uiState.value = _uiState.value.copy(
                    images = images,
                    initialIndex = if (index != -1) index else 0,
                    isLoading = false,
                    authHeader = auth,
                    serverUrl = serverUrl,
                    isContentLoadedFromWebDav = contentIsWebDav,
                    containerName = if (isZip) File(filePath).name else null,
                    persistZoom = userPreferencesRepository.persistZoom.value,
                    upscaleFilter = userPreferencesRepository.upscaleFilter.value
                )

                // Observe preferences
                viewModelScope.launch {
                    userPreferencesRepository.persistZoom.collect { 
                        _uiState.value = _uiState.value.copy(persistZoom = it)
                    }
                }
                viewModelScope.launch {
                    userPreferencesRepository.upscaleFilter.collect {
                        _uiState.value = _uiState.value.copy(upscaleFilter = it)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun setPersistZoom(persist: Boolean) {
        userPreferencesRepository.setPersistZoom(persist)
    }

    fun setUpscaleFilter(upscale: Boolean) {
        userPreferencesRepository.setUpscaleFilter(upscale)
    }

    fun setDualPageOrder(order: Int) {
        userPreferencesRepository.setDualPageOrder(order)
    }

        fun toggleBookmark(path: String, index: Int, isWebDav: Boolean, serverId: Int?, type: String) {
            viewModelScope.launch {
                val title = if (path.endsWith("/")) path.dropLast(1).substringAfterLast("/") else path.substringAfterLast("/")
                bookmarkDao.insertBookmark(
                    com.uviewer_android.data.Bookmark(
                        title = title,
                        path = path,
                        isWebDav = isWebDav,
                        serverId = serverId,
                        type = type,
                        position = index,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }
