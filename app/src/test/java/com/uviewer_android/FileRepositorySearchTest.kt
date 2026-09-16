package com.uviewer_android

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.repository.FileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRepositorySearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `search walks sub folders and reports matches as it finds them`() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "TopBook.epub").writeText("a")
        val nested = File(root, "Books/Novels").apply { mkdirs() }
        File(nested, "DeepBook.epub").writeText("b")
        File(nested, "ignore.txt").writeText("c")

        val found = mutableListOf<FileEntry>()
        FileRepository().searchFilesRecursively(root.absolutePath, "book") { entry ->
            synchronized(found) { found.add(entry) }
        }

        assertEquals(2, found.size)
        assertNull(found.first { it.name == "TopBook.epub" }.location)
        assertEquals("Books/Novels", found.first { it.name == "DeepBook.epub" }.location)
    }
}
