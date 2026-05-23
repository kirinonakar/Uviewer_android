package com.uviewer_android.ui.viewer

import android.net.Uri
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.MainActivity
import com.uviewer_android.R
import com.uviewer_android.ui.AppViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    initialPage: Int? = null,
    viewModel: PdfViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    libraryViewModel: com.uviewer_android.ui.library.LibraryViewModel? = null,
    activity: MainActivity? = null
) {
    BackHandler { onBack() }

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchCurrent by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(filePath) { mutableIntStateOf(initialPage ?: 0) }
    var hasLoaded by rememberSaveable(filePath) { mutableStateOf(false) }
    var webViewReady by remember { mutableStateOf(false) }

    val currentActivity = activity ?: (context as? MainActivity) ?: remember(context) {
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is MainActivity) break
            c = c.baseContext
        }
        c as? MainActivity
    }

    val fileName = remember(filePath) { File(filePath).name }
    val currentPageForUi by remember { derivedStateOf { currentPage.coerceAtLeast(0) } }

    DisposableEffect(isFullScreen, pageCount, currentPageForUi) {
        libraryViewModel?.setViewerBottomBarBackgroundColor(Color.Black)
        if (!isFullScreen && pageCount > 0) {
            libraryViewModel?.setViewerBottomBarContent {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Page: ${currentPageForUi + 1} / $pageCount (${((currentPageForUi + 1) * 100 / pageCount.coerceAtLeast(1))}% )",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }

                    val sliderInteractionSource = remember { MutableInteractionSource() }
                    Slider(
                        value = currentPageForUi.toFloat(),
                        onValueChange = { value ->
                            val target = value.toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            currentPage = target
                            webViewRef?.evaluateJavascript("window.UviewerPdf && window.UviewerPdf.scrollToPage(${target + 1});", null)
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                        thumb = {
                            SliderDefaults.Thumb(
                                interactionSource = remember { MutableInteractionSource() },
                                thumbSize = androidx.compose.ui.unit.DpSize(16.dp, 16.dp),
                                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                            )
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(4.dp),
                                colors = SliderDefaults.colors(
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        interactionSource = sliderInteractionSource
                    )
                }
            }
        } else {
            libraryViewModel?.setViewerBottomBarContent(null)
        }
        onDispose {
            libraryViewModel?.setViewerBottomBarContent(null)
            libraryViewModel?.setViewerBottomBarBackgroundColor(null)
        }
    }

    LaunchedEffect(filePath) {
        val pageToLoad = if (hasLoaded) currentPage else initialPage
        viewModel.loadPdf(filePath, isWebDav, serverId, pageToLoad)
        hasLoaded = true
    }

    LaunchedEffect(currentPage, pageCount, uiState.localFilePath) {
        if (!uiState.isLoading && uiState.localFilePath != null && pageCount > 0) {
            delay(300)
            viewModel.updateProgress(filePath, currentPage, pageCount, isWebDav, serverId)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(currentActivity, lifecycleOwner) {
        val window = currentActivity?.window
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleOwner.lifecycle.addObserver(observer)
        currentActivity?.volumeKeyPagingActive = true

        onDispose {
            if (pageCount > 0) {
                viewModel.saveProgressBlocking(filePath, currentPage, pageCount)
            }
            try {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {}
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentActivity?.volumeKeyPagingActive = false
            val wv = webViewRef
            webViewRef = null
            try {
                wv?.stopLoading()
                wv?.removeAllViews()
                wv?.destroy()
            } catch (_: Exception) {}
        }
    }

    val isLightAppTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val useLightStatusBar = if (!isFullScreen) isLightAppTheme else false

    LaunchedEffect(useLightStatusBar, isFullScreen) {
        try {
            val window = currentActivity?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                window.setTransparentSystemBarColors()
                insetsController.isAppearanceLightStatusBars = useLightStatusBar
                insetsController.isAppearanceLightNavigationBars = false // Navigation bar is on a black background
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

    fun parseSearchResult(value: String?) {
        if (value.isNullOrBlank() || value == "null") {
            searchCurrent = 0
            searchTotal = 0
            return
        }
        try {
            val json = JSONObject(value)
            searchCurrent = json.optInt("current", 0)
            searchTotal = json.optInt("total", 0)
            val page = json.optInt("page", currentPage)
            if (page >= 0) currentPage = page
        } catch (_: Exception) {
            searchCurrent = 0
            searchTotal = 0
        }
    }

    fun runPdfSearch(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            isSearching = false
            searchCurrent = 0
            searchTotal = 0
            webViewRef?.evaluateJavascript("window.UviewerPdf && window.UviewerPdf.clearSearch();", null)
            return
        }
        isSearching = true
        val quoted = JSONObject.quote(query)
        webViewRef?.evaluateJavascript("window.UviewerPdf.find($quoted);", null)
    }

    LaunchedEffect(currentActivity, webViewReady) {
        if (currentActivity != null && webViewReady) {
            currentActivity.keyEvents.collect { keyCode: Int ->
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                    webViewRef?.evaluateJavascript("window.UviewerPdf && window.UviewerPdf.previousPage();", null)
                } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                    webViewRef?.evaluateJavascript("window.UviewerPdf && window.UviewerPdf.nextPage();", null)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0),
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
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        ),
                        title = { 
                            val isPath = filePath.contains("/") || filePath.contains("\\")
                            val isLong = filePath.length > 20
                            val displayText = if (isPath && isLong) {
                                java.io.File(filePath).name.ifEmpty { filePath }
                            } else {
                                filePath
                            }
                            Text(
                                text = displayText,
                                style = if (isPath && isLong) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                                maxLines = if (isPath && isLong) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(24.dp))
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                viewModel.toggleBookmark(filePath, currentPage, pageCount, isWebDav, serverId)
                                android.widget.Toast.makeText(context, "Bookmark Saved: Page ${currentPage + 1}", android.widget.Toast.LENGTH_SHORT).show()
                            }, enabled = pageCount > 0, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Bookmark, contentDescription = "Bookmark", modifier = Modifier.size(24.dp))
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
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Transparent)
        ) {
            if (showSearch) {
                ViewerSearchBar(
                    query = searchQuery,
                    matchText = "$searchCurrent / $searchTotal",
                    isSearching = isSearching,
                    isSupported = true,
                    onQueryChange = { runPdfSearch(it) },
                    onClear = { runPdfSearch("") },
                    onPrevious = {
                        webViewRef?.evaluateJavascript("window.UviewerPdf.previousMatch();") { parseSearchResult(it) }
                    },
                    onNext = {
                        webViewRef?.evaluateJavascript("window.UviewerPdf.nextMatch();") { parseSearchResult(it) }
                    },
                    onClose = {
                        showSearch = false
                        runPdfSearch("")
                    }
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (uiState.error != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(text = "Error: ${uiState.error}", color = Color.Red)
                        Button(onClick = onBack) {
                            Text(stringResource(R.string.back))
                        }
                    }
                } else if (uiState.localFilePath != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.allowViewerFileUrlAccess()
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = false
                                setBackgroundColor(android.graphics.Color.rgb(32, 33, 36))
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onPdfLoaded(totalPages: Int) {
                                            post {
                                                pageCount = totalPages
                                            }
                                        }

                                        @JavascriptInterface
                                        fun onPageChanged(pageIndex: Int, totalPages: Int) {
                                            post {
                                                if (totalPages > 0) pageCount = totalPages
                                                currentPage = pageIndex.coerceAtLeast(0)
                                            }
                                        }

                                        @JavascriptInterface
                                        fun onSearchResult(current: Int, total: Int, pageIndex: Int) {
                                            post {
                                                isSearching = false
                                                searchCurrent = current
                                                searchTotal = total
                                                if (pageIndex >= 0) currentPage = pageIndex
                                            }
                                        }

                                        @JavascriptInterface
                                        fun toggleControls() {
                                            post { onToggleFullScreen() }
                                        }
                                    },
                                    "Android"
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        webViewReady = true
                                        val pdfUrl = Uri.fromFile(File(uiState.localFilePath!!)).toString()
                                        val js = "window.UviewerPdf.loadPdf(${JSONObject.quote(pdfUrl)}, ${uiState.initialPage});"
                                        view?.evaluateJavascript(js, null)
                                    }
                                }
                                loadUrl("file:///android_asset/pdfjs/uviewer_pdf.html")
                            }
                        },
                        update = { webView ->
                            if (webViewRef !== webView) webViewRef = webView
                        }
                    )
                }
            }
        }
    }
}
