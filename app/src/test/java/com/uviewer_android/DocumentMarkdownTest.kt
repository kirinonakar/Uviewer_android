package com.uviewer_android

import com.uviewer_android.ui.viewer.convertDocumentMarkdownToHtml
import com.uviewer_android.ui.viewer.ViewerDocumentSecurity
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DocumentMarkdownTest {
    @Test fun diagramFencesReachTheSecurePageAsLiteralCode() {
        val source = "flowchart LR\n A[\"한글 & <태그>\"] --> B[완료]\n"
        for (language in listOf("mermaid", "pele")) {
            val html = convertDocumentMarkdownToHtml("# Diagram\n\n```$language\n$source```\n")
            val page = Jsoup.parse(ViewerDocumentSecurity.page(html) { it })
            val code = page.selectFirst("pre > code.language-$language")!!
            assertEquals(source, code.wholeText())
            assertEquals("Diagram", page.selectFirst("h1")!!.text())
            assertTrue(code.children().isEmpty())
        }
    }

    @Test fun diagramMathAndMarkupRemainLiteralWhileMarkdownStillRenders() {
        val source = "flowchart LR\n A[\"${'$'}${'$'}x < y${'$'}${'$'}\"] --> B[\"**완료**\"]\n"
        val html = convertDocumentMarkdownToHtml("**Bold**\n\n```mermaid\n$source```\n\n${'$'}x+1${'$'}")
        val document = Jsoup.parse(html)
        assertEquals(source, document.selectFirst("pre code")!!.wholeText())
        assertEquals("Bold", document.selectFirst("strong")!!.text())
        assertTrue(document.text().contains("${'$'}x+1${'$'}"))
    }

    @Test fun nestedFencesAndOtherCodeAlsoKeepTheirOriginalText() {
        val html = convertDocumentMarkdownToHtml(
            "> ~~~mermaid\n> flowchart TD\n> A[\"**한글**\"] --> B\n> ~~~\n\n" +
                "`**한글** ${'$'}x${'$'}`\n\n    **한글** ${'$'}y${'$'}\n"
        )
        val document = Jsoup.parse(html)
        assertEquals("flowchart TD\nA[\"**한글**\"] --> B\n", document.selectFirst("blockquote pre code")!!.wholeText())
        assertEquals("**한글** ${'$'}x${'$'}", document.selectFirst("p > code")!!.wholeText())
        assertEquals("**한글** ${'$'}y${'$'}\n", document.select("pre code").last()!!.wholeText())
    }
}
