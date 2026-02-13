package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
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
    var showFontSettingsDialog by remember { mutableStateOf(false) }
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
                        title = { Text(uiState.fileName ?: stringResource(R.string.title_document_viewer), maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                viewModel.toggleBookmark(filePath, uiState.currentLine, isWebDav, serverId, type.name)
                            }) {
                                Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
                            }
                            if (type == FileEntry.FileType.EPUB) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.table_of_contents))
                                }
                            } else if (uiState.totalLines > 0) {
                                IconButton(onClick = { showGoToLineDialog = true }) {
                                    Text("G", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { viewModel.toggleVerticalMode() }) {
                                Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.toggle_vertical))
                            }
                            IconButton(onClick = { showFontSettingsDialog = true }) {
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
                                             val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant' });"
                                             webViewRef?.evaluateJavascript(js, null)
                                         }
                                     },
                                     valueRange = 1f..uiState.totalLines.toFloat(),
                                     modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                 )
                             }

                             // Text Chunk Navigation
                             if (type == FileEntry.FileType.TEXT && (uiState.currentChunkIndex > 0 || uiState.hasMoreContent)) {
                                 Row(
                                     modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                     horizontalArrangement = Arrangement.SpaceBetween,
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     TextButton(
                                         onClick = { viewModel.prevChunk() },
                                         enabled = uiState.currentChunkIndex > 0
                                     ) {
                                         Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                         Spacer(Modifier.width(8.dp))
                                         Text("Prev Seg")
                                     }

                                     Text(
                                         "Segment ${uiState.currentChunkIndex + 1}",
                                         style = MaterialTheme.typography.bodyMedium
                                     )

                                     TextButton(
                                         onClick = { viewModel.nextChunk() },
                                         enabled = uiState.hasMoreContent
                                     ) {
                                         Text("Next Seg")
                                         Spacer(Modifier.width(8.dp))
                                         Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                     }
                                 }
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
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.error_fmt, uiState.error ?: ""))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                }
            } else {
                 AndroidView(
                     modifier = Modifier
                         .fillMaxSize()
                         .padding(innerPadding),
                    factory = { context ->
                        object : WebView(context) {
                            var targetLine: Int = 1
                            var lastContentHash: Int = 0
                            
                            fun getHorizontalScrollRangePublic(): Int {
                                return computeHorizontalScrollRange()
                            }
                        }.apply {
                            settings.allowFileAccess = true 
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val customView = view as? Object
                                    val line = try { customView?.javaClass?.getField("targetLine")?.getInt(customView) ?: 1 } catch(e:Exception) { 1 }
                                    
                                    // Restore Scroll
                                    if (line > 1) {
                                         // We need totalLines which is in uiState. But here we are in Factory/Client.
                                         // We can't easily access uiState. 
                                         // We will rely on the 'update' block to restore scroll after content load.
                                    }
                                }
                            }
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
                                false
                            }
                            
                            setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                                 if (uiState.isVertical) {
                                     val range = getHorizontalScrollRangePublic()
                                     val maxScroll = (range - width).coerceAtLeast(1)
                                     val rawProgress = 1.0f - (scrollX.toFloat() / maxScroll)
                                     val p = rawProgress.coerceIn(0f, 1f)
                                     val line = (p * uiState.totalLines).toInt().coerceIn(1, uiState.totalLines)
                                     if (kotlin.math.abs(line - currentLine) > 0) {
                                         currentLine = line
                                     }
                                 } else {
                                     if (uiState.totalLines > 0 && contentHeight > 0) {
                                          val progress = scrollY.toFloat() / (contentHeight - height).coerceAtLeast(1)
                                          val line = (progress * uiState.totalLines).toInt().coerceIn(1, uiState.totalLines)
                                          currentLine = line
                                     }
                                 }
                            }
                        }
                    },
                    update = { webView ->
                        val customWebView = webView as? WebView // It's already a WebView
                        val currentHash = uiState.content.hashCode()
                        val previousHash = (webView.tag as? Int) ?: 0
                        
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
                         val jsWritingMode = "document.body.style.writingMode = '${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"}';"

                         if (currentHash != previousHash) {
                             webView.tag = currentHash
                             if (uiState.url != null) {
                                 webView.loadUrl(uiState.url!!)
                             } else {
                                 val baseUrl = if (filePath.startsWith("/")) "file://${java.io.File(filePath).parent}/" else null
                                 webView.loadDataWithBaseURL(baseUrl, contentWithStyle, "text/html", "UTF-8", null)
                             }
                             
                             // Restore Scroll after load
                             // Since load is async, we can't do it here directly for the *content check*.
                             // But we can post a runnable?
                             webView.postDelayed({
                                 webView.evaluateJavascript(jsWritingMode, null)
                                 // Restore position
                                 if (currentLine > 1 && uiState.totalLines > 0) {
                                     val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant' });"
                                     webView.evaluateJavascript(js, null)
                                 }
                             }, 500) // Delay to ensure render. 500ms is heuristic.
                         } else {
                             // Just update writing mode if needed (though hash check covers it usually)
                             webView.evaluateJavascript(jsWritingMode, null)
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
                                   val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant' });"
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
         if (showFontSettingsDialog) {
            FontSettingsDialog(
                viewModel = viewModel,
                onDismiss = { showFontSettingsDialog = false }
            )
        }
    }
}

@Composable
fun FontSettingsDialog(
    viewModel: DocumentViewerViewModel,
    onDismiss: () -> Unit
) {
    val fontSize: Int by viewModel.fontSize.collectAsState()
    val fontFamily: String by viewModel.fontFamily.collectAsState()
    val docBackgroundColor: String by viewModel.docBackgroundColor.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.font_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Font Size
                Column {
                    Text(stringResource(R.string.font_size_fmt, fontSize))
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { viewModel.setFontSize(it.toInt()) },
                        valueRange = 12f..36f,
                        steps = 23 // Corrected steps for range 12-36
                    )
                }
                
                // Font Family
                Column {
                    Text(stringResource(R.string.font_family))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("serif", "sans-serif", "monospace").forEach { family ->
                            FilterChip(
                                selected = fontFamily == family,
                                onClick = { viewModel.setFontFamily(family) },
                                label = { Text(family.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }

                // Background Color
                Column {
                    Text(stringResource(R.string.document_background))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mapOf(
                            UserPreferencesRepository.DOC_BG_WHITE to "White",
                            UserPreferencesRepository.DOC_BG_SEPIA to "Sepia",
                            UserPreferencesRepository.DOC_BG_DARK to "Dark"
                        ).forEach { (bg, label) ->
                            FilterChip(
                                selected = docBackgroundColor == bg,
                                onClick = { viewModel.setDocBackgroundColor(bg) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
