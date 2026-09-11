package com.uviewer_android.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Precision

/** A small static preview exists only while the original request is loading. */
@Composable
internal fun ImageLoadingPreview(
    originalRequest: ImageRequest,
    enabled: Boolean,
    isSplit: Boolean = false,
    isRight: Boolean = false,
    alignment: Alignment = Alignment.Center
) {
    // Archive fetchers do not implement Coil's network policy; avoid downloading entries twice.
    val scheme = (originalRequest.data as? android.net.Uri)?.scheme
    if (!enabled || scheme == "webdav-zip" || scheme == "webdav-7z") {
        ImageLoadingIndicator()
        return
    }

    val previewRequest = remember(originalRequest) {
        originalRequest.newBuilder()
            .size(768)
            .precision(Precision.INEXACT)
            .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
            .transformations(emptyList())
            // Remote previews may use cached bytes, but must not compete with the original download.
            .networkCachePolicy(CachePolicy.DISABLED)
            // Decode a static frame, including for animated sources. The original owns playback.
            .decoderFactory(BitmapFactoryDecoder.Factory())
            .crossfade(false)
            .build()
    }
    SubcomposeAsyncImage(
        model = previewRequest,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        loading = { ImageLoadingIndicator() },
        error = { ImageLoadingIndicator() },
        success = { state ->
            ViewerImageContent(state.painter, isSplit, isRight, alignment)
        }
    )
}

@Composable
private fun ImageLoadingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

/** Use identical geometry for the preview and original, especially for split pages. */
@Composable
internal fun ViewerImageContent(
    painter: Painter,
    isSplit: Boolean = false,
    isRight: Boolean = false,
    alignment: Alignment = Alignment.Center
) {
    if (!isSplit) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alignment = alignment
        )
        return
    }

    val srcSize = painter.intrinsicSize
    if (srcSize.width > 0 && srcSize.height > 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Layout(content = {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }) { measurables, constraints ->
                val aspectRatio = (srcSize.width / 2f) / srcSize.height
                val width: Int
                val height: Int
                if (constraints.maxWidth / aspectRatio <= constraints.maxHeight) {
                    width = constraints.maxWidth
                    height = (width / aspectRatio).toInt()
                } else {
                    height = constraints.maxHeight
                    width = (height * aspectRatio).toInt()
                }
                val image = measurables[0].measure(Constraints.fixed(width * 2, height))
                layout(width, height) {
                    image.place(if (isRight) -width else 0, 0)
                }
            }
        }
    }
}
