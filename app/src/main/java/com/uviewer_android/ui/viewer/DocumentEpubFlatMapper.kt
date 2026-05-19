package com.uviewer_android.ui.viewer

import com.uviewer_android.data.model.EpubSpineItem

internal data class DocumentEpubFlatPosition(
    val flatStartLine: Int,
    val chunkIndex: Int,
    val tocChapters: List<EpubSpineItem>,
    val currentChapterIndex: Int
)

internal object DocumentEpubFlatMapper {
    fun mapSavedPosition(
        savedLine: Int,
        flatLines: List<String>,
        chapterStarts: Map<Int, Int>,
        chapterLineCounts: Map<Int, Int>,
        spine: List<EpubSpineItem>,
        linesPerChunk: Int
    ): DocumentEpubFlatPosition {
        val flatStartLine = resolveFlatStartLine(
            savedLine = savedLine,
            flatLineCount = flatLines.size,
            chapterStarts = chapterStarts,
            chapterLineCounts = chapterLineCounts
        )
        val chunkIndex = (flatStartLine - 1) / linesPerChunk
        val tocChapters = spine.mapIndexed { index, item ->
            val startLine = chapterStarts[index] ?: 1
            item.copy(href = "line-$startLine")
        }
        val currentChapterIndex = tocChapters.indexOfLast {
            it.href.startsWith("line-") &&
                (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= flatStartLine
        }.coerceAtLeast(0)

        return DocumentEpubFlatPosition(
            flatStartLine = flatStartLine,
            chunkIndex = chunkIndex,
            tocChapters = tocChapters,
            currentChapterIndex = currentChapterIndex
        )
    }

    private fun resolveFlatStartLine(
        savedLine: Int,
        flatLineCount: Int,
        chapterStarts: Map<Int, Int>,
        chapterLineCounts: Map<Int, Int>
    ): Int {
        val chapterIndex = savedLine / 1000000
        val lineInChapter = savedLine % 1000000
        if (chapterIndex !in chapterStarts) {
            return savedLine.coerceIn(1, flatLineCount)
        }

        val chapterStart = chapterStarts[chapterIndex] ?: 1
        val chapterLineCount = chapterLineCounts[chapterIndex] ?: 0
        if (lineInChapter > chapterLineCount &&
            lineInChapter >= chapterStart &&
            lineInChapter <= flatLineCount
        ) {
            return lineInChapter
        }

        return (chapterStart + lineInChapter - 1).coerceIn(1, flatLineCount)
    }
}
