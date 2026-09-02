package com.uviewer_android.ui.library

import com.uviewer_android.data.model.FileEntry

/** Returns entries whose displayed name contains [query], ignoring letter case. */
fun filterFilesByName(entries: List<FileEntry>, query: String): List<FileEntry> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return entries

    return entries.filter { entry ->
        entry.name.contains(normalizedQuery, ignoreCase = true)
    }
}
