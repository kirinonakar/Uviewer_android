package com.uviewer_android.ui.viewer

import com.uviewer_android.data.repository.UserPreferencesRepository

/** Content/layout inputs only: scrolling, indexing progress and TOC updates must not rebuild HTML. */
internal data class DocumentWebPageRequest(
    val content: String,
    val isVertical: Boolean,
    val background: String,
    val textColor: String,
    val fontFamily: String,
    val fontSize: Int,
    val sideMargin: Int,
    val baseUrl: String?,
    val resourceRoots: List<String>
) {
    fun styledHtml(): String {
        val style = ViewerScripts.getStyleSheet(isVertical, background, textColor, fontFamily, fontSize, sideMargin)
        val viewport = if (isVertical) {
            "<meta name=\"viewport\" content=\"height=device-height, initial-scale=1.0, user-scalable=yes, minimum-scale=1.0, maximum-scale=5.0\">"
        } else {
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=yes\">"
        }
        var html = content
        if (html.contains("<meta name=\"viewport\"", ignoreCase = true)) {
            html = viewportPattern.replace(html, viewport)
        } else if (html.contains("<head>", ignoreCase = true)) {
            html = html.replace("<head>", "<head>$viewport", ignoreCase = true)
        }
        return when {
            html.contains("</head>", ignoreCase = true) -> html.replace("</head>", "$style</head>", ignoreCase = true)
            html.contains("<body", ignoreCase = true) -> {
                val match = bodyPattern.find(html)
                if (match == null) "$style$html" else
                    html.substring(0, match.range.last + 1) + style + html.substring(match.range.last + 1)
            }
            else -> "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$viewport$style</head><body>$html</body></html>"
        }
    }

    companion object {
        private val viewportPattern = Regex("<meta name=\"viewport\"[^>]*>", RegexOption.IGNORE_CASE)
        private val bodyPattern = Regex("<body[^>]*>", RegexOption.IGNORE_CASE)

        fun from(state: DocumentViewerUiState): DocumentWebPageRequest {
            val (background, text) = when (state.docBackgroundColor) {
                UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#322D29"
                UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
                UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4" to "#343426"
                UserPreferencesRepository.DOC_BG_CUSTOM -> state.customDocBackgroundColor to state.customDocTextColor
                else -> "#ffffff" to "#000000"
            }
            return DocumentWebPageRequest(state.content, state.isVertical, background, text,
                state.fontFamily, state.fontSize, state.sideMargin, state.baseUrl, state.resourceRoots)
        }
    }
}
