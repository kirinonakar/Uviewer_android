package com.uviewer_android.ui.favorites

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onNavigateToViewer: (FavoriteItem) -> Unit,
    viewModel: FavoritesViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val favorites by viewModel.favorites.collectAsState()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.tab_folders), stringResource(R.string.tab_files))

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.title_favorites)) })
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(text = title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val filteredFavorites = if (selectedTabIndex == 0) {
            favorites.filter { it.type == "FOLDER" }
        } else {
            favorites.filter { it.type != "FOLDER" }
        }

        if (filteredFavorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_favorites))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(filteredFavorites) { item ->
                    FavoriteItemRow(
                        item = item,
                        onClick = { onNavigateToViewer(item) },
                        onDelete = { viewModel.deleteFavorite(item) },
                        onTogglePin = { viewModel.togglePin(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun FavoriteItemRow(
    item: FavoriteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    var useSmallFont by remember { mutableStateOf(false) }
    val textStyle = (if (useSmallFont) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
        .copy(fontWeight = FontWeight.Normal)
    
    ListItem(
        headlineContent = { 
            Text(
                text = item.title,
                style = textStyle,
                onTextLayout = { textLayoutResult ->
                    if (textLayoutResult.lineCount >= 3) {
                        useSmallFont = true
                    }
                }
            )
        },
        supportingContent = { 
            val parentPath = try {
                java.io.File(item.path).parent ?: ""
            } catch (e: Exception) {
                item.path
            }
            Text(parentPath) 
        },
        trailingContent = {
            Row {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (item.isPinned) Icons.Filled.LocationOn else Icons.Outlined.LocationOn,
                        contentDescription = if (item.isPinned) "Unpin" else "Pin",
                        tint = if (item.isPinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
