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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val loadingProgress: Float? = null,
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
        private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository,
        private val cacheManager: com.uviewer_android.data.utils.CacheManager
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

                    // Add to Favorites (File) with new rules
                    val favorites = favoriteDao.getAllFavorites().first()
                    val existing = favorites.find { it.path == path && it.position == index }
                    
                    // Check if any favorite with the same title/archive is already pinned
                    val pinnedItem = favorites.find { 
                        (it.path == path || it.title == archiveName || it.title.startsWith("$archiveName - ")) && it.isPinned 
                    }
                    val wasPinned = pinnedItem != null

                    val total = currentImages.size
                    val progress = if (total > 0) (index.toFloat() / (total - 1).coerceAtLeast(1)) else 0f

                    if (existing != null) {
                        // Rule 1: Exactly same file and position -> Move to top
                        favoriteDao.updateFavorite(existing.copy(
                            timestamp = System.currentTimeMillis(),
                            isPinned = wasPinned || existing.isPinned,
                            pinOrder = if (wasPinned) (pinnedItem?.pinOrder ?: 0) else existing.pinOrder,
                            progress = progress,
                            positionTitle = imageName
                        ))
                        // If a DIFFERENT item was pinned, unpin it
                        if (wasPinned && pinnedItem?.id != existing.id) {
                            favoriteDao.updateFavorite(pinnedItem!!.copy(isPinned = false, pinOrder = 0))
                        }
                    } else {
                        // Rule 2: Same document/archive, different location/position -> Max 3
                        val sameDocFavorites = favorites.filter { 
                            it.path == path || it.title == archiveName || it.title.startsWith("$archiveName - ")
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
                                type = type,
                                position = index,
                                positionTitle = imageName,
                                isPinned = wasPinned, // Transfer pin status
                                pinOrder = if (wasPinned) (pinnedItem?.pinOrder ?: 0) else 0,
                                progress = progress,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        // Unpin the old one if it was different
                        if (wasPinned && (pinnedItem?.path != path || pinnedItem?.position != index)) {
                             favoriteDao.updateFavorite(pinnedItem!!.copy(isPinned = false, pinOrder = 0))
                        }
                    }
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
            val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") || it.endsWith(".cbr") || it.endsWith(".7z") || it.endsWith(".cb7") }
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
                        val isStreamable = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") }
                        if (isWebDav && serverId != null && isStreamable) {
                            // Streaming for WebDAV Zip
                            val zipSize = webDavRepository.getFileSize(serverId, filePath)
                            val manager = com.uviewer_android.data.utils.RemoteZipManager(webDavRepository, serverId, filePath, zipSize)
                            val entries = manager.getEntries()
                            val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                            val zipImages = entries.filter { !it.isDirectory && it.name.lowercase().let { n -> 
                                imageExtensions.any { ext -> n.endsWith(".$ext") }
                            } }.map { entry ->
                                val uriBuilder = android.net.Uri.Builder()
                                    .scheme("webdav-zip")
                                    .authority(serverId.toString())
                                
                                filePath.split("/").filter { it.isNotEmpty() }.forEach {
                                    uriBuilder.appendPath(it)
                                }
                                uriBuilder.appendQueryParameter("entry", entry.name)
                                val uri = uriBuilder.build()
                                    
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
                        } else if (isWebDav && serverId != null) {
                            // Non-ZIP Archive on WebDAV (RAR, 7Z)
                            val context = getApplication<Application>()
                            val cacheDir = context.getExternalFilesDir("cache") ?: context.cacheDir
                            val archiveExt = filePath.substringAfterLast('.').lowercase()
                            
                            // Stable cache key for this server and file path
                            val cacheKey = "${serverId}_${filePath}".hashCode().toString(36)
                            val unzipDir = File(cacheDir, "unzipped_$cacheKey")
                            val isDoneFile = File(unzipDir, ".extracted_done")
                            val tempFile = File(cacheDir, "temp_download_$cacheKey.$archiveExt")
                            val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

                            if (isDoneFile.exists()) {
                                Log.d("ImageViewer", "Archive already extracted in cache: $unzipDir")
                                cacheManager.touch(unzipDir)
                                unzipDir.walkTopDown()
                                    .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                                    .map { file ->
                                        FileEntry(file.name, file.absolutePath, false, FileEntry.FileType.IMAGE, file.lastModified(), file.length())
                                    }.sortedBy { it.name.lowercase() }.toList()
                            } else {
                                // For all non-streamable archives (RAR, 7Z), download first
                                val fileSize = webDavRepository.getFileSize(serverId, filePath)
                                cacheManager.ensureCapacity(fileSize + (fileSize * 2))

                                if (archiveExt == "7z" || archiveExt == "cb7") {
                                    // Step 1: Remote Listing (Fast)
                                    val manager = com.uviewer_android.data.utils.Remote7zManager(webDavRepository, serverId, filePath, fileSize)
                                    val entries = manager.getEntries()
                                    val remoteImages = entries.filter { !it.isDirectory && it.name.lowercase().let { n -> 
                                        imageExtensions.any { ext -> n.endsWith(".$ext") }
                                    } }.map { entry ->
                                        // Points to where the file WILL be after extraction
                                        // Normalize separators and trim leading slash to ensure correct joining
                                        val entryNormalizedName = entry.name.replace('\\', '/').trimStart('/')
                                        val targetFile = File(unzipDir, entryNormalizedName)
                                        val uri = android.net.Uri.Builder()
                                            .scheme("waiting-file")
                                            .authority("") // Ensures waiting-file:/// format
                                            .path(targetFile.absolutePath)
                                            .build()
                                        FileEntry(
                                            name = entry.name.substringAfterLast('/'),
                                            path = uri.toString(),
                                            isDirectory = false,
                                            type = FileEntry.FileType.IMAGE,
                                            lastModified = 0L,
                                            size = entry.size,
                                            serverId = serverId,
                                            isWebDav = true
                                        )
                                    }.sortedBy { it.name.lowercase() }

                                    // Step 2: Background download & extract if not done
                                    viewModelScope.launch {
                                        try {
                                            if (!isDoneFile.exists()) {
                                                Log.d("ImageViewer", "Background 7z download started: $filePath")
                                                webDavRepository.downloadFile(serverId, filePath, tempFile) { progress ->
                                                    // Optional UI progress
                                                }
                                                unzipDir.mkdirs()
                                                com.uviewer_android.data.utils.ArchiveExtractor.extract(tempFile, unzipDir)
                                                isDoneFile.createNewFile()
                                                tempFile.delete()
                                                Log.d("ImageViewer", "7z extraction done: $unzipDir")
                                            }
                                            
                                            // Refresh silently to local paths
                                            val localImages = unzipDir.walkTopDown()
                                                .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                                                .map { file ->
                                                    FileEntry(file.name, file.absolutePath, false, FileEntry.FileType.IMAGE, file.lastModified(), file.length())
                                                }.sortedBy { it.name.lowercase() }.toList()

                                            if (localImages.isNotEmpty()) {
                                                _uiState.value = _uiState.value.copy(images = localImages)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("ImageViewer", "Background 7z processing failed", e)
                                        }
                                    }
                                    
                                    if (remoteImages.isNotEmpty()) remoteImages else throw Exception("No images found in the archive ($archiveExt).")
                                } else {
                                    // For other archives (like RAR), wait for download but with progress
                                    webDavRepository.downloadFile(serverId, filePath, tempFile) { progress ->
                                        _uiState.value = _uiState.value.copy(loadingProgress = progress)
                                    }
                                    _uiState.value = _uiState.value.copy(loadingProgress = null) // Clear progress when done

                                    unzipDir.mkdirs()
                                    com.uviewer_android.data.utils.ArchiveExtractor.extract(tempFile, unzipDir)
                                    isDoneFile.createNewFile()
                                    tempFile.delete()

                                    unzipDir.walkTopDown()
                                        .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                                        .map { file ->
                                            FileEntry(file.name, file.absolutePath, false, FileEntry.FileType.IMAGE, file.lastModified(), file.length())
                                        }.sortedBy { it.name.lowercase() }.toList()
                                }
                            }
                        } else {
                            // Local Zip logic
                            val context = getApplication<Application>()
                            val cacheDir = context.getExternalFilesDir("cache") ?: context.cacheDir
                            val zipFile = File(filePath)
                            val unzipDir = File(cacheDir, "zip_${zipFile.name}_unzipped")
                            
                            if (unzipDir.exists()) {
                                cacheManager.touch(unzipDir)
                            } else {
                                cacheManager.ensureCapacity(zipFile.length() * 2)
                                unzipDir.mkdirs()
                                com.uviewer_android.data.utils.ArchiveExtractor.extract(zipFile, unzipDir)
                            }
                            
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
                        // Try full path match first
                        val normalizedFile = File(normalizedFilePath)
                        var found = images.indexOfFirst { 
                            File(it.path).absolutePath == normalizedFile.absolutePath 
                        }
                        // Fallback: case-insensitive path match
                        if (found == -1) {
                            found = images.indexOfFirst { 
                                it.path.trimEnd('/').equals(normalizedFilePath, ignoreCase = true) 
                            }
                        }
                        // Fallback: match by filename (handles URL encoding/decoding mismatches)
                        if (found == -1) {
                            val fileName = normalizedFilePath.substringAfterLast('/').substringAfterLast('\\')
                            found = images.indexOfFirst { it.name.equals(fileName, ignoreCase = true) }
                        }
                        Log.d("ImageViewer", "Path match: normalizedFilePath=$normalizedFilePath, found=$found, savedIndex=$savedIndex")
                        if (found != -1) found else savedIndex.coerceIn(0, images.size - 1)
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

    private fun computeImageSaveData(index: Int): kotlin.collections.Map<String, Any?>? {
        val path = currentPath ?: return null
        val archiveName = File(path).name
        val imageName = if (index >= 0 && index < uiState.value.images.size) uiState.value.images[index].name else null
        val displayTitle = if (imageName != null && archiveName != imageName) "$archiveName - $imageName" else archiveName
        val total = uiState.value.images.size
        val progress = if (total > 0) (index.toFloat() / (total - 1).coerceAtLeast(1)) else 0f
        return mapOf("path" to path, "progress" to progress, "title" to displayTitle, "imageName" to imageName)
    }

    fun updateProgress(index: Int) {
         val data = computeImageSaveData(index) ?: return
         viewModelScope.launch {
             try {
                 recentFileDao.updatePositionWithTitle(
                     path = data["path"] as String,
                     pageIndex = index,
                     progress = data["progress"] as Float,
                     title = data["title"] as String,
                     positionTitle = data["imageName"] as? String,
                     lastAccessed = System.currentTimeMillis()
                 )
             } catch (e: Exception) { e.printStackTrace() }
         }
    }

    fun saveProgressBlocking(index: Int) {
        val data = computeImageSaveData(index) ?: return
        try {
            runBlocking {
                recentFileDao.updatePositionWithTitle(
                    path = data["path"] as String,
                    pageIndex = index,
                    progress = data["progress"] as Float,
                    title = data["title"] as String,
                    positionTitle = data["imageName"] as? String,
                    lastAccessed = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
