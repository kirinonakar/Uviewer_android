package com.uviewer_android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import kotlinx.coroutines.flow.combine

data class LibraryUiState(
    val currentPath: String = "/",
    val fileList: List<FileEntry> = emptyList(),
    val favoritePaths: Set<String> = emptySet(), // Paths of favorite items
    val isLoading: Boolean = false,
    val isWebDav: Boolean = false,
    val serverId: Int? = null,
    val error: String? = null
)

class LibraryViewModel(
    private val fileRepository: FileRepository,
    private val webDavRepository: WebDavRepository,
    private val favoriteDao: FavoriteDao,
    private val webDavServerDao: com.uviewer_android.data.WebDavServerDao
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    // Keep track of servers
    private val _servers = webDavServerDao.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Combine state with favorites flow
    val uiState: StateFlow<LibraryUiState> = combine(
        _state,
        favoriteDao.getAllFavorites(),
        _servers
    ) { state, favorites, servers ->
        // If at root and local, append servers
        val files = if (state.currentPath == "/" && state.serverId == null) {
            val serverEntries = servers.map { server ->
                FileEntry(
                    name = server.name,
                    path = "/", // Root of that server
                    isDirectory = true,
                    type = FileEntry.FileType.FOLDER,
                    lastModified = 0L,
                    size = 0L,
                    serverId = server.id, // Important: ID links to server
                    isWebDav = true
                )
            }
            // Local files + Server entries
            // We need to fetch local files too. They are in state.fileList?
            // Yes, state.fileList contains what loadFiles fetched.
            // But we need to make sure we don't duplicate or overwrite if we use this combine.
            // Actually, loadFiles sets fileList.
            // If we just modify fileList here, it works visually.
            // But if loadFiles *only* fetches local files, we add servers here.
            state.fileList + serverEntries
        } else {
            state.fileList
        }
        
        state.copy(
            fileList = files,
            favoritePaths = favorites.map { it.path }.toSet()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    init {
        loadFiles("/")
    }

    fun loadFiles(path: String, serverId: Int? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val files = if (serverId != null) {
                    webDavRepository.listFiles(serverId, path)
                } else {
                    fileRepository.listFiles(path)
                }
                
                // If root local, we only fetched local files here. 
                // The combine block will add servers.
                
                _state.value = _state.value.copy(
                    currentPath = path,
                    fileList = files,
                    isWebDav = serverId != null,
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
            val isFavorite = uiState.value.favoritePaths.contains(entry.path)
            if (isFavorite) {
                // To delete, we need the favorite item. 
                // Since we only have path in set, we need to find it or change DAO to delete by path.
                // Or just get the list from flow and find it.
                // Ideally DAO has deleteByPath.
                // Implementation:
                // We'll iterate current favorites from flow (we don't have direct access here easily without collecting).
                // Actually uiState is derived.
                // Let's add deleteByPath to DAO or fetch-delete.
                // Simple fetch-delete:
                favoriteDao.deleteFavoriteByPath(entry.path)
            } else {
                favoriteDao.insertFavorite(
                    FavoriteItem(
                        title = entry.name,
                        path = entry.path,
                        isWebDav = uiState.value.isWebDav,
                        serverId = uiState.value.serverId,
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
        val parentPath = java.io.File(currentPath).parent ?: "/"
        loadFiles(parentPath, _state.value.serverId)
    }
}
