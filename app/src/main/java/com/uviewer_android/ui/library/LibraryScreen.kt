package com.uviewer_android.ui.library

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider

import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToViewer: (FileEntry) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.currentPath) },
                navigationIcon = {
                        if (uiState.currentPath != "/") {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                items(uiState.fileList) { file ->
                    val isFavorite = uiState.favoritePaths.contains(file.path)
                    FileItemRow(
                        file = file, 
                        isFavorite = isFavorite,
                        onToggleFavorite = { viewModel.toggleFavorite(file) },
                        onClick = {
                            if (file.isDirectory) {
                                viewModel.navigateTo(file)
                            } else {
                                onNavigateToViewer(file)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FileItemRow(
    file: FileEntry, 
    isFavorite: Boolean, 
    onToggleFavorite: () -> Unit, 
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null
            )
        },
        headlineContent = { Text(file.name) },
        supportingContent = {
            if (!file.isDirectory) {
                Text(file.size.toString()) // Format size properly later
            }
        },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
