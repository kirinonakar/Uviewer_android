package com.uviewer_android

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uviewer_android.ui.viewer.DocumentFileResolver
import com.uviewer_android.ui.viewer.ViewerResourceLoader
import com.uviewer_android.ui.viewer.ViewerWebViewClient
import com.uviewer_android.ui.viewer.configureViewerSecurity
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DocumentWebViewLoadingTest {
    @Test fun textPageLoadsWithRelativeResourcesAndOnlyTrustedScripts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "webview_loading_${UUID.randomUUID()}").apply { mkdirs() }
        val chapter = File(root, "chapter.txt").apply { writeText("문서 테스트") }
        File(root, "page.png").outputStream().use {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
        }
        File(root, "style.css").writeText("#line-1 { color: rgb(12, 34, 56); }")
        val finished = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        var webView: WebView? = null
        try {
            instrumentation.runOnMainSync {
                val resources = ViewerResourceLoader(context).apply { configure(listOf(root.path)) }
                webView = WebView(context).apply {
                    settings.configureViewerSecurity()
                    webViewClient = object : ViewerWebViewClient(resources) {
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) errors.add("${request.url}: ${response.statusCode}")
                        }
                        override fun onPageFinished(view: WebView?, url: String?) { finished.countDown() }
                    }
                }
                val html = """<link rel="stylesheet" href="style.css"><p id="line-1">문서 테스트</p>
                    <img src="page.png" onerror="window.documentInjected = true">
                    <script>window.documentInjected = true;</script>"""
                val baseUrl = resources.rewriteUrl(DocumentFileResolver.baseUrlFor(chapter)!!)
                webView.loadUrl(resources.prepareDocument(html, baseUrl))
            }
            assertTrue("WebView did not finish loading", finished.await(25, TimeUnit.SECONDS))
            assertTrue("Main document was rejected: $errors", errors.isEmpty())
            val evaluated = CountDownLatch(1)
            var result: String? = null
            instrumentation.runOnMainSync {
                webView!!.evaluateJavascript("""[document.body.innerText, typeof window.renderMath,
                    typeof window.documentInjected, document.querySelector('img')?.naturalWidth,
                    document.getElementById('line-1') ? getComputedStyle(document.getElementById('line-1')).color : 'missing']""") {
                    result = it
                    evaluated.countDown()
                }
            }
            assertTrue("JavaScript evaluation timed out", evaluated.await(10, TimeUnit.SECONDS))
            val values = JSONArray(result!!)
            assertTrue(values.getString(0).contains("문서 테스트"))
            assertEquals("function", values.getString(1))
            assertEquals("undefined", values.getString(2))
            assertEquals(1, values.getInt(3))
            assertEquals("rgb(12, 34, 56)", values.getString(4))
        } finally {
            webView?.let { view -> instrumentation.runOnMainSync { view.destroy() } }
            root.deleteRecursively()
        }
    }
}
