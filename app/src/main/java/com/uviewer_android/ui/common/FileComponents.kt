package com.uviewer_android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.R

@Composable
fun FileItemRow(
    file: FileEntry,
    isFavorite: Boolean,
    isPinnedTab: Boolean = false,
    isRemoteTab: Boolean = false,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onClick: () -> Unit
) {
    val typography = MaterialTheme.typography
    var textStyle by remember { mutableStateOf(typography.bodyLarge) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            val icon = when {
                file.path.startsWith("server:") -> Icons.Default.Dns
                file.type == FileEntry.FileType.FOLDER -> Icons.Filled.Folder
                file.type == FileEntry.FileType.ZIP || file.type == FileEntry.FileType.RAR || file.type == FileEntry.FileType.SEVEN_ZIP -> Icons.Filled.Archive
                file.type == FileEntry.FileType.IMAGE -> Icons.Filled.Image
                file.type == FileEntry.FileType.AUDIO -> Icons.Filled.MusicNote
                file.type == FileEntry.FileType.VIDEO -> Icons.Filled.Movie
                file.type == FileEntry.FileType.PDF || file.type == FileEntry.FileType.EPUB -> Icons.Filled.Book
                else -> Icons.Filled.Description
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (file.path.startsWith("server:")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.isWebDav && (file.isPinned || isPinnedTab || isFavorite) && !isRemoteTab) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "WebDAV",
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    file.name, 
                    maxLines = Int.MAX_VALUE, 
                    style = textStyle,
                    onTextLayout = { if (it.lineCount >= 3) textStyle = typography.bodyMedium }
                ) 
            }
        },
        supportingContent = {
            Column {
                val progressText = when (file.type) {
                    FileEntry.FileType.EPUB -> {
                        if (file.position > 0) {
                            val chapter = (file.position / 1000000) + 1
                            val line = file.position % 1000000
                            "Chapter $chapter, Line $line"
                        } else null
                    }
                    FileEntry.FileType.PDF -> if (file.position >= 0) "Page ${file.position + 1}" else null
                    FileEntry.FileType.TEXT, FileEntry.FileType.HTML -> if (file.position > 0) "Line ${file.position}" else null
                    FileEntry.FileType.ZIP -> {
                        if (!file.positionTitle.isNullOrEmpty()) {
                            file.positionTitle
                        } else if (file.position >= 0) {
                            "Page ${file.position + 1}"
                        } else null
                    }
                    else -> null
                }
                if (progressText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(text = progressText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(text = "${(file.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    LinearProgressIndicator(
                        progress = { file.progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp).padding(vertical = 2.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                if (isPinnedTab) {
                    val parentPath = if (file.path.startsWith("server:")) "" else {
                        try { java.io.File(file.path).parent ?: "" } catch (e: Exception) { "" }
                    }
                    if (parentPath.isNotEmpty()) {
                        Text(
                            parentPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (!file.isDirectory) {
                    Text(
                        com.uviewer_android.data.repository.FileRepository.formatFileSize(file.size),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        trailingContent = {
            if (isPinnedTab) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Unpin",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (file.isPinned) Icons.Filled.LocationOn else Icons.Outlined.LocationOn,
                            contentDescription = if (file.isPinned) "Unpin" else "Pin",
                            tint = if (file.isPinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun FileItemGridCard(
    file: FileEntry,
    isFavorite: Boolean,
    isPinnedTab: Boolean = false,
    isRemoteTab: Boolean = false,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onClick: () -> Unit
) {
    val typography = MaterialTheme.typography
    var textStyle by remember { mutableStateOf(typography.labelMedium) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val isZip = file.type == FileEntry.FileType.ZIP || file.type == FileEntry.FileType.IMAGE_ZIP
                if ((file.type == FileEntry.FileType.IMAGE || isZip) && !file.isWebDav) {
                    AsyncImage(
                        model = java.io.File(file.path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when {
                        file.path.startsWith("server:") -> Icons.Default.Dns
                        file.isDirectory -> Icons.Filled.Folder
                        file.type == FileEntry.FileType.ZIP || file.type == FileEntry.FileType.RAR || file.type == FileEntry.FileType.SEVEN_ZIP -> Icons.Filled.Archive
                        file.type == FileEntry.FileType.IMAGE -> Icons.Filled.Image
                        file.type == FileEntry.FileType.AUDIO -> Icons.Filled.MusicNote
                        file.type == FileEntry.FileType.VIDEO -> Icons.Filled.Movie
                        file.type == FileEntry.FileType.PDF || file.type == FileEntry.FileType.EPUB -> Icons.Filled.Book
                        else -> Icons.Filled.Description
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (file.path.startsWith("server:")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Overlay for Pin/Favorite
                Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    Row(modifier = Modifier.align(Alignment.TopEnd)) {
                        if (file.isPinned) {
                            Icon(
                                if (isPinnedTab) Icons.Default.Delete else Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(if (isPinnedTab) 20.dp else 16.dp).clickable { if (isPinnedTab) onTogglePin() },
                                tint = if (isPinnedTab) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (isFavorite && !isPinnedTab) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (file.isWebDav && (file.isPinned || isPinnedTab || isFavorite) && !isRemoteTab) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "WebDAV",
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        file.name,
                        style = textStyle,
                        maxLines = Int.MAX_VALUE,
                        textAlign = TextAlign.Center,
                        onTextLayout = { if (it.lineCount >= 3) textStyle = typography.labelSmall }
                    )
                }
                val progressText = when (file.type) {
                    FileEntry.FileType.EPUB -> {
                        if (file.position > 0) {
                            val chapter = (file.position / 1000000) + 1
                            val line = file.position % 1000000
                            "ch$chapter L$line"
                        } else null
                    }
                    FileEntry.FileType.PDF -> if (file.position >= 0) "P${file.position + 1}" else null
                    FileEntry.FileType.TEXT, FileEntry.FileType.HTML -> if (file.position > 0) "L${file.position}" else null
                    FileEntry.FileType.ZIP -> {
                        if (!file.positionTitle.isNullOrEmpty()) {
                            file.positionTitle
                        } else if (file.position >= 0) {
                            "P${file.position + 1}"
                        } else null
                    }
                    else -> null
                }
                if (progressText != null) {
                    Text(
                        text = "$progressText (${(file.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        progress = { file.progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                if (isPinnedTab) {
                    val parentPath = if (file.path.startsWith("server:")) "" else {
                        try { java.io.File(file.path).parent ?: "" } catch (e: Exception) { "" }
                    }
                    if (parentPath.isNotEmpty()) {
                        Text(
                            parentPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (!file.isDirectory) {
                    Text(
                        com.uviewer_android.data.repository.FileRepository.formatFileSize(file.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
