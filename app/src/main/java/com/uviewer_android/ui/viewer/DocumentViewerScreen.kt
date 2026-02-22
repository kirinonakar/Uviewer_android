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
                                             if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
                                                 webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                 return@post
                                             }
                                             
                                             if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.hasMoreContent) {
                                                 viewModel.nextChunk()
                                             } else if (type == FileEntry.FileType.EPUB) {
                                                 viewModel.nextChapter()
                                             } else {
                                                 webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                             }
                                         }
                                     }
                                    @android.webkit.JavascriptInterface
                                     fun autoLoadPrev() {
                                         post {
                                             if (isPageLoading || viewModel.uiState.value.isLoading || isInteractingWithSlider || isNavigating) {
                                                  webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
                                                  return@post
                                             }
                                             
                                             if (type == FileEntry.FileType.TEXT && viewModel.uiState.value.currentChunkIndex > 0) {
                                                 viewModel.prevChunk()
                                             } else if (type == FileEntry.FileType.EPUB) {
                                                 viewModel.prevChapter()
                                             } else {
                                                  webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
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
                                       
val linePrefix = if (type == FileEntry.FileType.EPUB) "${uiState.currentChapterIndex}-" else ""
val jsScrollLogic = ViewerScripts.getScrollLogic(
    isVertical = isVertical,
    pagingMode = pagingMode,
    enableAutoLoading = enableAutoLoading,
    targetLine = targetLine,
    totalLines = totalLines,
    linePrefix = linePrefix
)
                                        
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

val style = ViewerScripts.getStyleSheet(
    isVertical = uiState.isVertical,
    bgColor = bgColor,
    textColor = textColor,
    fontFamily = uiState.fontFamily,
    fontSize = uiState.fontSize,
    sideMargin = uiState.sideMargin
)
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

