package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectTapGestures

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
    var showEncodingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId)
    }

    // Status Bar Logic
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    // Determine if we need light status bar (black text) or dark status bar (white text)
    // Based on docBackgroundColor. If Sepia/White -> Light Status Bar (Black Text). If Dark -> Dark Status Bar (White Text).
    // But if system is in Dark Theme, and we use White background, we might want Light Status Bar?
    // Let's stick to the document background.
    val useLightStatusBar = uiState.docBackgroundColor != com.uviewer_android.data.repository.UserPreferencesRepository.DOC_BG_DARK
    
    DisposableEffect(useLightStatusBar, isFullScreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (isFullScreen) {
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = useLightStatusBar
            }
        }
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                 // Restore based on system theme
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = !systemInDarkTheme
            }
        }
    }

    // Update current line from UI state if needed, or maintain local state from scroll
    LaunchedEffect(uiState.currentLine) {
        if (uiState.currentLine > 0) currentLine = uiState.currentLine
    }

    // Progress saving with debounce
    LaunchedEffect(currentLine) {
        if (currentLine > 1) {
            kotlinx.coroutines.delay(3000)
            viewModel.updateProgress(currentLine)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp) // Limit width
            ) {
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
                            label = { Text(item.title ?: "Chapter ${index + 1}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            selected = index == uiState.currentChapterIndex,
                        onClick = {
                            if (item.href.startsWith("line-")) {
                                val line = item.href.replace("line-", "").toIntOrNull() ?: 1
                                viewModel.jumpToLine(line)
                                currentLine = line
                                if (webViewRef != null) {
                                    val js = "var el = document.getElementById('line-$line'); if(el) el.scrollIntoView({ behavior: 'instant' });"
                                    webViewRef?.evaluateJavascript(js, null)
                                }
                            } else {
                                viewModel.loadChapter(index)
                            }
                            scope.launch { drawerState.close() }
                        },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        },
        gesturesEnabled = false // Custom gesture implementation below
    ) {
        Scaffold(
            topBar = {
                if (!isFullScreen) {
                    TopAppBar(
                        title = { 
                            if (!uiState.isLoading) {
                                Text(uiState.fileName ?: stringResource(R.string.title_document_viewer), maxLines = 1) 
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { showEncodingDialog = true }) {
                                Icon(Icons.Default.Translate, contentDescription = "Encoding")
                            }
                            val context = androidx.compose.ui.platform.LocalContext.current
                            IconButton(onClick = {
                                val typeStr = if (type == FileEntry.FileType.EPUB) "EPUB" else "TEXT"
                                viewModel.toggleBookmark(filePath, currentLine, isWebDav, serverId, typeStr)
                                android.widget.Toast.makeText(context, "Bookmark Saved", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
                            }
                            if (type == FileEntry.FileType.EPUB || (type == FileEntry.FileType.TEXT && uiState.epubChapters.isNotEmpty())) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.table_of_contents))
                                }
                            } else if (uiState.totalLines > 0) {
                                IconButton(onClick = { showGoToLineDialog = true }) {
                                    Text("G", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            IconButton(onClick = { showFontSettingsDialog = true }) {
                                Icon(Icons.Default.FormatSize, contentDescription = stringResource(R.string.font_settings))
                            }
                        }
                    )
                }
                
                if (showEncodingDialog) {
                    AlertDialog(
                        onDismissRequest = { showEncodingDialog = false },
                        title = { Text(stringResource(R.string.select_encoding)) },
                        text = {
                            Column {
                                val encodings = listOf(
                                    stringResource(R.string.encoding_auto) to null,
                                    "UTF-8" to "UTF-8",
                                    stringResource(R.string.encoding_sjis) to "Shift_JIS",
                                    stringResource(R.string.encoding_euckr) to "EUC-KR",
                                    stringResource(R.string.encoding_johab) to "x-Johab",
                                    stringResource(R.string.encoding_w1252) to "windows-1252"
                                )
                                encodings.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                viewModel.setManualEncoding(value, isWebDav, serverId)
                                                showEncodingDialog = false 
                                            }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = uiState.manualEncoding == value, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(label)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showEncodingDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            },
            bottomBar = {
                if (!isFullScreen) {
                    BottomAppBar {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (!uiState.isLoading) {
                                Text(
                                    uiState.fileName ?: "", 
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            } else {
                                Spacer(modifier = Modifier.height(14.dp)) // Spacer to maintain layout height
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Line: $currentLine / ${uiState.totalLines}", style = MaterialTheme.typography.bodySmall)
                                // Percentage
                                Text("${if (uiState.totalLines > 0) (currentLine * 100 / uiState.totalLines) else 0}%", style = MaterialTheme.typography.bodySmall)
                            }
                            
                            // Chunk Navigation for Large Files
                            if (uiState.hasMoreContent || uiState.currentChunkIndex > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { viewModel.prevChunk() }, enabled = uiState.currentChunkIndex > 0) { Text("Prev Seg") }
                                    TextButton(onClick = { viewModel.nextChunk() }, enabled = uiState.hasMoreContent) { Text("Next Seg") }
                                }
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
                                 onValueChangeFinished = {
                                     viewModel.jumpToLine(currentLine)
                                 },
                                 valueRange = 1f..uiState.totalLines.toFloat().coerceAtLeast(1f),
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
            },
            snackbarHost = {} // Add snackbar host if needed
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
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                     AndroidView(
                         modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            object : WebView(context) {
                                fun getHorizontalScrollRangePublic(): Int = computeHorizontalScrollRange()
                            }.apply {
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun onLineChanged(line: Int) {
                                        post {
                                            if (line != currentLine) {
                                                currentLine = line
                                            }
                                        }
                                    }
                                }, "Android")

                                settings.allowFileAccess = true 
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val js = """
                                            window.onscroll = function() {
                                                var el = document.elementFromPoint(window.innerWidth/2, 20);
                                                while (el && (!el.id || !el.id.startsWith('line-'))) {
                                                    el = el.parentElement;
                                                }
                                                if (el && el.id.startsWith('line-')) {
                                                    var line = parseInt(el.id.replace('line-', ''));
                                                    Android.onLineChanged(line);
                                                }
                                            };
                                        """.trimIndent()
                                        
                                        val jsVertical = """
                                            window.onscroll = function() {
                                                // For vertical: Right side
                                                var el = document.elementFromPoint(window.innerWidth - 50, window.innerHeight / 2);
                                                 while (el && (!el.id || !el.id.startsWith('line-'))) {
                                                    el = el.parentElement;
                                                }
                                                if (el && el.id.startsWith('line-')) {
                                                    var line = parseInt(el.id.replace('line-', ''));
                                                    Android.onLineChanged(line);
                                                }
                                            };
                                        """.trimIndent()
                                        
                                        val finalJs = js 
                                        view?.evaluateJavascript(finalJs, null)
                                        
                                        if (currentLine > 1) {
                                            val restoreJs = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant' });"
                                            view?.evaluateJavascript(restoreJs, null)
                                        }
                                    }
                                }
                                webViewRef = this
                                
                                val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                        val width = width
                                        val x = e.x
                                        // Custom instant scrolling via JS to avoid animation
                                        if (false) {
                                            // Vertical Text (Removed)
                                        } else {
                                            // Standard Text (Vertical Scroll)
                                            // Left side = Prev
                                            // Right side = Next
                                            if (x < width / 3) {
                                                // Prev Page Logic
                                                if (canScrollVertically(-1)) {
                                                    webViewRef?.evaluateJavascript("window.scrollBy({ top: -window.innerHeight, behavior: 'instant' });", null)
                                                } else {
                                                    viewModel.prevChapter()
                                                }
                                            } else if (x > width * 2 / 3) {
                                                // Next Page Logic
                                                if (canScrollVertically(1)) {
                                                    webViewRef?.evaluateJavascript("window.scrollBy({ top: window.innerHeight, behavior: 'instant' });", null)
                                                } else {
                                                    viewModel.nextChapter()
                                                }
                                            } else {
                                                onToggleFullScreen()
                                            }
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
                                
                                setOnScrollChangeListener { _, _, _, _, _ ->
                                     // JS handle scroll
                                }
                            }
                        },
                        update = { webView ->
                            val wv = webView as android.webkit.WebView
                            val currentHash = uiState.content.hashCode()
                            val previousHash = (wv.tag as? Int) ?: 0
                             
                             val (bgColor, textColor) = when (uiState.docBackgroundColor) {
                                  UserPreferencesRepository.DOC_BG_SEPIA -> "#f5f5dc" to "#5b4636"
                                  UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#e0e0e0"
                                  else -> "#ffffff" to "#000000"
                             }
                                  val style = """
                                  <style>
                                      html, body {
                                          margin: 0;
                                          padding: 0;
                                          background-color: $bgColor !important;
                                          color: $textColor !important;
                                          writing-mode: horizontal-tb !important;
                                          -webkit-writing-mode: horizontal-tb !important;
                                      }
                                      body {
                                          font-family: ${uiState.fontFamily} !important;
                                          font-size: ${uiState.fontSize}px !important;
                                          line-height: 1.6 !important;
                                          padding: 1.5em !important; 
                                          box-sizing: border-box !important;
                                          word-wrap: break-word !important;
                                          overflow-wrap: break-word !important;
                                          /* Ensure safe area for cutouts if needed */
                                          padding-top: env(safe-area-inset-top, 1.5em);
                                          padding-bottom: calc(env(safe-area-inset-bottom, 1.5em) + 50vh); /* Allow scrolling past end */
                                          padding-left: env(safe-area-inset-left, 1.5em);
                                          padding-right: env(safe-area-inset-right, 1.5em);
                                      }
                                      </style>
                                  """
                                  // Inject style intelligently
                                  val contentWithStyle = if (uiState.content.contains("</head>")) {
                                      uiState.content.replace("</head>", "$style</head>")
                                  } else {
                                      "$style${uiState.content}"
                                  }
      
                                  if (contentWithStyle.hashCode() != previousHash) {
                                      wv.tag = contentWithStyle.hashCode()
                                      if (uiState.url != null) {
                                          wv.loadUrl(uiState.url!!)
                                      } else {
                                          // Use provided baseUrl or fallback to parent directory of filePath
                                          val baseUrl = uiState.baseUrl ?: (if (filePath.startsWith("/")) "file://${java.io.File(filePath).parent}/" else null)
                                          wv.loadDataWithBaseURL(baseUrl, contentWithStyle, "text/html", "UTF-8", null)
                                      }
                                      
                                      // Restore Scroll after load
                                      wv.post {
                                          if (currentLine > 1 && uiState.totalLines > 0) {
                                              val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant' });"
                                              wv.evaluateJavascript(js, null)
                                          }
                                      }
                                  }
                             }
                          )
                          
                          // Custom Swipe Detection for TOC (Left Edge Only)
                          if (type == FileEntry.FileType.EPUB || (type == FileEntry.FileType.TEXT && uiState.epubChapters.isNotEmpty())) {
                              Box(
                                  modifier = Modifier
                                      .fillMaxHeight()
                                      .width(40.dp) // Only active on left edge
                                      .align(Alignment.CenterStart)
                                      .pointerInput(Unit) {
                                          detectHorizontalDragGestures(
                                              onHorizontalDrag = { _, dragAmount ->
                                                  if (dragAmount > 20) {
                                                      scope.launch { drawerState.open() }
                                                  }
                                              }
                                          )
                                      }
                              )
                          }
                 }
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
                                viewModel.jumpToLine(line)
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
                    var sliderValue by remember { mutableFloatStateOf(fontSize.toFloat()) }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { viewModel.setFontSize(sliderValue.toInt()) },
                        valueRange = 12f..36f,
                        steps = 23 // Corrected steps for range 12-36
                    )
                }
                
                // Font Family
                Column {
                    Text(stringResource(R.string.font_family))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("serif", "sans-serif").forEach { family ->
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
