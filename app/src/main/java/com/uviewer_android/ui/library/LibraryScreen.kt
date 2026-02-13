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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.LocationOn
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
    // LaunchedEffect(selectedTab) { // Removed as it was causing redundant loads and issues
    //     val isWebDav = selectedTab == 1
    //     if (uiState.isWebDavTab != isWebDav) {
    //          viewModel.loadInitialPath(isWebDav)
    //     }
    // }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        uiState.currentPath,
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    if (uiState.currentPath != rootPath && uiState.currentPath != "WebDAV") {
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
                    onClick = { 
                        if (selectedTab != 0) {
                            selectedTab = 0
                            viewModel.loadInitialPath(false)
                        }
                    },
                    text = { Text(stringResource(R.string.local)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { 
                        if (selectedTab != 1) {
                            selectedTab = 1
                            viewModel.loadInitialPath(true)
                        }
                    },
                    text = { Text(stringResource(R.string.remote)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { 
                        selectedTab = 2
                        // No need to load path, just show favorites from state
                    },
                    text = { Text("Pin") } 
                )
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val listToShow = if (selectedTab == 2) uiState.pinnedFiles else uiState.fileList
                LazyColumn {
                    items(listToShow) { file ->
                        val isFavorite = uiState.favoritePaths.contains(file.path)
                        FileItemRow(
                            file = file,
                            isFavorite = isFavorite,
                            onToggleFavorite = { viewModel.toggleFavorite(file) },
                            onTogglePin = { viewModel.togglePin(file) },
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
    onTogglePin: () -> Unit,
    onClick: () -> Unit
) {
    // Check if ThumbUp is available as "Pin" proxy or use text "Pin"
    // Icons.Filled.PushPin is likely not in default material-icons-core.
    // I will use Icons.Filled.ThumbUp as a visual distinction for now, or Icons.Filled.CheckCircle
    // Actually, let's use Icons.Filled.Lock for "Fixed/Pinned" concept?
    // Or better, just text "Pin" if icon is unsure.
    // But I'll assume Icons.Filled.Star is Favorite.
    // Let's use Icons.Filled.KeyboardDoubleArrowUp for "Pin to top"?
    // I'll stick to a standard icon available. Icons.Filled.Info?
    // Let's use Icons.Filled.LocationOn (Pin-like).

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null
            )
        },
        headlineContent = { 
            Text(
                file.name, 
                maxLines = 2, 
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            ) 
        },
        supportingContent = {
            if (!file.isDirectory) {
                Text(
                    com.uviewer_android.data.repository.FileRepository.formatFileSize(file.size),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (file.isPinned) androidx.compose.material.icons.Icons.Filled.LocationOn else androidx.compose.material.icons.Icons.Outlined.LocationOn,
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
    )
}
