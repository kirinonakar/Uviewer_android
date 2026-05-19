package com.uviewer_android.ui.viewer

import com.uviewer_android.data.model.EpubSpineItem
import com.uviewer_android.data.parser.AozoraParser
import com.uviewer_android.data.utils.LargeTextReader

internal object DocumentTextTocScanner {
    suspend fun scan(
        reader: LargeTextReader,
        totalLines: Int,
        currentLine: () -> Int,
        onUpdate: (chapters: List<EpubSpineItem>, currentChapterIndex: Int) -> Unit
    ) {
        val allChapters = mutableListOf<EpubSpineItem>()
        val scanChunkSize = 10000
        for (startLine in 1..totalLines step scanChunkSize) {
            val chunkText = reader.readLines(startLine, scanChunkSize)
            val chunkChapters = AozoraParser.extractTitles(chunkText, startLine - 1).map { (title, line) ->
                EpubSpineItem(
                    title = title,
                    href = "line-$line",
                    id = "line-$line"
                )
            }
            allChapters.addAll(chunkChapters)
            if (allChapters.size % 50 == 0 || startLine + scanChunkSize > totalLines) {
                val currentIndex = allChapters.indexOfLast {
                    it.href.startsWith("line-") &&
                        (it.href.removePrefix("line-").toIntOrNull() ?: 0) <= currentLine()
                }.coerceAtLeast(0)
                onUpdate(allChapters.toList(), currentIndex)
            }
        }
    }
}
