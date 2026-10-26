package com.uviewer_android.ui.viewer

import java.net.URI
import java.util.UUID

/** The current sanitized page is served at an exact HTTPS URL, never a data: URL. */
internal class ViewerDocumentPage {
    data class PreparedPage(val url: String, val html: String)
    @Volatile private var current: PreparedPage? = null

    fun prepare(html: String, baseUrl: String?, rewriteUrl: (String) -> String): String {
        return activate(build(html, baseUrl, rewriteUrl))
    }

    /** Build off the UI thread, then publish only the newest completed request on the UI thread. */
    fun build(html: String, baseUrl: String?, rewriteUrl: (String) -> String): PreparedPage {
        val fallback = ViewerDocumentSecurity.ORIGIN + "/document/"
        val base = runCatching { URI(baseUrl ?: fallback) }.getOrNull()?.takeIf {
            it.scheme == "https" && it.rawAuthority == "appassets.androidplatform.net" &&
                (it.path.startsWith("/resources/") || it.path == "/document/") && it.path.endsWith('/')
        } ?: URI(fallback)
        // Keep the resource directory as the page directory so relative images/styles resolve.
        val url = base.resolve("reader-${UUID.randomUUID()}.html").toASCIIString()
        return PreparedPage(url, ViewerDocumentSecurity.page(html, rewriteUrl))
    }

    fun activate(page: PreparedPage): String {
        current = page
        return page.url
    }

    fun contentFor(url: String): String? = current?.takeIf { it.url == url }?.html
}
