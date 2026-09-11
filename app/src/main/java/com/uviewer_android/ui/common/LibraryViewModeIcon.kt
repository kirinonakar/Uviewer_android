package com.uviewer_android.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.uviewer_android.R
import com.uviewer_android.data.model.LibraryViewMode

@Composable
fun LibraryViewModeIcon(mode: LibraryViewMode) {
    val (icon, description) = when (mode.next()) {
        LibraryViewMode.GRID_2 -> Icons.Default.GridView to R.string.switch_to_grid_2
        LibraryViewMode.GRID_4 -> Icons.Default.ViewModule to R.string.switch_to_grid_4
        LibraryViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList to R.string.switch_to_list
    }
    Icon(icon, contentDescription = stringResource(description))
}
