package com.uviewer_android.ui.viewer

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.uviewer_android.data.model.FileEntry

@Composable
fun ViewerScreen(
    filePath: String, 
    fileType: String?, 
    isWebDav: Boolean, 
    serverId: Int?,
    onBack: () -> Unit = {} // Add callback
) {
    val type = try {
        fileType?.let { FileEntry.FileType.valueOf(it) } ?: FileEntry.FileType.UNKNOWN
    } catch (e: IllegalArgumentException) {
        FileEntry.FileType.UNKNOWN
    }

    when (type) {
        FileEntry.FileType.IMAGE -> ImageViewerScreen(filePath, isWebDav, serverId, onBack = onBack)
        FileEntry.FileType.TEXT, FileEntry.FileType.EPUB -> DocumentViewerScreen(filePath, type, isWebDav, serverId, onBack = onBack)
        FileEntry.FileType.AUDIO, FileEntry.FileType.VIDEO -> MediaPlayerScreen(filePath, isWebDav, serverId, onBack = onBack)
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.unsupported_type_fmt, fileType ?: "", filePath))
            }
        }
    }
}
