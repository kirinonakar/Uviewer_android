package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.table_of_contents),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                LazyColumn {
                    itemsIndexed(uiState.epubChapters) { index, item ->
                        NavigationDrawerItem(
                            label = { Text(item.title ?: "Chapter ${index + 1}") },
                            selected = index == uiState.currentChapterIndex,
                            onClick = {
                                viewModel.loadChapter(index)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        },
        gesturesEnabled = type == FileEntry.FileType.EPUB
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_document_viewer)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (type == FileEntry.FileType.EPUB) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.table_of_contents))
                            }
                        }
                        IconButton(onClick = { viewModel.toggleVerticalMode() }) {
                            Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.toggle_vertical))
                        }
                        IconButton(onClick = { /* TODO: Font settings dialog */ }) {
                            Icon(Icons.Default.FormatSize, contentDescription = stringResource(R.string.font_settings))
                        }
                    }
                )
            },
            bottomBar = {
                if (type == FileEntry.FileType.EPUB && uiState.epubChapters.size > 1) {
                    BottomAppBar {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.prevChapter() },
                                enabled = uiState.currentChapterIndex > 0
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.prev_chapter))
                            }
                            
                            Text(
                                "${uiState.currentChapterIndex + 1} / ${uiState.epubChapters.size}",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            TextButton(
                                onClick = { viewModel.nextChapter() },
                                enabled = uiState.currentChapterIndex < uiState.epubChapters.size - 1
                            ) {
                                Text(stringResource(R.string.next_chapter))
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.error != null) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.error_fmt, uiState.error ?: ""))
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
                        val js = "document.body.style.writingMode = '${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"}';"

                        if (uiState.url != null) {
                            if (webView.url != uiState.url) {
                                webView.loadUrl(uiState.url!!)
                            }
                            webView.evaluateJavascript(js, null)
                        } else {
                            webView.loadDataWithBaseURL(null, uiState.content, "text/html", "UTF-8", null)
                            webView.evaluateJavascript(js, null)
                        }
                    }
                )
            }
        }
    }
}
