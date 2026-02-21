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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.uviewer_android.MainActivity
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
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext

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
    var currentLine by rememberSaveable { mutableIntStateOf(initialLine ?: 1) }
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showFontSettingsDialog by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showEncodingDialog by remember { mutableStateOf(false) }
    var isPageLoading by remember { mutableStateOf(false) }
    var isNavigating by remember { mutableStateOf(false) }
    var bottomMaskHeight by remember { mutableFloatStateOf(0f) }
    var topMaskHeight by remember { mutableFloatStateOf(0f) }
    var leftMaskWidth by remember { mutableFloatStateOf(0f) }
    var rightMaskWidth by remember { mutableFloatStateOf(0f) }
    
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isSliderDragged by sliderInteractionSource.collectIsDraggedAsState()
    val isSliderPressed by sliderInteractionSource.collectIsPressedAsState()
    val isInteractingWithSlider = isSliderDragged || isSliderPressed
    var tempSliderValue by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(filePath) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId, initialLine)
    }

    val context = LocalContext.current
    val currentActivity = activity ?: (context as? MainActivity)

    // Keep screen on while viewing document
    DisposableEffect(currentActivity) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentActivity?.volumeKeyPagingActive = true
        onDispose {
            // Save progress one last time on dispose
            viewModel.updateProgress(currentLine)

            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            currentActivity?.volumeKeyPagingActive = false
        }
    }
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
    LaunchedEffect(currentActivity) {
        currentActivity?.keyEvents?.collect { keyCode ->
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
            kotlinx.coroutines.delay(1000)
            viewModel.updateProgress(currentLine)
        }
    }

    LaunchedEffect(uiState.appendTrigger) {
        if (uiState.appendTrigger == 0L) return@LaunchedEffect
        // [수정됨] updateType 3번(Replace) 포함
        if (uiState.contentUpdateType in 1..3) {
            val base64Html = android.util.Base64.encodeToString(uiState.content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val jsFunction = when (uiState.contentUpdateType) {
                1 -> "appendHtmlBase64"
                2 -> "prependHtmlBase64"
                3 -> "replaceHtmlBase64"
                else -> ""
            }
            val chunkIdx = if (type == FileEntry.FileType.EPUB) uiState.currentChapterIndex else uiState.currentChunkIndex
            val targetLine = uiState.currentLine
            // JS로 함수 호출 시 targetLine 전달
            // [수정됨] evaluateJavascript에 콜백 블록을 추가하여 실행 후 잠금을 해제합니다.
            webViewRef?.evaluateJavascript("if(window.$jsFunction) { window.$jsFunction('$base64Html', $chunkIdx, $targetLine); window.isScrolling = false; }") {
                webViewRef?.postDelayed({
                    isPageLoading = false
                    isNavigating = false
                }, 300) // UI가 렌더링될 시간(300ms)을 주고 잠금 해제
            }
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
                                    color = if (uiState.isVertical) Color(0xFF007AFF) else LocalContentColor.current
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
                                    stringResource(R.string.encoding_johab) to "JO-HAB",
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
                            val isEpub = type == FileEntry.FileType.EPUB
                            val totalCh = uiState.epubChapters.size.coerceAtLeast(1)

                            // [핵심 1] 글로벌 진행도 계산
                            val sliderValue = if (isEpub) {
                                val chIdx = uiState.currentChapterIndex
                                val chLines = uiState.totalLines.coerceAtLeast(1)
                                chIdx.toFloat() + (currentLine.toFloat() / chLines).coerceIn(0f, 1f)
                            } else {
                                currentLine.toFloat()
                            }

                            val sliderRange = if (isEpub) {
                                0f..totalCh.toFloat() // EPUB 범위: 0 ~ 총 챕터 수
                            } else {
                                1f..uiState.totalLines.toFloat().coerceAtLeast(1f) // 일반 텍스트 범위
                            }

                            // 퍼센트 표시도 글로벌 스케일에 맞게 수정
                            val progressPercent = if (isEpub) {
                                ((sliderValue / totalCh) * 100).toInt().coerceIn(0, 100)
                            } else {
                                if (uiState.totalLines > 0) (currentLine * 100 / uiState.totalLines).coerceIn(0, 100) else 0
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    if (isEpub) {
                                        val ch = uiState.currentChapterIndex + 1
                                        "Ch: $ch / $totalCh | Line: $currentLine / ${uiState.totalLines}"
                                    } else {
                                        "Line: $currentLine / ${uiState.totalLines}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("$progressPercent%", style = MaterialTheme.typography.bodySmall)
                            }

                            val displayValue = if (tempSliderValue >= 0f) tempSliderValue else sliderValue

                            Slider(
                                value = displayValue,
                                onValueChange = { tempSliderValue = it },
                                onValueChangeFinished = {
                                    val finalVal = tempSliderValue
                                    tempSliderValue = -1f // 임시값 초기화
                                    
                                    if (isEpub) {
                                        // [EPUB 점프] 슬라이더 위치에 해당하는 챕터로 이동
                                        val targetCh = finalVal.toInt().coerceIn(0, totalCh - 1)
                                        
                                        // 돔(DOM) 찌꺼기가 남지 않게 화면을 비우고 점프
                                        isNavigating = true
                                        isPageLoading = true
                                        webViewRef?.evaluateJavascript("document.body.innerHTML = ''; window.scrollTo(0,0);", null)
                                        viewModel.jumpToChapter(targetCh)
                                        
                                    } else {
                                        // [일반 텍스트 점프]
                                        val targetLine = finalVal.toInt()
                                        currentLine = targetLine
                                        val targetChunk = (targetLine - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                                        
                                        if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(targetLine - uiState.currentLine) > 50) {
                                            isNavigating = true
                                            isPageLoading = true
                                            // [수정됨] 아래 줄(document.body.innerHTML = '')을 삭제하거나 주석 처리하세요.
                                            // webViewRef?.evaluateJavascript("document.body.innerHTML = ''; window.scrollTo(0,0);", null)
                                        }
                                        viewModel.jumpToLine(targetLine)
                                        
                                        // 동일 청크 내 점프인 경우의 스크롤 복구
                                        if (targetChunk == uiState.currentChunkIndex) {
                                            val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                            webViewRef?.evaluateJavascript(js) {
                                                webViewRef?.postDelayed({
                                                    isPageLoading = false
                                                    isNavigating = false
                                                }, 500)
                                            }
                                        }
                                    }
                                },
                                valueRange = sliderRange,
                                interactionSource = sliderInteractionSource,
                                modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp)
                            )
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
                                    fun onLineChangedStr(lineStr: String) {
                                        post {
                                            if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) return@post
                                            
                                            if (type == FileEntry.FileType.EPUB) {
                                                val parts = lineStr.split("-")
                                                if (parts.size == 2) {
                                                    val ch = parts[0].toIntOrNull() ?: return@post
                                                    val ln = parts[1].toIntOrNull() ?: return@post
                                                    if (ln != currentLine) {
                                                        currentLine = ln
                                                        viewModel.setEpubPosition(ch, ln)
                                                    }
                                                }
                                            } else {
                                                val ln = lineStr.toIntOrNull() ?: return@post
                                                if (ln != currentLine) {
                                                    currentLine = ln
                                                    viewModel.setCurrentLine(ln)
                                                }
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

                                    @android.webkit.JavascriptInterface
                                    fun updateBottomMask(height: Float) {
                                        post {
                                             bottomMaskHeight = height
                                        }
                                    }
                                    
                                    @android.webkit.JavascriptInterface
                                    fun updateTopMask(height: Float) {
                                        post {
                                             topMaskHeight = height
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun updateLeftMask(width: Float) {
                                        post {
                                             leftMaskWidth = width
                                        }
                                    }
                                    
                                    @android.webkit.JavascriptInterface
                                    fun updateRightMask(width: Float) {
                                        post {
                                             rightMaskWidth = width
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
                                         val enableAutoLoading = type == FileEntry.FileType.TEXT || type == FileEntry.FileType.EPUB
                                        val isVertical = uiState.isVertical

                                         val pagingMode = uiState.pagingMode
                                         val jsScrollLogic = """
                                             (function() {
                                                 var isVertical = $isVertical;
                                                 var pagingMode = $pagingMode;
                                                 var enableAutoLoading = $enableAutoLoading;
                                                 
                                                  // 1. 시스템(JS) 스크롤과 유저 스크롤을 구분하기 위한 락(Lock) 변수
                                                  window.isSystemScrolling = false;
                                                  window.sysScrollTimer = null;

                                                  // 2. JS가 강제로 스크롤을 조작할 때 사용할 안전한 래퍼 함수
                                                  window.safeScrollBy = function(x, y) {
                                                      window.isSystemScrolling = true; // 스크롤 이벤트 무시 시작
                                                      window.scrollBy(x, y);
                                                      
                                                      // 보정이 끝난 후 충분한 시간(250ms)이 지난 뒤에 락을 풉니다.
                                                      if (window.sysScrollTimer) clearTimeout(window.sysScrollTimer);
                                                      window.sysScrollTimer = setTimeout(function() {
                                                          window.isSystemScrolling = false;
                                                      }, 250); 
                                                  };
                                                 
                                                  // 1. Restore scroll position
                                                  if ($targetLine === $totalLines && $totalLines > 1) {
                                                       if (typeof jumpToBottom === 'function') { jumpToBottom(); }
                                                       else {
                                                            if (isVertical) window.scrollTo(-1000000, 0); 
                                                            else window.scrollTo(0, 1000000);
                                                       }
                                                  } else {
                                                      var el = document.getElementById('line-${if (type == FileEntry.FileType.EPUB) "${uiState.currentChapterIndex}-" else ""}$targetLine'); 
                                                      if(el) {
                                                          el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                                                      } else if ($targetLine === 1) {
                                                          if (isVertical) window.scrollTo(1000000, 0); 
                                                          else window.scrollTo(0, 0);
                                                      }
                                                  }

                                                    // [수정됨] 청크 개수 제한을 5로 늘려 이전2 + 현재 + 이후2 구조가 가능하게 함
                                                   window.MAX_CHUNKS = 5; 
                                                   
                                                   // [추가됨] 앞뒤 청크를 미리(미리보기 화면의 1.5배 전부터) 불러오는 독립 함수
                                                   window.checkPreload = function() {
                                                       if (!enableAutoLoading) return;
                                                       var w = window.innerWidth;
                                                       var h = window.innerHeight;
                                                       // 여유 마진을 화면의 1.5배로 크게 잡음 (사용자가 도달하기 전에 미리 로드)
                                                       var preloadMarginX = w * 0.3; 
                                                       var preloadMarginY = h * 0.3;

                                                       if (isVertical) {
                                                           var maxScrollX = document.documentElement.scrollWidth - window.innerWidth;
                                                           if (window.pageXOffset <= -(maxScrollX - preloadMarginX)) {
                                                               window.isScrolling = true; Android.autoLoadNext();
                                                           } else if (window.pageXOffset >= -preloadMarginX) { 
                                                               window.isScrolling = true; Android.autoLoadPrev();
                                                           }
                                                       } else {
                                                           var scrollPosition = window.innerHeight + window.pageYOffset;
                                                           var bottomPosition = document.documentElement.scrollHeight;
                                                           if (scrollPosition >= bottomPosition - preloadMarginY) {
                                                               window.isScrolling = true; Android.autoLoadNext();
                                                           } else if (window.pageYOffset <= preloadMarginY) {
                                                               window.isScrolling = true; Android.autoLoadPrev();
                                                           }
                                                       }
                                                   };
                                                   window.appendHtmlBase64 = function(base64Str, chunkIndex) {
                                                       try {
                                                           var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                                                           var parser = new DOMParser();
                                                           var doc = parser.parseFromString(htmlStr, 'text/html');
                                                           var container = document.body;
                                                           var endMarker = document.getElementById('end-marker');
                                                           
                                                           var chunkWrapper = document.createElement('div');
                                                           chunkWrapper.className = 'content-chunk';
                                                           chunkWrapper.dataset.index = chunkIndex;
                                                           
                                                           var hr = document.createElement('hr');
                                                           hr.style.cssText = "border: none; border-top: 1px dashed #888; margin: 3em 1em; width: 80%; opacity: 0.5;";
                                                           chunkWrapper.appendChild(hr);

                                                           Array.from(doc.body.childNodes).forEach(function(node) {
                                                               if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                                                               chunkWrapper.appendChild(node);
                                                           });

                                                           if (endMarker) container.insertBefore(chunkWrapper, endMarker);
                                                           else container.appendChild(chunkWrapper);

                                                           // 이미지 로딩 등으로 인한 높이 변화 대기 후 GC 실행
                                                            setTimeout(function() {
                                                               window.enforceChunkLimit(true);
                                                               window.updateMask();
                                                               window.isScrolling = false; 
                                                               window.checkPreload(); // 추가됨: 로드 직후 한 번 더 공간 검사
                                                           }, 100);
                                                       } catch(e) { console.error(e); window.isScrolling = false; }
                                                   };

                                                   window.prependHtmlBase64 = function(base64Str, chunkIndex) {
                                                       try {
                                                           var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                                                           var parser = new DOMParser();
                                                           var doc = parser.parseFromString(htmlStr, 'text/html');
                                                           var container = document.body;
                                                           
                                                           var chunkWrapper = document.createElement('div');
                                                           chunkWrapper.className = 'content-chunk';
                                                           chunkWrapper.dataset.index = chunkIndex;

                                                           Array.from(doc.body.childNodes).forEach(function(node) {
                                                               if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                                                               chunkWrapper.appendChild(node);
                                                           });

                                                           var hr = document.createElement('hr');
                                                           hr.style.cssText = "border: none; border-top: 1px dashed #888; margin: 3em 1em; width: 80%; opacity: 0.5;";
                                                           chunkWrapper.appendChild(hr);

                                                           container.insertBefore(chunkWrapper, container.firstChild);

                                                           // [핵심 해결책] ResizeObserver: 이미지가 비동기 로딩되며 크기가 커질 때마다 실시간으로 스크롤 역산
                                                           var lastWidth = 0;
                                                           var lastHeight = 0;
                                                           
                                                           var ro = new ResizeObserver(function(entries) {
                                                               for (var i = 0; i < entries.length; i++) {
                                                                   var entry = entries[i];
                                                                   var newWidth = entry.contentRect.width;
                                                                   var newHeight = entry.contentRect.height;
                                                                   
                                                                   var diffW = newWidth - lastWidth;
                                                                   var diffH = newHeight - lastHeight;
                                                                   
                                                                   lastWidth = newWidth;
                                                                   lastHeight = newHeight;

                                                                   // 이미지가 커지면서 밀어낸 공간만큼 스크롤을 이동시켜 시야를 꽉 잡아줌
                                                                   if (isVertical) {
                                                                       window.safeScrollBy(-diffW, 0); 
                                                                   } else {
                                                                       window.safeScrollBy(0, diffH);
                                                                   }
                                                               }
                                                           });
                                                           
                                                           // 관찰 시작 (이때 초기 DOM 크기에 대한 첫 번째 보정이 자동으로 발생합니다)
                                                           ro.observe(chunkWrapper);

                                                           // 이미지가 전부 로딩될 넉넉한 시간(2초) 뒤에 관찰을 끄고 메모리 확보
                                                           setTimeout(function() {
                                                               ro.disconnect();
                                                           }, 2000);

                                                           // 로딩 락은 짧게 해제하여 유저가 계속 스크롤 할 수 있게 허용
                                                            setTimeout(function() {
                                                               window.enforceChunkLimit(false); 
                                                               window.updateMask();
                                                               window.isScrolling = false; 
                                                               window.checkPreload(); // 추가됨
                                                           }, 150);
                                                       } catch(e) { console.error(e); window.isScrolling = false; }
                                                   };

                                                   // [추가됨] 깜빡임 없는 슬라이더 점프를 위한 함수
                                                   window.replaceHtmlBase64 = function(base64Str, chunkIndex, targetLine) {
                                                       try {
                                                           var htmlStr = decodeURIComponent(escape(window.atob(base64Str)));
                                                           var parser = new DOMParser();
                                                           var doc = parser.parseFromString(htmlStr, 'text/html');
                                                           var container = document.body;

                                                           // 화면을 비우지 않고 기존 청크만 즉시 삭제 및 교체 (깜빡임 최소화)
                                                           var chunks = document.querySelectorAll('.content-chunk');
                                                           chunks.forEach(function(c) { c.parentNode.removeChild(c); });

                                                           var chunkWrapper = document.createElement('div');
                                                           chunkWrapper.className = 'content-chunk';
                                                           chunkWrapper.dataset.index = chunkIndex;

                                                           Array.from(doc.body.childNodes).forEach(function(node) {
                                                               if (node.id === 'end-marker' || node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
                                                               chunkWrapper.appendChild(node);
                                                           });

                                                           container.insertBefore(chunkWrapper, container.firstChild);

                                                           // 내용 렌더링 후 목표 라인으로 즉각 스크롤 이동
                                                           setTimeout(function() {
                                                               var el = document.getElementById('line-' + targetLine);
                                                               if(el) {
                                                                   el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                                                               }
                                                               window.updateMask();
                                                               window.isScrolling = false;
                                                               window.checkPreload(); 
                                                               
                                                               // [추가됨] 점프가 끝난 즉시 안드로이드에 현재 라인 번호를 쏴줍니다.
                                                               window.detectAndReportLine();
                                                           }, 50);
                                                       } catch(e) { console.error(e); window.isScrolling = false; }
                                                   };

                                                   window.enforceChunkLimit = function(isAppend) {
                                                       var chunks = document.querySelectorAll('.content-chunk');
                                                       if (chunks.length > window.MAX_CHUNKS) {
                                                           if (isAppend) {
                                                               // 뒤로 스크롤 중: 맨 앞(위/오른쪽)의 가장 오래된 청크 삭제
                                                               var oldestChunk = chunks[0];
                                                               var oldScrollWidth = document.documentElement.scrollWidth;
                                                               var oldScrollHeight = document.documentElement.scrollHeight;

                                                               oldestChunk.parentNode.removeChild(oldestChunk);

                                                               var newScrollWidth = document.documentElement.scrollWidth;
                                                               var newScrollHeight = document.documentElement.scrollHeight;

                                                               // [핵심] 줄어든 크기만큼 즉시 스크롤을 당겨옴
                                                               if (isVertical) {
                                                                   var diff = oldScrollWidth - newScrollWidth;
                                                                   window.safeScrollBy(diff, 0);
                                                               } else {
                                                                   var diff = oldScrollHeight - newScrollHeight;
                                                                   window.safeScrollBy(0, -diff);
                                                               }
                                                           } else {
                                                               // 앞으로 스크롤 중: 맨 뒤(아래/왼쪽)의 청크 삭제 (보정 불필요)
                                                               var newestChunk = chunks[chunks.length - 1];
                                                               newestChunk.parentNode.removeChild(newestChunk);
                                                           }
                                                       }
                                                   };

                                                  window.detectAndReportLine = function() {
                                                      var width = window.innerWidth;
                                                      var height = window.innerHeight;
                                                      var points = [];
                                                      if (isVertical) {
                                                          var offsetsX = [5, 15, 30, 50, 80];
                                                          var offsetsY = [0.1, 0.25, 0.5, 0.75, 0.9];
                                                          for (var i = 0; i < offsetsX.length; i++) {
                                                              for (var j = 0; j < offsetsY.length; j++) {
                                                                  if (width - offsetsX[i] > 0) {
                                                                      points.push({x: width - offsetsX[i], y: height * offsetsY[j]});
                                                                  }
                                                              }
                                                          }
                                                      } else {
                                                          var cx = width / 2;
                                                          var ty = 20;
                                                          points = [{x: cx, y: ty}, {x: cx - 50, y: ty}, {x: cx + 50, y: ty}, {x: cx, y: ty + 40}];
                                                      }
                                                      var foundLineStr = null;
                                                      for (var i = 0; i < points.length; i++) {
                                                          var el = document.elementFromPoint(points[i].x, points[i].y);
                                                          while (el && el.tagName !== 'BODY' && el.tagName !== 'HTML') {
                                                              if (el.id && el.id.startsWith('line-')) {
                                                                  foundLineStr = el.id.replace('line-', '');
                                                                  break;
                                                              }
                                                              el = el.parentElement;
                                                          }
                                                          if (foundLineStr) break;
                                                      }
                                                      if (foundLineStr) {
                                                          Android.onLineChangedStr(foundLineStr);
                                                      }
                                                  };

                                                  window.getVisualLines = function() {
                                                      var w = window.innerWidth;
                                                      var h = window.innerHeight;
                                                      var textLines = [];
                                                      var seenRuby = new Set();
                                                      var padding = isVertical ? w : h;
                                                      
                                                      var walker = document.createTreeWalker(
                                                          document.body,
                                                          NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
                                                          {
                                                              acceptNode: function(node) {
                                                                  if (node.nodeType === 1) {
                                                                       // [수정] 스크롤 엔진이 이미지 영역의 경계를 알 수 있도록 ACCEPT로 변경
                                                                       if (node.classList && node.classList.contains('image-page-wrapper')) return NodeFilter.FILTER_ACCEPT;
                                                                       if (node.tagName === 'IMG' || node.tagName === 'SVG' || node.tagName === 'FIGURE') return NodeFilter.FILTER_ACCEPT;
                                                                       var tag = node.tagName;
                                                                       if (tag === 'P' || tag === 'DIV' || tag === 'TABLE' || tag === 'SECTION') {
                                                                           var r = node.getBoundingClientRect();
                                                                           if (!isVertical) {
                                                                               if (r.bottom < -padding || r.top > h + padding) return NodeFilter.FILTER_REJECT;
                                                                           } else {
                                                                               if (r.left > w + padding || r.right < -padding) return NodeFilter.FILTER_REJECT;
                                                                           }
                                                                       }
                                                                       return NodeFilter.FILTER_SKIP;
                                                                  }
                                                                  if (node.nodeType === 3 && node.nodeValue.trim().length > 0) return NodeFilter.FILTER_ACCEPT;
                                                                  return NodeFilter.FILTER_SKIP;
                                                              }
                                                          }
                                                      );
                                                      
                                                      var node;
                                                      while ((node = walker.nextNode())) {
                                                          // [추가] 이미지 래퍼 자체를 거대한 텍스트 라인처럼 배열에 추가
                                                          if (node.nodeType === 1 && node.classList && node.classList.contains('image-page-wrapper')) {
                                                              var r = node.getBoundingClientRect();
                                                              textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: true });
                                                              continue;
                                                          }

                                                          var el = node.parentElement;
                                                          if (!el) continue;
                                                          // 이미 래퍼를 통해 영역을 잡았으므로 내부 이미지는 무시
                                                          if (node.nodeType === 1 && (node.tagName === 'IMG' || node.tagName === 'SVG' || node.tagName === 'FIGURE') && el.classList.contains('image-page-wrapper')) continue;
                                                          
                                                          var rubyParent = el.closest('ruby');
                                                          if (rubyParent) {
                                                              if (seenRuby.has(rubyParent)) continue;
                                                              seenRuby.add(rubyParent);
                                                              var rubyWalker = document.createTreeWalker(rubyParent, NodeFilter.SHOW_TEXT, null);
                                                              var rNode;
                                                              var rRects = [];
                                                              while ((rNode = rubyWalker.nextNode())) {
                                                                  if (rNode.nodeValue.trim().length === 0) continue;
                                                                  var range = document.createRange();
                                                                  range.selectNodeContents(rNode);
                                                                  var rects = range.getClientRects();
                                                                  for (var i = 0; i < rects.length; i++) {
                                                                      if (rects[i].width > 0 && rects[i].height > 0) rRects.push({
                                                                          top: rects[i].top, bottom: rects[i].bottom, left: rects[i].left, right: rects[i].right
                                                                      });
                                                                  }
                                                              }
                                                              if (rRects.length > 0) {
                                                                  var parts = [];
                                                                  for (var i = 0; i < rRects.length; i++) {
                                                                      var current = rRects[i];
                                                                      var added = false;
                                                                      for (var j = 0; j < parts.length; j++) {
                                                                          var p = parts[j];
                                                                          var hOverlap = Math.min(p.right, current.right) - Math.max(p.left, current.left);
                                                                          var vOverlap = Math.min(p.bottom, current.bottom) - Math.max(p.top, current.top);
                                                                          var isSamePart = false;
                                                                          if (!isVertical) {
                                                                              if (hOverlap > -10 && vOverlap > -10) isSamePart = true;
                                                                          } else {
                                                                              if (vOverlap > -10 && hOverlap > -10) isSamePart = true;
                                                                          }
                                                                          if (isSamePart) {
                                                                              p.top = Math.min(p.top, current.top);
                                                                              p.bottom = Math.max(p.bottom, current.bottom);
                                                                              p.left = Math.min(p.left, current.left);
                                                                              p.right = Math.max(p.right, current.right);
                                                                              added = true;
                                                                              break;
                                                                          }
                                                                      }
                                                                      if (!added) {
                                                                          parts.push({ top: current.top, bottom: current.bottom, left: current.left, right: current.right });
                                                                      }
                                                                  }
                                                                  for (var i = 0; i < parts.length; i++) {
                                                                      textLines.push(parts[i]);
                                                                  }
                                                              }
                                                          } else {
                                                               var rects; if (node.nodeType === 1) { rects = [node.getBoundingClientRect()]; } else { var range = document.createRange(); range.selectNodeContents(node); rects = range.getClientRects(); }
                                                              for (var i = 0; i < rects.length; i++) {
                                                                  var r = rects[i];
                                                                  if (r.width > 0 && r.height > 0) textLines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right });
                                                              }
                                                          }
                                                      }
                                                      
                                                      if (!isVertical) {
                                                          textLines.sort(function(a, b) { var diff = a.top - b.top; return diff !== 0 ? diff : a.left - b.left; });
                                                          var lines = [];
                                                          if (textLines.length === 0) return lines;
                                                          var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: textLines[0].isImageWrapper };
                                                          for (var i = 1; i < textLines.length; i++) {
                                                              var r = textLines[i];
                                                              var vOverlap = Math.min(currentLine.bottom, r.bottom) - Math.max(currentLine.top, r.top);
                                                              var minHeight = Math.min(currentLine.bottom - currentLine.top, r.bottom - r.top);
                                                              if (vOverlap > Math.max(2, minHeight * 0.6)) { 
                                                                  currentLine.top = Math.min(currentLine.top, r.top);
                                                                  currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                                                  currentLine.left = Math.min(currentLine.left, r.left);
                                                                  currentLine.right = Math.max(currentLine.right, r.right);
                                                                  currentLine.isImageWrapper = currentLine.isImageWrapper || r.isImageWrapper;
                                                              } else {
                                                                  lines.push(currentLine);
                                                                  currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: r.isImageWrapper };
                                                              }
                                                          }
                                                          lines.push(currentLine);
                                                          return lines;
                                                      } else {
                                                          textLines.sort(function(a, b) { var diff = b.right - a.right; return diff !== 0 ? diff : a.top - b.top; });
                                                          var lines = [];
                                                          if (textLines.length === 0) return lines;
                                                          var currentLine = { top: textLines[0].top, bottom: textLines[0].bottom, left: textLines[0].left, right: textLines[0].right, isImageWrapper: textLines[0].isImageWrapper };
                                                          for (var i = 1; i < textLines.length; i++) {
                                                              var r = textLines[i];
                                                              var hOverlap = Math.min(currentLine.right, r.right) - Math.max(currentLine.left, r.left);
                                                              var minWidth = Math.min(currentLine.right - currentLine.left, r.right - r.left);
                                                              if (hOverlap > Math.max(2, minWidth * 0.6)) { 
                                                                  currentLine.top = Math.min(currentLine.top, r.top);
                                                                  currentLine.bottom = Math.max(currentLine.bottom, r.bottom);
                                                                  currentLine.left = Math.min(currentLine.left, r.left);
                                                                  currentLine.right = Math.max(currentLine.right, r.right);
                                                                  currentLine.isImageWrapper = currentLine.isImageWrapper || r.isImageWrapper;
                                                              } else {
                                                                  lines.push(currentLine);
                                                                  currentLine = { top: r.top, bottom: r.bottom, left: r.left, right: r.right, isImageWrapper: r.isImageWrapper };
                                                              }
                                                          }
                                                          lines.push(currentLine);
                                                          return lines;
                                                      }
                                                  };

                                                   window.calculateMasks = function() {
                                                       var masks = { top: 0, bottom: 0, left: 0, right: 0 };
                                                       if (pagingMode !== 1) return masks;
                                                       var lines = window.getVisualLines();
                                                       if (lines.length === 0) return masks;
                                                       var w = window.innerWidth;
                                                       var h = window.innerHeight;
                                                       
                                                       if (!isVertical) {
                                                           var visible = lines.filter(function(l) { return l.bottom > 0.05 && l.top < h - 0.05; });
                                                           if (visible.length === 0) return masks;
                                                           
                                                           // [수정] 이미지는 마스크 드로잉에서 완벽히 제외
                                                           var textVisible = visible.filter(function(l) { return !l.isImageWrapper; });
                                                           
                                                           var cutTop = textVisible.filter(function(l) { return l.top < -0.05; });
                                                           if (cutTop.length > 0) {
                                                               var maxB = 0;
                                                               for (var i = 0; i < cutTop.length; i++) { if (cutTop[i].bottom > maxB) maxB = cutTop[i].bottom; }
                                                               masks.top = Math.ceil(maxB + 1);
                                                           }
                                                           
                                                           var cutBottom = textVisible.filter(function(l) { return l.bottom > h + 0.05; });
                                                           if (cutBottom.length > 0) {
                                                               var minT = h;
                                                               for (var i = 0; i < cutBottom.length; i++) { if (cutBottom[i].top < minT) minT = cutBottom[i].top; }
                                                               masks.bottom = Math.ceil(h - minT + 1);
                                                           }
                                                       } else {
                                                           var visible = lines.filter(function(l) { return l.right > 0.05 && l.left < w - 0.05; });
                                                           if (visible.length === 0) return masks;
                                                           
                                                           var textVisible = visible.filter(function(l) { return !l.isImageWrapper; });
                                                           
                                                           var cutRight = textVisible.filter(function(l) { return l.right > w + 0.05; });
                                                           if (cutRight.length > 0) {
                                                               var minL = w;
                                                               for (var i = 0; i < cutRight.length; i++) { if (cutRight[i].left < minL) minL = cutRight[i].left; }
                                                               masks.right = Math.ceil(w - minL + 1);
                                                           }
                                                           
                                                           var cutLeft = textVisible.filter(function(l) { return l.left < -0.05; });
                                                           if (cutLeft.length > 0) {
                                                               var maxR = 0;
                                                               for (var i = 0; i < cutLeft.length; i++) { if (cutLeft[i].right > maxR) maxR = cutLeft[i].right; }
                                                               masks.left = Math.ceil(maxR + 1);
                                                           }
                                                       }
                                                       // Navigation direction filter: hide masks on the side we're coming from
                                                       if (window._scrollDir === 1) { masks.top = 0; masks.right = 0; }
                                                       if (window._scrollDir === -1) { masks.bottom = 0; masks.left = 0; }
                                                       return masks;
                                                   };

                                                  window.updateMask = function() {
                                                      if (pagingMode !== 1) {
                                                          Android.updateBottomMask(0); Android.updateTopMask(0); Android.updateLeftMask(0); Android.updateRightMask(0);
                                                          return;
                                                      }
                                                      var masks = window.calculateMasks();
                                                      Android.updateTopMask(masks.top > 0 ? masks.top : 0);
                                                      Android.updateBottomMask(masks.bottom > 0 ? masks.bottom : 0);
                                                      Android.updateLeftMask(masks.left > 0 ? masks.left : 0);
                                                      Android.updateRightMask(masks.right > 0 ? masks.right : 0);
                                                  };

                                                  window.jumpToBottom = function() {
                                                      var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                                                      var gap = FS * 0.8;
                                                      if (isVertical) {
                                                          window.scrollTo(-1000000, 0);
                                                          if (pagingMode === 1) {
                                                              var w = document.documentElement.clientWidth;
                                                              var lines = window.getVisualLines();
                                                              if (lines.length > 0) {
                                                                  var farRightLine = lines[0];
                                                                  if (farRightLine.right > w) {
                                                                      var scrollDelta = farRightLine.right - (w - gap);
                                                                      window.scrollBy({ left: scrollDelta, behavior: 'instant' });
                                                                  }
                                                              }
                                                          }
                                                      } else {
                                                          window.scrollTo(0, 1000000);
                                                          if (pagingMode === 1) {
                                                              var lines = window.getVisualLines();
                                                              if (lines.length > 0) {
                                                                  var topCutLine = lines[0];
                                                                  if (topCutLine.top < 0) {
                                                                      var scrollDelta = topCutLine.top - gap;
                                                                      window.scrollBy({ top: scrollDelta, behavior: 'instant' });
                                                                  }
                                                              }
                                                          }
                                                      }
                                                  };

                                                  window.pageDown = function() {
                                                      window._scrollDir = 1;
                                                      var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                                                      var h = window.innerHeight;
                                                      var isAtBottom = false;
                                                      if (!isVertical) {
                                                          if (h + window.pageYOffset >= document.documentElement.scrollHeight - 20) isAtBottom = true;
                                                      } else {
                                                          var maxScrollX = document.documentElement.scrollWidth - w;
                                                          if (window.pageXOffset <= -(maxScrollX - 20)) isAtBottom = true;
                                                      }
                                                      if (isAtBottom) { Android.autoLoadNext(); return; }
                                                      if (pagingMode === 1) {
                                                          var lines = window.getVisualLines();
                                                          var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                                                          var gap = (lines.some(function(l) { return (l.bottom - l.top > h * 0.8) || (l.right - l.left > w * 0.8); })) ? 0 : FS * 0.8;
                                                          if (!isVertical) {
                                                              var visible = lines.filter(function(l) { return l.bottom > -2 && l.top < h + 2; });
                                                              var scrollDelta = h;
                                                              if (visible.length > 0) {
                                                                  var last = visible[visible.length - 1];
                                                                  // [수정] 대상이 이미지인 경우 gap을 0으로 만들어 오차 없이 정렬
                                                                  if (last.bottom > h + 2 && last.top < h) scrollDelta = last.top - (last.isImageWrapper ? 0 : gap);
                                                                  else {
                                                                      var idx = lines.indexOf(last);
                                                                      if (idx >= 0 && idx < lines.length - 1) {
                                                                          var nextLine = lines[idx + 1];
                                                                          scrollDelta = nextLine.top - (nextLine.isImageWrapper ? 0 : gap);
                                                                      }
                                                                  }
                                                              }
                                                              window.scrollBy({ top: Math.min(scrollDelta, h), behavior: 'instant' });
                                                          } else {
                                                              var visible = lines.filter(function(l) { return l.left < w + 2 && l.right > -2; });
                                                              var scrollDelta = -w;
                                                              if (visible.length > 0) {
                                                                  var last = visible[visible.length - 1];
                                                                  if (last.left < 0) scrollDelta = last.right + (last.isImageWrapper ? 0 : gap) - w;
                                                                  else {
                                                                      var idx = lines.indexOf(last);
                                                                      if (idx >= 0 && idx < lines.length - 1) {
                                                                          var nextLine = lines[idx + 1];
                                                                          scrollDelta = nextLine.right + (nextLine.isImageWrapper ? 0 : gap) - w;
                                                                      }
                                                                  }
                                                              }
                                                              window.scrollBy({ left: Math.max(scrollDelta, -w), behavior: 'instant' });
                                                          }
                                                      } else {
                                                          var moveSize = (isVertical ? w : h) - 40;
                                                          if (isVertical) window.scrollBy({ left: -moveSize, behavior: 'instant' });
                                                          else window.scrollBy({ top: moveSize, behavior: 'instant' });
                                                      }
                                                      window.detectAndReportLine(); window.updateMask();
                                                  };

                                                  window.pageUp = function() {
                                                      window._scrollDir = -1;
                                                      var w = isVertical ? document.documentElement.clientWidth : window.innerWidth;
                                                      var h = window.innerHeight;
                                                      var isAtTop = false;
                                                      if (!isVertical) { if (window.pageYOffset <= 20) isAtTop = true; }
                                                      else { if (window.pageXOffset >= -20) isAtTop = true; }
                                                      if (isAtTop) { Android.autoLoadPrev(); return; }
                                                      if (pagingMode === 1) {
                                                          var lines = window.getVisualLines();
                                                          var FS = parseFloat(window.getComputedStyle(document.body).fontSize) || 16;
                                                          var gap = (lines.some(function(l) { return (l.bottom - l.top > h * 0.8) || (l.right - l.left > w * 0.8); })) ? 0 : FS * 0.8;
                                                          if (!isVertical) {
                                                              var firstIdx = lines.findIndex(function(l) { return l.top >= -2; });
                                                              var prevIdx = firstIdx > 0 ? firstIdx - 1 : -1;
                                                              if (prevIdx >= 0) {
                                                                  var targetBottom = lines[prevIdx].bottom;
                                                                  var topIdx = prevIdx;
                                                                  for (var i = prevIdx; i >= 0; i--) { if (targetBottom - lines[i].top <= h - gap) topIdx = i; else break; }
                                                                  var targetLine = lines[topIdx];
                                                                  // [수정] 대상이 이미지인 경우 gap을 0으로 처리
                                                                  window.scrollBy({ top: Math.max(targetLine.top - (targetLine.isImageWrapper ? 0 : gap), -h), behavior: 'instant' });
                                                              } else window.scrollBy({ top: -h, behavior: 'instant' });
                                                          } else {
                                                              var firstVisible = lines.find(function(l) { return l.right < w + 2 && l.left > -2; });
                                                              var prevIdx = firstVisible ? lines.indexOf(firstVisible) - 1 : -1;
                                                              if (prevIdx >= 0) {
                                                                  var targetLine = lines[prevIdx];
                                                                  window.scrollBy({ left: Math.min(targetLine.left - (targetLine.isImageWrapper ? 0 : gap), w), behavior: 'instant' });
                                                              } else window.scrollBy({ left: w, behavior: 'instant' });
                                                          }
                                                      } else {
                                                          var moveSize = (isVertical ? w : h) - 40;
                                                          if (isVertical) window.scrollBy({ left: moveSize, behavior: 'instant' });
                                                      else window.scrollBy({ top: -moveSize, behavior: 'instant' });
                                                      }
                                                      window.detectAndReportLine(); window.updateMask();
                                                  };

                                                    // [수정됨] 기존 window.onscroll 전체를 아래 코드로 교체
                                                   var scrollTimer = null;
                                                   window.onscroll = function() {
                                                        if (window.isSystemScrolling) return; 

                                                       if (scrollTimer) clearTimeout(scrollTimer);
                                                       scrollTimer = setTimeout(function() {
                                                            if (window.isSystemScrolling) return; 

                                                           window.detectAndReportLine();
                                                           window.updateMask();
                                                           
                                                           if (window.isScrolling) return; 
                                                           
                                                           // 복잡한 여백 계산은 checkPreload가 담당
                                                           window.checkPreload();
                                                       }, 150); 
                                                   };

                                                  setTimeout(window.updateMask, 100);
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
                                           overflow-anchor: auto !important;
                                           
                                           /* [중요] html에도 writing-mode 적용하여 브라우저 좌표축 동기화 */
                                           writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                           -webkit-writing-mode: ${if (uiState.isVertical) "vertical-rl" else "horizontal-tb"} !important;
                                       }
                                   
                                        body {
                                            min-height: 100vh !important;
                                            height: ${if (uiState.isVertical) "100vh" else "auto"} !important; /* 가로쓰기 시 auto로 두어야 무한 세로 스크롤 가능 */
                                            width: ${if (uiState.isVertical) "auto" else "100%"} !important; /* 세로쓰기 시 auto로 두어야 무한 가로 스크롤 가능 */
                                            margin: 0 !important;
                                            padding: 0 !important; /* body 패딩 제거 (좌표 계산 오차 원인) */
                                            padding-left: 0 !important;
                                            padding-right: 0 !important;
                                            
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
                                            overflow-anchor: auto !important;
                                        }
                                        
                                        /* 2. 문단 설정: 여백이 터치 감지를 방해하지 않도록 조정 */
                                        p, div, h1, h2, h3, h4, h5, h6 {
                                            display: block !important; 
                                            height: auto !important;
                                            width: auto !important;
                                            margin-top: 0 !important;
                                            /* 줄 간격 */
                                            margin-bottom: ${if (uiState.isVertical) "0" else "0.5em"} !important;
                                            margin-left: ${if (uiState.isVertical) "0.4em" else "0"} !important;
                                            
                                            /* 전체 여백 적용 */
                                            padding-left: ${if (uiState.isVertical) "0.3em" else "${uiState.sideMargin / 20.0}em"} !important;
                                            padding-right: ${if (uiState.isVertical) "0.3em" else "${uiState.sideMargin / 20.0}em"} !important;
                                            padding-top: ${if (uiState.isVertical) "${uiState.sideMargin / 20.0}em" else "0"} !important;
                                            padding-bottom: ${if (uiState.isVertical) "${uiState.sideMargin / 20.0}em" else "0"} !important;
                                            
                                             box-sizing: border-box !important;
                                             text-align: left !important;
                                         }
                                         .content-chunk {
                                             overflow-anchor: auto !important;
                                         }
                                        /* Remove padding for images to make them edge-to-edge */
                                        div:has(img), p:has(img), div:has(svg), p:has(svg), div:has(figure), p:has(figure), .image-page-wrapper {
                                            padding: 0 !important;
                                            margin: 0 !important;
                                        }
                                        .image-page-wrapper {
                                            width: 100vw !important;
                                            height: 100vh !important;
                                            min-width: 100vw !important;  /* 추가: 축소 방지 */
                                            min-height: 100vh !important; /* 추가: 축소 방지 */
                                            flex-shrink: 0 !important;    /* 추가: flex 레이아웃에서 찌그러짐 방지 */
                                            display: flex !important;
                                            justify-content: center !important;
                                            align-items: center !important;
                                            overflow: hidden !important;
                                            margin: 0 !important;
                                            padding: 0 !important;
                                            box-sizing: border-box !important;
                                            break-inside: avoid !important; /* 대체 속성 */
                                        }
                                        img, svg, figure {
                                            max-width: 100% !important;
                                            max-height: 100% !important;
                                            width: auto !important;
                                            height: auto !important;
                                            display: block !important;
                                            margin: 0 auto !important;
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
                                        .blank-line {
                                            margin: 0 !important;
                                            padding: 0 !important;
                                            width: ${if (uiState.isVertical) "0.2em" else "auto"} !important;
                                            min-width: ${if (uiState.isVertical) "0.2em" else "auto"} !important;
                                            height: ${if (uiState.isVertical) "auto" else "0.2em"} !important;
                                            min-height: ${if (uiState.isVertical) "auto" else "0.2em"} !important;
                                            display: block !important;
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
      
                                   if (uiState.contentUpdateType == 0) { // 전체 리로드(점프, 최초 진입)일 때만 실행
                                       if (contentWithStyle.hashCode() != previousHash) {
                                           wv.tag = contentWithStyle.hashCode()
                                           isPageLoading = true
                                           // If navigation was triggered, ensure isNavigating lock is on
                                           isNavigating = true 
                                           
                                           if (uiState.url != null) {
                                               wv.loadUrl(uiState.url!!)
                                           } else {
                                               // Use provided baseUrl or fallback to parent directory of filePath
                                               val baseUrl = uiState.baseUrl ?: (if (filePath.startsWith("/")) "file:///${java.io.File(filePath).parent?.replace(java.io.File.separator, "/")}/" else null)
                                               wv.loadDataWithBaseURL(baseUrl, contentWithStyle, "text/html", "UTF-8", null)
                                           }
                                           
                                           // Restore Scroll after load (if not navigating which handles it in onPageFinished)
                                           wv.post {
                                               if (!isNavigating && currentLine > 1 && uiState.totalLines > 0) {
                                                   val linePostfix = if (type == FileEntry.FileType.EPUB) "${uiState.currentChapterIndex}-" else ""
                                                   val js = "var el = document.getElementById('line-$linePostfix$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                                                   wv.evaluateJavascript(js, null)
                                               }
                                           }
                                       }
                                   }
                             }
                          )
                          if (uiState.pagingMode == 1 && bottomMaskHeight > 0f && !uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.BottomStart)
                                                                       .fillMaxWidth()
                                                                       .height(bottomMaskHeight.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                          if (uiState.pagingMode == 1 && topMaskHeight > 0f && !uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopStart) // Top Mask
                                                                       .fillMaxWidth()
                                                                       .height(topMaskHeight.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                          if (uiState.pagingMode == 1 && leftMaskWidth > 0f && uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopStart)
                                                                       .fillMaxHeight()
                                                                       .width(leftMaskWidth.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
                                                          if (uiState.pagingMode == 1 && rightMaskWidth > 0f && uiState.isVertical) {
                                                               Box(
                                                                   modifier = Modifier
                                                                       .align(Alignment.TopEnd)
                                                                       .fillMaxHeight()
                                                                       .width(rightMaskWidth.dp)
                                                                       .background(targetDocColor)
                                                               )
                                                          }
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

