package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
    LaunchedEffect(isFullScreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
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
        
        val pagerState = rememberPagerState(initialPage = uiState.initialIndex) {
            pageCount
        }
        
        // Since we changed pageCount, we need to make sure currentPage is valid or mapped
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
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // pageCount might be a property in newer APIs or a lambda
                // For androidx.compose.foundation.pager.HorizontalPager if pageCount is not a parameter then it is state.pageCount
                // But we used rememberPagerState { uiState.images.size } which sets the count.
                // Wait, if we change the mode (Single/Dual), the effective page count changes.
                // In Compose 1.5+ HorizontalPager(state = pagerState). The count is in the state lambda.
                // If we want dynamic count, we need to recreate state or check API.
                // Ah, the state lambda is `pageCount: () -> Int`.
                // So rememberPagerState { pageCount } where pageCount is our dynamic variable.
                
                userScrollEnabled = (scales[pagerState.currentPage] ?: 1f) == 1f
            ) { page ->
                // Calculate indices based on order
                val p1 = page * 2
                val p2 = page * 2 + 1
                
                val (firstIdx, secondIdx) = if (dualPageOrder == 1) { // RTL: Second index on Left, First on Right
                    p2 to p1
                } else { // LTR: First on Left, Second on Right
                    p1 to p2
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val firstImage = if (firstIdx < uiState.images.size) uiState.images[firstIdx] else null
                    val secondImage = if (isDualPage && secondIdx < uiState.images.size) uiState.images[secondIdx] else null

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
                         ZoomableImage(
                            imageUrl = firstImage.path,
                            isWebDav = uiState.isContentLoadedFromWebDav,
                            authHeader = uiState.authHeader,
                            serverUrl = uiState.serverUrl,
                            scale = if (uiState.persistZoom) globalScale else (scales.getOrPut(page) { 1f }),
                            upscaleFilter = uiState.upscaleFilter,
                            onScaleChanged = { newScale -> 
                                if (uiState.persistZoom) globalScale = newScale else scales[page] = newScale 
                            }
                        )
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
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    onScaleChanged(newScale)
                    
                    if (newScale > 1f) {
                        val width = size.width
                        val height = size.height
                        val scaleChange = newScale / oldScale
                        
                        offsetX = (pan.x + (offsetX - (centroid.x - width / 2)) * scaleChange + (centroid.x - width / 2))
                        offsetY = (pan.y + (offsetY - (centroid.y - height / 2)) * scaleChange + (centroid.y - height / 2))
                        
                        val maxOffsetX = (width * (newScale - 1)) / 2
                        val maxOffsetY = (height * (newScale - 1)) / 2
                        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
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
                val loaderBuilder = if (isWebDav && serverUrl != null) {
                   val fullUrl = try {
                        val baseHttpUrl = serverUrl.trimEnd('/').toHttpUrl()
                        val builder = baseHttpUrl.newBuilder()
                        url.split("/").filter { it.isNotEmpty() }.forEach {
                            builder.addPathSegment(it)
                        }
                        builder.build().toString()
                    } catch (e: Exception) {
                        serverUrl.trimEnd('/') + url
                    }

                    ImageRequest.Builder(context)
                        .data(fullUrl)
                        .apply {
                            if (authHeader != null) {
                                addHeader("Authorization", authHeader)
                            }
                        }
                } else {
                    ImageRequest.Builder(context)
                        .data(java.io.File(url))
                }
                
                return loaderBuilder
                    .crossfade(true)
                    .build()
            }
            
            // Custom ImageLoader for GIF/WebP support
            val imageLoader = coil.ImageLoader.Builder(context)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
                .build()

            AsyncImage(
                model = buildRequest(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                imageLoader = imageLoader,
                filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
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
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    onScaleChanged(newScale)
                    
                    if (newScale > 1f) {
                        val width = size.width
                        val height = size.height
                        val scaleChange = newScale / oldScale
                        
                        offsetX = (pan.x + (offsetX - (centroid.x - width / 2)) * scaleChange + (centroid.x - width / 2))
                        offsetY = (pan.y + (offsetY - (centroid.y - height / 2)) * scaleChange + (centroid.y - height / 2))
                        
                        val maxOffsetX = (width * (newScale - 1)) / 2
                        val maxOffsetY = (height * (newScale - 1)) / 2
                        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
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
                return if (isWebDav && serverUrl != null) {
                   val fullUrl = try {
                        val baseHttpUrl = serverUrl.trimEnd('/').toHttpUrl()
                        val builder = baseHttpUrl.newBuilder()
                        url.split("/").filter { it.isNotEmpty() }.forEach {
                            builder.addPathSegment(it)
                        }
                        builder.build().toString()
                    } catch (e: Exception) {
                        serverUrl.trimEnd('/') + url
                    }

                    ImageRequest.Builder(context)
                        .data(fullUrl)
                        .apply {
                            if (authHeader != null) {
                                addHeader("Authorization", authHeader)
                            }
                        }
                        .crossfade(true)
                        .build()
                } else {
                    ImageRequest.Builder(context)
                        .data(java.io.File(url))
                        .crossfade(true)
                        .build()
                }
            }

            if (firstImageUrl != null) {
                AsyncImage(
                    model = buildRequest(firstImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd,
                    filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            
            if (secondImageUrl != null) {
                AsyncImage(
                    model = buildRequest(secondImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    filterQuality = if (upscaleFilter) FilterQuality.High else FilterQuality.Low,
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
