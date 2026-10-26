package com.uviewer_android

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.model.SortOption
import com.uviewer_android.data.utils.FileEntrySort
import org.junit.Assert.assertEquals
import org.junit.Test

class FileEntrySortTest {
    private fun remote(name: String, modified: Long = 0, directory: Boolean = false, path: String = "/$name") =
        FileEntry(name, path, directory, if (directory) FileEntry.FileType.FOLDER else FileEntry.FileType.IMAGE,
            modified, 12, isWebDav = true, serverId = 1)

    private fun sorted(entries: List<FileEntry>, option: SortOption) =
        entries.sortedWith(FileEntrySort.comparator(option)).map { it.name }

    @Test fun remoteFoldersAndFilesUseNaturalOrder() {
        val entries = listOf(remote("10.jpg"), remote("vol10", directory = true), remote("2.jpg"), remote("vol2", directory = true), remote("1.jpg"))
        assertEquals(listOf("vol2", "vol10", "1.jpg", "2.jpg", "10.jpg"), sorted(entries, SortOption.NAME))
    }

    @Test fun newestAndOldestSortByRemoteTimestampWithNaturalTiesAndUnknownDatesLast() {
        val entries = listOf(remote("20.jpg"), remote("10.jpg", 200), remote("2.jpg", 200), remote("1.jpg", 100))
        assertEquals(listOf("2.jpg", "10.jpg", "1.jpg", "20.jpg"), sorted(entries, SortOption.DATE_DESC))
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "20.jpg"), sorted(entries, SortOption.DATE_ASC))
    }

    @Test fun streamedSearchResultsAreResortedInsteadOfKeepingDiscoveryOrder() {
        val entries = listOf(remote("page10.jpg", 100, path = "/vol10/page10.jpg"), remote("page2.jpg", 300, path = "/vol2/page2.jpg"))
        assertEquals(listOf("page2.jpg", "page10.jpg"), sorted(entries, SortOption.NAME))
        assertEquals(listOf("page2.jpg", "page10.jpg"), sorted(entries, SortOption.DATE_DESC))
        assertEquals(listOf("page10.jpg", "page2.jpg"), sorted(entries, SortOption.DATE_ASC))
    }

    @Test fun folderDatesAreSortedWithinTheFolderGroupAndViewersCanDisableGrouping() {
        val entries = listOf(remote("file.jpg", 400), remote("vol10", 100, true), remote("vol2", 200, true))
        assertEquals(listOf("vol2", "vol10", "file.jpg"), sorted(entries, SortOption.DATE_DESC))
        assertEquals(listOf("file.jpg", "vol2", "vol10"), entries.sortedWith(FileEntrySort.comparator(SortOption.DATE_DESC, directoriesFirst = false)).map { it.name })
    }
}
