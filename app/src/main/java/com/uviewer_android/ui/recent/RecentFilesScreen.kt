package com.uviewer_android.ui.recent

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
        }
    }
}

@Composable
fun RecentFileItemRow(
    file: RecentFile,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    var useSmallFont by remember { mutableStateOf(false) }
    val textStyle = (if (useSmallFont) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
        .copy(fontWeight = FontWeight.Normal)
    
    ListItem(
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.isWebDav) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "WebDAV",
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = file.title,
                    style = textStyle,
                    onTextLayout = { textLayoutResult ->
                        if (textLayoutResult.lineCount >= 3) {
                            useSmallFont = true
                        }
                    }
                )
            }
        },
        supportingContent = {
            Column {
                val progressText = when (file.type) {
                    "EPUB" -> {
                        val chapter = (file.pageIndex / 1000000) + 1
                        val line = file.pageIndex % 1000000
                        "Chapter $chapter, Line $line"
                    }
                    "PDF" -> "Page ${file.pageIndex + 1}"
                    "TEXT", "DOCUMENT", "HTML" -> "Line ${file.pageIndex}"
                    "ZIP" -> {
                        if (!file.positionTitle.isNullOrEmpty()) {
                            file.positionTitle
                        } else {
                            "Page ${file.pageIndex + 1}"
                        }
                    }
                    else -> null
                }
                if (progressText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(text = progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(text = "${(file.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    LinearProgressIndicator(
                        progress = file.progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(vertical = 4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Text(stringResource(R.string.last_accessed, dateFormat.format(Date(file.lastAccessed))))
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
