package com.uviewer_android.data.utils

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.model.SortOption

internal object FileEntrySort {
    private val naturalName = Comparator<FileEntry> { a, b ->
        val nameOrder = NaturalPathComparator.compare(a.name, b.name)
        if (nameOrder != 0) nameOrder else NaturalPathComparator.compare(a.path, b.path)
    }

    fun comparator(
        option: SortOption,
        directoriesFirst: Boolean = true,
        nameComparator: Comparator<FileEntry> = naturalName
    ): Comparator<FileEntry> = Comparator { a, b ->
        val directoryOrder = if (directoriesFirst) b.isDirectory.compareTo(a.isDirectory) else 0
        if (directoryOrder != 0) return@Comparator directoryOrder
        val order = when (option) {
            SortOption.NAME -> 0
            SortOption.DATE_ASC, SortOption.DATE_DESC -> {
                // Unknown timestamps always follow dated entries in either direction.
                val missingOrder = (a.lastModified <= 0L).compareTo(b.lastModified <= 0L)
                if (missingOrder != 0) missingOrder
                else if (a.lastModified <= 0L) 0
                else if (option == SortOption.DATE_ASC) a.lastModified.compareTo(b.lastModified)
                else b.lastModified.compareTo(a.lastModified)
            }
            SortOption.SIZE_ASC -> a.size.compareTo(b.size)
            SortOption.SIZE_DESC -> b.size.compareTo(a.size)
        }
        if (order != 0) order else nameComparator.compare(a, b)
    }
}
