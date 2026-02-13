package com.uviewer_android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.R
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToViewer: (FileEntry) -> Unit,
    initialPath: String? = null,
    initialServerId: Int? = null,
    viewModel: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(if (initialServerId != null && initialServerId != -1) 1 else 0) }

    // Handle initial navigation (e.g. from Favorites)
    LaunchedEffect(initialPath, initialServerId) {
        if (!initialPath.isNullOrEmpty()) {
            viewModel.openFolder(initialPath, initialServerId)
            selectedTab = if (initialServerId != null && initialServerId != -1) 1 else 0
        }
    }

    // Handle tab switching
    LaunchedEffect(selectedTab) {
        // Only load root if the current state doesn't match the tab (to avoid overwriting initial navigation)
        // Check if we need to switch context
        val isWebDav = selectedTab == 1
        if (uiState.isWebDavTab != isWebDav) {
             viewModel.loadInitialPath(isWebDav)
        }
    }

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
                },
                actions = {
                    var sortExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { sortExpanded = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Name") },
                            onClick = { viewModel.setSortOption(SortOption.NAME); sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Date (Newest)") },
                            onClick = { viewModel.setSortOption(SortOption.DATE_DESC); sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Date (Oldest)") },
                            onClick = { viewModel.setSortOption(SortOption.DATE_ASC); sortExpanded = false }
                        )
                    }

                    IconButton(onClick = { viewModel.navigateToRoot() }) {
                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.go_to_root))
                    }
                }
            )
        },
        floatingActionButton = {}
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.local)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.remote))
                    }
                )
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
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
