package com.uviewer_android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.model.LibraryViewMode
import com.uviewer_android.data.model.SortOption
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.data.repository.CredentialsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class LibraryUiState(
    val currentPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    val fileList: List<FileEntry> = emptyList(),
    val fileNameFilter: String = "",
    val searchResults: List<FileEntry> = emptyList(),
    val isSearching: Boolean = false,
    val favoritePaths: Set<String> = emptySet(),
    val pinnedFiles: List<FileEntry> = emptyList(), // For Pin Tab
    val mostRecentFile: com.uviewer_android.data.RecentFile? = null,
    val isLoading: Boolean = false,
    val selectedTabIndex: Int = 0,
    val serverId: Int? = null,
    val error: String? = null,
    val sortOption: SortOption = SortOption.NAME,
    val viewMode: LibraryViewMode = LibraryViewMode.LIST
)

private data class LibraryCombinedSources(
    val state: LibraryUiState,
    val favorites: List<FavoriteItem>,
    val sort: SortOption,
    val mostRecent: com.uviewer_android.data.RecentFile?,
    val servers: List<com.uviewer_android.data.WebDavServer>
)

class LibraryViewModel(
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val favoriteDao: FavoriteDao,
    private val webDavServerDao: WebDavServerDao,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository,
    private val credentialsManager: CredentialsManager
) : ViewModel() {

    private var thumbnailPreloadJob: kotlinx.coroutines.Job? = null
    private var thumbnailPreloadItems = emptyList<com.uviewer_android.data.utils.LibraryThumbnail>()
    private var fileNameSearchJob: kotlinx.coroutines.Job? = null

    fun preloadThumbnails(files: List<FileEntry>, cache: com.uviewer_android.data.utils.LibraryThumbnailCache) {
        val thumbnails = files.filter(com.uviewer_android.data.utils.LibraryThumbnail::supports)
            .map(com.uviewer_android.data.utils.LibraryThumbnail::from)
        if (thumbnails == thumbnailPreloadItems && thumbnailPreloadJob?.isActive == true) return
        thumbnailPreloadJob?.cancel()
        thumbnailPreloadItems = thumbnails
        // ViewModel scope keeps folder preparation running while an image viewer is open.
        thumbnailPreloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (thumbnail in thumbnails) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    cache.preload(thumbnail)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    android.util.Log.w("ThumbnailCache", "Unable to preload ${thumbnail.path}", exception)
                }
            }
        }
    }


    private val _state = MutableStateFlow(LibraryUiState())
    private val _viewerBottomBarContent = MutableStateFlow<(@Composable () -> Unit)?>(null)
    val viewerBottomBarContent = _viewerBottomBarContent.asStateFlow()

    fun setViewerBottomBarContent(content: (@Composable () -> Unit)?) {
        _viewerBottomBarContent.value = content
    }

    private val _viewerBottomBarBackgroundColor = MutableStateFlow<Color?>(null)
    val viewerBottomBarBackgroundColor = _viewerBottomBarBackgroundColor.asStateFlow()

    fun setViewerBottomBarBackgroundColor(color: Color?) {
        _viewerBottomBarBackgroundColor.value = color
    }

    private val _servers = webDavServerDao.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sortOption = MutableStateFlow(
        try {
            SortOption.valueOf(userPreferencesRepository.getLibrarySortOption())
        } catch (e: Exception) {
            SortOption.NAME
        }
    )
    private val _pinnedFilesOverride = MutableStateFlow<List<FileEntry>?>(null)

    init {
        val lastTab = userPreferencesRepository.getLastLibraryTab()
        val lastServerId = userPreferencesRepository.getLastServerId()
        
        val isWebDav = lastTab == 1
        var lastPath = when (lastTab) {
            1 -> userPreferencesRepository.getLastWebDavPath() ?: "WebDAV"
            2 -> "/" // Pin tab doesn't have a path, but we need a default
            else -> userPreferencesRepository.getLastLocalPath() ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        }

        // "마지막 위치가 없으면 홈으로" logic handles by default values above.
        // If lastPath doesn't exist, we might want to check, but usually it's persistent.

        val effectiveServerId = if (isWebDav && lastServerId != -1) lastServerId else null
        val effectivePath = if (isWebDav && (effectiveServerId == null || lastPath == "WebDAV")) "WebDAV" else lastPath
        
        _state.value = _state.value.copy(
            selectedTabIndex = lastTab,
            serverId = effectiveServerId,
            currentPath = effectivePath,
            viewMode = userPreferencesRepository.getLibraryViewMode()
        )

        if (lastTab == 2) {
            // Pin tab just shows pinnedFiles from combine flow
        } else if (isWebDav && (effectiveServerId == null || effectivePath == "WebDAV")) {
            showServerList()
        } else {
            loadFiles(effectivePath, effectiveServerId)
        }
    }


    private val librarySources = combine(
        _state,
        favoriteDao.getAllFavorites(),
        _sortOption,
        recentFileDao.getMostRecentFile().onStart { emit(null) },
        _servers
    ) { state, favorites, sort, mostRecent, servers ->
        LibraryCombinedSources(state, favorites, sort, mostRecent, servers)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        librarySources,
        _pinnedFilesOverride
    ) { sources, pinnedOverride ->
        val state = sources.state
        val favorites = sources.favorites
        val sort = sources.sort
        val mostRecent = sources.mostRecent
        val servers = sources.servers

        var listToProcess = state.fileList
        
        // If we are in WebDAV tab and explicitly at the server list path OR no server selected
        val isServerList = state.selectedTabIndex == 1 && (state.serverId == null || state.currentPath == "WebDAV")
        
        if (isServerList) {
            listToProcess = servers.map { server ->
                FileEntry(
                    name = server.name,
                    path = "server:${server.id}", 
                    isDirectory = true,
                    type = FileEntry.FileType.FOLDER,
                    lastModified = 0L,
                    size = 0L,
                    serverId = server.id,
                    isWebDav = true
                )
            }
        }
        
        val sortedList = when (sort) {
            SortOption.NAME -> listToProcess.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortOption.DATE_ASC -> listToProcess.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.lastModified }, { it.name.lowercase() }))
            SortOption.DATE_DESC -> listToProcess.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { -it.lastModified }, { it.name.lowercase() }))
            SortOption.SIZE_ASC -> listToProcess.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.size }, { it.name.lowercase() }))
            SortOption.SIZE_DESC -> listToProcess.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { -it.size }, { it.name.lowercase() }))
        }
        
        // Create FileEntry list from Favorites (including isPinned status)
        val favoriteEntries = favorites.mapNotNull { 
            try {
                FileEntry(
                    name = it.title,
                    path = it.path,
                    isDirectory = it.type == "FOLDER",
                    type = if (it.type == "FOLDER") FileEntry.FileType.FOLDER else FileEntry.FileType.valueOf(it.type.uppercase()),
                    lastModified = 0L,
                    size = 0L,
                    isWebDav = it.isWebDav,
                    serverId = it.serverId,
                    isPinned = it.isPinned,
                    pinOrder = it.pinOrder,
                    position = it.position,
                    positionTitle = it.positionTitle,
                    progress = it.progress
                ) 
            } catch (e: Exception) {
                // If it's a legacy "DOCUMENT" type or invalid, map to TEXT or ignore
                if (it.type.equals("DOCUMENT", ignoreCase = true)) {
                    FileEntry(
                        name = it.title,
                        path = it.path,
                        isDirectory = false,
                        type = FileEntry.FileType.TEXT,
                        lastModified = 0L,
                        size = 0L,
                        isWebDav = it.isWebDav,
                        serverId = it.serverId,
                        isPinned = it.isPinned,
                        pinOrder = it.pinOrder,
                        position = it.position,
                        positionTitle = it.positionTitle,
                        progress = it.progress
                    )
                } else null
            }
        }

        state.copy(
            fileList = sortedList.map { file ->
                // Update isPinned status and position for current file list
                val favorite = favorites.find { it.path == file.path }
                file.copy(
                    isPinned = favorite?.isPinned == true,
                    pinOrder = favorite?.pinOrder ?: 0,
                    position = favorite?.position ?: -1,
                    positionTitle = favorite?.positionTitle,
                    progress = favorite?.progress ?: 0f
                )
            },
            searchResults = state.searchResults.map { file ->
                val favorite = favorites.find { it.path == file.path }
                file.copy(
                    isPinned = favorite?.isPinned == true,
                    pinOrder = favorite?.pinOrder ?: 0,
                    position = favorite?.position ?: -1,
                    positionTitle = favorite?.positionTitle,
                    progress = favorite?.progress ?: 0f
                )
            },
            favoritePaths = favorites.map { it.path }.toSet(),
            pinnedFiles = if (pinnedOverride != null) {
                pinnedOverride.mapNotNull { file ->
                    // Find the current pinned favorite for this path to get latest metadata
                    val fav = favorites.find { it.path == file.path && it.isPinned }
                    if (fav != null) {
                        file.copy(
                            name = fav.title,
                            position = fav.position,
                            positionTitle = fav.positionTitle,
                            progress = fav.progress,
                            isPinned = true
                        )
                    } else null // Item is no longer pinned in DB
                }
            } else {
                favoriteEntries.filter { it.isPinned }.distinctBy { it.path }.sortedBy { it.pinOrder }
            },
            sortOption = sort,
            mostRecentFile = mostRecent
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
        userPreferencesRepository.setLibrarySortOption(option.name)
    }

    fun toggleSortOption() {
        setSortOption(_sortOption.value.nextToggleOption())
    }

    fun toggleViewMode() {
        val newMode = _state.value.viewMode.next()
        _state.value = _state.value.copy(viewMode = newMode)
        userPreferencesRepository.setLibraryViewMode(newMode)
    }

    fun setFileNameFilter(query: String) {
        _state.value = _state.value.copy(fileNameFilter = query)
        startFileNameSearch(query)
    }

    fun clearFileNameFilter() {
        setFileNameFilter("")
    }

    /**
     * (Re)starts the filename search for [query].
     *
     * The filter walks the current folder and every folder beneath it, listing folders on several
     * threads, and publishes each match as soon as it is discovered so the list grows live.
     */
    private fun startFileNameSearch(query: String) {
        fileNameSearchJob?.cancel()
        fileNameSearchJob = null

        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }

        val tabIndex = _state.value.selectedTabIndex
        val searchPath = _state.value.currentPath
        val serverId = _state.value.serverId
        // The pinned tab and the WebDAV server list have no folder tree to walk.
        val canSearch = when (tabIndex) {
            2 -> false
            1 -> serverId != null && searchPath != "WebDAV"
            else -> true
        }
        if (!canSearch) {
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }

        fileNameSearchJob = viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true, searchResults = emptyList())

            val pending = ArrayList<FileEntry>(SEARCH_BATCH_SIZE)
            var lastPublish = System.currentTimeMillis()
            val onResult: suspend (FileEntry) -> Unit = { entry ->
                pending.add(entry)
                val now = System.currentTimeMillis()
                if (pending.size >= SEARCH_BATCH_SIZE || now - lastPublish >= SEARCH_PUBLISH_INTERVAL_MS) {
                    lastPublish = now
                    publishSearchResults(pending)
                }
            }

            try {
                if (tabIndex == 1 && serverId != null) {
                    webDavRepository.searchFilesRecursively(
                        serverId,
                        searchPath,
                        normalizedQuery,
                        onResult = onResult
                    )
                } else {
                    fileRepository.searchFilesRecursively(searchPath, normalizedQuery, onResult = onResult)
                }
                publishSearchResults(pending)
                _state.value = _state.value.copy(isSearching = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w("LibrarySearch", "Filename search failed", e)
                _state.value = _state.value.copy(isSearching = false)
            }
        }
    }

    /**
     * Appends collected matches to [LibraryUiState.searchResults] on the main thread, so the UI state
     * is still updated from one thread while folders keep being listed on the IO threads.
     */
    private suspend fun publishSearchResults(pending: MutableList<FileEntry>) {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            _state.value = _state.value.copy(searchResults = _state.value.searchResults + batch)
        }
    }
    
    fun selectTab(index: Int) {
        if (_state.value.selectedTabIndex == index) return
        
        userPreferencesRepository.setLastLibraryTab(index)
        _pinnedFilesOverride.value = null // Clear override when switching tabs
        
        if (index == 2) {
            fileNameSearchJob?.cancel()
            fileNameSearchJob = null
            _state.value = _state.value.copy(
                selectedTabIndex = index,
                searchResults = emptyList(),
                isSearching = false
            )
            return
        }

        val isWebDav = index == 1
        val lastPath = if (isWebDav) {
             userPreferencesRepository.getLastWebDavPath() ?: "WebDAV"
        } else {
             userPreferencesRepository.getLastLocalPath() ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        }
        val lastServerIdRaw = if (isWebDav) userPreferencesRepository.getLastServerId() else -1
        val effectiveServerId = if (isWebDav && lastServerIdRaw != -1) lastServerIdRaw else null
        val effectivePath = if (isWebDav && (effectiveServerId == null || lastPath == "WebDAV")) "WebDAV" else lastPath

        _state.value = _state.value.copy(
            selectedTabIndex = index, 
            serverId = effectiveServerId,
            currentPath = effectivePath
        )
        
        if (isWebDav && (effectiveServerId == null || effectivePath == "WebDAV")) {
            showServerList()
        } else {
            loadFiles(effectivePath, effectiveServerId)
        }
    }

    private fun showServerList() {
        fileNameSearchJob?.cancel()
        fileNameSearchJob = null
        _state.value = _state.value.copy(
            currentPath = "WebDAV",
            fileList = emptyList(), 
            serverId = null,
            isLoading = false,
            searchResults = emptyList(),
            isSearching = false
        )
        // Persistence: save that we are at the server list
        userPreferencesRepository.setLastServerId(-1)
        userPreferencesRepository.setLastWebDavPath("WebDAV")
    }

    private fun loadFiles(path: String, serverId: Int? = _state.value.serverId) {
        // Guard: Don't load files if we should be showing the server list
        if (_state.value.selectedTabIndex == 1 && (serverId == null || path == "WebDAV")) {
            showServerList()
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val files = if (_state.value.selectedTabIndex == 1 && serverId != null) {
                    webDavRepository.listFiles(serverId, path)
                } else {
                    fileRepository.listFiles(path)
                }
                _state.value = _state.value.copy(
                    currentPath = path,
                    fileList = files,
                    serverId = serverId,
                    isLoading = false
                )
                
                // Save path specifically for the current tab
                if (_state.value.selectedTabIndex == 1) {
                    userPreferencesRepository.setLastWebDavPath(path)
                    serverId?.let { userPreferencesRepository.setLastServerId(it) }
                } else if (_state.value.selectedTabIndex == 0) {
                    userPreferencesRepository.setLastLocalPath(path)
                }
                userPreferencesRepository.setLastLibraryPath(path)
                userPreferencesRepository.setLastLibraryTab(_state.value.selectedTabIndex)

                // The filter walks sub-folders, so restart it from the folder that just loaded.
                if (_state.value.fileNameFilter.isNotBlank()) {
                    startFileNameSearch(_state.value.fileNameFilter)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun toggleFavorite(entry: FileEntry) {
        viewModelScope.launch {
            val favorites = favoriteDao.getAllFavorites().first()
            val existing = favorites.find { it.path == entry.path }
            
            if (existing != null) {
                // Rule: If already favorited, remove it (Toggle OFF)
                favoriteDao.deleteFavoriteByPath(entry.path)
            } else {
                // Rule: Same document name, different location (Transfer Pin / Duplicates)
                val pinnedItem = favorites.find { 
                    (it.title == entry.name || it.title.startsWith("${entry.name} - ")) && it.isPinned 
                }
                val wasPinned = pinnedItem != null

                val sameDocFavorites = favorites.filter { 
                    it.title == entry.name || it.title.startsWith("${entry.name} - ") 
                }
                if (sameDocFavorites.size >= 3) {
                    val oldest = sameDocFavorites.minByOrNull { it.timestamp }
                    if (oldest != null) {
                        favoriteDao.deleteFavorite(oldest)
                    }
                }
                
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = entry.isWebDav,
                        serverId = entry.serverId,
                        type = entry.type.name,
                        isPinned = wasPinned, // Transfer pin status
                        timestamp = System.currentTimeMillis()
                    )
                )
                
                // Unpin the old one if it was different
                if (pinnedItem != null && pinnedItem.path != entry.path) {
                    favoriteDao.updateFavorite(pinnedItem.copy(isPinned = false))
                }
            }
        }
    }

    fun togglePin(entry: FileEntry) {
        viewModelScope.launch {
            val favorites = favoriteDao.getAllFavorites().first()
            val existing = favorites.find { it.path == entry.path }
            
            // When pinning, if another item with same title is pinned, unpin it
            val isPinning = if (existing != null) !existing.isPinned else true
            if (isPinning) {
                val otherPinned = favorites.find { 
                    (it.title == entry.name || it.title.startsWith("${entry.name} - ")) && it.isPinned && it.path != entry.path 
                }
                otherPinned?.let {
                    favoriteDao.updateFavorite(it.copy(isPinned = false))
                }
            }

            if (existing != null) {
                val maxOrder = favorites.filter { it.isPinned }.maxOfOrNull { it.pinOrder } ?: 0
                favoriteDao.updateFavorite(existing.copy(
                    isPinned = !existing.isPinned,
                    pinOrder = if (!existing.isPinned) maxOrder + 1 else 0
                ))
            } else {
                val maxOrder = favorites.filter { it.isPinned }.maxOfOrNull { it.pinOrder } ?: 0
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = entry.isWebDav,
                        serverId = entry.serverId,
                        type = entry.type.name,
                        isPinned = true,
                        pinOrder = maxOrder + 1
                    )
                )
            }
            // Clear override to refresh from DB
            _pinnedFilesOverride.value = null
        }
    }

    fun reorderPinnedFiles(fromIndex: Int, toIndex: Int) {
        val pinned = uiState.value.pinnedFiles
        if (fromIndex !in pinned.indices || toIndex !in pinned.indices) return
        
        val newList = pinned.toMutableList()
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        
        // Update local state immediately
        _pinnedFilesOverride.value = newList
        
        viewModelScope.launch {
            // Re-assign pinOrder based on new positions
            val favorites = favoriteDao.getAllFavorites().first()
            val updates = newList.mapIndexedNotNull { index, file ->
                val fav = favorites.find { it.path == file.path }
                if (fav != null && fav.pinOrder != index) {
                    fav.copy(pinOrder = index)
                } else null
            }
            if (updates.isNotEmpty()) {
                favoriteDao.updateFavorites(updates)
            }
            // Clear override after DB is updated to ensure we sync with source of truth
            _pinnedFilesOverride.value = null
        }
    }

    fun navigateTo(entry: FileEntry) {
        if (entry.isDirectory) {
            val tabIndex = if (entry.isWebDav) 1 else 0
            _state.value = _state.value.copy(selectedTabIndex = tabIndex, serverId = entry.serverId)
            loadFiles(entry.path, entry.serverId)
        }
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath
        val serverId = _state.value.serverId
        val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

        if (_state.value.selectedTabIndex == 1 && serverId != null && currentPath == "/") {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else if (currentPath != rootPath && currentPath != "/" && currentPath != "WebDAV") {
            val parentPath = currentPath.trimEnd('/').substringBeforeLast('/', "/")
            loadFiles(if(parentPath.isEmpty()) "/" else parentPath)
        }
    }

    fun navigateToRoot() {
        if (_state.value.selectedTabIndex == 1) {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else {
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            loadFiles(root)
        }
    }

    fun openFolder(path: String, serverId: Int?) {
        val tabIndex = if (serverId != null && serverId != -1) 1 else 0
        _state.value = _state.value.copy(selectedTabIndex = tabIndex, serverId = serverId)
        loadFiles(path, serverId)
    }

    fun addServer(name: String, url: String, username: String, pass: String) {
        viewModelScope.launch {
            val id = webDavServerDao.insertServer(com.uviewer_android.data.WebDavServer(name = name, url = url)).toInt()
            credentialsManager.saveCredentials(id, username, pass)
        }
    }

    fun deleteServer(server: com.uviewer_android.data.WebDavServer) {
        viewModelScope.launch {
            webDavServerDao.deleteServer(server)
            credentialsManager.clearCredentials(server.id)
            if (_state.value.serverId == server.id) {
                showServerList()
            }
        }
    }

    suspend fun getSiblings(path: String, isWebDav: Boolean, serverId: Int?): List<FileEntry> {
        val parentPath = if (isWebDav) {
            val p = if (path.endsWith("/")) path.dropLast(1) else path
            val lastSlash = p.lastIndexOf('/')
            if (lastSlash == -1) "/" else p.substring(0, lastSlash + 1)
        } else {
            java.io.File(path).parent ?: "/"
        }

        return try {
            if (isWebDav && serverId != null) {
                webDavRepository.listFiles(serverId, parentPath)
            } else {
                fileRepository.listFiles(parentPath)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getNextFile(currentPath: String, isWebDav: Boolean, serverId: Int?): FileEntry? {
        val siblings = getSiblings(currentPath, isWebDav, serverId).filter { !it.isDirectory }
        val index = siblings.indexOfFirst { it.path == currentPath }
        return if (index != -1 && index < siblings.size - 1) siblings[index + 1] else null
    }

    suspend fun getPrevFile(currentPath: String, isWebDav: Boolean, serverId: Int?): FileEntry? {
        val siblings = getSiblings(currentPath, isWebDav, serverId).filter { !it.isDirectory }
        val index = siblings.indexOfFirst { it.path == currentPath }
        return if (index > 0) siblings[index - 1] else null
    }

    private companion object {
        /** Matches pushed to the UI in one batch while a recursive filename search runs. */
        const val SEARCH_BATCH_SIZE = 64

        /** Longest time results are collected before they are pushed to the UI. */
        const val SEARCH_PUBLISH_INTERVAL_MS = 120L
    }
}
