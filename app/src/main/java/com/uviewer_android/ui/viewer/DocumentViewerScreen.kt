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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.repository.UserPreferencesRepository
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
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // showControls replaced by !isFullScreen
    var currentLine by remember { mutableIntStateOf(1) }
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId)
    }

    // Update current line from UI state if needed, or maintain local state from scroll
    LaunchedEffect(uiState.currentLine) {
        if (uiState.currentLine > 0) currentLine = uiState.currentLine
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
                if (!isFullScreen) {
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
                            } else if (uiState.totalLines > 0) {
                                IconButton(onClick = { showGoToLineDialog = true }) {
                                    Icon(Icons.Default.FormatSize, contentDescription = "Go to Line") // Reusing icon for now
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
                }
            },
            bottomBar = {
                if (!isFullScreen) {
                    BottomAppBar {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (uiState.totalLines > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Line: $currentLine / ${uiState.totalLines}")
                                    // Percentage
                                    Text("${(currentLine * 100 / uiState.totalLines)}%")
                                }
                                Slider(
                                    value = currentLine.toFloat(),
                                    onValueChange = { 
                                        currentLine = it.toInt()
                                        if (uiState.totalLines > 0 && webViewRef != null) {
                                            val js = "window.scrollTo(0, document.body.scrollHeight * ${currentLine.toFloat() / uiState.totalLines});"
                                            webViewRef?.evaluateJavascript(js, null)
                                        }
                                    },
                                    valueRange = 1f..uiState.totalLines.toFloat(),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }
                            
                            if (type == FileEntry.FileType.EPUB && uiState.epubChapters.size > 1) {
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
                            settings.allowFileAccess = true // Fix for EPUB webpage not available
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            webViewRef = this
                            
                            val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                    val width = width
                                    val x = e.x
                                    if (x < width / 3) {
                                        pageUp(false)
                                    } else if (x > width * 2 / 3) {
                                        pageDown(false)
                                    } else {
                                        onToggleFullScreen()
                                    }
                                    return true
                                }
                                
                                override fun onDown(e: android.view.MotionEvent): Boolean {
                                    return true
                                }
                            })
                            
                            setOnTouchListener { _, event ->
                                gestureDetector.onTouchEvent(event) 
                                // Return false to let WebView handle scrolls and other events
                                // But if it was a tap, we want to consume it? 
                                // Actually calling pageUp/pageDown inside onSingleTapUp is enough.
                                // If we return false, WebView might interpret tap as click (e.g. on link).
                                // We are prioritizing navigation over links here.
                                // Ideally verify hit test result.
                                false
                            }
                            
                            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                if (uiState.totalLines > 0 && contentHeight > 0) {
                                     val progress = scrollY.toFloat() / (contentHeight - height).coerceAtLeast(1)
                                     val line = (progress * uiState.totalLines).toInt().coerceIn(1, uiState.totalLines)
                                     currentLine = line
                                }
                            }
                        }
                    },
                    update = { webView ->
                        val (bgColor, textColor) = when (uiState.docBackgroundColor) {
                            UserPreferencesRepository.DOC_BG_SEPIA -> "#f5f5dc" to "#5b4636"
                            UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#e0e0e0"
                            else -> "#ffffff" to "#000000"
                        }

                        val style = """
                            <style>
                            body {
                                background-color: $bgColor;
                                color: $textColor;
                                font-family: ${uiState.fontFamily};
                                font-size: ${uiState.fontSize}px;
                                line-height: 1.6;
                                padding: 1em;
                            }
                            </style>
                        """
                        val contentWithStyle = "$style${uiState.content}"
                        val js = "document.body.style.writingMode = '${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"}';"

                        if (uiState.url != null) {
                            if (webView.url != uiState.url) {
                                webView.loadUrl(uiState.url!!)
                            }
                            webView.evaluateJavascript(js, null)
                        } else {
                             webView.loadDataWithBaseURL(null, contentWithStyle, "text/html", "UTF-8", null)
                             webView.evaluateJavascript(js, null)
                        }
                    }
                )
            }
        }
        
        if (showGoToLineDialog) {
            var targetLineStr by remember { mutableStateOf(currentLine.toString()) }
            AlertDialog(
                onDismissRequest = { showGoToLineDialog = false },
                title = { Text("Go to Line") },
                text = {
                    Column {
                        Text("Enter line number (1 - ${uiState.totalLines})")
                        OutlinedTextField(
                            value = targetLineStr, 
                            onValueChange = { if (it.all { c -> c.isDigit() }) targetLineStr = it },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {  
                         val line = targetLineStr.toIntOrNull()
                         if (line != null) {
                             currentLine = line.coerceIn(1, uiState.totalLines)
                             if (uiState.totalLines > 0 && webViewRef != null) {
                                 val js = "window.scrollTo(0, document.body.scrollHeight * ${currentLine.toFloat() / uiState.totalLines});"
                                 webViewRef?.evaluateJavascript(js, null)
                             }
                             showGoToLineDialog = false 
                         }
                    }) { Text("Go") }
                },
                dismissButton = {
                    TextButton(onClick = { showGoToLineDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}
