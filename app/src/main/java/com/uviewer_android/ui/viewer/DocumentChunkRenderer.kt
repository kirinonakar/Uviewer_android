package com.uviewer_android.ui.viewer

import android.app.Application
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.parser.AozoraParser
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object DocumentChunkRenderer {
    suspend fun renderTextChunk(
        chunkText: String,
        chunkIndex: Int,
        globalLineOffset: Int,
        filePath: String,
        fileType: FileEntry.FileType?,
        isWebDavContext: Boolean,
        serverIdContext: Int?,
        uiState: DocumentViewerUiState,
        languageTag: String,
        colors: Pair<String, String>,
        application: Application,
        webDavRepository: WebDavRepository
    ): String = withContext(Dispatchers.Default) {
        if (fileType == FileEntry.FileType.HTML || filePath.endsWith(".html", ignoreCase = true)) {
            val resetCss = DocumentHtmlTemplates.textHtmlResetCss(
                uiState = uiState,
                colors = colors,
                fontFamily = htmlFontFamily(uiState)
            )
            return@withContext DocumentHtmlTemplates.wrapRawHtmlChunk(
                chunkText = chunkText,
                languageTag = languageTag,
                resetCss = resetCss
            )
        }

        val imageRootPath = if (!isWebDavContext) {
            val separator = File.separator
            "file:///${File(filePath).parentFile?.absolutePath?.replace(separator, "/") ?: ""}"
        } else {
            ""
        }

        var htmlBody = if (filePath.endsWith(".md", ignoreCase = true)) {
            convertDocumentMarkdownToHtml(chunkText)
        } else {
            AozoraParser.parse(chunkText, globalLineOffset, imageRootPath, uiState.isVertical)
        }

        htmlBody = resolveChunkImages(
            htmlBody = htmlBody,
            filePath = filePath,
            isWebDavContext = isWebDavContext,
            serverIdContext = serverIdContext,
            application = application,
            webDavRepository = webDavRepository
        )

        AozoraParser.wrapInHtml(
            htmlBody,
            uiState.isVertical,
            uiState.fontFamily,
            uiState.fontSize,
            colors.first,
            colors.second,
            uiState.sideMargin,
            chunkIndex,
            languageTag
        )
    }

    suspend fun renderEpubFlatChunk(
        flatLines: List<String>,
        startLine: Int,
        endLine: Int,
        globalLineOffset: Int,
        chunkIndex: Int,
        uiState: DocumentViewerUiState,
        languageTag: String,
        colors: Pair<String, String>
    ): String = withContext(Dispatchers.Default) {
        val lines = flatLines.subList(startLine - 1, endLine)
        val htmlBody = lines.mapIndexed { index, line ->
            wrapEpubFlatLine(line, globalLineOffset + index + 1)
        }.joinToString("\n")

        AozoraParser.wrapInHtml(
            htmlBody,
            uiState.isVertical,
            uiState.fontFamily,
            uiState.fontSize,
            colors.first,
            colors.second,
            uiState.sideMargin,
            chunkIndex,
            languageTag
        )
    }

    private suspend fun resolveChunkImages(
        htmlBody: String,
        filePath: String,
        isWebDavContext: Boolean,
        serverIdContext: Int?,
        application: Application,
        webDavRepository: WebDavRepository
    ): String {
        if (!isWebDavContext) {
            return resolveDocumentLocalImages(
                content = htmlBody,
                parentDir = File(filePath).parentFile,
                webDavRepository = webDavRepository,
                application = application,
                serverId = null
            )
        }

        if (serverIdContext == null) return htmlBody

        val parentPath = if (filePath.contains("/")) filePath.substringBeforeLast("/") else ""
        return resolveDocumentLocalImages(
            content = htmlBody,
            parentDir = null,
            webDavRepository = webDavRepository,
            application = application,
            serverId = serverIdContext,
            parentPath = parentPath
        )
    }

    private fun wrapEpubFlatLine(line: String, lineNumber: Int): String {
        val trimmed = line.trim()
        return when {
            line.isEmpty() -> "<div id=\"line-$lineNumber\" class=\"blank-line\"></div>"
            trimmed.startsWith("<img", ignoreCase = true) ||
                trimmed.startsWith("<svg", ignoreCase = true) ||
                trimmed.startsWith("<image", ignoreCase = true) ||
                trimmed.startsWith("<figure", ignoreCase = true) -> {
                "<div id=\"line-$lineNumber\" class=\"image-page-wrapper\">$line</div>"
            }
            else -> "<div id=\"line-$lineNumber\">$line</div>"
        }
    }

    private fun htmlFontFamily(uiState: DocumentViewerUiState): String {
        return when (uiState.fontFamily) {
            "serif" -> "serif"
            "sans-serif" -> "sans-serif"
            else -> "serif"
        }
    }
}
