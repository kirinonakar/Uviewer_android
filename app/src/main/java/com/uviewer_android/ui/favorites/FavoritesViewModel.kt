package com.uviewer_android.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val favoriteDao: FavoriteDao
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteItem>> = favoriteDao.getAllFavorites()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteFavorite(item: FavoriteItem) {
        viewModelScope.launch {
            favoriteDao.deleteFavorite(item)
        }
    }
}
