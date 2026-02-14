package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.uviewer_android.ui.AppViewModelProvider
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    viewModel: ImageViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val invertImageControl by viewModel.invertImageControl.collectAsState()
    val dualPageOrder by viewModel.dualPageOrder.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Ensure status bar icons are visible (white) on dark background even in light theme
    val systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    DisposableEffect(isFullScreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
        }
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                // Restore based on system theme
                insetsController.isAppearanceLightStatusBars = !systemInDarkTheme
            }
        }
    }

    // Load images on start
    LaunchedEffect(filePath) {
        viewModel.loadImages(filePath, isWebDav, serverId)
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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
        
        var isDualPage by remember { mutableStateOf(false) }
        val pageCount = if (isDualPage) (totalImages + 1) / 2 else totalImages
        
        val pagerState = rememberPagerState(initialPage = if (isDualPage) uiState.initialIndex / 2 else uiState.initialIndex) {
            pageCount
        }
        
        // Sync pager state with initialIndex from ViewModel
        LaunchedEffect(uiState.initialIndex, isDualPage) {
            val target = if (isDualPage) uiState.initialIndex / 2 else uiState.initialIndex
            if (pagerState.currentPage != target && target < pageCount) {
                pagerState.scrollToPage(target)
            }
        }
        // This is tricky with Compose Pager state preservation on count change.
        // For simplicity, we restart at 0 or try to map. 
        // Better: Use a derived state or handle it carefully.
        // If we switch to Dual, page -> page / 2.
        // If we switch to Single, page -> page * 2.
        // But pagerState.scrollToPage must be called in LaunchedEffect.
        
        val scales = remember { mutableStateMapOf<Int, Float>() }
        // Hoist global scale for "maintain zoom" feature
        var globalScale by remember { mutableFloatStateOf(1f) }
        
        var currentPageIndex by remember { mutableIntStateOf(uiState.initialIndex) }
        
        // Ensure starting page is correct even if state hasn't synced yet
        val initialMappedPage = remember { if (isDualPage) uiState.initialIndex / 2 else uiState.initialIndex }
        
        // Update index when mode toggles
        LaunchedEffect(isDualPage) {
             val target = if (isDualPage) currentPageIndex / 2 else currentPageIndex * 2
             if (target < pageCount) {
                 pagerState.scrollToPage(target)
             }
        }
        
        // Track current page to sync back
        LaunchedEffect(pagerState.currentPage) {
             currentPageIndex = if (isDualPage) pagerState.currentPage * 2 else pagerState.currentPage
             viewModel.updateProgress(currentPageIndex)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(pagerState, isDualPage) {
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
                                    goPrev && !isTopTap -> {
                                        val current = pagerState.currentPage
                                        if (current > 0) {
                                            pagerState.scrollToPage(current - 1)
                                        }
                                    }
                                    goNext && !isTopTap -> {
                                        val current = pagerState.currentPage
                                        if (current < pageCount - 1) {
                                            pagerState.scrollToPage(current + 1)
                                        }
                                    }
                                    else -> {
                                        // Center tap - do nothing or toggle UI if preferred
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
                Log.d("ImageViewer", "Pager state: currentPage=${pagerState.currentPage}, pageCount=$pageCount")
                val currentScale = if (uiState.persistZoom) globalScale else (scales[pagerState.currentPage] ?: 1f)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = currentScale <= 1.1f,
                    beyondViewportPageCount = 1
                ) { page ->
                    Log.d("ImageViewer", "Rendering Pager item for page $page")
                    
                    val (firstIdx, secondIdx) = if (isDualPage) {
                        val p1 = page * 2
                        val p2 = page * 2 + 1
                        if (dualPageOrder == 1) Pair(p2, p1) else Pair(p1, p2)
                    } else {
                        Pair(page, -1)
                    }

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val firstImage = if (firstIdx >= 0 && firstIdx < uiState.images.size) uiState.images[firstIdx] else null
                    val secondImage = if (isDualPage && secondIdx >= 0 && secondIdx < uiState.images.size) uiState.images[secondIdx] else null

                    if (firstImage != null) {
                        Log.d("ImageViewer", "Page $page: Showing image $firstIdx: ${firstImage.name}")
                    }

                    key(firstIdx) {
                        if (isDualPage) {
                            ZoomableDualImage(
                                firstImageUrl = firstImage?.path,
                                secondImageUrl = secondImage?.path,
                                isWebDav = uiState.isContentLoadedFromWebDav,
                                authHeader = uiState.authHeader,
                                serverUrl = uiState.serverUrl,
                                scale = if (uiState.persistZoom) globalScale else (scales.getOrPut(page) { 1f }),
                                upscaleFilter = uiState.upscaleFilter,
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
                                upscaleFilter = uiState.upscaleFilter,
                                onScaleChanged = { newScale -> 
                                    if (uiState.persistZoom) globalScale = newScale else scales[page] = newScale 
                                }
                            )
                        }
                    }
                }
            }

            // Top Bar
            if (!isFullScreen) {
                // Determine title
                val title = if (isDualPage) {
                     val p = pagerState.currentPage * 2
                     val img1 = uiState.images[p].name
                     val img2 = if (p + 1 < uiState.images.size) uiState.images[p+1].name else null
                     val pageTitle = if (img2 != null) "$img1 / $img2" else img1
                     if (uiState.containerName != null) "${uiState.containerName} - $pageTitle" else pageTitle
                } else {
                    if (pagerState.currentPage < uiState.images.size) {
                        val imgName = uiState.images[pagerState.currentPage].name
                        if (uiState.containerName != null) "${uiState.containerName} - $imgName" else imgName
                    } else ""
                }
                
                var showSettingsDialog by remember { mutableStateOf(false) }

                TopAppBar(
                    title = { Text(title, color = Color.White, maxLines = 1) }, 
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val type = if (filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") }) "IMAGE_ZIP" else "IMAGE"
                            viewModel.toggleBookmark(filePath, pagerState.currentPage, isWebDav, serverId, type)
                            android.widget.Toast.makeText(context, "Bookmark Saved", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Bookmark", tint = Color.White)
                        }
                        IconButton(onClick = { isDualPage = !isDualPage }) {
                            Icon(
                                if (isDualPage) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel, 
                                contentDescription = "Toggle Dual Page",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text(stringResource(R.string.section_image_viewer)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = uiState.persistZoom, onCheckedChange = { viewModel.setPersistZoom(it) })
                                    Text(stringResource(R.string.persist_zoom))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = uiState.upscaleFilter, onCheckedChange = { viewModel.setUpscaleFilter(it) })
                                    Text(stringResource(R.string.upscale_filter))
                                }
                                Column {
                                    Text(stringResource(R.string.dual_page_order))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = dualPageOrder == 0,
                                            onClick = { viewModel.setDualPageOrder(0) },
                                            label = { Text(stringResource(R.string.dual_page_ltr)) }
                                        )
                                        FilterChip(
                                            selected = dualPageOrder == 1,
                                            onClick = { viewModel.setDualPageOrder(1) },
                                            label = { Text(stringResource(R.string.dual_page_rtl)) }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { showSettingsDialog = false }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ZoomableImage(
    imageUrl: String,
    isWebDav: Boolean,
    authHeader: String?,
    serverUrl: String?,
    scale: Float,
    upscaleFilter: Boolean,
    onScaleChanged: (Float) -> Unit,
    secondImageUrl: String? = null // For dual page
) {
    Log.d("ImageViewer", "ZoomableImage: url=$imageUrl, isWebDav=$isWebDav, serverUrl=$serverUrl")
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var zoom = 1f
                    var panX = 0f
                    var panY = 0f
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                panX += panChange.x
                                panY += panChange.y

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = kotlin.math.abs(1 - zoom) * centroidSize
                                val panMotion = androidx.compose.ui.geometry.Offset(panX, panY).getDistance()

                                if (zoomMotion > touchSlop ||
                                    panMotion > touchSlop
                                ) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                
                                // Zoom Logic
                                if (zoomChange != 1f) {
                                    val oldScale = scale
                                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                    onScaleChanged(newScale)
                                    
                                    val scaleChange = newScale / oldScale
                                    val width = size.width
                                    val height = size.height
                                    
                                     offsetX = (panChange.x + (offsetX - (centroid.x - width / 2)) * scaleChange + (centroid.x - width / 2))
                                     offsetY = (panChange.y + (offsetY - (centroid.y - height / 2)) * scaleChange + (centroid.y - height / 2))
                                     
                                     val maxOffsetX = (width * (newScale - 1)) / 2
                                     val maxOffsetY = (height * (newScale - 1)) / 2
                                     offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                     offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                                } else {
                                     // Pan Logic
                                     val width = size.width
                                     val height = size.height
                                     val maxOffsetX = (width * (scale - 1)) / 2
                                     val maxOffsetY = (height * (scale - 1)) / 2
                                     
                                     offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                     offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                }
                                
                                // CONSUME EVENTS if we are zoomed in or strictly panning/zooming
                                if (scale > 1f || zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                    event.changes.forEach { 
                                        if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                                            it.consume() 
                                        }
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        ) {
            val context = LocalContext.current
            
            // Helper to build ImageRequest
            fun buildRequest(url: String): ImageRequest {
                Log.d("ImageViewer", "ZoomableImage: buildRequest: url=$url, isWebDav=$isWebDav, serverUrl=$serverUrl, authHeader=${authHeader != null}")
                val loaderBuilder = if (isWebDav && serverUrl != null) {
                   val fullUrl = try {
                        val baseHttpUrl = serverUrl.trimEnd('/').toHttpUrl()
                        val builder = baseHttpUrl.newBuilder()
                        url.split("/").filter { it.isNotEmpty() }.forEach {
                            builder.addPathSegment(it)
                        }
                        builder.build().toString()
                    } catch (e: Exception) {
                        val trimmedBase = serverUrl.trimEnd('/')
                        val trimmedPath = if (url.startsWith("/")) url else "/$url"
                        trimmedBase + trimmedPath
                    }
                    Log.d("ImageViewer", "ZoomableImage: Built WebDAV URL: $fullUrl")

                    ImageRequest.Builder(context)
                        .data(fullUrl)
                        .apply {
                            if (authHeader != null) {
                                addHeader("Authorization", authHeader)
                                Log.d("ImageViewer", "ZoomableImage: Added Authorization header.")
                            }
                        }
                } else {
                    Log.d("ImageViewer", "ZoomableImage: Requesting Local: $url")
                    ImageRequest.Builder(context)
                        .data(java.io.File(url))
                }
                
                return loaderBuilder
                    .crossfade(true)
                    .allowHardware(false) // Disable hardware bitmaps to prevent potential black screen issues
                    .build()
            }
            
            val imageLoader = remember {
                coil.ImageLoader.Builder(context)
                    .components {
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            add(coil.decode.ImageDecoderDecoder.Factory())
                        } else {
                            add(coil.decode.GifDecoder.Factory())
                        }
                    }
                    .build()
            }

            coil.compose.SubcomposeAsyncImage(
                model = buildRequest(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                imageLoader = imageLoader,
                filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                },
                error = { state ->
                    val errorMsg = state.result.throwable.message ?: "Unknown error"
                    Log.e("ImageViewer", "Failed to load image: $imageUrl", state.result.throwable)
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Image Error", color = Color.Red, style = MaterialTheme.typography.bodyMedium)
                            Text(imageUrl.substringAfterLast("/"), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Text(errorMsg, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ZoomableDualImage(
    firstImageUrl: String?,
    secondImageUrl: String?,
    isWebDav: Boolean,
    authHeader: String?,
    serverUrl: String?,
    scale: Float,
    upscaleFilter: Boolean,
    onScaleChanged: (Float) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
               awaitEachGesture {
                    var zoom = 1f
                    var panX = 0f
                    var panY = 0f
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                panX += panChange.x
                                panY += panChange.y

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = kotlin.math.abs(1 - zoom) * centroidSize
                                val panMotion = androidx.compose.ui.geometry.Offset(panX, panY).getDistance()

                                if (zoomMotion > touchSlop ||
                                    panMotion > touchSlop
                                ) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                
                                if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                    val oldScale = scale
                                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                    onScaleChanged(newScale)
                                    
                                    val scaleChange = newScale / oldScale
                                    val width = size.width
                                    val height = size.height
                                    
                                     offsetX = (panChange.x + (offsetX - (centroid.x - width / 2)) * scaleChange + (centroid.x - width / 2))
                                     offsetY = (panChange.y + (offsetY - (centroid.y - height / 2)) * scaleChange + (centroid.y - height / 2))
                                     
                                     val maxOffsetX = (width * (newScale - 1)) / 2
                                     val maxOffsetY = (height * (newScale - 1)) / 2
                                     offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                     offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                                     
                                     event.changes.forEach { 
                                        if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                                            it.consume() 
                                        }
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        ) {
            val context = LocalContext.current
            
            // Helper to build ImageRequest
            fun buildRequest(url: String): ImageRequest {
                Log.d("ImageViewer", "ZoomableDualImage: buildRequest: url=$url, isWebDav=$isWebDav, serverUrl=$serverUrl, authHeader=${authHeader != null}")
                val loaderBuilder = if (isWebDav && serverUrl != null) {
                   val fullUrl = try {
                        val baseHttpUrl = serverUrl.trimEnd('/').toHttpUrl()
                        val builder = baseHttpUrl.newBuilder()
                        url.split("/").filter { it.isNotEmpty() }.forEach {
                            builder.addPathSegment(it)
                        }
                        builder.build().toString()
                    } catch (e: Exception) {
                        val trimmedBase = serverUrl.trimEnd('/')
                        val trimmedPath = if (url.startsWith("/")) url else "/$url"
                        trimmedBase + trimmedPath
                    }

                    Log.d("ImageViewer", "ZoomableDualImage: Built WebDAV URL (Dual): $fullUrl")
                    ImageRequest.Builder(context)
                        .data(fullUrl)
                        .apply {
                            if (authHeader != null) {
                                addHeader("Authorization", authHeader)
                                Log.d("ImageViewer", "ZoomableDualImage: Added Authorization header.")
                            }
                        }
                } else {
                    Log.d("ImageViewer", "ZoomableDualImage: Requesting Local (Dual): $url")
                    ImageRequest.Builder(context)
                        .data(java.io.File(url))
                }

                return loaderBuilder
                    .crossfade(true)
                    .allowHardware(false)
                    .build()
            }

            val imageLoader = remember {
                coil.ImageLoader.Builder(context)
                    .components {
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            add(coil.decode.ImageDecoderDecoder.Factory())
                        } else {
                            add(coil.decode.GifDecoder.Factory())
                        }
                    }
                    .build()
            }

            if (firstImageUrl != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = buildRequest(firstImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = imageLoader,
                    alignment = Alignment.CenterEnd,
                    filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    },
                    error = { state ->
                        val errorMsg = state.result.throwable.message ?: "Unknown error"
                        Log.e("ImageViewer", "Failed to load image 1 (Dual): $firstImageUrl", state.result.throwable)
                        Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Error", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                Text(errorMsg, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            
            if (secondImageUrl != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = buildRequest(secondImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = imageLoader,
                    alignment = Alignment.CenterStart,
                    filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    },
                    error = { state ->
                        val errorMsg = state.result.throwable.message ?: "Unknown error"
                        Log.e("ImageViewer", "Failed to load image 2 (Dual): $secondImageUrl", state.result.throwable)
                        Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Error", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                Text(errorMsg, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
