package com.uviewer_android

import com.uviewer_android.ui.viewer.ViewerDocumentSecurity
import com.uviewer_android.ui.viewer.ViewerResourcePolicy
import com.uviewer_android.ui.viewer.DocumentFileResolver
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerDocumentSecurityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun activeHtmlAndSvgAreRemovedForInitialAndIncrementalContent() {
        val html = """<base href="https://evil.test"><meta http-equiv="refresh" content="0;url=https://evil.test">
            <script nonce="forged">Android.autoLoadNext()</script><script src="https://evil.test/a.js"></script>
            <img src="page.jpg" OnError="Android.autoLoadPrev()"><a href="java&#x09;script:alert(1)">link</a>
            <svg onload="alert(1)"><script>alert(1)</script><foreignObject><iframe srcdoc="bad"></iframe></foreignObject>
            <image xlink:href="javascript:alert(1)"/><animate attributeName="href" values="javascript:alert(1)"/></svg>
            <form action="https://evil.test"><input autofocus onfocus="alert(1)"></form>"""
        for (result in listOf(ViewerDocumentSecurity.sanitize(html), ViewerDocumentSecurity.page(html) { it })) {
            val doc = Jsoup.parse(result)
            assertTrue(doc.select("base, iframe, object, embed, foreignObject, animate, form, input").isEmpty())
            assertFalse(doc.select("meta").any { it.attr("http-equiv").equals("refresh", true) })
            assertFalse(doc.getAllElements().any { e -> e.attributes().any { it.key.startsWith("on", true) } })
            assertFalse(doc.getAllElements().any { e -> e.attributes().any { it.value.contains("javascript:") } })
            assertFalse(doc.select("script").any { it.data().contains("Android") || it.attr("src").contains("evil.test") })
        }
    }

    @Test fun readingMarkupSurvivesAndOnlyBundledScriptsReceiveFreshNonce() {
        val html = """<style>.page { color: red; }</style><div id="line-3" class="content-chunk" style="padding:1em">
            <ruby>漢字<rt>かんじ</rt></ruby><svg viewBox="0 0 10 20"><image href="page.svg"/></svg>
            <img src="page.jpg"><table><tr><td>text</td></tr></table>\(x+1\)</div>"""
        val page = Jsoup.parse(ViewerDocumentSecurity.page(html) { it })
        assertEquals("かんじ", page.selectFirst("ruby rt")!!.text())
        assertEquals("page.jpg", page.selectFirst("img")!!.attr("src"))
        assertEquals("0 0 10 20", page.selectFirst("svg")!!.attr("viewBox"))
        assertNotNull(page.getElementById("line-3"))
        assertEquals(5, page.select("script").size)
        assertNotNull(page.selectFirst("script[src$=pele/pele.global.js]"))
        assertNotNull(page.selectFirst("script[src$=viewer/diagrams.js]"))
        assertNotNull(page.selectFirst("link[href$=viewer/diagrams.css]"))
        val nonce = page.selectFirst("script")!!.attr("nonce")
        assertTrue(nonce.isNotEmpty())
        assertTrue(page.select("script").all { it.attr("nonce") == nonce && it.attr("src").startsWith(ViewerDocumentSecurity.ORIGIN + "/assets/") })
        val policy = page.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(policy.contains("script-src 'nonce-$nonce'"))
        assertTrue(policy.contains("frame-src 'none'"))
        assertTrue(Jsoup.parse(ViewerDocumentSecurity.sanitize(html)).select("script").isEmpty())
    }

    @Test fun localResourcesAreScopedAndTraversalAndExecutableFilesAreDenied() {
        val root = temp.newFolder("book")
        val image = java.io.File(root, "chapter 2/한글.jpg").apply { parentFile!!.mkdirs(); writeText("image") }
        val secret = temp.newFile("secret.jpg")
        java.io.File(root, "evil.js").writeText("Android.autoLoadNext()")
        val policy = ViewerResourcePolicy(listOf(root.path))
        val url = policy.rewriteUrl(image.toURI().toASCIIString())
        assertEquals("$url#cover", policy.rewriteUrl(image.toURI().toASCIIString() + "#cover"))
        val path = java.net.URI(url).path.removePrefix("/resources/")
        assertEquals(image.canonicalFile, policy.resourceFile(path))
        assertNull(policy.resourceFile("0/../secret.jpg"))
        assertNull(policy.resourceFile("0/evil.js"))
        assertNull(policy.resourceFile("1/chapter 2/한글.jpg"))
        assertTrue(policy.rewriteUrl(secret.toURI().toASCIIString()).endsWith("/blocked"))
        assertTrue(policy.rewriteUrl(root.toURI().toASCIIString()).endsWith("/resources/0/"))
        val output = ViewerDocumentSecurity.sanitize("<img src=\"${image.toURI().toASCIIString()}\">") { policy.rewriteUrl(it) }
        assertEquals(url, Jsoup.parse(output).selectFirst("img")!!.attr("src"))
    }

    @Test fun generatedBaseUrlsPreserveSpacesAndUrlDelimitersInDirectoryNames() {
        val root = temp.newFolder("book # % 한글")
        val chapter = java.io.File(root, "chapter.html").apply { writeText("book") }
        val image = java.io.File(root, "page.jpg").apply { writeText("image") }
        val policy = ViewerResourcePolicy(listOf(root.path))
        val baseUrl = policy.rewriteUrl(DocumentFileResolver.baseUrlFor(chapter)!!)
        assertTrue(baseUrl.endsWith("/resources/0/"))
        val resource = java.net.URI(baseUrl).resolve("page.jpg").path.removePrefix("/resources/")
        assertEquals(image.canonicalFile, policy.resourceFile(resource))
    }
}
