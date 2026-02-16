package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

import androidx.activity.compose.BackHandler

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
    onNavigateToNext: () -> Unit = {},
    onNavigateToPrev: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    activity: com.uviewer_android.MainActivity? = null
) {
    BackHandler { onBack() }
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val isAppDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val docBackgroundColor by viewModel.docBackgroundColor.collectAsState()

    // [추가] 문서 배경색을 Compose Color 객체로 변환
    val targetDocColor = remember(docBackgroundColor, uiState.customDocBackgroundColor) {
        val colorHex = when (docBackgroundColor) {
            UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb"
            UserPreferencesRepository.DOC_BG_DARK -> "#121212"
            UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4"
            UserPreferencesRepository.DOC_BG_CUSTOM -> {
                val custom = uiState.customDocBackgroundColor
                if (custom != null && custom.startsWith("#")) custom else "#FFFFFF"
            }
            else -> "#FFFFFF"
        }
        try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            Color.White
        }
    }
    
    // [추가] 상태바 아이콘 색상 결정 (배경이 어두우면 아이콘은 밝게)
    val useDarkIcons = remember(targetDocColor, isFullScreen, isAppDark) {
         if (isFullScreen) {
             targetDocColor.luminance() > 0.5f // 배경이 밝으면(>0.5) 어두운 아이콘 사용
         } else {
             !isAppDark // 앱이 다크모드가 아니면 어두운 아이콘 사용
         }
    }

    // [수정] Window 설정 (상태바 색상 강제 적용 + 아이콘 색상)
    LaunchedEffect(isFullScreen, targetDocColor, useDarkIcons) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            
            // 상태바 배경색 설정 (Legacy 지원 및 Edge-to-Edge가 아닌 경우 대비)
            // 전체화면일 땐 문서색, 아닐 땐 투명(Scaffold 배경이 보임)
            val statusBarColorInt = (if (isFullScreen) targetDocColor else Color.Transparent).toArgb()
            
            window.statusBarColor = statusBarColorInt
            window.navigationBarColor = statusBarColorInt
            
            // 아이콘 밝기 설정
            insetsController.isAppearanceLightStatusBars = useDarkIcons
            insetsController.isAppearanceLightNavigationBars = useDarkIcons
            
            if (isFullScreen) {
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
                val tocListState = rememberLazyListState()
                LaunchedEffect(drawerState.isOpen) {
                    if (drawerState.isOpen && uiState.currentChapterIndex >= 0) {
                        tocListState.scrollToItem(uiState.currentChapterIndex)
                    }
                }
                LazyColumn(state = tocListState) {
                    itemsIndexed(uiState.epubChapters) { index, item ->
                        val isSelected = index == uiState.currentChapterIndex
                        NavigationDrawerItem(
                            label = { 
                                Column {
                                    Text(item.title ?: "Chapter ${index + 1}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (isSelected) {
                                        Text("Current Location: Line $currentLine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            selected = isSelected,
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
                                        }, 500) // 100 -> 500ms
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
            // [핵심 수정] 전체 화면일 때 Scaffold 배경색을 문서 배경색과 일치시킴
            containerColor = if (isFullScreen) targetDocColor else MaterialTheme.colorScheme.background,
            topBar = {
                if (!isFullScreen) {
                    TopAppBar(
                        title = { 
                            // Filename hidden as requested
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = onNavigateToPrev) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.prev_file))
                            }
                            IconButton(onClick = onNavigateToNext) {
                                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.next_file))
                            }
                            IconButton(onClick = { viewModel.toggleVerticalReading() }) {
                                Text(
                                    "V",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (uiState.isVertical) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            IconButton(onClick = { showEncodingDialog = true }) {
                                Icon(Icons.Default.Translate, contentDescription = "Encoding")
                            }
                            val context = androidx.compose.ui.platform.LocalContext.current
                            IconButton(onClick = {
                                val typeStr = if (type == FileEntry.FileType.EPUB) "EPUB" else "TEXT"
                                viewModel.toggleBookmark(filePath, currentLine, isWebDav, serverId, typeStr)
                                val msg = if (type == FileEntry.FileType.EPUB) {
                                    val ch = uiState.currentChapterIndex + 1
                                    "Bookmark Saved: Ch $ch / Line $currentLine"
                                } else {
                                    "Bookmark Saved: Line $currentLine"
                                }
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 3.dp
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            if (!uiState.isLoading) {
                                Text(
                                    uiState.fileName ?: "", 
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.height(14.dp)) // Spacer to maintain layout height
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    if (type == FileEntry.FileType.EPUB) {
                                        val ch = uiState.currentChapterIndex + 1
                                        val totalCh = uiState.epubChapters.size
                                        "Ch: $ch / $totalCh | Line: $currentLine / ${uiState.totalLines}"
                                    } else {
                                        "Line: $currentLine / ${uiState.totalLines}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                                     
                                     if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(currentLine - uiState.currentLine) > 50) {
                                         isNavigating = true
                                         isPageLoading = true
                                     }
                                     viewModel.jumpToLine(currentLine)
                                     
                                     if (targetChunk == uiState.currentChunkIndex) {
                                         val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                         webViewRef?.evaluateJavascript(js) {
                                             webViewRef?.postDelayed({
                                                 isPageLoading = false
                                                 isNavigating = false
                                             }, 500) // 100 -> 500ms
                                         }
                                     }
                                 },
                                 valueRange = 1f..uiState.totalLines.toFloat().coerceAtLeast(1f),
                                 interactionSource = sliderInteractionSource,
                                 modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp)
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
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun onLineChanged(line: Int) {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) return@post
                                            
                                            // Bypass chunk check for EPUB as it doesn't use chunks the same way
                                            if (type != FileEntry.FileType.EPUB) {
                                                val reportedChunkIndex = (line - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                                                if (reportedChunkIndex != viewModel.uiState.value.currentChunkIndex) {
                                                    return@post
                                                }
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
                                settings.allowContentAccess = true
                                settings.allowFileAccessFromFileURLs = true
                                settings.allowUniversalAccessFromFileURLs = true
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    settings.safeBrowsingEnabled = false
                                }
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                                isHorizontalScrollBarEnabled = uiState.isVertical
                                isVerticalScrollBarEnabled = !uiState.isVertical
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val targetLine = viewModel.uiState.value.currentLine
                                        val totalLines = viewModel.uiState.value.totalLines
                                        val enableAutoLoading = type == FileEntry.FileType.TEXT
                                        val isVertical = uiState.isVertical

                                         val jsScrollLogic = """
                                             (function() {
                                                 var isVertical = $isVertical;
                                                 
                                                 // 1. 초기 스크롤 위치 복원
                                                 if ($targetLine === $totalLines && $totalLines > 1) {
                                                      if (typeof jumpToBottom === 'function') { jumpToBottom(); }
                                                      else {
                                                           if (isVertical) window.scrollTo(-1000000, 0); // Left end
                                                           else window.scrollTo(0, 1000000);
                                                      }
                                                 } else {
                                                     var el = document.getElementById('line-$targetLine'); 
                                                     if(el) {
                                                         el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                                                     } else if ($targetLine === 1) {
                                                         if (isVertical) window.scrollTo(1000000, 0); // Right end (start)
                                                         else window.scrollTo(0, 0);
                                                     }
                                                 }
                                                                                                  // 1.5. 루비 병합 및 자간 벌어짐 방지 (세로쓰기 전용)
                                                  if (isVertical) {
                                                      var rubies = Array.from(document.querySelectorAll('ruby'));
                                                      for (var i = 0; i < rubies.length; i++) {
                                                          var ruby = rubies[i];
                                                          if (!ruby.parentNode) continue;
                                                          var baseText = Array.from(ruby.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                                          var rubyText = Array.from(ruby.querySelectorAll('rt')).map(function(r) { return r.textContent; }).join('');
                                                          if (rubyText.length === 0) continue;

                                                          // 구조 정규화 (순서 꼬임 방지)
                                                          while (ruby.firstChild) ruby.removeChild(ruby.firstChild);
                                                          ruby.appendChild(document.createTextNode(baseText));
                                                          var rt = document.createElement('rt');
                                                          rt.textContent = rubyText;
                                                          ruby.appendChild(rt);

                                                          var baseLen = baseText.length;
                                                          var rubyLen = rubyText.length;
                                                          var needsMerge = (baseLen === 1 && rubyLen >= 2) || 
                                                                           (baseLen === 2 && rubyLen >= 4) || 
                                                                           (baseLen === 3 && rubyLen >= 6) ||
                                                                           (baseLen >= 4 && rubyLen >= baseLen * 1.5);
          
                                                          if (needsMerge) {
                                                              // 1. 앞쪽 인접 루비 병합
                                                              while (ruby.previousSibling && ruby.previousSibling.tagName === 'RUBY') {
                                                                  var prev = ruby.previousSibling;
                                                                  var pRts = Array.from(prev.querySelectorAll('rt'));
                                                                  var pRubyText = pRts.map(function(r) { return r.textContent; }).join('');
                                                                  var pBase = Array.from(prev.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                                                  ruby.insertBefore(document.createTextNode(pBase), ruby.firstChild);
                                                                  rt.textContent = pRubyText + rt.textContent;
                                                                  prev.parentNode.removeChild(prev);
                                                              }
                                                              // 2. 뒤쪽 인접 루비 병합
                                                              while (ruby.nextSibling && ruby.nextSibling.tagName === 'RUBY') {
                                                                  var next = ruby.nextSibling;
                                                                  var nRts = Array.from(next.querySelectorAll('rt'));
                                                                  var nRubyText = nRts.map(function(r) { return r.textContent; }).join('');
                                                                  var nBase = Array.from(next.childNodes).filter(function(n) { return n.nodeType === 3; }).map(function(n) { return n.textContent; }).join('');
                                                                  ruby.insertBefore(document.createTextNode(nBase), rt);
                                                                  rt.textContent = rt.textContent + nRubyText;
                                                                  next.parentNode.removeChild(next);
                                                              }
                                                              // 3. 앞쪽 일반 글자 1자 흡수 (중앙 정렬)
                                                              var finalPrev = ruby.previousSibling;
                                                              if (finalPrev && finalPrev.nodeType === 3) {
                                                                  var txt = finalPrev.textContent;
                                                                  if (txt.length > 0) {
                                                                      ruby.insertBefore(document.createTextNode(txt[txt.length - 1]), ruby.firstChild);
                                                                      finalPrev.textContent = txt.substring(0, txt.length - 1);
                                                                  }
                                                              }
                                                              // 4. 뒤쪽 일반 글자 1자 흡수
                                                              var finalNext = ruby.nextSibling;
                                                              if (finalNext && finalNext.nodeType === 3) {
                                                                  var txt = finalNext.textContent;
                                                                  if (txt.length > 0) {
                                                                      ruby.insertBefore(document.createTextNode(txt[0]), rt);
                                                                      finalNext.textContent = txt.substring(1);
                                                                  }
                                                              }
                                                          }
                                                      }
                                                  } else {
                                                      // 가폭모드(기존 압축 로직 유지)
                                                      document.querySelectorAll('rt').forEach(function(el) {
                                                          var rubyText = el.textContent.trim();
                                                          var baseNode = el.previousSibling || el.parentElement.firstChild;
                                                          var baseText = baseNode ? baseNode.textContent.trim() : "";
                                                          var baseLen = baseText.length;
                                                          var rubyLen = rubyText.length;
                                                          
                                                          var needCompression = false;
                                                          if (baseLen === 1 && rubyLen >= 2) needCompression = true;
                                                          else if (baseLen === 2 && rubyLen >= 4) needCompression = true;
                                                          else if (baseLen === 3 && rubyLen >= 6) needCompression = true;
                                                          else if (baseLen >= 4 && rubyLen >= baseLen * 1.5) needCompression = true;
                                                          
                                                          if (needCompression) {
                                                              el.classList.add('ruby-wide');
                                                              if (el.children.length === 0) {
                                                                  el.innerHTML = '<span>' + rubyText + '</span>';
                                                              }
                                                          }
                                                      });
                                                  }
                                                                                                  // 2. 정확한 페이지 이동 함수 (딱 한 페이지씩)
                                                  window.detectAndReportLine = function() {
                                                      var width = window.innerWidth;
                                                      var height = window.innerHeight;
                                                      
                                                      var points = [];
                                                      if (isVertical) {
                                                          // 세로쓰기: 화면 오른쪽 가장자리가 현재 읽는 위치
                                                          var rx = width - 50; // 오른쪽에서 50px 지점
                                                          points = [
                                                              {x: rx, y: height * 0.2},
                                                              {x: rx, y: height * 0.4},
                                                              {x: rx, y: height * 0.5},
                                                              {x: rx, y: height * 0.6},
                                                              {x: rx, y: height * 0.8}
                                                          ];
                                                      } else {
                                                          // 가로쓰기: 화면 상단 중앙이 현재 읽는 위치
                                                          var cx = width / 2;
                                                          var cy = height / 2;
                                                          points = [
                                                              {x: cx, y: cy},
                                                              {x: cx + 20, y: cy}, {x: cx - 20, y: cy},
                                                              {x: cx, y: cy + 20}, {x: cx, y: cy - 20}
                                                          ];
                                                      }
                                                      
                                                      var foundLine = -1;
                                                      for (var i = 0; i < points.length; i++) {
                                                          var el = document.elementFromPoint(points[i].x, points[i].y);
                                                          while (el && el.tagName !== 'BODY' && el.tagName !== 'HTML') {
                                                              if (el.id && el.id.startsWith('line-')) {
                                                                  foundLine = parseInt(el.id.replace('line-', ''));
                                                                  break;
                                                              }
                                                              el = el.parentElement;
                                                          }
                                                          if (foundLine > 0) break;
                                                      }
                                                      if (foundLine > 0) {
                                                          Android.onLineChanged(foundLine);
                                                      }
                                                  };

                                                  window.pageDown = function() {
                                                      var pageSize = isVertical ? document.documentElement.clientWidth : document.documentElement.clientHeight;
                                                      var overlap = 40; // 겹침 거리 (약 한 글자)
                                                      var moveSize = pageSize - overlap;
                                                      
                                                      if (isVertical) {
                                                          var oldX = window.pageXOffset;
                                                          window.scrollBy({ left: -moveSize, behavior: 'instant' });
                                                          // 끝에 도달하여 이동량이 적을 때 다음 청크 로드
                                                          if (Math.abs(window.pageXOffset - oldX) < 10) Android.autoLoadNext();
                                                      } else {
                                                          var oldY = window.pageYOffset;
                                                          window.scrollBy({ top: moveSize, behavior: 'instant' });
                                                          if (Math.abs(window.pageYOffset - oldY) < 10) Android.autoLoadNext();
                                                      }
                                                      window.detectAndReportLine();
                                                  };
                                                                                                  window.pageUp = function() {
                                                      var pageSize = isVertical ? document.documentElement.clientWidth : document.documentElement.clientHeight;
                                                      var overlap = 40;
                                                      var moveSize = pageSize - overlap;
          
                                                      if (isVertical) {
                                                          var oldX = window.pageXOffset;
                                                          window.scrollBy({ left: moveSize, behavior: 'instant' });
                                                          if (Math.abs(window.pageXOffset - oldX) < 10) Android.autoLoadPrev();
                                                      } else {
                                                          var oldY = window.pageYOffset;
                                                          window.scrollBy({ top: -moveSize, behavior: 'instant' });
                                                          if (Math.abs(window.pageYOffset - oldY) < 10 && oldY === 0) Android.autoLoadPrev();
                                                      }
                                                      window.detectAndReportLine();
                                                  };
         
                                                 // 3. 스크롤 감지 및 라인 번호 업데이트 (그리드 서치 적용)
                                                 var scrollTimer = null;
                                                  window.onscroll = function() {
                                                      if (scrollTimer) clearTimeout(scrollTimer);
                                                      scrollTimer = setTimeout(function() {
                                                          window.detectAndReportLine();
                                                          
                                                          var isScrolling = false; // Guard for autoload calls
                                                         
                                                         // 자동 로딩 로직
                                                         if ($enableAutoLoading) {
                                                             if (isVertical) {
                                                                 var maxScrollX = document.documentElement.scrollWidth - window.innerWidth;
                                                                 // Chromium vertical-rl: 0 is Right (Start), Negative max is Left (End)
                                                                 // 왼쪽 끝 도달 시 다음 챕터
                                                                 if (window.pageXOffset <= -(maxScrollX - 10)) {
                                                                    if (!isScrolling) { isScrolling = true; Android.autoLoadNext(); }
                                                                 }
                                                                 // 오른쪽 끝 도달 시 이전 챕터
                                                                 else if (window.pageXOffset >= -10) { 
                                                                    if (!isScrolling) { isScrolling = true; Android.autoLoadPrev(); }
                                                                 }
                                                             } else {
                                                                 // 가로 모드
                                                                 var scrollPosition = window.innerHeight + window.pageYOffset;
                                                                 var bottomPosition = document.documentElement.scrollHeight;
                                                                 if (scrollPosition >= bottomPosition - 5) {
                                                                    if (!isScrolling) { isScrolling = true; Android.autoLoadNext(); }
                                                                 }
                                                                 else if (window.pageYOffset <= 0) {
                                                                    if (!isScrolling) { isScrolling = true; Android.autoLoadPrev(); }
                                                                 }
                                                             }
                                                         }
                                                     }, 100);
                                                 };
                                             })();
                                         """.trimIndent()
                                        
                                        view?.evaluateJavascript(jsScrollLogic) {
                                            webViewRef?.postDelayed({
                                                isPageLoading = false
                                                isNavigating = false
                                            }, 500) // 100 -> 500ms
                                        }
                                    }
                                }
                                webViewRef = this
                                
                                val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                        val width = width
                                        val x = e.x
                                        // Custom instant scrolling via JS to avoid animation
                                        if (uiState.isVertical) {
                                            // 세로쓰기: 오른쪽 터치 = 이전(pageUp), 왼쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                webViewRef?.evaluateJavascript("window.pageDown();", null) // 앞으로
                                            } else if (x > width * 2 / 3) {
                                                webViewRef?.evaluateJavascript("window.pageUp();", null)   // 뒤로
                                            } else {
                                                onToggleFullScreen()
                                            }
                                        } else {
                                            // 가로쓰기: 왼쪽 터치 = 이전(pageUp), 오른쪽 터치 = 다음(pageDown)
                                            if (x < width / 3) {
                                                webViewRef?.evaluateJavascript("window.pageUp();", null)
                                            } else if (x > width * 2 / 3) {
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
            UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb" to "#322D29"
            UserPreferencesRepository.DOC_BG_DARK -> "#121212" to "#cccccc"
            UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4" to "#343426"
            UserPreferencesRepository.DOC_BG_CUSTOM -> uiState.customDocBackgroundColor to uiState.customDocTextColor
            else -> "#ffffff" to "#000000"
        }
                                   val style = """
                                   <style>
                                       /* 1. 스크롤 방향 강제 및 오버스크롤(튕김) 방지 */
                                       html {
                                           width: 100vw !important;
                                           height: 100vh !important;
                                           margin: 0 !important;
                                           padding: 0 !important;
                                           /* 세로쓰기일 땐 X축만, 가로쓰기일 땐 Y축만 허용 */
                                           overflow-x: ${if (uiState.isVertical) "scroll" else "hidden"} !important;
                                           overflow-y: ${if (uiState.isVertical) "hidden" else "scroll"} !important;
                                           /* 튕김 효과 방지 */
                                           overscroll-behavior: none !important;
                                           /* 터치 동작 제한 (세로쓰기면 좌우 스크롤만 허용) */
                                           touch-action: ${if (uiState.isVertical) "pan-x" else "pan-y"} !important;
                                           
                                           /* [중요] html에도 writing-mode 적용하여 브라우저 좌표축 동기화 */
                                           writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                           -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                       }
                                   
                                       body {
                                           height: 100vh !important;
                                           min-height: 100vh !important;
                                           width: ${if (uiState.isVertical) "auto" else "100%"} !important;
                                           margin: 0 !important;
                                           padding: 0 !important; /* body 패딩 제거 (좌표 계산 오차 원인) */
                                           
                                           background-color: $bgColor !important;
                                           color: $textColor !important;
                                           
                                           writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                           -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                           
                                           font-family: ${uiState.fontFamily} !important;
                                           font-size: ${uiState.fontSize}px !important;
                                           line-height: 1.8 !important;
                                           
                                           /* 안전 영역 패딩 */
                                           padding-top: env(safe-area-inset-top, 0) !important;
                                           padding-bottom: env(safe-area-inset-bottom, 0) !important;
                                       }
                                       
                                       /* 2. 문단 설정: 여백이 터치 감지를 방해하지 않도록 조정 */
                                       p, div, h1, h2, h3, h4, h5, h6 {
                                           display: block !important; 
                                           height: auto !important;
                                           width: auto !important;
                                           margin-top: 0 !important;
                                           /* 줄 간격 */
                                           margin-bottom: ${if (uiState.isVertical) "0" else "0.5em"} !important;
                                           margin-left: ${if (uiState.isVertical) "1em" else "0"} !important;
                                           
                                           /* 전체 여백 적용 */
                                           padding-left: ${if (uiState.isVertical) "0" else "${uiState.sideMargin / 20.0}em"} !important;
                                           padding-right: ${if (uiState.isVertical) "0" else "${uiState.sideMargin / 20.0}em"} !important;
                                           padding-top: ${if (uiState.isVertical) "${uiState.sideMargin / 20.0}em" else "0"} !important;
                                           padding-bottom: ${if (uiState.isVertical) "${uiState.sideMargin / 20.0}em" else "0"} !important;
                                           
                                           box-sizing: border-box !important;
                                       }
                                       /* Remove padding for images to make them edge-to-edge */
                                       div:has(img), p:has(img) {
                                           padding: 0 !important;
                                       }
                                       img {
                                           max-width: 100% !important;
                                           height: auto !important;
                                           display: block !important;
                                           margin: 1em auto !important;
                                           object-fit: contain !important;
                                       }
                                       img[style*="display: none"] {
                                           margin: 0 !important;
                                           height: 0 !important;
                                       }
                                      /* Table wrapping support */
                                      table {
                                          width: 100% !important;
                                          table-layout: fixed !important;
                                          border-collapse: collapse !important;
                                          margin: 1em 0 !important;
                                      }
                                      th, td {
                                          border: 1px solid #888 !important;
                                          padding: 8px !important;
                                          white-space: normal !important;
                                          word-wrap: break-word !important;
                                          overflow-wrap: break-word !important;
                                          vertical-align: top !important;
                                      }
                                      rt {
                                          font-size: 0.5em !important;
                                          text-align: center !important;
                                      }
                                      .ruby-wide {
                                          margin-left: -0.3em !important;
                                          margin-right: -0.3em !important;
                                      }
                                      .ruby-wide span {
                                          display: inline-block !important;
                                          transform: scaleX(0.75) !important;
                                          transform-origin: center bottom !important;
                                          white-space: nowrap !important;
                                      }
                                      div[id^="line-"] {
                                          break-inside: avoid !important;
                                      }
                                      </style>
                                  """
                                   // Inject style intelligently and prevent Quirks Mode
                                   val contentWithStyle = if (uiState.content.contains("</head>")) {
                                       uiState.content.replace("</head>", "$style</head>")
                                   } else if (uiState.content.contains("<body")) {
                                       val match = "<body[^>]*>".toRegex().find(uiState.content)
                                       if (match != null) {
                                           uiState.content.substring(0, match.range.last + 1) + style + uiState.content.substring(match.range.last + 1)
                                       } else {
                                           "$style${uiState.content}"
                                       }
                                   } else {
                                       // 태그가 없는 순수 텍스트라면, 표준 HTML 뼈대를 만들어 감싸줌 (높이 확보의 핵심)
                                       """
                                       <!DOCTYPE html>
                                       <html>
                                       <head>
                                           <meta charset="utf-8">
                                           <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes" />
                                           $style
                                       </head>
                                       <body>
                                           ${uiState.content}
                                       </body>
                                       </html>
                                       """.trimIndent()
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
                    if (line != null && line in 1..uiState.totalLines) {
                        val targetChunk = (line - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                        if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(line - currentLine) > 50) {
                            isNavigating = true
                            isPageLoading = true
                        }
                        viewModel.jumpToLine(line)
                        currentLine = line // 즉시 로컬 상태 업데이트
                        if (targetChunk == uiState.currentChunkIndex && webViewRef != null) {
                            val js = "var el = document.getElementById('line-$line'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                            webViewRef?.evaluateJavascript(js) {
                                webViewRef?.postDelayed({
                                    isPageLoading = false
                                    isNavigating = false
                                }, 500)
                            }
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
                        listOf(
                            UserPreferencesRepository.DOC_BG_WHITE to stringResource(R.string.doc_bg_white),
                            UserPreferencesRepository.DOC_BG_SEPIA to stringResource(R.string.doc_bg_sepia),
                            UserPreferencesRepository.DOC_BG_COMFORT to stringResource(R.string.doc_bg_comfort)
                        ).forEach { (bg, label) ->
                            FilterChip(
                                selected = docBackgroundColor == bg,
                                onClick = { viewModel.setDocBackgroundColor(bg) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            UserPreferencesRepository.DOC_BG_DARK to stringResource(R.string.doc_bg_dark),
                            UserPreferencesRepository.DOC_BG_CUSTOM to stringResource(R.string.doc_bg_custom)
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

