package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.uviewer_android.R
import android.util.Log
import androidx.compose.foundation.clickable
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
import coil.request.ImageRequest
import androidx.compose.ui.draw.clipToBounds
import com.uviewer_android.ui.AppViewModelProvider
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

class SharpenTransformation(private val intensity: Int) : coil.transform.Transformation {
    override val cacheKey: String = "sharpen_$intensity"
    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        if (intensity <= 0) return input
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val outputPixels = IntArray(width * height)
        val alpha = intensity.toFloat() / 20f
        val center = 1f + 4f * alpha
        val neighbor = -alpha
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val p = pixels[idx]
                val pl = pixels[idx - 1]
                val pr = pixels[idx + 1]
                val pt = pixels[idx - width]
                val pb = pixels[idx + width]
                
                var r = (((p shr 16) and 0xFF) * center + (((pl shr 16) and 0xFF) + ((pr shr 16) and 0xFF) + ((pt shr 16) and 0xFF) + ((pb shr 16) and 0xFF)) * neighbor).toInt()
                var g = (((p shr 8) and 0xFF) * center + (((pl shr 8) and 0xFF) + ((pr shr 8) and 0xFF) + ((pt shr 8) and 0xFF) + ((pb shr 8) and 0xFF)) * neighbor).toInt()
                var b = ((p and 0xFF) * center + ((pl and 0xFF) + (pr and 0xFF) + (pt and 0xFF) + (pb and 0xFF)) * neighbor).toInt()
                
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                outputPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val output = android.graphics.Bitmap.createBitmap(width, height, input.config ?: android.graphics.Bitmap.Config.ARGB_8888)
        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    isWebDav: Boolean,
    serverId: Int?,
    initialIndex: Int? = null,
    viewModel: ImageViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    activity: com.uviewer_android.MainActivity? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val invertImageControl by viewModel.invertImageControl.collectAsState()
    val dualPageOrder by viewModel.dualPageOrder.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Status Bar Logic
    val isLightAppTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    
    // When UI is shown (!isFullScreen), icons follow the app's current theme (Light/Dark).
    // When UI is hidden (isFullScreen), icons follow the viewer background (always black for images).
    val useLightStatusBar = if (!isFullScreen) {
        isLightAppTheme
    } else {
        false // PDF full screen background is black
    }
    
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.isAppearanceLightStatusBars = isLightAppTheme
            }
        }
    }

    LaunchedEffect(useLightStatusBar, isFullScreen) {
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

    // Load images on start
    LaunchedEffect(filePath) {
        viewModel.loadImages(filePath, isWebDav, serverId, initialIndex)
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
        
        val scales = remember { mutableStateMapOf<Int, Float>() }
        var globalScale by remember { mutableFloatStateOf(1f) }
        var currentPageIndex by remember { mutableIntStateOf(uiState.initialIndex) }

        // Use key to recreate pagerState when toggling dual mode to ensure immediate mapping
        val pagerState = key(isDualPage) {
            val initial = if (isDualPage) currentPageIndex / 2 else currentPageIndex
            rememberPagerState(initialPage = initial.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) {
                pageCount
            }
        }
        
        // Update currentPageIndex when pager changes
        LaunchedEffect(pagerState.currentPage, isDualPage) {
             currentPageIndex = if (isDualPage) pagerState.currentPage * 2 else pagerState.currentPage
             viewModel.updateProgress(currentPageIndex)
        }

        LaunchedEffect(activity) {
            activity?.keyEvents?.collect { keyCode ->
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
            containerColor = if (isFullScreen) Color.Black else MaterialTheme.colorScheme.background,
            topBar = {
                if (!isFullScreen) {
                    val containerName = uiState.containerName
                    val imageTitle = if (isDualPage) {
                        val p = pagerState.currentPage * 2
                        if (p >= 0 && p < uiState.images.size) {
                            val img1 = uiState.images[p].name
                            val img2 = if (p + 1 < uiState.images.size) uiState.images[p+1].name else null
                            if (img2 != null) "$img1 / $img2" else img1
                        } else ""
                    } else {
                        if (pagerState.currentPage >= 0 && pagerState.currentPage < uiState.images.size) {
                            uiState.images[pagerState.currentPage].name
                        } else ""
                    }
                    
                    var showSettingsDialog by remember { mutableStateOf(false) }

                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    if (containerName != null) {
                                        Text(containerName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                        Text(imageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Text(imageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }, 
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                                }
                            },
                            actions = {
                                val isZip = filePath.lowercase().let { it.endsWith(".zip") || it.endsWith(".cbz") || it.endsWith(".rar") }
                                val currentImageIndex = if (isDualPage) (pagerState.currentPage * 2).coerceAtMost(uiState.images.size - 1) else pagerState.currentPage
                                IconButton(onClick = { 
                                    viewModel.toggleBookmark(filePath, currentImageIndex, isWebDav, serverId, if (isZip) "ZIP" else "IMAGE", uiState.images)
                                    android.widget.Toast.makeText(context, "Bookmark Saved", android.widget.Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
                                }
                                IconButton(onClick = { isDualPage = !isDualPage }) {
                                    Icon(
                                        if (isDualPage) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel, 
                                        contentDescription = "Toggle Dual Page"
                                    )
                                }
                                IconButton(onClick = { viewModel.setInvertImageControl(!invertImageControl) }) {
                                    Icon(
                                        Icons.Default.SwapHoriz,
                                        contentDescription = "Invert Controls",
                                        tint = if (invertImageControl) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                    )
                                }
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                        HorizontalDivider()
                        
                        if (showSettingsDialog) {
                            AlertDialog(
                                onDismissRequest = { showSettingsDialog = false },
                                title = { Text(stringResource(R.string.section_image_viewer)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { viewModel.setInvertImageControl(!invertImageControl) }.padding(vertical = 4.dp)) {
                                            Checkbox(checked = invertImageControl, onCheckedChange = { viewModel.setInvertImageControl(it) })
                                            Column {
                                                Text(stringResource(R.string.invert_image_control))
                                                Text(stringResource(R.string.invert_image_control_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { viewModel.setPersistZoom(!uiState.persistZoom) }) {
                                            Checkbox(checked = uiState.persistZoom, onCheckedChange = { viewModel.setPersistZoom(it) })
                                            Text(stringResource(R.string.persist_zoom))
                                        }
                                        Column {
                                            Text(stringResource(R.string.sharpening_amount) + ": ${uiState.sharpeningAmount}")
                                            Slider(
                                                value = uiState.sharpeningAmount.toFloat(),
                                                onValueChange = { viewModel.setSharpeningAmount(it.toInt()) },
                                                valueRange = 0f..10f,
                                                steps = 9
                                            )
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
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
                val currentScale = if (uiState.persistZoom) globalScale else (scales[pagerState.currentPage] ?: 1f)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = currentScale <= 1.1f,
                    reverseLayout = invertImageControl,
                    beyondViewportPageCount = 1
                ) { page ->
                    val (firstIdx, secondIdx) = if (isDualPage) {
                        val p1 = page * 2
                        val p2 = page * 2 + 1
                        if (dualPageOrder == 1) Pair(p2, p1) else Pair(p1, p2)
                    } else {
                        Pair(page, -1)
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val firstImage = if (firstIdx >= 0 && firstIdx < uiState.images.size) uiState.images[firstIdx] else null
                        val secondImage = if (isDualPage && secondIdx >= 0 && secondIdx < uiState.images.size) uiState.images[secondIdx] else null

                        key(firstIdx) {
                            if (isDualPage) {
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
                                    }
                                )
                            }
                        }
                    }
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
    sharpeningAmount: Int,
    onScaleChanged: (Float) -> Unit,
    secondImageUrl: String? = null // For dual page
) {
    val currentScale by rememberUpdatedState(scale)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset offsets when image changes
    LaunchedEffect(imageUrl) {
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds() // Prevent overlap when zoomed
            .pointerInput(imageUrl) {
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
                                
                                // Pan/Zoom Logic combined
                                if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                    val oldScale = currentScale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 5f)
                                    onScaleChanged(newScale)
                                    
                                    val scaleChange = newScale / oldScale
                                    val width = size.width
                                    val height = size.height
                                    
                                    // Adjust offsets to keep centroid stable during zoom
                                    offsetX = (panChange.x + (offsetX - (centroid.x - width / 2)) * scaleChange + (centroid.x - width / 2))
                                    offsetY = (panChange.y + (offsetY - (centroid.y - height / 2)) * scaleChange + (centroid.y - height / 2))
                                    
                                    val maxOffsetX = (width * (newScale - 1)) / 2
                                    val maxOffsetY = (height * (newScale - 1)) / 2
                                    offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                    offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                                    
                                    // Consume events ONLY IF we are zoomed or performing a transform
                                    if (newScale > 1f || zoomChange != 1f) {
                                        event.changes.forEach { it.consume() }
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
                    .apply {
                        if (sharpeningAmount > 0) {
                            transformations(SharpenTransformation(sharpeningAmount))
                        }
                    }
                    .allowHardware(false) // Disable hardware bitmaps to prevent potential black screen issues
                    .build()
            }
            


            coil.compose.SubcomposeAsyncImage(
                model = buildRequest(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = if (sharpeningAmount > 0) FilterQuality.High else FilterQuality.Medium,
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
    sharpeningAmount: Int,
    onScaleChanged: (Float) -> Unit
) {
    val currentScale by rememberUpdatedState(scale)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(firstImageUrl, secondImageUrl) {
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds() // Prevent overlap when zoomed
            .pointerInput(firstImageUrl, secondImageUrl) {
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
                                    val oldScale = currentScale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 5f)
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
                                     
                                    if (newScale > 1f || zoomChange != 1f) {
                                        event.changes.forEach { it.consume() }
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
                    .apply {
                        if (sharpeningAmount > 0) {
                            transformations(SharpenTransformation(sharpeningAmount))
                        }
                    }
                    .allowHardware(false)
                    .build()
            }



            if (firstImageUrl != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = buildRequest(firstImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd,
                    filterQuality = if (sharpeningAmount > 0) FilterQuality.High else FilterQuality.Medium,
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
                    alignment = Alignment.CenterStart,
                    filterQuality = if (sharpeningAmount > 0) FilterQuality.High else FilterQuality.Medium,
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
