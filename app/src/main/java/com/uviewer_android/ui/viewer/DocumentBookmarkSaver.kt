package com.uviewer_android.ui.viewer

import com.uviewer_android.data.Bookmark
import com.uviewer_android.data.BookmarkDao
import com.uviewer_android.data.FavoriteDao
import com.uviewer_android.data.FavoriteItem
import kotlinx.coroutines.flow.first
import java.io.File

internal object DocumentBookmarkSaver {
    suspend fun save(
        bookmarkDao: BookmarkDao,
        favoriteDao: FavoriteDao,
        path: String,
        line: Int,
        isWebDav: Boolean,
        serverId: Int?,
        type: String,
        uiState: DocumentViewerUiState,
        epubChapterStartLines: Map<Int, Int>,
        isEpubFlat: Boolean
    ) {
        val fileName = File(path).name
        val savePosition = if (type == "EPUB") {
            if (isEpubFlat) {
                val chIdx = epubChapterStartLines.entries
                    .filter { it.value <= line }
                    .maxByOrNull { it.value }?.key ?: 0
                val startLine = epubChapterStartLines[chIdx] ?: 1
                val lineInChapter = (line - startLine + 1).coerceAtLeast(1)
                chIdx * 1000000 + lineInChapter
            } else {
                uiState.currentChapterIndex * 1000000 + line
            }
        } else {
            line
        }

        val bookmarkTitle = when (type) {
            "EPUB" -> {
                val ch = uiState.currentChapterIndex + 1
                val total = uiState.totalLines
                val pct = if (total > 0) (line * 100 / total) else 0
                "$fileName - ch$ch $pct%"
            }
            "DOCUMENT", "TEXT", "PDF" -> "$fileName - line $line"
            else -> fileName
        }

        bookmarkDao.insertBookmark(
            Bookmark(
                title = bookmarkTitle,
                path = path,
                isWebDav = isWebDav,
                serverId = serverId,
                type = type,
                position = savePosition,
                timestamp = System.currentTimeMillis()
            )
        )

        val favorites = favoriteDao.getAllFavorites().first()
        val existing = favorites.find { it.path == path && it.position == savePosition }
        val pinnedItem = favorites.find {
            (it.path == path || it.title == fileName || it.title.startsWith("$fileName - ")) && it.isPinned
        }
        val wasPinned = pinnedItem != null

        if (existing != null) {
            favoriteDao.updateFavorite(
                existing.copy(
                    timestamp = System.currentTimeMillis(),
                    isPinned = wasPinned || existing.isPinned,
                    pinOrder = if (wasPinned) pinnedItem.pinOrder else existing.pinOrder
                )
            )
            if (pinnedItem != null && pinnedItem.id != existing.id) {
                favoriteDao.updateFavorite(pinnedItem.copy(isPinned = false, pinOrder = 0))
            }
            return
        }

        val sameDocFavorites = favorites.filter {
            it.path == path || it.title == fileName || it.title.startsWith("$fileName - ")
        }
        if (sameDocFavorites.size >= 3) {
            val oldest = sameDocFavorites.minByOrNull { it.timestamp }
            if (oldest != null) {
                favoriteDao.deleteFavorite(oldest)
            }
        }
        val progress = if (type == "EPUB") {
            val totalChapters = uiState.epubChapters.size
            if (totalChapters > 0) (uiState.currentChapterIndex.toFloat() / totalChapters) else 0f
        } else {
            if (uiState.totalLines > 0) (line.toFloat() / uiState.totalLines) else 0f
        }

        favoriteDao.insertFavorite(
            FavoriteItem(
                title = bookmarkTitle,
                path = path,
                isWebDav = isWebDav,
                serverId = serverId,
                type = type,
                position = savePosition,
                isPinned = wasPinned,
                pinOrder = if (wasPinned) pinnedItem.pinOrder else 0,
                progress = progress,
                timestamp = System.currentTimeMillis()
            )
        )
        if (pinnedItem != null && (pinnedItem.path != path || pinnedItem.position != savePosition)) {
            favoriteDao.updateFavorite(pinnedItem.copy(isPinned = false, pinOrder = 0))
        }
    }
}
