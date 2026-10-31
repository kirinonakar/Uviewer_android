package com.uviewer_android.ui.viewer

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

internal class ViewerResourceLoader(context: Context, private val pdfFile: File? = null) {
    private val document = ViewerDocumentPage()
    @Volatile private var policy = ViewerResourcePolicy(emptyList())
    private val assets = WebViewAssetLoader.AssetsPathHandler(context)
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/") { path ->
            val allowed = if (pdfFile != null) path.startsWith("pdfjs/")
                else path.startsWith("katex/") || path in setOf(
                    "pele/pele.global.js", "viewer/diagrams.js", "viewer/diagrams.css", "viewer/document.js"
                )
            if (allowed) assets.handle(path) else denied()
        }
        .addPathHandler("/resources/") { path ->
            val file = policy.resourceFile(path)
            if (file == null) denied() else runCatching {
                WebResourceResponse(ViewerResourcePolicy.passiveMime(file), null, file.inputStream()).apply {
                    responseHeaders = mapOf("X-Content-Type-Options" to "nosniff", "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:")
                }
            }.getOrElse { denied() }
        }
        .addPathHandler("/pdf/") { path ->
            val currentPdf = pdfFile
            if (path == "current.pdf" && currentPdf != null) runCatching {
                WebResourceResponse("application/pdf", null, currentPdf.inputStream())
            }.getOrElse { denied() } else denied()
        }
        .build()

    fun configure(rootPaths: List<String>) { policy = ViewerResourcePolicy(rootPaths) }
    fun rewriteUrl(url: String): String = policy.rewriteUrl(url)
    fun prepareDocument(html: String, baseUrl: String?): String = document.prepare(html, baseUrl, ::rewriteUrl)

    fun buildDocument(html: String, baseUrl: String?, rootPaths: List<String>): ViewerDocumentPage.PreparedPage {
        val requestPolicy = ViewerResourcePolicy(rootPaths)
        return document.build(html, baseUrl?.let(requestPolicy::rewriteUrl), requestPolicy::rewriteUrl)
    }

    fun activateDocument(page: ViewerDocumentPage.PreparedPage, rootPaths: List<String>): String {
        configure(rootPaths)
        return document.activate(page)
    }

    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1) {
            document.contentFor(uri.toString())?.let { html ->
                return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))).apply {
                    responseHeaders = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
                }
            }
            return loader.shouldInterceptRequest(uri) ?: denied()
        }
        // HTTPS images/styles/fonts remain usable in documents; CSP forbids other network use.
        if (pdfFile == null && uri.scheme == "https") return null
        return denied()
    }

    companion object {
        const val PDF_URL = ViewerDocumentSecurity.ORIGIN + "/pdf/current.pdf"
        const val PDF_VIEWER_URL = ViewerDocumentSecurity.ORIGIN + "/assets/pdfjs/uviewer_pdf.html"
        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

internal open class ViewerWebViewClient(private val resources: ViewerResourceLoader) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        resources.intercept(request.url)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? =
        resources.intercept(Uri.parse(url))

    // The bridge is only for the app-created viewer page. Never navigate it to document links.
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = true
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
}
