package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    libraryViewModel: com.uviewer_android.ui.library.LibraryViewModel? = null,
    activity: com.uviewer_android.MainActivity? = null
) {
    BackHandler { onBack() }
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // showControls replaced by !isFullScreen
    val currentLineState = rememberSaveable { mutableIntStateOf(initialLine ?: 1) }
    var currentLine by currentLineState
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showFontSettingsDialog by remember { mutableStateOf(false) }
    val webViewRefState = remember { mutableStateOf<WebView?>(null) }
    var webViewRef by webViewRefState
    var showEncodingDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }

    val pageLoadingState = remember { mutableStateOf(false) }
    var isPageLoading by pageLoadingState
    val navigatingState = remember { mutableStateOf(false) }
    var isNavigating by navigatingState
    
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isSliderDragged by sliderInteractionSource.collectIsDraggedAsState()
    val isSliderPressed by sliderInteractionSource.collectIsPressedAsState()
    val isInteractingWithSlider = isSliderDragged || isSliderPressed
    var tempSliderValue by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(filePath, initialLine) {
        viewModel.loadDocument(filePath, type, isWebDav, serverId, initialLine)
    }

    val context = LocalContext.current
    val currentActivity = activity ?: (context as? MainActivity) ?: remember(context) {
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is MainActivity) break
            c = c.baseContext
        }
        c as? MainActivity
    }

    val docBackgroundColor by viewModel.docBackgroundColor.collectAsState()
    val applyDocumentSearchHighlight = {
        val searchState = viewModel.uiState.value.searchState
        if (showSearch && searchState.query.isNotBlank()) {
            val relativeIndex = viewModel.getCurrentMatchRelativeIndex()
            webViewRef?.evaluateJavascript(
                ViewerSearchScripts.highlightDocument(searchState.query, searchState.currentMatch, relativeIndex),
                null
            )
        }
    }

    // [추가] 문서 배경색을 Compose Color 객체로 변환
    val targetDocColor = remember(docBackgroundColor, uiState.customDocBackgroundColor) {
        val colorHex = when (docBackgroundColor) {
            UserPreferencesRepository.DOC_BG_SEPIA -> "#e6dacb"
            UserPreferencesRepository.DOC_BG_DARK -> "#121212"
            UserPreferencesRepository.DOC_BG_COMFORT -> "#E9E2E4"
            UserPreferencesRepository.DOC_BG_CUSTOM -> {
                val custom = uiState.customDocBackgroundColor
                if (custom.startsWith("#")) custom else "#FFFFFF"
            }
            else -> "#FFFFFF"
        }
        try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            Color.White
        }
    }

    androidx.compose.runtime.DisposableEffect(isFullScreen, uiState, currentLine, tempSliderValue, type, targetDocColor) {
        if (!isFullScreen) {
            libraryViewModel?.setViewerBottomBarBackgroundColor(targetDocColor)
            libraryViewModel?.setViewerBottomBarContent {
                DocumentViewerBottomBar(
                    uiState = uiState,
                    type = type,
                    currentLine = currentLine,
                    tempSliderValue = tempSliderValue,
                    sliderInteractionSource = sliderInteractionSource,
                    onSliderValueChange = { tempSliderValue = it },
                    onSliderValueChangeFinished = {
                        val finalVal = tempSliderValue
                        tempSliderValue = -1f
                        val isEpub = type == FileEntry.FileType.EPUB
                        val isEpubFlat = isEpub
                        val totalCh = uiState.epubChapters.size.coerceAtLeast(1)

                        if (isEpub && !isEpubFlat) {
                            val targetCh = finalVal.toInt().coerceIn(0, totalCh - 1)
                            isNavigating = true
                            isPageLoading = true
                            webViewRef?.evaluateJavascript("document.body.innerHTML = ''; window.scrollTo(0,0);", null)
                            viewModel.jumpToChapter(targetCh)
                        } else {
                            val targetLine = finalVal.toInt()
                            currentLine = targetLine
                            val targetChunk = (targetLine - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                            if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(targetLine - uiState.currentLine) > 50) {
                                isNavigating = true
                                isPageLoading = true
                            }
                            viewModel.jumpToLine(targetLine)
                            if (targetChunk == uiState.currentChunkIndex) {
                                val js = "var el = document.getElementById('line-$currentLine'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });"
                                webViewRef?.evaluateJavascript(js) {
                                    webViewRef?.postDelayed({
                                        isPageLoading = false
                                        isNavigating = false
                                    }, 800)
                                }
                            }
                        }
                    }
                )
            }
        } else {
            libraryViewModel?.setViewerBottomBarContent(null)
            libraryViewModel?.setViewerBottomBarBackgroundColor(null)
        }
        onDispose {
            libraryViewModel?.setViewerBottomBarContent(null)
            libraryViewModel?.setViewerBottomBarBackgroundColor(null)
        }
    }

    // Keep screen on while viewing document
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(currentActivity, lifecycleOwner) {
        val window = currentActivity?.window
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // Restore WebView scroll position on foreground
                    val targetLine = currentLine
                    val totalLines = uiState.totalLines
                    val isVertical = uiState.isVertical
                    webViewRef?.let { wv ->
                        val js = """
                            (function() {
                                var targetLine = $targetLine;
                                var totalLines = $totalLines;
                                var isVertical = $isVertical;
                                function doScroll() {
                                    if (targetLine === totalLines && totalLines > 1) {
                                        if (typeof jumpToBottom === 'function') { jumpToBottom(); }
                                        else {
                                            if (isVertical) window.scrollTo(-1000000, 0);
                                            else window.scrollTo(0, 1000000);
                                        }
                                    } else {
                                        var el = document.getElementById('line-' + targetLine);
                                        if (el) {
                                            el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
                                        }
                                    }
                                }
                                doScroll();
                                setTimeout(doScroll, 50);
                                setTimeout(doScroll, 100);
                                setTimeout(doScroll, 200);
                                setTimeout(doScroll, 500);
                            })();
                        """.trimIndent()
                        wv.evaluateJavascript(js, null)
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    // Save progress immediately when backgrounding/pausing
                    viewModel.updateProgress(currentLine)
                }
                else -> {}
            }
        }
        
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleOwner.lifecycle.addObserver(observer)
        
        currentActivity?.volumeKeyPagingActive = true
        onDispose {
            // Save progress one last time on dispose (blocking to ensure DB write completes)
            viewModel.saveProgressBlocking(currentLine)

            // Set ref to null BEFORE destroy to prevent pending JS callbacks
            // from accessing a destroyed WebView (DeadObjectException prevention)
            val wv = webViewRef
            webViewRef = null
            wv?.let {
                it.stopLoading()
                it.clearHistory()
                it.removeAllViews()
                it.destroy()
            }

            try {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {}
            lifecycleOwner.lifecycle.removeObserver(observer)
            
            currentActivity?.volumeKeyPagingActive = false
        }
    }
    val isAppDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    
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
        try {
            val window = currentActivity?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                
                // 상태바 배경색 설정 (Legacy 지원 및 Edge-to-Edge가 아닌 경우 대비)
                // 전체화면일 땐 문서색, 아닐 땐 투명(Scaffold 배경이 보임)
                // 상태바 배경색을 투명하게 설정하여 Scaffold 배경이 보이도록 함
                window.setTransparentSystemBarColors()
                
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
        } catch (_: Exception) {}
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
            val isEpubFlat = type == FileEntry.FileType.EPUB && uiState.isVertical
            val chunkIdx = if (type == FileEntry.FileType.EPUB && !isEpubFlat) uiState.currentChapterIndex else uiState.currentChunkIndex
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

    // [추가] 백그라운드 프리로드 취소 시 JS 락 해제 트리거 관찰
    LaunchedEffect(uiState.jsUnlockTrigger) {
        if (uiState.jsUnlockTrigger > 0L) {
            webViewRef?.evaluateJavascript("window.isScrolling = false;", null)
            isNavigating = false
            isPageLoading = false
        }
    }

    LaunchedEffect(uiState.searchState.currentMatch) {
        val match = uiState.searchState.currentMatch
        if (showSearch && match != null && !uiState.searchState.isSearching) {
            currentLine = match.line
            viewModel.jumpToLine(match.line)
        }
    }

    LaunchedEffect(showSearch, uiState.searchState.query, uiState.searchState.currentIndex, uiState.appendTrigger) {
        applyDocumentSearchHighlight()
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
                                    val js = "var el = document.getElementById('line-$line'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });"
                                    webViewRef?.evaluateJavascript(js) {
                                        webViewRef?.postDelayed({
                                            isNavigating = false
                                            isPageLoading = false
                                        }, 800) // 500 -> 800ms
                                    }
                                }
                            } else {
                                // EPUB 가로모드: 챕터 기반 로딩
                                isNavigating = true
                                isPageLoading = true
                                viewModel.jumpToChapter(index)
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
            containerColor = targetDocColor,
            topBar = {
                if (!isFullScreen) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.dp
                    ) {
                        TopAppBar(
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            title = { 
                                // Filename hidden as requested
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(24.dp))
                                }
                            },
                            actions = {
                                IconButton(onClick = onNavigateToPrev, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.prev_file), modifier = Modifier.size(24.dp))
                                }
                                IconButton(onClick = onNavigateToNext, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.next_file), modifier = Modifier.size(24.dp))
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
                                }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Bookmark, contentDescription = "Bookmark", modifier = Modifier.size(24.dp))
                                }
                                if (type == FileEntry.FileType.EPUB || (type == FileEntry.FileType.TEXT && uiState.epubChapters.isNotEmpty())) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.table_of_contents), modifier = Modifier.size(24.dp))
                                    }
                                } else if (uiState.totalLines > 0) {
                                    IconButton(onClick = { showGoToLineDialog = true }, modifier = Modifier.size(40.dp)) {
                                        Text("G", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                Box {
                                    IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(24.dp))
                                    }
                                    DropdownMenu(
                                        expanded = showMoreMenu,
                                        onDismissRequest = { showMoreMenu = false }
                                    ) {
                                        SearchDropdownMenuItem(
                                            onClick = {
                                                showSearch = true
                                                showMoreMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.toggle_vertical)) },
                                            onClick = {
                                                viewModel.toggleVerticalReading()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Text(
                                                    "V",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (uiState.isVertical) Color(0xFF007AFF) else LocalContentColor.current
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.select_encoding)) },
                                            onClick = {
                                                showEncodingDialog = true
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Translate, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.font_settings)) },
                                            onClick = {
                                                showFontSettingsDialog = true
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.FormatSize, contentDescription = null)
                                            }
                                        )
                                    }
                                }

                            }
                        )
                    }
                }
                
                if (showEncodingDialog) {
                    DocumentEncodingDialog(
                        uiState = uiState,
                        onSelectEncoding = { viewModel.setManualEncoding(it, isWebDav, serverId) },
                        onDismiss = { showEncodingDialog = false }
                    )
                }
            },
            snackbarHost = {} // Add snackbar host if needed
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
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
                        if (showSearch) {
                            val searchState = uiState.searchState
                            ViewerSearchBar(
                                query = searchState.query,
                                matchText = if (searchState.query.isBlank()) {
                                    "0 / 0"
                                } else {
                                    "${(searchState.currentIndex + 1).coerceAtLeast(0)} / ${searchState.matches.size}"
                                },
                                isSearching = searchState.isSearching,
                                isSupported = true,
                                onQueryChange = viewModel::updateSearchQuery,
                                onClear = viewModel::clearSearch,
                                onPrevious = viewModel::previousSearchMatch,
                                onNext = viewModel::nextSearchMatch,
                                onClose = {
                                    showSearch = false
                                    viewModel.clearSearch()
                                    webViewRef?.evaluateJavascript(
                                        ViewerSearchScripts.highlightDocument("", null),
                                        null
                                    )
                                }
                            )
                        }
                        DocumentViewerWebView(
                            modifier = Modifier.weight(1f),
                            filePath = filePath,
                            type = type,
                            uiState = uiState,
                            targetDocColor = targetDocColor,
                            viewModel = viewModel,
                            webViewRefState = webViewRefState,
                            currentLineState = currentLineState,
                            pageLoadingState = pageLoadingState,
                            navigatingState = navigatingState,
                            isInteractingWithSlider = isInteractingWithSlider,
                            onToggleFullScreen = onToggleFullScreen,
                            applyDocumentSearchHighlight = applyDocumentSearchHighlight
                        )
            }
        }
    }
    }
    }

    DocumentLoadingOverlay(uiState)


    if (showGoToLineDialog) {
        DocumentGoToLineDialog(
            currentLine = currentLine,
            totalLines = uiState.totalLines,
            onDismiss = { showGoToLineDialog = false },
            onGoToLine = { line ->
                val targetChunk = (line - 1) / DocumentViewerViewModel.LINES_PER_CHUNK
                if (targetChunk != uiState.currentChunkIndex || kotlin.math.abs(line - currentLine) > 50) {
                    isNavigating = true
                    isPageLoading = true
                }
                viewModel.jumpToLine(line)
                currentLine = line
                if (targetChunk == uiState.currentChunkIndex && webViewRef != null) {
                    val js = "var el = document.getElementById('line-$line'); if(el) el.scrollIntoView({ behavior: 'instant', block: 'start' });"
                    webViewRef?.evaluateJavascript(js) {
                        webViewRef?.postDelayed({
                            isPageLoading = false
                            isNavigating = false
                        }, 500)
                    }
                }
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

