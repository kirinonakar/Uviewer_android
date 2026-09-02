package com.uviewer_android

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.ui.library.filterFilesByName
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFileFilterTest {
    @Test
    fun `blank query returns all entries`() {
        val entries = listOf(entry("Book.epub"), entry("Notes.txt"))

        assertEquals(entries, filterFilesByName(entries, "  "))
    }

    @Test
    fun `filter matches filename without case sensitivity`() {
        val entries = listOf(entry("My Novel.epub"), entry("NOVEL-notes.txt"), entry("Another.pdf"))

        assertEquals(
            listOf("My Novel.epub", "NOVEL-notes.txt"),
            filterFilesByName(entries, "nOvEl").map { it.name }
        )
    }

    private fun entry(name: String) = FileEntry(
        name = name,
        path = "/storage/emulated/0/$name",
        isDirectory = false,
        type = FileEntry.FileType.UNKNOWN,
        lastModified = 0L,
        size = 0L
    )
}
