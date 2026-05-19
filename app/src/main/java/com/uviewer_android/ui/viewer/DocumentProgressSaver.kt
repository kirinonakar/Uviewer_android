package com.uviewer_android.ui.viewer

import com.uviewer_android.data.RecentFileDao
import com.uviewer_android.data.model.FileEntry
import kotlinx.coroutines.runBlocking
import java.io.File

internal data class DocumentProgressSaveData(
    val savePosition: Int,
    val progress: Float,
    val title: String
)

internal object DocumentProgressSaver {
    fun compute(
        line: Int,
        filePath: String,
        fileType: FileEntry.FileType?,
        uiState: DocumentViewerUiState,
        isEpubFlat: Boolean,
        epubChapterStartLines: Map<Int, Int>
    ): DocumentProgressSaveData? {
        if (filePath.isEmpty()) return null

        val fileName = File(filePath).name
        val savePosition = if (fileType == FileEntry.FileType.EPUB) {
            if (isEpubFlat) {
                val chIdx = epubChapterStartLines.entries
                    .filter { it.value <= line }
                    .maxByOrNull { it.value }?.key ?: 0
                val lineInChapter = line - (epubChapterStartLines[chIdx] ?: 1) + 1
                chIdx * 1000000 + lineInChapter
            } else {
                uiState.currentChapterIndex * 1000000 + line
            }
        } else {
            line
        }

        val title = if (fileType == FileEntry.FileType.EPUB) {
            val ch = uiState.currentChapterIndex + 1
            val total = uiState.totalLines
            val pct = if (total > 0) (line * 100 / total) else 0
            "$fileName - ch$ch $pct%"
        } else {
            fileName
        }

        val totalLines = uiState.totalLines
        val progress = if (fileType == FileEntry.FileType.EPUB) {
            if (isEpubFlat) {
                if (totalLines > 0) (line.toFloat() / totalLines) else 0f
            } else {
                val totalChapters = uiState.epubChapters.size
                if (totalChapters > 0) (uiState.currentChapterIndex.toFloat() / totalChapters) else 0f
            }
        } else {
            if (totalLines > 0) (line.toFloat() / totalLines) else 0f
        }

        return DocumentProgressSaveData(savePosition, progress, title)
    }

    suspend fun update(
        recentFileDao: RecentFileDao,
        filePath: String,
        data: DocumentProgressSaveData
    ) {
        recentFileDao.updatePosition(
            path = filePath,
            pageIndex = data.savePosition,
            progress = data.progress,
            title = data.title,
            lastAccessed = System.currentTimeMillis()
        )
    }

    fun updateBlocking(
        recentFileDao: RecentFileDao,
        filePath: String,
        data: DocumentProgressSaveData
    ) {
        runBlocking {
            update(recentFileDao, filePath, data)
        }
    }
}
