package com.uviewer_android.ui.favorites

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.R
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.AppViewModelProvider
import com.uviewer_android.ui.common.FileItemRow
import com.uviewer_android.ui.common.FileItemGridCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onNavigateToViewer: (FileEntry) -> Unit,
    viewModel: FavoritesViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val favorites by viewModel.favorites.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_folders), 
        stringResource(R.string.tab_files),
        stringResource(R.string.pinned)
    )

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    
    // Drag and Drop state
    var draggedItemPath by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var currentItemIndex by remember { mutableStateOf<Int?>(null) }
    var initialNodeOffset by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 0.dp
                ) {
                    TopAppBar(
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                        ),
                        title = { Text(stringResource(R.string.title_favorites), style = MaterialTheme.typography.titleMedium) },
                        actions = {
                            IconButton(onClick = { viewModel.toggleViewMode() }) {
                                Icon(
                                    if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.ViewModule,
                                    contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View"
                                )
                            }
                        }
                    )
                }
                
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp
                ) {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        divider = {},
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier
                                    .tabIndicatorOffset(selectedTabIndex)
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { 
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                    ) 
                                },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val filteredFavorites: List<FileEntry> = when (selectedTabIndex) {
            0 -> favorites.filter { it.isDirectory }
            1 -> favorites.filter { !it.isDirectory }
            else -> favorites.filter { it.isPinned }.sortedBy { it.pinOrder }
        }

        if (filteredFavorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                val emptyText = if (selectedTabIndex == 2) stringResource(R.string.no_pinned_files) else stringResource(R.string.no_favorites)
                Text(emptyText)
            }
        } else {
            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    state = gridState,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    gridItemsIndexed(filteredFavorites, key = { _, item -> item.path }) { index, item ->
                        val isDragging = draggedItemPath == item.path
                        val isPinnedTab = selectedTabIndex == 2
                        
                        val itemModifier = if (isPinnedTab) {
                            Modifier
                                .zIndex(if (isDragging) 10f else 0f)
                                .pointerInput(item.path) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { 
                                            draggedItemPath = item.path
                                            val info = gridState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                            initialNodeOffset = info?.offset?.y ?: 0
                                            currentItemIndex = info?.index ?: index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            
                                            val info = gridState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                            if (info != null && info.index == currentItemIndex) {
                                                val visualCenterY = initialNodeOffset + dragOffsetY + info.size.height / 2
                                                val targetItem = gridState.layoutInfo.visibleItemsInfo.find { 
                                                    visualCenterY.toInt() in it.offset.y..(it.offset.y + it.size.height)
                                                }
                                                if (targetItem != null && targetItem.key != item.path) {
                                                    currentItemIndex = targetItem.index
                                                    viewModel.reorderPinnedFavorites(info.index, targetItem.index)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedItemPath = null
                                            dragOffsetY = 0f
                                            currentItemIndex = null
                                        },
                                        onDragCancel = {
                                            draggedItemPath = null
                                            dragOffsetY = 0f
                                            currentItemIndex = null
                                        }
                                    )
                                }
                                .offset {
                                    if (isDragging) {
                                        val currentInfo = gridState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                        val adjY = if (currentInfo != null) initialNodeOffset - currentInfo.offset.y else 0
                                        IntOffset(0, (dragOffsetY + adjY).toInt())
                                    } else IntOffset.Zero
                                }
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        shadowElevation = 8f
                                    }
                                }
                        } else Modifier

                        Box(modifier = itemModifier) {
                            FileItemGridCard(
                                file = item,
                                isFavorite = true,
                                isPinnedTab = isPinnedTab,
                                onClick = { onNavigateToViewer(item) },
                                onToggleFavorite = { viewModel.deleteFavorite(item) },
                                onTogglePin = { viewModel.togglePin(item) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    itemsIndexed(filteredFavorites, key = { _, item -> item.path }) { index, item ->
                        val isDragging = draggedItemPath == item.path
                        val isPinnedTab = selectedTabIndex == 2
                        
                        val itemModifier = if (isPinnedTab) {
                            Modifier
                                .zIndex(if (isDragging) 10f else 0f)
                                .pointerInput(item.path) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { 
                                            draggedItemPath = item.path
                                            val info = listState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                            initialNodeOffset = info?.offset ?: 0
                                            currentItemIndex = info?.index ?: index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            
                                            val info = listState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                            if (info != null && info.index == currentItemIndex) {
                                                val visualCenterY = initialNodeOffset + dragOffsetY + info.size / 2
                                                val targetItem = listState.layoutInfo.visibleItemsInfo.find { 
                                                    visualCenterY.toInt() in it.offset..(it.offset + it.size)
                                                }
                                                if (targetItem != null && targetItem.key != item.path) {
                                                    currentItemIndex = targetItem.index
                                                    viewModel.reorderPinnedFavorites(info.index, targetItem.index)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedItemPath = null
                                            dragOffsetY = 0f
                                            currentItemIndex = null
                                        },
                                        onDragCancel = {
                                            draggedItemPath = null
                                            dragOffsetY = 0f
                                            currentItemIndex = null
                                        }
                                    )
                                }
                                .offset {
                                    if (isDragging) {
                                        val currentInfo = listState.layoutInfo.visibleItemsInfo.find { it.key == item.path }
                                        val adjY = if (currentInfo != null) initialNodeOffset - currentInfo.offset else 0
                                        IntOffset(0, (dragOffsetY + adjY).toInt())
                                    } else IntOffset.Zero
                                }
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        shadowElevation = 8f
                                    }
                                }
                        } else Modifier

                        Box(modifier = itemModifier) {
                            FileItemRow(
                                file = item,
                                isFavorite = true,
                                isPinnedTab = isPinnedTab,
                                onClick = { onNavigateToViewer(item) },
                                onToggleFavorite = { viewModel.deleteFavorite(item) },
                                onTogglePin = { viewModel.togglePin(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}
