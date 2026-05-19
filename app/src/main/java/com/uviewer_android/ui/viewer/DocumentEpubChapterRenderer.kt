package com.uviewer_android.ui.viewer

import com.uviewer_android.data.parser.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class DocumentEpubChapterContent(
    val content: String,
    val lineCount: Int,
    val baseUrl: String,
    val isImageOnly: Boolean
)

internal object DocumentEpubChapterRenderer {
    suspend fun render(
        chapterHref: String,
        chapterIndex: Int,
        uiState: DocumentViewerUiState,
        colors: Pair<String, String>
    ): DocumentEpubChapterContent = withContext(Dispatchers.Default) {
        val chapterFile = File(chapterHref.substringBefore("#"))
        val rawContent = try {
            chapterFile.readText()
        } catch (e: Exception) {
            "Error reading chapter: ${e.message}"
        }
        val resetCss = DocumentHtmlTemplates.epubChapterResetCss(
            uiState = uiState,
            colors = colors,
            fontFamily = epubFontFamily(uiState)
        )
        val processedResult = EpubParser.prepareHtmlForViewer(
            rawContent,
            resetCss,
            baseDir = chapterFile.parentFile,
            idPrefix = "$chapterIndex-",
            isVertical = uiState.isVertical
        )
        val content = processedResult.first
        val lineCount = processedResult.second
        val separator = File.separator

        DocumentEpubChapterContent(
            content = content,
            lineCount = lineCount,
            baseUrl = "file:///${chapterFile.parent?.replace(separator, "/")}/",
            isImageOnly = lineCount <= 3 && content.contains("image-page-wrapper")
        )
    }

    private fun epubFontFamily(uiState: DocumentViewerUiState): String {
        return when (uiState.fontFamily) {
            "serif" -> "'Sawarabi Mincho', serif"
            "sans-serif" -> "'Sawarabi Gothic', sans-serif"
            else -> "serif"
        }
    }
}
