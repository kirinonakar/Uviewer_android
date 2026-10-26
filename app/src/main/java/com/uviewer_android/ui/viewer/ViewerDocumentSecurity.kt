package com.uviewer_android.ui.viewer

import org.jsoup.Jsoup
import java.util.UUID

/** Applied to both full pages and every fragment inserted into an existing viewer page. */
internal object ViewerDocumentSecurity {
    const val ORIGIN = "https://appassets.androidplatform.net"
    private val tags = ("html head body title meta style link a abbr article aside b bdi bdo blockquote br " +
        "caption center cite code col colgroup dd del details dfn div dl dt em figcaption figure footer " +
        "h1 h2 h3 h4 h5 h6 header hr i img ins kbd li main mark nav ol p pre q rp rt ruby s samp section " +
        "small span strong sub summary sup table tbody td tfoot th thead time tr u ul var wbr " +
        "svg g image path rect circle ellipse line polyline polygon text tspan defs clippath mask use symbol").split(' ').toSet()
    private val attributes = ("id class style title lang dir role colspan rowspan span width height align valign " +
        "alt src href xlink:href viewbox preserveaspectratio d x y x1 y1 x2 y2 cx cy r rx ry points fill " +
        "stroke stroke-width transform opacity xmlns xmlns:xlink clip-path mask dx dy text-anchor " +
        "font-size font-family start reversed type data-app-style").split(' ').toSet()
    private val activeTags = setOf("script", "iframe", "object", "embed", "template", "noscript", "foreignobject", "form")

    fun sanitize(html: String, rewriteUrl: (String) -> String = { it }): String =
        sanitizeDocument(html, rewriteUrl).outerHtml()

    private fun sanitizeDocument(html: String, rewriteUrl: (String) -> String): org.jsoup.nodes.Document {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        for (element in doc.getAllElements().toList()) {
            val tag = element.normalName()
            if (tag == "#root") continue
            if (tag in activeTags) {
                element.remove()
                continue
            }
            if (tag !in tags) {
                element.unwrap()
                continue
            }
            if (tag == "meta") {
                if (!element.hasAttr("charset") && element.attr("name").lowercase() != "viewport") {
                    element.remove()
                }
                element.attributes().asList().filter { it.key.lowercase() !in setOf("charset", "name", "content") }
                    .forEach { element.removeAttr(it.key) }
                continue
            }
            val stylesheet = tag == "link" && element.attr("rel").equals("stylesheet", true)
            if (tag == "link" && !stylesheet) {
                element.remove()
                continue
            }
            for (attribute in element.attributes().asList()) {
                val key = attribute.key.lowercase()
                if (key !in attributes && !(stylesheet && key == "rel")) {
                    element.removeAttr(attribute.key)
                } else if (key in setOf("src", "href", "xlink:href")) {
                    val value = attribute.value
                    // Strip control characters before checking schemes, including entity-decoded tabs.
                    val normalized = value.filterNot { it <= ' ' }.lowercase()
                    val scheme = normalized.substringBefore(':', "")
                    if (scheme.isNotEmpty() && scheme !in setOf("https", "file", "data") ||
                        (scheme == "data" && !normalized.startsWith("data:image/"))) {
                        element.removeAttr(attribute.key)
                    } else {
                        element.attr(attribute.key, rewriteUrl(value))
                    }
                }
            }
        }
        return doc
    }

    fun page(html: String, rewriteUrl: (String) -> String): String {
        val doc = sanitizeDocument(html, rewriteUrl)
        val nonce = UUID.randomUUID().toString()
        // No document inline scripts, handlers, frames, forms or network APIs. Only the
        // three scripts inserted here receive a nonce; document attributes cannot supply one.
        val policy = "default-src 'none'; script-src 'nonce-$nonce'; " +
            "style-src 'unsafe-inline' https:; img-src https: data:; font-src https: data:; " +
            "connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", policy)
        doc.head().appendElement("link").attr("rel", "stylesheet").attr("href", "$ORIGIN/assets/katex/katex.min.css")
        for (path in listOf("katex/katex.min.js", "katex/contrib/auto-render.min.js", "viewer/document.js")) {
            doc.head().appendElement("script").attr("nonce", nonce).attr("src", "$ORIGIN/assets/$path")
        }
        return doc.outerHtml()
    }
}
