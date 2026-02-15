package com.uviewer_android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.data.repository.CredentialsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryUiState(
    val currentPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    val fileList: List<FileEntry> = emptyList(),
    val favoritePaths: Set<String> = emptySet(),
    val pinnedFiles: List<FileEntry> = emptyList(), // For Pin Tab
    val mostRecentFile: com.uviewer_android.data.RecentFile? = null,
    val isLoading: Boolean = false,
    val selectedTabIndex: Int = 0,
    val serverId: Int? = null,
    val error: String? = null,
    val sortOption: SortOption = SortOption.NAME
)

enum class SortOption { NAME, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }

class LibraryViewModel(
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val favoriteDao: FavoriteDao,
    private val webDavServerDao: WebDavServerDao,
    private val recentFileDao: com.uviewer_android.data.RecentFileDao,
    private val userPreferencesRepository: com.uviewer_android.data.repository.UserPreferencesRepository,
    private val credentialsManager: CredentialsManager
) : ViewModel() {


    private val _state = MutableStateFlow(LibraryUiState())
    private val _servers = webDavServerDao.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sortOption = MutableStateFlow(SortOption.NAME)

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
            currentPath = effectivePath
        )

        if (lastTab == 2) {
            // Pin tab just shows pinnedFiles from combine flow
        } else if (isWebDav && (effectiveServerId == null || effectivePath == "WebDAV")) {
            showServerList()
        } else {
            loadFiles(effectivePath, effectiveServerId)
        }
    }


    val uiState: StateFlow<LibraryUiState> = combine(
        _state,
        favoriteDao.getAllFavorites(),
        _sortOption,
        recentFileDao.getMostRecentFile().onStart { emit(null) },
        _servers
    ) { state, favorites, sort, mostRecent, servers ->
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
            SortOption.DATE_ASC -> listToProcess.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
            SortOption.DATE_DESC -> listToProcess.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
            SortOption.SIZE_ASC -> listToProcess.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
            SortOption.SIZE_DESC -> listToProcess.sortedWith(compareBy({ !it.isDirectory }, { -it.size }))
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
                    isPinned = it.isPinned
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
                        isPinned = it.isPinned
                    )
                } else null
            }
        }

        state.copy(
            fileList = sortedList.map { file ->
                // Update isPinned status for current file list
                val favorite = favorites.find { it.path == file.path }
                file.copy(isPinned = favorite?.isPinned == true)
            },
            favoritePaths = favorites.map { it.path }.toSet(),
            pinnedFiles = favoriteEntries.filter { it.isPinned }.sortedBy { it.name.lowercase() },
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
    }
    
    fun selectTab(index: Int) {
        if (_state.value.selectedTabIndex == index) return
        
        userPreferencesRepository.setLastLibraryTab(index)
        
        if (index == 2) {
            _state.value = _state.value.copy(selectedTabIndex = index)
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
        _state.value = _state.value.copy(
            currentPath = "WebDAV",
            fileList = emptyList(), 
            serverId = null,
            isLoading = false
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
                favoriteDao.deleteFavorite(existing)
            } else {
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = entry.isWebDav,
                        serverId = entry.serverId,
                        type = entry.type.name,
                        isPinned = false // Default to false when starring
                    )
                )
            }
        }
    }

    fun togglePin(entry: FileEntry) {
        viewModelScope.launch {
            val favorites = favoriteDao.getAllFavorites().first()
            val existing = favorites.find { it.path == entry.path }
            if (existing != null) {
                // Update existing favorite
                favoriteDao.updateFavorite(existing.copy(isPinned = !existing.isPinned))
            } else {
                // If pinning from file list and not yet favorited, insert as Favorite AND Pinned
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = entry.isWebDav,
                        serverId = entry.serverId,
                        type = entry.type.name,
                        isPinned = true
                    )
                )
            }
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
}
