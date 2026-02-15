package com.uviewer_android.ui.recent

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.RecentFile
import com.uviewer_android.ui.AppViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    onNavigateToViewer: (RecentFile) -> Unit,
    viewModel: RecentFilesViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val recentFiles by viewModel.recentFiles.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_recent_files)) },
                actions = {
                    if (recentFiles.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAll() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear All")
                        }
                    }
                    val viewMode by viewModel.viewMode.collectAsState()
                    IconButton(onClick = { viewModel.toggleViewMode() }) {
                        Icon(
                            imageVector = if (viewMode == 0) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
                            contentDescription = if (viewMode == 0) stringResource(R.string.grid_view) else stringResource(R.string.list_view)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (recentFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_recent_files))
            }
        } else {
            val viewMode by viewModel.viewMode.collectAsState()
            if (viewMode == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    item { HorizontalDivider() }
                    items(recentFiles) { file ->
                        RecentFileItemRow(
                            file = file,
                            onClick = { onNavigateToViewer(file) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(top = 4.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentFiles) { file ->
                        RecentFileThumbnailItem(
                            file = file,
                            onClick = { onNavigateToViewer(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentFileItemRow(
    file: RecentFile,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    ListItem(
        headlineContent = { Text(file.title) },
        supportingContent = { Text(stringResource(R.string.last_accessed, dateFormat.format(Date(file.lastAccessed)))) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun RecentFileThumbnailItem(
    file: RecentFile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                // Determine file type from extension for icon
                val extension = file.path.substringAfterLast('.', "").lowercase()
                val isImage = extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
                
                if (isImage && !file.isWebDav) {
                    androidx.compose.foundation.Image(
                        painter = coil.compose.rememberAsyncImagePainter(file.path),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    val icon = when {
                        extension in listOf("jpg", "jpeg", "png", "gif", "webp") -> Icons.Filled.Image
                        extension in listOf("zip", "rar", "cbz") -> Icons.Filled.Archive
                        extension in listOf("pdf", "epub") -> Icons.Filled.Book
                        extension in listOf("mp3", "wav", "flac") -> Icons.Filled.MusicNote
                        extension in listOf("mp4", "mkv", "avi") -> Icons.Filled.Movie
                        else -> Icons.Filled.Description
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            Text(
                file.title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                dateFormat.format(Date(file.lastAccessed)),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp).fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
