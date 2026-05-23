package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.uviewer_android.R
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import androidx.compose.ui.draw.clipToBounds
import com.uviewer_android.ui.AppViewModelProvider
import com.uviewer_android.MainActivity
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.activity.compose.BackHandler
import android.view.WindowManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    initialIndex: Int? = null,
    viewModel: ImageViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
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
    val invertImageControl by viewModel.invertImageControl.collectAsState()
    val dualPageOrder by viewModel.dualPageOrder.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentPageIndex by rememberSaveable(filePath) { mutableIntStateOf(initialIndex ?: 0) }
    var appliedInitialIndex by rememberSaveable(filePath) { mutableStateOf<Int?>(null) }
    var hasLoaded by rememberSaveable(filePath) { mutableStateOf(false) }
    // viewMode is extracted from uiState where needed

    // Sync is done after pagerState is created (see below)

    // Status Bar Logic
    val isLightAppTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    
    // When UI is shown (!isFullScreen), icons follow the app's current theme (Light/Dark).
    // When UI is hidden (isFullScreen), icons follow the viewer background (always black for images).
    val useLightStatusBar = if (!isFullScreen) {
        isLightAppTheme
    } else {
        false // PDF full screen background is black
    }
    
    val currentActivity = activity ?: (context as? MainActivity) ?: remember(context) {
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is MainActivity) break
            c = c.baseContext
        }
        c as? MainActivity
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
            // Save progress on exit (blocking to ensure DB write completes)
            viewModel.saveProgressBlocking(currentPageIndex)

            try {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {}
            lifecycleOwner.lifecycle.removeObserver(observer)
            
            currentActivity?.volumeKeyPagingActive = false
            try {
                if (window != null) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    insetsController.isAppearanceLightStatusBars = isLightAppTheme
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(useLightStatusBar, isFullScreen) {
        try {
            val window = currentActivity?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                
                // Explicitly set bar colors to transparent to show the background
                window.setTransparentSystemBarColors()

                insetsController.isAppearanceLightStatusBars = useLightStatusBar
                insetsController.isAppearanceLightNavigationBars = false // Navigation bar is on a black background
                
                if (isFullScreen) {
                    // Hide navigation, keep status
                    insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                    insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        } catch (_: Exception) {}
    }

    // Load images on start
    LaunchedEffect(filePath) {
        val indexToLoad = if (hasLoaded) currentPageIndex else initialIndex
        viewModel.loadImages(filePath, isWebDav, serverId, indexToLoad)
        hasLoaded = true
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                uiState.loadingProgress?.let { progress ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.width(200.dp)
                    )
                }
            }
        }
        return
    } else if (uiState.error != null) {
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
        return
    }

    if (uiState.images.isNotEmpty()) {
        // Recalculate page count and current page
        val totalImages = uiState.images.size
        // We define pageCount before pagerState because pagerState needs it
        
        // This is tricky: we need isDualPage state, THEN calculate pageCount, THEN pass to pagerState.
        // But isDualPage is state. 
        // Let's hoist declarations.
        
        val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") || it.endsWith(".cbr") || it.endsWith(".7z") || it.endsWith(".cb7") }
        val viewMode = uiState.viewMode
        val pageCount = when (viewMode) {
            ViewMode.SINGLE -> totalImages
            ViewMode.DUAL -> (totalImages + 1) / 2
            ViewMode.SPLIT -> totalImages * 2
        }
        
        val scales = remember { mutableStateMapOf<Int, Float>() }
        var globalScale by remember { mutableFloatStateOf(1f) }
        // currentPageIndex hoisted to top level

        // Recreate pagerState when viewMode or images change, maintaining current relative position
        val pagerState = key(viewMode, uiState.images) {
            val initial = when (viewMode) {
                ViewMode.SINGLE -> currentPageIndex
                ViewMode.DUAL -> currentPageIndex / 2
                ViewMode.SPLIT -> currentPageIndex * 2
            }
            rememberPagerState(initialPage = initial.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) {
                pageCount
            }
        }

        LaunchedEffect(uiState.initialIndex, uiState.images) {
            if (uiState.images.isNotEmpty() && appliedInitialIndex != uiState.initialIndex) {
                val targetIndex = uiState.initialIndex.coerceIn(0, totalImages - 1)
                val targetPage = when (viewMode) {
                    ViewMode.SINGLE -> targetIndex
                    ViewMode.DUAL -> targetIndex / 2
                    ViewMode.SPLIT -> targetIndex * 2
                }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

                currentPageIndex = targetIndex
                pagerState.scrollToPage(targetPage)
                appliedInitialIndex = uiState.initialIndex
            }
        }

        androidx.compose.runtime.DisposableEffect(isFullScreen, uiState, currentPageIndex, viewMode, pageCount) {
            libraryViewModel?.setViewerBottomBarBackgroundColor(Color.Black)
            if (!isFullScreen && uiState.images.size > 1) {
                libraryViewModel?.setViewerBottomBarContent {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // File Name (Archive Name)
                        val containerName = uiState.containerName
                        if (containerName != null) {
                            Text(
                                text = containerName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        
                        // Image Name
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentImgIdx = currentPageIndex.coerceIn(0, uiState.images.size - 1)
                            val imageName = uiState.images.getOrNull(currentImgIdx)?.name ?: ""
                            
                            Text(
                                text = imageName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Text(
                                text = "${currentImgIdx + 1} / ${uiState.images.size} (${((currentImgIdx + 1) * 100 / uiState.images.size)}%)",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        
                        val sliderInteractionSource = remember { MutableInteractionSource() }
                        
                        // Slider for navigation
                        Slider(
                            value = currentPageIndex.toFloat(),
                            onValueChange = { 
                                val targetIdx = it.toInt()
                                if (targetIdx != currentPageIndex) {
                                    scope.launch {
                                        val targetPage = when (viewMode) {
                                            ViewMode.SINGLE -> targetIdx
                                            ViewMode.DUAL -> targetIdx / 2
                                            ViewMode.SPLIT -> targetIdx * 2
                                        }
                                        pagerState.scrollToPage(targetPage.coerceIn(0, pageCount - 1))
                                    }
                                }
                            },
                            valueRange = 0f..(uiState.images.size - 1).coerceAtLeast(0).toFloat(),
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
        
        // Update currentPageIndex when pager changes
        LaunchedEffect(pagerState.currentPage, viewMode) {
             currentPageIndex = when (viewMode) {
                 ViewMode.SINGLE -> pagerState.currentPage
                 ViewMode.DUAL -> pagerState.currentPage * 2
                 ViewMode.SPLIT -> pagerState.currentPage / 2
             }
             viewModel.updateProgress(currentPageIndex)
        }

        LaunchedEffect(currentActivity) {
            currentActivity?.keyEvents?.collect { keyCode ->
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                    // Previous image
                    if (pagerState.currentPage > 0) {
                        pagerState.scrollToPage(pagerState.currentPage - 1)
                    }
                } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                    // Next image
                    if (pagerState.currentPage < pageCount - 1) {
                        pagerState.scrollToPage(pagerState.currentPage + 1)
                    }
                }
            }
        }

        Scaffold(
            containerColor = Color.Black,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                if (!isFullScreen) {
                    val containerName = uiState.containerName
                    val imageTitle = when (viewMode) {
                        ViewMode.DUAL -> {
                            val p = pagerState.currentPage * 2
                            if (p >= 0 && p < uiState.images.size) {
                                val img1 = uiState.images[p].name
                                val img2 = if (p + 1 < uiState.images.size) uiState.images[p+1].name else null
                                val progress = if (isZip) " [${p + 1}/$totalImages]" else ""
                                if (img2 != null) "$img1 / $img2$progress" else "$img1$progress"
                            } else ""
                        }
                        ViewMode.SPLIT -> {
                            val imgIdx = pagerState.currentPage / 2
                            if (imgIdx >= 0 && imgIdx < uiState.images.size) {
                                val side = if (pagerState.currentPage % 2 == 0) " (Left)" else " (Right)"
                                val progress = if (isZip) " [${imgIdx + 1}/$totalImages]" else ""
                                uiState.images[imgIdx].name + side + progress
                            } else ""
                        }
                        else -> {
                            if (pagerState.currentPage >= 0 && pagerState.currentPage < uiState.images.size) {
                                val progress = if (isZip) " [${pagerState.currentPage + 1}/$totalImages]" else ""
                                uiState.images[pagerState.currentPage].name + progress
                            } else ""
                        }
                    }
                    
                    var showSettingsDialog by remember { mutableStateOf(false) }

                    Column {
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
                                    val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") || it.endsWith(".cbr") || it.endsWith(".7z") || it.endsWith(".cb7") }
                                    val currentImageIndex = when (viewMode) {
                                        ViewMode.DUAL -> (pagerState.currentPage * 2).coerceAtMost(uiState.images.size - 1)
                                        ViewMode.SPLIT -> (pagerState.currentPage / 2).coerceAtMost(uiState.images.size - 1)
                                        else -> pagerState.currentPage
                                    }
                                    IconButton(onClick = { 
                                        viewModel.toggleBookmark(filePath, currentImageIndex, isWebDav, serverId, if (isZip) "ZIP" else "IMAGE", uiState.images)
                                        val archiveName = if (filePath.endsWith("/")) filePath.dropLast(1).substringAfterLast("/") else filePath.substringAfterLast("/")
                                        val imageName = if (currentImageIndex >= 0 && currentImageIndex < uiState.images.size) uiState.images[currentImageIndex].name else ""
                                        val displayTitle = if (imageName.isNotEmpty() && archiveName != imageName) "$archiveName - $imageName" else imageName
                                        android.widget.Toast.makeText(context, "Bookmark Saved: $displayTitle", android.widget.Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Bookmark, contentDescription = "Bookmark", modifier = Modifier.size(24.dp))
                                    }
                                    IconButton(onClick = { viewModel.toggleViewMode() }, modifier = Modifier.size(40.dp)) {
                                        Icon(
                                            when (viewMode) {
                                                ViewMode.SINGLE -> Icons.Default.ViewCarousel
                                                ViewMode.DUAL -> Icons.Default.ViewAgenda
                                                ViewMode.SPLIT -> Icons.Default.VerticalSplit
                                            }, 
                                            contentDescription = "Toggle View Mode",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.setInvertImageControl(!invertImageControl) }, modifier = Modifier.size(40.dp)) {
                                        Icon(
                                            if (invertImageControl) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Flip Controls",
                                            tint = if (invertImageControl) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(24.dp))
                                    }
                                }
                            )
                        }
                        
                        if (showSettingsDialog) {
                            com.uviewer_android.ui.theme.UviewerAlertDialog(
                                onDismissRequest = { showSettingsDialog = false },
                                shape = RoundedCornerShape(28.dp),
                                title = { Text(stringResource(R.string.section_image_viewer), style = MaterialTheme.typography.headlineSmall) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically, 
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { viewModel.setInvertImageControl(!invertImageControl) }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Checkbox(checked = invertImageControl, onCheckedChange = { viewModel.setInvertImageControl(it) })
                                            Column {
                                                Text(stringResource(R.string.invert_image_control), style = MaterialTheme.typography.bodyLarge)
                                                Text(stringResource(R.string.invert_image_control_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically, 
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { viewModel.setPersistZoom(!uiState.persistZoom) }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Checkbox(checked = uiState.persistZoom, onCheckedChange = { viewModel.setPersistZoom(it) })
                                            Text(stringResource(R.string.persist_zoom), style = MaterialTheme.typography.bodyLarge)
                                        }
                                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                                            Text(stringResource(R.string.sharpening_amount) + ": ${uiState.sharpeningAmount}", style = MaterialTheme.typography.labelMedium)
                                            val sharpeningInteractionSource = remember { MutableInteractionSource() }
                                            Slider(
                                                value = uiState.sharpeningAmount.toFloat(),
                                                onValueChange = { viewModel.setSharpeningAmount(it.toInt()) },
                                                valueRange = 0f..10f,
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
                                                interactionSource = sharpeningInteractionSource
                                            )
                                        }
                                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                                            Text(stringResource(R.string.dual_page_order), style = MaterialTheme.typography.labelMedium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                                FilterChip(
                                                    selected = dualPageOrder == 0,
                                                    onClick = { viewModel.setDualPageOrder(0) },
                                                    label = { Text(stringResource(R.string.dual_page_ltr)) },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                FilterChip(
                                                    selected = dualPageOrder == 1,
                                                    onClick = { viewModel.setDualPageOrder(1) },
                                                    label = { Text(stringResource(R.string.dual_page_rtl)) },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { showSettingsDialog = false },
                                        shape = RoundedCornerShape(20.dp)
                                    ) { Text("Close") }
                                }
                            )
                        }
                    }
                }
            },
            snackbarHost = {}
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
                    .pointerInput(pagerState, viewMode) {
                        detectTapGestures(
                            onTap = { offset ->
                                scope.launch {
                                    val screenWidth = size.width
                                    val screenHeight = size.height
                                    val thirdOfWidth = screenWidth / 3
                                    val isLeftTap = offset.x < thirdOfWidth
                                    val isRightTap = offset.x > thirdOfWidth * 2
                                    val isTopTap = offset.y < screenHeight / 4 // Top 25%
                                    
                                    val goPrev = if (invertImageControl) isRightTap else isLeftTap
                                    val goNext = if (invertImageControl) isLeftTap else isRightTap

                                    when {
                                        isTopTap -> {
                                            onToggleFullScreen()
                                        }
                                        goPrev -> {
                                            val current = pagerState.currentPage
                                            if (current > 0) {
                                                pagerState.scrollToPage(current - 1)
                                            }
                                        }
                                        goNext -> {
                                            val current = pagerState.currentPage
                                            if (current < pageCount - 1) {
                                                pagerState.scrollToPage(current + 1)
                                            }
                                        }
                                        else -> {
                                            onToggleFullScreen()
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                val currentScale = if (uiState.persistZoom) globalScale else (scales[pagerState.currentPage] ?: 1f)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = currentScale <= 1.1f,
                    reverseLayout = invertImageControl,
                    beyondViewportPageCount = 1
                ) { page ->
                    val quad = when (viewMode) {
                        ViewMode.DUAL -> {
                            val p1 = page * 2
                            val p2 = page * 2 + 1
                            if (dualPageOrder == 1) Quad(p2, p1, false, false)
                            else Quad(p1, p2, false, false)
                        }
                        ViewMode.SPLIT -> {
                            val imgIdx = page / 2
                            val right = page % 2 == 1
                            val actualRight = if (dualPageOrder == 1) !right else right
                            Quad(imgIdx, -1, true, actualRight)
                        }
                        else -> Quad(page, -1, false, false)
                    }
                    val firstIdx = quad.first
                    val secondIdx = quad.second
                    val isSplit = quad.third
                    val isRight = quad.fourth

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val firstImage = if (firstIdx >= 0 && firstIdx < uiState.images.size) uiState.images[firstIdx] else null
                        val secondImage = if (secondIdx >= 0 && secondIdx < uiState.images.size) uiState.images[secondIdx] else null

                        key(firstIdx, isSplit, isRight) {
                            if (viewMode == ViewMode.DUAL) {
                                ZoomableDualImage(
                                    firstImageUrl = firstImage?.path,
                                    secondImageUrl = secondImage?.path,
                                    isWebDav = uiState.isContentLoadedFromWebDav,
                                    authHeader = uiState.authHeader,
                                    serverUrl = uiState.serverUrl,
                                    scale = if (uiState.persistZoom) globalScale else (scales.getOrPut(page) { 1f }),
                                    sharpeningAmount = uiState.sharpeningAmount,
                                    onScaleChanged = { newScale -> 
                                        if (uiState.persistZoom) globalScale = newScale else scales[page] = newScale 
                                    }
                                )
                            } else if (firstImage != null) {
                                val currentScale = if (uiState.persistZoom) globalScale else (scales.getOrPut(page) { 1f })
                                 ZoomableImage(
                                    imageUrl = firstImage.path,
                                    isWebDav = uiState.isContentLoadedFromWebDav,
                                    authHeader = uiState.authHeader,
                                    serverUrl = uiState.serverUrl,
                                    scale = currentScale,
                                    sharpeningAmount = uiState.sharpeningAmount,
                                    onScaleChanged = { newScale -> 
                                        if (uiState.persistZoom) globalScale = newScale else scales[page] = newScale 
                                    },
                                    isSplit = isSplit,
                                    isRight = isRight
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
