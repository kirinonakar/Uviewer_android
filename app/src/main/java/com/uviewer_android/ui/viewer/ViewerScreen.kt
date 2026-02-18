package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uviewer_android.data.model.FileEntry

@Composable
fun ViewerScreen(
    filePath: String, 
    fileType: String?, 
    isWebDav: Boolean, 
    serverId: Int?,
    initialPosition: Int? = null,
    onBack: () -> Unit = {},
    onNavigateToNext: () -> Unit = {},
    onNavigateToPrev: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    activity: com.uviewer_android.MainActivity? = null
) {
    val type = try {
        fileType?.let { FileEntry.FileType.valueOf(it.uppercase()) } ?: FileEntry.FileType.UNKNOWN
    } catch (e: Exception) {
        FileEntry.FileType.UNKNOWN
    }
    
    val resolvedType = if (type == FileEntry.FileType.UNKNOWN) {
        val ext = filePath.substringAfterLast(".", "").lowercase()
        when (ext) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif" -> FileEntry.FileType.IMAGE
            "zip", "cbz", "rar", "7z" -> FileEntry.FileType.ZIP
            "txt", "md", "csv", "log", "aozora" -> FileEntry.FileType.TEXT
            "epub" -> FileEntry.FileType.EPUB
            "pdf" -> FileEntry.FileType.PDF
            "mp3", "wav", "m4a", "flac" -> FileEntry.FileType.AUDIO
            "mp4", "mkv", "avi", "webm" -> FileEntry.FileType.VIDEO
            "html", "htm" -> FileEntry.FileType.HTML
            else -> FileEntry.FileType.UNKNOWN
        }
    } else type
    
    // Convert initialPosition to index or line
    // For images, it's 0-based. For documents, it's 1-based (line).

    when (resolvedType) {
        FileEntry.FileType.IMAGE, FileEntry.FileType.ZIP, FileEntry.FileType.WEBP, FileEntry.FileType.IMAGE_ZIP -> {
            ImageViewerScreen(filePath, isWebDav, serverId, initialIndex = initialPosition, onBack = onBack, onNavigateToNext = onNavigateToNext, onNavigateToPrev = onNavigateToPrev, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen, activity = activity)
        }
        FileEntry.FileType.TEXT, FileEntry.FileType.EPUB, FileEntry.FileType.HTML, FileEntry.FileType.CSV -> {
            DocumentViewerScreen(filePath, resolvedType, isWebDav, serverId, initialLine = initialPosition, onBack = onBack, onNavigateToNext = onNavigateToNext, onNavigateToPrev = onNavigateToPrev, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen, activity = activity)
        }
        FileEntry.FileType.AUDIO, FileEntry.FileType.VIDEO -> MediaPlayerScreen(filePath, resolvedType, isWebDav, serverId, onBack = onBack, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen, activity = activity)
        FileEntry.FileType.PDF -> {
             PdfViewerScreen(
                 filePath = filePath, 
                 isWebDav = isWebDav, 
                 serverId = serverId, 
                 initialPage = initialPosition,
                 onBack = onBack, 
                 isFullScreen = isFullScreen, 
                 onToggleFullScreen = onToggleFullScreen,
                 activity = activity
             )
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.unsupported_type_fmt, fileType ?: "", filePath))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
