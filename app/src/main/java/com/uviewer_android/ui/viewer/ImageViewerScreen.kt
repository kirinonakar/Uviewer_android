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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val scope = rememberCoroutineScope()

    // Load images on start
    LaunchedEffect(filePath) {
        viewModel.loadImages(filePath, isWebDav, serverId)
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (uiState.error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.error_fmt, uiState.error ?: ""))
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
                                val thirdOfScreen = size.width / 3
                                when {
                                    offset.x < thirdOfScreen -> {
                                        val current = pagerState.currentPage
                                        if (current > 0) {
                                            pagerState.animateScrollToPage(current - 1)
                                        }
                                    }
                                    offset.x > thirdOfScreen * 2 -> {
                                        val current = pagerState.currentPage
                                        if (current < pageCount - 1) {
                                            pagerState.animateScrollToPage(current + 1)
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
                // Calculate indices
                val firstIndex = if (isDualPage) page * 2 else page
                val secondIndex = if (isDualPage) page * 2 + 1 else -1
                
                if (firstIndex < uiState.images.size) {
                    val firstImage = uiState.images[firstIndex]
                    val secondImage = if (secondIndex < uiState.images.size && secondIndex != -1) uiState.images[secondIndex] else null
                    
                    ZoomableImage(
                        imageUrl = firstImage.path,
                        isWebDav = uiState.isContentLoadedFromWebDav,
                        authHeader = uiState.authHeader,
                        serverUrl = uiState.serverUrl,
                        scale = scales.getOrPut(page) { 1f },
                        onScaleChanged = { newScale -> scales[page] = newScale },
                        secondImageUrl = secondImage?.path
                    )
                }
            }

            // Top Bar
            if (!isFullScreen) {
                // Determine title
                val title = if (isDualPage) {
                     val p = pagerState.currentPage * 2
                     if (p + 1 < uiState.images.size) {
                         "${uiState.images[p].name} / ${uiState.images[p+1].name}"
                     } else {
                         uiState.images[p].name
                     }
                } else {
                    if (pagerState.currentPage < uiState.images.size) uiState.images[pagerState.currentPage].name else ""
                }
                
                TopAppBar(
                    title = { Text(title, color = Color.White, maxLines = 1) }, 
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { isDualPage = !isDualPage }) {
                            Icon(
                                if (isDualPage) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel, 
                                contentDescription = "Toggle Dual Page",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
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
    onScaleChanged: (Float) -> Unit,
    secondImageUrl: String? = null // For dual page
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    onScaleChanged(newScale)
                    if (newScale > 1f) {
                        val maxOffsetX = (size.width * (newScale - 1)) / 2
                        val maxOffsetY = (size.height * (newScale - 1)) / 2
                        offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
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

            AsyncImage(
                model = buildRequest(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            
            if (secondImageUrl != null) {
                Spacer(modifier = Modifier.width(4.dp))
                AsyncImage(
                    model = buildRequest(secondImageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}
