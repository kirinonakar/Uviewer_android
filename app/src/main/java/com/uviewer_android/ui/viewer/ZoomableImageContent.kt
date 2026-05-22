package com.uviewer_android.ui.viewer

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
fun ZoomableImage(
    imageUrl: String,
    isWebDav: Boolean,
    authHeader: String?,
    serverUrl: String?,
    scale: Float,
    sharpeningAmount: Int,
    onScaleChanged: (Float) -> Unit,
    secondImageUrl: String? = null,
    isSplit: Boolean = false,
    isRight: Boolean = false
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
                Log.d("ImageViewer", "ZoomableImage: buildRequest: url=$url, isWebDav=$isWebDav")
                
                // Handle custom schemes (webdav-zip, waiting-file, webdav-7z)
                if (url.contains("://") || url.startsWith("waiting-file:")) {
                     return ImageRequest.Builder(context)
                        .data(android.net.Uri.parse(url))
                        .crossfade(true)
                        .apply {
                            val isAnimated = url.lowercase().let { it.endsWith(".webp") || it.endsWith(".gif") }
                            if (sharpeningAmount > 0 && !isAnimated) {
                                transformations(SharpenTransformation(sharpeningAmount))
                            }
                        }
                        .allowHardware(false)
                        .build()
                }

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
                        // Skip sharpening for animated formats (WebP, GIF) as transformations break animation playback
                        val isAnimated = url.lowercase().let { it.endsWith(".webp") || it.endsWith(".gif") }
                        if (sharpeningAmount > 0 && !isAnimated) {
                            transformations(SharpenTransformation(sharpeningAmount))
                        }
                    }
                    .allowHardware(false) // Disable hardware bitmaps to prevent potential black screen issues
                    .build()
            }
            


            val imageRequest = remember(imageUrl, isWebDav, authHeader, serverUrl, sharpeningAmount) {
                buildRequest(imageUrl)
            }

            coil.compose.SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = null,
                filterQuality = if (sharpeningAmount > 0) FilterQuality.High else FilterQuality.Medium,
                modifier = Modifier.fillMaxSize()
            ) {
                val state = painter.state
                when (state) {
                    is coil.compose.AsyncImagePainter.State.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                    is coil.compose.AsyncImagePainter.State.Error -> {
                        val errorMsg = state.result.throwable.message ?: "Unknown error"
                        Log.e("ImageViewer", "Failed to load image: $imageUrl", state.result.throwable)
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Image Error", color = Color.Red, style = MaterialTheme.typography.bodyMedium)
                                Text(imageUrl.substringAfterLast("/"), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Text(errorMsg, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                            }
                        }
                    }
                    is coil.compose.AsyncImagePainter.State.Success -> {
                        if (isSplit) {
                            val srcSize = state.painter.intrinsicSize
                            if (srcSize.width > 0 && srcSize.height > 0) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.ui.layout.Layout(
                                        content = {
                                            androidx.compose.foundation.Image(
                                                painter = state.painter,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
                                            )
                                        }
                                    ) { measurables, constraints ->
                                        val halfWidth = srcSize.width / 2f
                                        val ar = halfWidth / srcSize.height
                                        
                                        val width: Int
                                        val height: Int
                                        if (constraints.maxWidth / ar <= constraints.maxHeight) {
                                            width = constraints.maxWidth
                                            height = (width / ar).toInt()
                                        } else {
                                            height = constraints.maxHeight
                                            width = (height * ar).toInt()
                                        }
                                        
                                        val imagePlaceable = measurables[0].measure(
                                            androidx.compose.ui.unit.Constraints.fixed(width * 2, height)
                                        )
                                        
                                        layout(width, height) {
                                            val x = if (isRight) -width else 0
                                            imagePlaceable.place(x, 0)
                                        }
                                    }
                                }
                            }
                        } else {
                            androidx.compose.foundation.Image(
                                painter = state.painter,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    else -> {}
                }
            }
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
                Log.d("ImageViewer", "ZoomableDualImage: buildRequest: url=$url, isWebDav=$isWebDav")

                // Handle custom schemes (webdav-zip, waiting-file, webdav-7z)
                if (url.contains("://") || url.startsWith("waiting-file:")) {
                     return ImageRequest.Builder(context)
                        .data(android.net.Uri.parse(url))
                        .crossfade(true)
                        .apply {
                            val isAnimated = url.lowercase().let { it.endsWith(".webp") || it.endsWith(".gif") }
                            if (sharpeningAmount > 0 && !isAnimated) {
                                transformations(SharpenTransformation(sharpeningAmount))
                            }
                        }
                        .allowHardware(false)
                        .build()
                }
                
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
                        // Skip sharpening for animated formats (WebP, GIF) as transformations break animation playback
                        val isAnimated = url.lowercase().let { it.endsWith(".webp") || it.endsWith(".gif") }
                        if (sharpeningAmount > 0 && !isAnimated) {
                            transformations(SharpenTransformation(sharpeningAmount))
                        }
                    }
                    .allowHardware(false)
                    .build()
            }



            val firstImageRequest = remember(firstImageUrl, isWebDav, authHeader, serverUrl, sharpeningAmount) {
                firstImageUrl?.let { buildRequest(it) }
            }

            val secondImageRequest = remember(secondImageUrl, isWebDav, authHeader, serverUrl, sharpeningAmount) {
                secondImageUrl?.let { buildRequest(it) }
            }

            if (firstImageUrl != null && firstImageRequest != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = firstImageRequest,
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
            
            if (secondImageUrl != null && secondImageRequest != null) {
                coil.compose.SubcomposeAsyncImage(
                    model = secondImageRequest,
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
