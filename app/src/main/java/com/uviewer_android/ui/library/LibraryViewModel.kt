package com.uviewer_android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.FileRepository
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryUiState(
    val currentPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    val fileList: List<FileEntry> = emptyList(),
    val favoritePaths: Set<String> = emptySet(),
    val pinnedFiles: List<FileEntry> = emptyList(), // For Pin Tab
    val mostRecentFile: com.uviewer_android.data.RecentFile? = null,
    val isLoading: Boolean = false,
    val isWebDavTab: Boolean = false,
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
    private val recentFileDao: com.uviewer_android.data.RecentFileDao
) : ViewModel() {


    private val _state = MutableStateFlow(LibraryUiState())
    private val _servers = webDavServerDao.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sortOption = MutableStateFlow(SortOption.NAME)

    init {
        // Force initial load of Local root directory as soon as ViewModel is created
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        loadFiles(root)
    }


    val uiState: StateFlow<LibraryUiState> = combine(
        _state,
        favoriteDao.getAllFavorites(),
        _sortOption,
        recentFileDao.getMostRecentFile(),
        webDavServerDao.getAllServers()
    ) { state, favorites, sort, mostRecent, servers ->
        var listToProcess = state.fileList
        
        // If we are in WebDAV tab and no server selected, show server list automatically
        if (state.isWebDavTab && state.serverId == null) {
            listToProcess = servers.map { server ->
                FileEntry(
                    name = server.name,
                    path = "/",
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
        val favoriteEntries = favorites.map { 
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
    
    fun loadInitialPath(isWebDav: Boolean) {
        _state.value = _state.value.copy(
            isWebDavTab = isWebDav, 
            serverId = null,
            currentPath = if (isWebDav) "WebDAV" else android.os.Environment.getExternalStorageDirectory().absolutePath
        )
        if (isWebDav) {
            showServerList()
        } else {
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            loadFiles(root)
        }
    }

    private fun showServerList() {
        _state.value = _state.value.copy(
            currentPath = "WebDAV",
            fileList = emptyList(), // Will be populated by combine
            serverId = null,
            isLoading = false
        )
    }

    private fun loadFiles(path: String, serverId: Int? = _state.value.serverId) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val files = if (_state.value.isWebDavTab && serverId != null) {
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
            val existing = favoriteDao.getAllFavorites().first().find { it.path == entry.path }
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
            val existing = favoriteDao.getAllFavorites().first().find { it.path == entry.path }
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
            _state.value = _state.value.copy(isWebDavTab = entry.isWebDav, serverId = entry.serverId)
            loadFiles(entry.path, entry.serverId)
        }
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath
        val serverId = _state.value.serverId
        val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

        if (_state.value.isWebDavTab && serverId != null && currentPath == "/") {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else if (currentPath != rootPath && currentPath != "/") {
            val parentPath = currentPath.trimEnd('/').substringBeforeLast('/', "/")
            loadFiles(if(parentPath.isEmpty()) "/" else parentPath)
        }
    }

    fun navigateToRoot() {
        if (_state.value.isWebDavTab) {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else {
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            loadFiles(root)
        }
    }

    fun openFolder(path: String, serverId: Int?) {
        val isWebDav = serverId != null && serverId != -1
        _state.value = _state.value.copy(isWebDavTab = isWebDav, serverId = serverId)
        loadFiles(path, serverId)
    }
}