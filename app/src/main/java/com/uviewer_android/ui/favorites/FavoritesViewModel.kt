package com.uviewer_android.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val favoriteDao: FavoriteDao,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _isGridView = MutableStateFlow(userPreferencesRepository.getLibraryViewMode())
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _pinnedFavoritesOverride = MutableStateFlow<List<FileEntry>?>(null)

    val favorites: StateFlow<List<FileEntry>> = combine(
        favoriteDao.getAllFavorites(),
        _pinnedFavoritesOverride
    ) { favorites, override ->
        if (override != null) {
            // Filter out items that are no longer pinned or deleted in DB, but keep the override order
            override.mapNotNull { entry ->
                val fav = favorites.find { it.path == entry.path && it.isPinned }
                if (fav != null) {
                    entry.copy(
                        name = fav.title,
                        isPinned = true,
                        pinOrder = fav.pinOrder,
                        position = fav.position,
                        positionTitle = fav.positionTitle,
                        progress = fav.progress
                    )
                } else null
            }
        } else {
            // Create FileEntry list from Favorites consistently with LibraryViewModel
            favorites.mapNotNull { it.toFileEntry() }.distinctBy { it.path }.sortedByDescending { it.lastModified }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun toggleViewMode() {
        val newMode = !_isGridView.value
        _isGridView.value = newMode
        userPreferencesRepository.setLibraryViewMode(newMode)
    }

    fun deleteFavorite(item: FileEntry) {
        viewModelScope.launch {
            favoriteDao.deleteFavoriteByPath(item.path)
        }
    }

    fun togglePin(item: FileEntry) {
        viewModelScope.launch {
            val favorites = favoriteDao.getAllFavorites().first()
            val existing = favorites.find { it.path == item.path }
            val maxOrder = favorites.filter { it.isPinned }.maxOfOrNull { it.pinOrder } ?: 0
            
            if (existing != null) {
                favoriteDao.updateFavorite(existing.copy(
                    isPinned = !existing.isPinned,
                    pinOrder = if (!existing.isPinned) maxOrder + 1 else 0
                ))
            }
            _pinnedFavoritesOverride.value = null
        }
    }

    fun reorderPinnedFavorites(fromIndex: Int, toIndex: Int) {
        val currentPinned = favorites.value.filter { it.isPinned }.sortedBy { it.pinOrder }
        if (fromIndex !in currentPinned.indices || toIndex !in currentPinned.indices) return

        val newList = currentPinned.toMutableList()
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)

        _pinnedFavoritesOverride.value = newList

        viewModelScope.launch {
            val allFavorites = favoriteDao.getAllFavorites().first()
            val updates = newList.mapIndexedNotNull { index, entry ->
                val fav = allFavorites.find { it.path == entry.path }
                if (fav != null && fav.pinOrder != index) {
                    fav.copy(pinOrder = index)
                } else null
            }
            if (updates.isNotEmpty()) {
                favoriteDao.updateFavorites(updates)
            }
            _pinnedFavoritesOverride.value = null
        }
    }

    private fun FavoriteItem.toFileEntry(): FileEntry? {
        return try {
            FileEntry(
                name = title,
                path = path,
                isDirectory = type == "FOLDER",
                type = if (type == "FOLDER") FileEntry.FileType.FOLDER else FileEntry.FileType.valueOf(type.uppercase()),
                lastModified = timestamp,
                size = 0L,
                isWebDav = isWebDav,
                serverId = serverId,
                isPinned = isPinned,
                pinOrder = pinOrder,
                position = position,
                positionTitle = positionTitle,
                progress = progress
            )
        } catch (e: Exception) {
            if (type.equals("DOCUMENT", ignoreCase = true)) {
                FileEntry(
                    name = title,
                    path = path,
                    isDirectory = false,
                    type = FileEntry.FileType.TEXT,
                    lastModified = timestamp,
                    size = 0L,
                    isWebDav = isWebDav,
                    serverId = serverId,
                    isPinned = isPinned,
                    pinOrder = pinOrder,
                    position = position,
                    positionTitle = positionTitle,
                    progress = progress
                )
            } else null
        }
    }
}
