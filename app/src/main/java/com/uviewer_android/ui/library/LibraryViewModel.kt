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
    val currentPath: String = "/",
    val fileList: List<FileEntry> = emptyList(),
    val favoritePaths: Set<String> = emptySet(),
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

    val uiState: StateFlow<LibraryUiState> = combine(
        _state,
        favoriteDao.getAllFavorites(),
        _sortOption,
        recentFileDao.getMostRecentFile()
    ) { state, favorites, sort, mostRecent ->
        val sortedList = if (state.currentPath == "WebDAV" && state.fileList.all { it.isWebDav && it.isDirectory && it.path == "/" }) {
            // Server list, usually by ID or Name
            state.fileList.sortedBy { it.name }
        } else {
            when (sort) {
                SortOption.NAME -> state.fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                SortOption.DATE_ASC -> state.fileList.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
                SortOption.DATE_DESC -> state.fileList.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
                SortOption.SIZE_ASC -> state.fileList.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
                SortOption.SIZE_DESC -> state.fileList.sortedWith(compareBy({ !it.isDirectory }, { -it.size }))
            }
        }
        
        state.copy(
            fileList = sortedList,
            favoritePaths = favorites.map { it.path }.toSet(),
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
        _state.value = _state.value.copy(isWebDavTab = isWebDav, serverId = null)
        if (isWebDav) {
            showServerList()
        } else {
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            loadFiles(root)
        }
    }

    private fun showServerList() {
        viewModelScope.launch { 
            val servers = _servers.first()
            val serverEntries = servers.map { server ->
                FileEntry(
                    name = server.name,
                    path = "/", // Root of that server
                    isDirectory = true,
                    type = FileEntry.FileType.FOLDER,
                    lastModified = 0L,
                    size = 0L,
                    serverId = server.id,
                    isWebDav = true
                )
            }
            _state.value = _state.value.copy(
                currentPath = "WebDAV",
                fileList = serverEntries,
                isLoading = false
            )
        }
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
            if (uiState.value.favoritePaths.contains(entry.path)) {
                favoriteDao.deleteFavoriteByPath(entry.path)
            } else {
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = entry.isWebDav,
                        serverId = entry.serverId,
                        type = entry.type.name
                    )
                )
            }
        }
    }

    fun navigateTo(entry: FileEntry) {
        if (entry.isDirectory) {
            loadFiles(entry.path, entry.serverId)
        }
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath
        val serverId = _state.value.serverId

        if (_state.value.isWebDavTab && serverId != null && currentPath == "/") {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else if (currentPath != "/") {
            val parentPath = currentPath.trimEnd('/').substringBeforeLast('/', "/")
            loadFiles(if(parentPath.isEmpty()) "/" else parentPath)
        }
    }

    fun navigateToRoot() {
        if (_state.value.isWebDavTab) {
            showServerList()
            _state.value = _state.value.copy(serverId = null)
        } else {
            loadFiles("/")
        }
    }

    fun openFolder(path: String, serverId: Int?) {
        val isWebDav = serverId != null && serverId != -1
        _state.value = _state.value.copy(isWebDavTab = isWebDav, serverId = serverId)
        loadFiles(path, serverId)
    }
}