package com.uviewer_android.ui.viewer

import android.app.Application
import android.util.Log
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
        private val favoriteDao: com.uviewer_android.data.FavoriteDao,
        private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository
    ) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageViewerUiState())
    val uiState: StateFlow<ImageViewerUiState> = _uiState.asStateFlow()

    // Preferences
    val invertImageControl = userPreferencesRepository.invertImageControl
    val dualPageOrder = userPreferencesRepository.dualPageOrder

    // Current context for progress saving
    private var currentPath: String? = null
    private var currentIsWebDav: Boolean = false
    private var currentServerId: Int? = null
    private var currentIsZip: Boolean = false

    fun setPersistZoom(persist: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setPersistZoom(persist)
        }
    }

    fun setUpscaleFilter(upscale: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUpscaleFilter(upscale)
        }
    }

    fun setDualPageOrder(order: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setDualPageOrder(order)
        }
    }

        fun toggleBookmark(path: String, index: Int, isWebDav: Boolean, serverId: Int?, type: String) {
            viewModelScope.launch {
                try {
                    val title = if (path.endsWith("/")) path.dropLast(1).substringAfterLast("/") else path.substringAfterLast("/")
                    
                    // Add to Bookmarks (Page/Position)
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

                    // Add to Favorites (File)
                    favoriteDao.insertFavorite(
                        com.uviewer_android.data.FavoriteItem(
                            title = title,
                            path = path,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = type
                        )
                    )
                } catch (e: Exception) {
                    // Log or handle error silently to prevent crash
                    e.printStackTrace()
                }
            }
        }

        fun loadImages(initialFilePath: String, isWebDav: Boolean, serverId: Int?) {
        Log.d("ImageViewer", "loadImages: path=$initialFilePath, webDav=$isWebDav, server=$serverId")
        
        val decodedPath = try {
            var path = initialFilePath
            while (path.contains("%")) {
                val next = java.net.URLDecoder.decode(path, "UTF-8")
                if (next == path) break
                path = next
            }
            path
        } catch (e: Exception) { initialFilePath }
        
        val filePath = decodedPath
        Log.d("ImageViewer", "Decoded path: $filePath")

            currentPath = filePath
            currentIsWebDav = isWebDav
            currentServerId = serverId
            val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") }
            currentIsZip = isZip

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Add to Recent (and retrieve saved index)
                var savedIndex = 0
                try {
                    val existing = recentFileDao.getFile(filePath)
                    savedIndex = existing?.pageIndex ?: 0
                    
                    val title = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                    recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = filePath,
                            title = title,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = if (isZip) "ZIP" else "IMAGE",
                            lastAccessed = System.currentTimeMillis(),
                            pageIndex = savedIndex // Preserve saved index on open
                        )
                    )
                } catch (e: Exception) {
                    // Ignore
                }

                try {
                    // ... Loading Logic ...
                    val contentIsWebDav = isWebDav && !isZip

                    val images = if (isZip) {
                        val context = getApplication<Application>()
                        val cacheDir = context.cacheDir
                        
                        val zipFile = if (isWebDav && serverId != null) {
                            val fileName = File(filePath).name
                            val tempFile = File(cacheDir, "temp_$fileName")
                            if (!tempFile.exists() || tempFile.length() == 0L) { // optimized check?
                                webDavRepository.downloadFile(serverId, filePath, tempFile)
                            }
                            // Re-download logic might be needed if forced? For now assume cache if exists? 
                            // Actually dangerous if file changed. Let's stick to original logic: download.
                            // But original logic didn't query existence efficiently.
                            // Let's stick to original safe download for now.
                            webDavRepository.downloadFile(serverId, filePath, tempFile)
                            tempFile
                        } else {
                            File(filePath)
                        }

                        val unzipDir = File(cacheDir, "zip_${zipFile.name}_unzipped")
                        // Smart Unzip? If already unzipped and valid?
                        // For now, re-unzip safely.
                        if (unzipDir.exists()) unzipDir.deleteRecursively()
                        unzipDir.mkdirs()
                        
                        EpubParser.unzip(zipFile, unzipDir)
                        
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
                        files.filter { it.type == FileEntry.FileType.IMAGE || it.type == FileEntry.FileType.WEBP || it.type == FileEntry.FileType.IMAGE_ZIP }
                            .sortedBy { it.name.lowercase() }
                    }

                    if (images.isEmpty()) {
                        throw Exception("No images found in the folder.")
                    }

                    // For Zip, use savedIndex. For Folder, find the file index.
                    val normalizedFilePath = filePath.trimEnd('/')
                    val index = if (isZip) {
                        if (images.isNotEmpty()) savedIndex.coerceIn(0, images.size - 1) else 0
                    } else {
                        val found = images.indexOfFirst { it.path.trimEnd('/') == normalizedFilePath }
                         if (found != -1) found else 0
                    }
                    
                    val auth = if (contentIsWebDav && serverId != null) {
                        webDavRepository.getAuthHeader(serverId)
                    } else null

                    val serverUrl = if (isWebDav && serverId != null) {
                        webDavRepository.getServer(serverId)?.url
                    } else null

                    Log.d("ImageViewer", "Loaded ${images.size} images. Initial index: $index")
                    Log.d("ImageViewer", "WebDAV Config: isWebDav=$contentIsWebDav, serverUrl=$serverUrl, hasAuth=${auth != null}")
                    if (index < images.size) {
                        Log.d("ImageViewer", "Target image: ${images[index].path}")
                    }

                _uiState.value = _uiState.value.copy(
                    images = images,
                    initialIndex = index,
                    isLoading = false,
                    authHeader = auth,
                    serverUrl = serverUrl,
                    isContentLoadedFromWebDav = contentIsWebDav,
                    containerName = if (isZip) File(filePath).name else null,
                    persistZoom = userPreferencesRepository.persistZoom.value,
                    upscaleFilter = userPreferencesRepository.upscaleFilter.value
                )
                  // ... loops ...
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
                Log.e("ImageViewer", "Error in loadImages", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
            }
    }

    fun updateProgress(index: Int) {
         val path = currentPath ?: return
         viewModelScope.launch {
             try {
                 val title = File(path).name
                 recentFileDao.insertRecent(
                     com.uviewer_android.data.RecentFile(
                         path = path,
                         title = title,
                         isWebDav = currentIsWebDav,
                         serverId = currentServerId,
                         type = if (currentIsZip) "ZIP" else "IMAGE",
                         lastAccessed = System.currentTimeMillis(),
                         pageIndex = index
                     )
                 )
             } catch (e: Exception) { e.printStackTrace() }
         }
    }
}
