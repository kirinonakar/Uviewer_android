package com.uviewer_android

import com.uviewer_android.data.model.EpubSpineItem
import com.uviewer_android.ui.viewer.DocumentWebPageRequest
import com.uviewer_android.ui.viewer.DocumentViewerUiState
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DocumentWebPageRequestTest {
    @Test fun readingProgressAndTocUpdatesDoNotRebuildTheDocument() {
        val state = DocumentViewerUiState(content = "<p id='line-1'>text</p>")
        val updated = state.copy(currentLine = 42, loadProgress = 0.5f,
            epubChapters = listOf(EpubSpineItem("chapter.html", "chapter")), currentChapterIndex = 2)
        assertEquals(DocumentWebPageRequest.from(state), DocumentWebPageRequest.from(updated))
        assertNotEquals(DocumentWebPageRequest.from(state), DocumentWebPageRequest.from(state.copy(fontSize = 28)))
        assertNotEquals(DocumentWebPageRequest.from(state), DocumentWebPageRequest.from(state.copy(content = "new")))
    }

    @Test fun pagePreparationPreservesMarkupAndAppliesViewportAndLayout() {
        val state = DocumentViewerUiState(content = "<html><head><meta name=\"viewport\" content=\"old\"></head><body><ruby>漢<rt>かん</rt></ruby></body></html>",
            isVertical = true, fontSize = 27, fontFamily = "sans-serif", sideMargin = 12)
        val page = Jsoup.parse(DocumentWebPageRequest.from(state).styledHtml())
        assertEquals(1, page.select("meta[name=viewport]").size)
        assertTrue(page.selectFirst("meta[name=viewport]")!!.attr("content").contains("height=device-height"))
        assertEquals("かん", page.selectFirst("rt")!!.text())
        assertTrue(page.selectFirst("style")!!.data().contains("27px"))
        val plain = Jsoup.parse(DocumentWebPageRequest.from(state.copy(content = "plain text")).styledHtml())
        assertEquals("plain text", plain.body().text())
        assertNotNull(plain.selectFirst("meta[name=viewport]"))
    }
}
