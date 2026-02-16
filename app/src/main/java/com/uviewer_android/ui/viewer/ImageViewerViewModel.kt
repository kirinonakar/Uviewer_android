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
    val sharpeningAmount: Int = 0,
    val viewMode: ViewMode = ViewMode.SINGLE
)

enum class ViewMode {
    SINGLE, DUAL, SPLIT
}

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

    fun setSharpeningAmount(amount: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setSharpeningAmount(amount)
        }
    }

    fun setInvertImageControl(invert: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setInvertImageControl(invert)
        }
    }


    fun setDualPageOrder(order: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setDualPageOrder(order)
        }
    }

        fun toggleBookmark(path: String, index: Int, isWebDav: Boolean, serverId: Int?, type: String, currentImages: List<FileEntry>) {
            viewModelScope.launch {
                try {
                    val archiveName = if (path.endsWith("/")) path.dropLast(1).substringAfterLast("/") else path.substringAfterLast("/")
                    val imageName = if (index >= 0 && index < currentImages.size) currentImages[index].name else null
                    val bookmarkTitle = if (imageName != null && archiveName != imageName) "$archiveName - $imageName" else archiveName

                    // Add to Bookmarks (Page/Position)
                    bookmarkDao.insertBookmark(
                        com.uviewer_android.data.Bookmark(
                            title = bookmarkTitle,
                            path = path,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = type,
                            position = index,
                            positionTitle = imageName,
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    // Add to Favorites (File)
                    favoriteDao.insertFavorite(
                        com.uviewer_android.data.FavoriteItem(
                            title = bookmarkTitle,
                            path = path,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = type,
                            position = index,
                            positionTitle = imageName,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    // Log or handle error silently to prevent crash
                    e.printStackTrace()
                }
            }
        }

        fun loadImages(initialFilePath: String, isWebDav: Boolean, serverId: Int?, initialIndex: Int? = null) {
        Log.d("ImageViewer", "loadImages: path=$initialFilePath, webDav=$isWebDav, server=$serverId, initialIndex=$initialIndex")
        
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
                    savedIndex = initialIndex ?: (existing?.pageIndex ?: 0)
                    val savedImageName = existing?.positionTitle
                    
                    val title = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                    recentFileDao.insertRecent(
                        com.uviewer_android.data.RecentFile(
                            path = filePath,
                            title = title,
                            isWebDav = isWebDav,
                            serverId = serverId,
                            type = if (isZip) "ZIP" else "IMAGE",
                            lastAccessed = System.currentTimeMillis(),
                            pageIndex = savedIndex, // Preserve saved index on open
                            positionTitle = existing?.positionTitle
                        )
                    )
                } catch (e: Exception) {
                    // Ignore
                    e.printStackTrace()
                }
                try {
                    // ... Loading Logic ...
                    val contentIsWebDav = isWebDav && !isZip

                    val images = if (isZip) {
                        if (isWebDav && serverId != null) {
                            // Streaming for WebDAV Zip
                            val zipSize = webDavRepository.getFileSize(serverId, filePath)
                            val manager = com.uviewer_android.data.utils.RemoteZipManager(webDavRepository, serverId, filePath, zipSize)
                            val entries = manager.getEntries()
                            val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                            val zipImages = entries.filter { !it.isDirectory && it.name.lowercase().let { n -> 
                                imageExtensions.any { ext -> n.endsWith(".$ext") }
                            } }.map { entry ->
                                val uri = android.net.Uri.Builder()
                                    .scheme("webdav-zip")
                                    .authority(serverId.toString())
                                    .path(filePath)
                                    .appendQueryParameter("entry", entry.name)
                                    .build()
                                    
                                FileEntry(
                                    name = entry.name.substringAfterLast('/'),
                                    path = uri.toString(),
                                    isDirectory = false,
                                    type = FileEntry.FileType.IMAGE,
                                    lastModified = 0L,
                                    size = entry.uncompressedSize,
                                    serverId = serverId,
                                    isWebDav = true
                                )
                            }.sortedBy { it.name.lowercase() }
                            
                            Log.d("ImageViewer", "Remote ZIP parsing complete. Found ${zipImages.size} images.")
                            if (zipImages.isEmpty()) {
                                Log.e("ImageViewer", "No images found in Remote ZIP! Total entries: ${entries.size}")
                                throw Exception("No images found in the zip file.")
                            }
                            zipImages
                        } else {
                            // Local Zip logic
                            val context = getApplication<Application>()
                            val cacheDir = context.cacheDir
                            val zipFile = File(filePath)

                            val unzipDir = File(cacheDir, "zip_${zipFile.name}_unzipped")
                            if (unzipDir.exists()) unzipDir.deleteRecursively()
                            unzipDir.mkdirs()
                            
                            EpubParser.unzip(zipFile, unzipDir)
                            
                            unzipDir.walkTopDown()
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
                        }
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
                    val savedImageName = recentFileDao.getFile(filePath)?.positionTitle
                    val index = if (isZip) {
                         if (savedImageName != null) {
                             val foundIdx = images.indexOfFirst { it.name == savedImageName }
                             if (foundIdx != -1) foundIdx else savedIndex.coerceIn(0, images.size - 1)
                         } else {
                             if (images.isNotEmpty()) savedIndex.coerceIn(0, images.size - 1) else 0
                         }
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
                    sharpeningAmount = userPreferencesRepository.sharpeningAmount.value
                )
                  // ... loops ...
                viewModelScope.launch {
                    userPreferencesRepository.persistZoom.collect { 
                        _uiState.value = _uiState.value.copy(persistZoom = it)
                    }
                }
                viewModelScope.launch {
                    userPreferencesRepository.sharpeningAmount.collect {
                        _uiState.value = _uiState.value.copy(sharpeningAmount = it)
                    }
                }
            } catch (e: Exception) {
                Log.e("ImageViewer", "Error in loadImages", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
            }
    }

    fun toggleViewMode() {
        val nextMode = when (_uiState.value.viewMode) {
            ViewMode.SINGLE -> ViewMode.DUAL
            ViewMode.DUAL -> ViewMode.SPLIT
            ViewMode.SPLIT -> ViewMode.SINGLE
        }
        _uiState.value = _uiState.value.copy(viewMode = nextMode)
    }

    fun updateProgress(index: Int) {
         val path = currentPath ?: return
         viewModelScope.launch {
             try {
                 val archiveName = File(path).name
                 val imageName = if (index >= 0 && index < uiState.value.images.size) uiState.value.images[index].name else null
                 val displayTitle = if (imageName != null && archiveName != imageName) "$archiveName - $imageName" else archiveName
                 
                 recentFileDao.insertRecent(
                     com.uviewer_android.data.RecentFile(
                         path = path,
                         title = displayTitle,
                         isWebDav = currentIsWebDav,
                         serverId = currentServerId,
                         type = if (currentIsZip) "ZIP" else "IMAGE",
                         lastAccessed = System.currentTimeMillis(),
                         pageIndex = index,
                         positionTitle = imageName
                     )
                 )
             } catch (e: Exception) { e.printStackTrace() }
         }
    }
}
