package com.uviewer_android

import com.uviewer_android.ui.viewer.ViewerDocumentPage
import com.uviewer_android.ui.viewer.ViewerDocumentSecurity
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ViewerDocumentPageTest {
    @Test fun backgroundBuildsCannotReplaceTheActivePageUntilPublished() {
        val pages = ViewerDocumentPage()
        val original = pages.prepare("<p>Original</p>", null) { it }
        val first = pages.build("<p>Discarded</p>", null) { it }
        val latest = pages.build("<p>Latest</p>", null) { it }
        assertNotNull(pages.contentFor(original))
        assertNull(pages.contentFor(first.url))
        pages.activate(latest)
        assertNull(pages.contentFor(original))
        assertNull(pages.contentFor(first.url))
        assertTrue(pages.contentFor(latest.url)!!.contains("Latest"))
    }

    @Test fun servesOnlyTheCurrentSanitizedHttpsPageAndPreservesRelativeResources() {
        val pages = ViewerDocumentPage()
        val base = ViewerDocumentSecurity.ORIGIN + "/resources/0/chapter%202/"
        val first = pages.prepare("<p>문서</p><script>window.injected=true</script><img src='page.jpg' onerror='bad()'>", base) { it }
        assertTrue(first.startsWith(base))
        assertEquals(base + "page.jpg", URI(first).resolve("page.jpg").toASCIIString())
        val document = Jsoup.parse(pages.contentFor(first)!!)
        assertEquals("문서", document.selectFirst("p")!!.text())
        assertFalse(document.selectFirst("img")!!.hasAttr("onerror"))
        assertTrue(document.select("script").all { it.data().isEmpty() && it.hasAttr("nonce") })
        assertNull(pages.contentFor(base))
        assertNull(pages.contentFor("data:text/html;charset=utf-8;base64,"))
        val second = pages.prepare("<p>다음</p>", base) { it }
        assertNotEquals(first, second)
        assertNull(pages.contentFor(first))
        assertTrue(pages.contentFor(second)!!.contains("다음"))
    }

    @Test fun missingOrUntrustedBaseUsesTheInternalDocumentDirectory() {
        for (base in listOf(null, "file:///storage/book/", "https://evil.test/resources/0/", "https://appassets.androidplatform.net:443/resources/0/")) {
            val url = ViewerDocumentPage().prepare("<p>text</p>", base) { it }
            assertTrue(url.startsWith(ViewerDocumentSecurity.ORIGIN + "/document/reader-"))
        }
    }
}
