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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    filePath: String,
    type: FileEntry.FileType,
    isWebDav: Boolean,
    serverId: Int?,
    initialLine: Int? = null,
    viewModel: DocumentViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    activity: com.uviewer_android.MainActivity? = null
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
    var isPageLoading by remember { mutableStateOf(false) }
    var isNavigating by remember { mutableStateOf(false) }
    
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isSliderDragged by sliderInteractionSource.collectIsDraggedAsState()
    val isSliderPressed by sliderInteractionSource.collectIsPressedAsState()
    val isInteractingWithSlider = isSliderDragged || isSliderPressed

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId, initialLine)
    }

    // Status Bar Logic
    val context = androidx.compose.ui.platform.LocalContext.current
    val isAppDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    
    val docBackgroundColor by viewModel.docBackgroundColor.collectAsState()
    
    // Expose status bar appearance based on document background if it's "dark" or "custom" (if custom is dark enough)
    // Simple heuristic for custom: if bg is dark, icons should be white.
    fun isColorDark(colorHex: String?): Boolean {
        if (colorHex == null) return false
        return try {
            val color = android.graphics.Color.parseColor(colorHex)
            val grey = 0.2126 * android.graphics.Color.red(color) + 
                       0.7152 * android.graphics.Color.green(color) + 
                       0.0722 * android.graphics.Color.blue(color)
            grey < 128
        } catch (e: Exception) { false }
    }

    val useLightStatusBar = if (!isFullScreen) {
        !isAppDark
    } else {
        when (docBackgroundColor) {
            UserPreferencesRepository.DOC_BG_DARK -> false
            UserPreferencesRepository.DOC_BG_CUSTOM -> !isColorDark(uiState.customDocBackgroundColor)
            else -> true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = !isAppDark
            }
        }
    }

    LaunchedEffect(isFullScreen, useLightStatusBar) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = useLightStatusBar
            if (isFullScreen) {
                // Hide navigation, keep status
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // Update current line from UI state if needed, or maintain local state from scroll
    LaunchedEffect(uiState.currentLine) {
        if (uiState.currentLine > 0) currentLine = uiState.currentLine
    }

    // Progress saving with debounce
    LaunchedEffect(activity) {
        activity?.keyEvents?.collect { keyCode ->
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                webViewRef?.evaluateJavascript("window.pageUp();", null)
            } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                webViewRef?.evaluateJavascript("window.pageDown();", null)
            }
        }
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.table_of_contents),
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                LazyColumn {
                    itemsIndexed(uiState.epubChapters) { index, item ->
                        NavigationDrawerItem(
                            label = { Text(item.title ?: "Chapter ${index + 1}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            selected = index == uiState.currentChapterIndex,
                        onClick = {
                            if (item.href.startsWith("line-")) {
                                val line = item.href.replace("line-", "").toIntOrNull() ?: 1
                                val targetChunk = (line - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                                
                                // Set lock
                                if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(line - currentLine) > 50) {
                                    isNavigating = true
                                    isPageLoading = true
                                }
                                
                                viewModel.jumpToLine(line)
                                currentLine = line
                                
                                // Handle same chunk scroll restoration
                                if (webViewRef != null && targetChunk == uiState.currentChunkIndex) {
                                    val js = "var el = document.getElementById('line-$line'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                    webViewRef?.evaluateJavascript(js) {
                                        webViewRef?.postDelayed({
                                            isNavigating = false
                                            isPageLoading = false
                                        }, 100)
                                    }
                                }
                            } else {
                                isNavigating = true
                                isPageLoading = true
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
        gesturesEnabled = false
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
                            
                            Slider(
                                 value = currentLine.toFloat(),
                                 onValueChange = { 
                                     currentLine = it.toInt()
                                 },
                                 onValueChangeFinished = {
                                     val targetChunk = (currentLine - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                                     
                                     // Unlock navigation mode if chunk changes or jump distance is large
                                     if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(currentLine - uiState.currentLine) > 50) {
                                         isNavigating = true
                                         isPageLoading = true
                                     }
                                     viewModel.jumpToLine(currentLine)
                                     
                                     // If same chunk, manual scroll and release lock after delay
                                     if (targetChunk == uiState.currentChunkIndex) {
                                         val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                         webViewRef?.evaluateJavascript(js) {
                                             webViewRef?.postDelayed({
                                                 isPageLoading = false
                                                 isNavigating = false
                                             }, 100)
                                         }
                                     }
                                 },
                                 valueRange = 1f..uiState.totalLines.toFloat().coerceAtLeast(1f),
                                 interactionSource = sliderInteractionSource,
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
                                     onClick = { 
                                         isNavigating = true
                                         isPageLoading = true
                                         viewModel.prevChapter() 
                                     },
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
                                     onClick = { 
                                         isNavigating = true
                                         isPageLoading = true
                                         viewModel.nextChapter() 
                                     },
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
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (uiState.error != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isFullScreen) HorizontalDivider()
                        Box(modifier = Modifier.weight(1f)) {
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
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) return@post

                                            val reportedChunkIndex = (line - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                                            if (reportedChunkIndex != viewModel.uiState.value.currentChunkIndex) {
                                                return@post
                                            }

                                            if (line != currentLine) {
                                                currentLine = line
                                            }
                                        }
                                    }
                                    @android.webkit.JavascriptInterface
                                    fun autoLoadNext() {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) return@post
                                            
                                            if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.hasMoreContent) {
                                                viewModel.nextChunk()
                                            } else if (type == FileEntry.FileType.EPUB) {
                                                viewModel.nextChapter()
                                            }
                                        }
                                    }
                                    @android.webkit.JavascriptInterface
                                    fun autoLoadPrev() {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) return@post
                                            
                                            if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.currentChunkIndex > 0) {
                                                viewModel.prevChunk()
                                            } else if (type == FileEntry.FileType.EPUB) {
                                                viewModel.prevChapter()
                                            }
                                        }
                                    }
                                }, "Android")

                                settings.allowFileAccess = true 
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        
                                        val targetLine = viewModel.uiState.value.currentLine
                                        val jsScrollLogic = """
                                            // 1. Restore scroll position
                                            var el = document.getElementById('line-$targetLine'); 
                                            if(el) {
                                                el.scrollIntoView({ behavior: 'instant', block: 'start' });
                                            } else if ($targetLine === 1) {
                                                window.scrollTo(0, 0);
                                            }
                                            
                                            // 1.5. Dynamic tagging for 3-character ruby
                                             document.querySelectorAll('rt').forEach(function(el) {
                                                 var text = el.textContent.trim();
                                                 if (text.length === 3) {
                                                     // rt 태그 안쪽에 span을 넣어 감싸줍니다.
                                                     el.innerHTML = '<span class="ruby-3-inner">' + text + '</span>';
                                                 }
                                             });
                                            
                                            // 2. 정확한 JS 기반 페이지 넘김 함수 생성
                                            window.pageDown = function() {
                                                var oldY = window.pageYOffset;
                                                window.scrollBy({ top: window.innerHeight - 40, behavior: 'instant' });
                                                // If Y didn't change, we're at the very bottom
                                                if (window.pageYOffset === oldY) {
                                                    Android.autoLoadNext();
                                                }
                                            };
                                            
                                            window.pageUp = function() {
                                                var oldY = window.pageYOffset;
                                                window.scrollBy({ top: -(window.innerHeight - 40), behavior: 'instant' });
                                                // If Y didn't change and we're at the top
                                                if (window.pageYOffset === oldY && oldY === 0) {
                                                    Android.autoLoadPrev();
                                                }
                                            };

                                            // 3. Set up scroll listener with delay and accurate bottom detection
                                            setTimeout(function() {
                                                var isScrolling = false;
                                                window.onscroll = function() {
                                                    // Line detection
                                                    var el = document.elementFromPoint(window.innerWidth/2, 20);
                                                    while (el && (!el.id || !el.id.startsWith('line-'))) {
                                                        el = el.parentElement;
                                                    }
                                                    if (el && el.id.startsWith('line-')) {
                                                        var line = parseInt(el.id.replace('line-', ''));
                                                        Android.onLineChanged(line);
                                                    }
                                                    
                                                    // Accurate bottom detection
                                                    var scrollPosition = window.innerHeight + window.pageYOffset;
                                                    var bottomPosition = document.documentElement.scrollHeight;
                                                    
                                                    if (scrollPosition >= bottomPosition - 5) {
                                                       if (!isScrolling) {
                                                           isScrolling = true;
                                                           Android.autoLoadNext();
                                                       }
                                                    }
                                                    if (window.pageYOffset <= 0) {
                                                       if (!isScrolling) {
                                                           isScrolling = true;
                                                           Android.autoLoadPrev();
                                                       }
                                                    }
                                                };
                                            }, 500); 
                                        """.trimIndent()
                                        
                                        view?.evaluateJavascript(jsScrollLogic) {
                                            view.postDelayed({
                                                isPageLoading = false
                                                isNavigating = false
                                            }, 100)
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
                                                // Left touch: pageUp
                                                webViewRef?.evaluateJavascript("window.pageUp();", null)
                                            } else if (x > width * 2 / 3) {
                                                // Right touch: pageDown
                                                webViewRef?.evaluateJavascript("window.pageDown();", null)
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
            UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#000000"
            UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
            UserPreferencesRepository.DOC_BG_CUSTOM -> uiState.customDocBackgroundColor to uiState.customDocTextColor
            else -> "#ffffff" to "#000000"
        }
                                  val style = """
                                  <style>
                                      html, body {
                                          margin: 0;
                                          padding: 0 !important;
                                          background-color: $bgColor !important;
                                          color: $textColor !important;
                                          writing-mode: horizontal-tb !important;
                                          -webkit-writing-mode: horizontal-tb !important;
                                      }
                                      body {
                                          font-family: ${uiState.fontFamily} !important;
                                          font-size: ${uiState.fontSize}px !important;
                                          line-height: 1.6 !important;
                                          padding: 0 !important; /* Remove body padding for full-width images */
                                          box-sizing: border-box !important;
                                          word-wrap: break-word !important;
                                          overflow-wrap: break-word !important;
                                          /* Ensure safe area for cutouts if needed */
                                          padding-top: env(safe-area-inset-top, 0);
                                          padding-bottom: calc(env(safe-area-inset-bottom, 0) + 50vh); /* Allow scrolling past end */
                                      }
                                      /* Padding for text elements to keep them readable */
                                      p, div, h1, h2, h3, h4, h5, h6 {
                                          padding-left: ${uiState.sideMargin / 20.0}em !important;
                                          padding-right: ${uiState.sideMargin / 20.0}em !important;
                                      }
                                      /* Remove padding for images to make them edge-to-edge */
                                      div:has(img), p:has(img) {
                                          padding: 0 !important;
                                      }
                                      img {
                                          width: 100% !important;
                                          height: auto !important;
                                          display: block !important;
                                          margin: 0 auto !important;
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
                                      isPageLoading = true
                                      // If navigation was triggered, ensure isNavigating lock is on
                                      isNavigating = true 
                                      
                                      if (uiState.url != null) {
                                          wv.loadUrl(uiState.url!!)
                                      } else {
                                          // Use provided baseUrl or fallback to parent directory of filePath
                                          val baseUrl = uiState.baseUrl ?: (if (filePath.startsWith("/")) "file://${java.io.File(filePath).parent}/" else null)
                                          wv.loadDataWithBaseURL(baseUrl, contentWithStyle, "text/html", "UTF-8", null)
                                      }
                                      
                                      // Restore Scroll after load (if not navigating which handles it in onPageFinished)
                                      wv.post {
                                          if (!isNavigating && currentLine > 1 && uiState.totalLines > 0) {
                                              val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                              wv.evaluateJavascript(js, null)
                                          }
                                      }
                                  }
                             }
                          )
                          

                }
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            if (uiState.loadProgress < 1f) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Indexing: ${(uiState.loadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
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

                // Side Margin
                val marginValue by viewModel.sideMargin.collectAsState()
                Column {
                    Text(stringResource(R.string.side_margin_fmt, marginValue))
                    Slider(
                        value = marginValue.toFloat(),
                        onValueChange = { viewModel.setSideMargin(it.toInt()) },
                        valueRange = 0f..40f,
                        steps = 40
                    )
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
