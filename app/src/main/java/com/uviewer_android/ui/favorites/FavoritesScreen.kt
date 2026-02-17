package com.uviewer_android.ui.favorites

import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import com.uviewer_android.data.FavoriteItem
import com.uviewer_android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onNavigateToViewer: (FavoriteItem) -> Unit,
    viewModel: FavoritesViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val favorites by viewModel.favorites.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_favorites)) })
        }
    ) { innerPadding ->
        if (favorites.isEmpty()) {
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
                item { HorizontalDivider() }
                
                val folders = favorites.filter { it.type == "FOLDER" }
                val files = favorites.filter { it.type != "FOLDER" }
                
                if (folders.isNotEmpty()) {
                    item {
                        Text(
                            text = "Folders",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(folders) { item ->
                        FavoriteItemRow(
                            item = item,
                            onClick = { onNavigateToViewer(item) },
                            onDelete = { viewModel.deleteFavorite(item) }
                        )
                    }
                }
                
                if (folders.isNotEmpty() && files.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                }
                
                if (files.isNotEmpty()) {
                    item {
                        Text(
                            text = "Files",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(files) { item ->
                        FavoriteItemRow(
                            item = item,
                            onClick = { onNavigateToViewer(item) },
                            onDelete = { viewModel.deleteFavorite(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteItemRow(
    item: FavoriteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
