package com.uviewer_android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    
    // Unified tab selection logic
    var selectedTab by remember { mutableIntStateOf(0) }
    var isInitialized by remember { mutableStateOf(false) }

    // Sync UI with ViewModel state
    LaunchedEffect(uiState.isWebDavTab) {
        if (!isInitialized) {
            selectedTab = if (uiState.isWebDavTab) 1 else 0
            isInitialized = true
        }
    }

    // Secondary sync: if ViewModel says we are on WebDAV but UI is on Local, something is wrong
    LaunchedEffect(uiState.isWebDavTab) {
        if (uiState.isWebDavTab && selectedTab == 0) {
            selectedTab = 1
        } else if (!uiState.isWebDavTab && selectedTab == 1) {
            selectedTab = 0
        }
    }

    var backPressedTime by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

    val localListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val remoteListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pinListState = androidx.compose.foundation.lazy.rememberLazyListState()

    val currentListState = when (selectedTab) {
        0 -> localListState
        1 -> remoteListState
        else -> pinListState
    }

    var showAddServerDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (uiState.currentPath != rootPath && uiState.currentPath != "WebDAV") {
            viewModel.navigateUp()
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - backPressedTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                backPressedTime = currentTime
                Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle initial navigation (e.g. from Favorites)
    LaunchedEffect(initialPath, initialServerId) {
        if (!initialPath.isNullOrEmpty()) {
            viewModel.openFolder(initialPath, initialServerId)
            selectedTab = if (initialServerId != null && initialServerId != -1) 1 else 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    val title = if (uiState.currentPath == rootPath) stringResource(R.string.nav_library)
                               else if (uiState.currentPath == "WebDAV") stringResource(R.string.remote)
                               else uiState.currentPath
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1) 
                },
                navigationIcon = {
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
        floatingActionButton = {
            // Show Add Server FAB if on Remote tab and at the root or server list
            val isRemoteRoot = selectedTab == 1 && (uiState.serverId == null || uiState.currentPath == "WebDAV" || uiState.currentPath == "/")
            if (isRemoteRoot) {
                FloatingActionButton(onClick = { showAddServerDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Server")
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            HorizontalDivider()
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
                
                if (listToShow.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (selectedTab == 1 && uiState.serverId == null) "No Servers Added" 
                                else if (selectedTab == 2) "No Pinned Files"
                                else "No Files Found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selectedTab == 1 && uiState.serverId == null) {
                                Text(
                                    "Click + to add a WebDAV server", 
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(state = currentListState, modifier = Modifier.fillMaxSize()) {
                        items(listToShow) { file ->
                            val isFavorite = uiState.favoritePaths.contains(file.path)
                            FileItemRow(
                                file = file,
                                isFavorite = isFavorite,
                                onToggleFavorite = { viewModel.toggleFavorite(file) },
                                onTogglePin = { viewModel.togglePin(file) },
                                onClick = {
                                    if (file.isDirectory) {
                                        if (file.path.startsWith("server:")) {
                                            // When clicking a server from the list, ALWAYS start at root "/"
                                            viewModel.openFolder("/", file.serverId)
                                        } else {
                                            viewModel.navigateTo(file)
                                        }
                                        if (selectedTab == 2) {
                                            selectedTab = if (file.isWebDav) 1 else 0
                                        }
                                    } else {
                                        onNavigateToViewer(file)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (showAddServerDialog) {
                var name by remember { mutableStateOf("") }
                var host by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("") }
                var useHttps by remember { mutableStateOf(false) }
                var user by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }

                AlertDialog(
                    onDismissRequest = { showAddServerDialog = false },
                    title = { Text("Add WebDAV Server") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. My Cloud)") }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Use HTTPS", modifier = Modifier.weight(1f))
                                Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                            }
                            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host (e.g. example.com/dav)") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = port, 
                                onValueChange = { port = it }, 
                                label = { Text("Port (Leave empty for default)") }, 
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = pass, 
                                onValueChange = { pass = it }, 
                                label = { Text("Password") }, 
                                modifier = Modifier.fillMaxWidth(), 
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (name.isNotEmpty() && host.isNotEmpty()) {
                                val protocol = if (useHttps) "https://" else "http://"
                                val portPart = if (port.isNotEmpty()) ":$port" else ""
                                val cleanHost = host.removePrefix("http://").removePrefix("https://").trim('/')
                                val finalUrl = "$protocol$cleanHost$portPart"
                                viewModel.addServer(name, finalUrl, user, pass)
                                showAddServerDialog = false
                            }
                        }) { Text("Add") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddServerDialog = false }) { Text("Cancel") }
                    }
                )
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
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            val icon = when {
                file.path.startsWith("server:") -> Icons.Default.Dns
                file.type == FileEntry.FileType.FOLDER -> Icons.Filled.Folder
                file.type == FileEntry.FileType.ZIP -> Icons.Filled.Archive
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
    )
}
