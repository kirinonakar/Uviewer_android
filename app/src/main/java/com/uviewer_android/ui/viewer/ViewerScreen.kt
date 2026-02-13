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
    onBack: () -> Unit = {},
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {}
) {
    val type = try {
        fileType?.let { FileEntry.FileType.valueOf(it) } ?: FileEntry.FileType.UNKNOWN
    } catch (e: IllegalArgumentException) {
        FileEntry.FileType.UNKNOWN
    }

    when (type) {
        FileEntry.FileType.IMAGE, FileEntry.FileType.ZIP, FileEntry.FileType.WEBP -> ImageViewerScreen(filePath, isWebDav, serverId, onBack = onBack, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen)
        FileEntry.FileType.TEXT, FileEntry.FileType.EPUB, FileEntry.FileType.HTML, FileEntry.FileType.CSV -> DocumentViewerScreen(filePath, type, isWebDav, serverId, onBack = onBack, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen)
        FileEntry.FileType.AUDIO, FileEntry.FileType.VIDEO -> MediaPlayerScreen(filePath, type, isWebDav, serverId, onBack = onBack, isFullScreen = isFullScreen, onToggleFullScreen = onToggleFullScreen)
        FileEntry.FileType.PDF -> {
             PdfViewerScreen(
                 filePath = filePath, 
                 isWebDav = isWebDav, 
                 serverId = serverId, 
                 onBack = onBack, 
                 isFullScreen = isFullScreen, 
                 onToggleFullScreen = onToggleFullScreen
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
