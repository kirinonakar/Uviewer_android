package com.uviewer_android.ui.viewer

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    filePath: String,
    type: FileEntry.FileType,
    isWebDav: Boolean,
    serverId: Int?,
    viewModel: DocumentViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleVerticalMode() }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Toggle Vertical")
                    }
                    IconButton(onClick = { /* TODO: Font settings dialog */ }) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Font Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("Error: ${uiState.error}")
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    // Apply settings via JS or loadData
                    val css = if (uiState.isVertical) "body { writing-mode: vertical-rl; overflow-x: auto; }" else "body { writing-mode: horizontal-tb; }"
                    val js = "document.body.style.writingMode = '${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"}';"

                    if (uiState.url != null) {
                        if (webView.url != uiState.url) {
                            webView.loadUrl(uiState.url!!)
                        }
                        // Inject JS after page load (needs WebViewClient onPageFinished, but for simplicity here try evaluateJavascript if supported)
                        // Actually best to set WebViewClient to inject on finish.
                        // For now, simple attempt:
                        webView.evaluateJavascript(js, null)
                    } else {
                        // Re-wrap content if needed, OR just inject JS if content is same but style changed.
                        // Since AozoraParser wraps with style, we might need to reload if style changes deeply.
                        // But let's try JS injection for immediate effect.
                        webView.loadDataWithBaseURL(null, uiState.content, "text/html", "UTF-8", null)
                        webView.evaluateJavascript(js, null)
                    }
                }
            )
        }
    }
}
