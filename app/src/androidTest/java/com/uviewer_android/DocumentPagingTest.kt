package com.uviewer_android

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uviewer_android.data.parser.AozoraParser
import com.uviewer_android.ui.viewer.DocumentPageNavigation
import com.uviewer_android.ui.viewer.DocumentViewerBootstrap
import com.uviewer_android.ui.viewer.ViewerResourceLoader
import com.uviewer_android.ui.viewer.ViewerScripts
import com.uviewer_android.ui.viewer.ViewerWebViewClient
import com.uviewer_android.ui.viewer.configureViewerSecurity
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DocumentPagingTest {
    @Test fun savedLineIsCorrectInTheFirstVisibleFrameAndDoesNotJumpLater() = verifyPaging(40, false, false)
    @Test fun nextPressedBeforeInitializationIsAppliedAndStaysApplied() = verifyPaging(1, true, false)
    @Test fun verticalTextAlsoOpensAtItsSavedLineWithoutDelayedCorrections() = verifyPaging(40, false, true)

    private fun verifyPaging(targetLine: Int, earlyNext: Boolean, vertical: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val initializationCount = AtomicInteger()
        var webView: WebView? = null
        val scenario = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val resources = ViewerResourceLoader(activity)
                val navigation = DocumentPageNavigation()
                val view = WebView(activity).apply {
                    settings.configureViewerSecurity()
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                }
                webView = view
                val overlay = android.view.View(activity).apply { setBackgroundColor(android.graphics.Color.WHITE) }
                val container = android.widget.FrameLayout(activity).apply {
                    addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                    addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                activity.setContentView(container)
                view.addJavascriptInterface(object {
                    @JavascriptInterface fun onLineChangedStr(line: String) {}
                    @JavascriptInterface fun updateTopMask(value: Float) {}
                    @JavascriptInterface fun updateBottomMask(value: Float) {}
                    @JavascriptInterface fun updateLeftMask(value: Float) {}
                    @JavascriptInterface fun updateRightMask(value: Float) {}
                    @JavascriptInterface fun onViewerReady(url: String) {
                        view.post {
                            if (!navigation.finishInitialization(url) { next ->
                                view.evaluateJavascript(if (next) "window.pageDown();" else "window.pageUp();", null)
                            }) return@post
                            initializationCount.incrementAndGet()
                            view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    container.removeView(overlay)
                                    ready.countDown()
                                }
                            })
                        }
                    }
                }, "Android")
                view.webViewClient = object : ViewerWebViewClient(resources) {
                    private fun initialize(url: String?) {
                        if (!navigation.beginInitialization(url)) return
                        view.evaluateJavascript(DocumentViewerBootstrap.script(url!!,
                            ViewerScripts.getScrollLogic(vertical, false, targetLine, 300, "")), null)
                    }
                    override fun onPageCommitVisible(view: WebView?, url: String?) { initialize(url) }
                    override fun onPageFinished(view: WebView?, url: String?) { initialize(url) }
                }
                val body = (1..300).joinToString("\n") { "This is reading line $it. 読書のテスト文章です。" }
                val parsed = AozoraParser.parse(body, 0, "", vertical)
                val html = AozoraParser.wrapInHtml(parsed, isVertical = vertical)
                    .replace("</head>", ViewerScripts.getStyleSheet(vertical, "#ffffff", "#000000", "serif", 18, 8) + "</head>")
                val url = resources.prepareDocument(html, null)
                navigation.beginLoad(url)
                if (earlyNext) navigation.request(true) { error("Early paging must be queued") }
                view.loadUrl(url)
            }
            assertTrue("Document did not become ready", ready.await(20, TimeUnit.SECONDS))
            val snapshot = """[window.pageXOffset, window.pageYOffset,
                document.getElementById('line-$targetLine').getBoundingClientRect().top,
                document.getElementById('line-$targetLine').getBoundingClientRect().right,
                document.documentElement.clientWidth]"""
            val first = evaluate(webView!!, snapshot)
            if (earlyNext) {
                assertTrue("Early next was lost: $first", first.getDouble(1) > 20)
            } else if (vertical) {
                assertEquals("Saved line must be at the right edge in the first frame", first.getDouble(4), first.getDouble(3), 3.0)
            } else {
                assertEquals("Saved line must be at the top in the first frame", 0.0, first.getDouble(2), 3.0)
            }
            android.os.SystemClock.sleep(800)
            val later = evaluate(webView, snapshot)
            assertEquals("A delayed callback moved horizontal scroll", first.getDouble(0), later.getDouble(0), 1.0)
            assertEquals("A delayed callback moved vertical scroll", first.getDouble(1), later.getDouble(1), 1.0)
            assertEquals("Commit/finished must not initialize the page twice", 1, initializationCount.get())
        } finally {
            webView?.let { view -> instrumentation.runOnMainSync { view.destroy() } }
            scenario.close()
        }
    }

    private fun evaluate(view: WebView, script: String): JSONArray {
        val finished = CountDownLatch(1)
        var result: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result = it; finished.countDown() }
        }
        assertTrue("JavaScript timed out", finished.await(10, TimeUnit.SECONDS))
        return JSONArray(result!!)
    }
}
