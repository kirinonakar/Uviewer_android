package com.uviewer_android

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider
import com.uviewer_android.ui.theme.UviewerTheme
import com.uviewer_android.ui.viewer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DocumentViewerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oneLoadingIndicatorCoversPreparationAndProgressChangesDoNotReloadThePage() {
        val state = mutableStateOf(DocumentViewerUiState(isLoading = true))
        val webView = mutableStateOf<WebView?>(null)
        val currentLine = mutableIntStateOf(1)
        val loading = mutableStateOf(false)
        val navigating = mutableStateOf(false)
        val navigation = DocumentPageNavigation()
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        lateinit var model: DocumentViewerViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity, AppViewModelProvider.Factory)[DocumentViewerViewModel::class.java]
        }
        compose.setContent {
            UviewerTheme {
                DocumentViewerWebView(
                    modifier = Modifier.fillMaxSize(), filePath = "/test.txt", type = FileEntry.FileType.TEXT,
                    uiState = state.value, targetDocColor = Color.White, viewModel = model,
                    pageNavigation = navigation, onPageRequested = { }, webViewRefState = webView,
                    currentLineState = currentLine, pageLoadingState = loading, navigatingState = navigating,
                    isInteractingWithSlider = false, onToggleFullScreen = { }, applyDocumentSearchHighlight = { },
                    isScrollRestoring = { false }, onLlmSelected = { }
                )
            }
        }
        try {
            compose.onAllNodes(progress).assertCountEquals(1)
            compose.runOnIdle {
                state.value = state.value.copy(isLoading = false, totalLines = 100,
                    content = (1..100).joinToString("") { "<p id='line-$it'>Reading line $it</p>" } +
                        "<script>window.documentInjected = true;</script>")
            }
            compose.waitUntil(20_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty() }
            val firstUrl = compose.runOnIdle {
                assertTrue(navigation.isReady)
                webView.value!!.url
            }
            compose.runOnIdle { state.value = state.value.copy(currentLine = 30, loadProgress = 0.5f) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(firstUrl, webView.value!!.url) }
            // A refresh that produces identical content should keep the existing WebView page.
            compose.runOnIdle { state.value = state.value.copy(isLoading = true) }
            compose.onAllNodes(progress).assertCountEquals(1)
            compose.runOnIdle { state.value = state.value.copy(isLoading = false) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(firstUrl, webView.value!!.url) }
            compose.onAllNodes(progress).assertCountEquals(0)

            val evaluated = CountDownLatch(1)
            var injected: String? = null
            compose.runOnIdle {
                webView.value!!.evaluateJavascript("window.documentInjected === true") {
                    injected = it
                    evaluated.countDown()
                }
            }
            assertTrue(evaluated.await(5, TimeUnit.SECONDS))
            assertEquals("false", injected)
        } finally {
            compose.runOnIdle { webView.value?.destroy() }
        }
    }
}
