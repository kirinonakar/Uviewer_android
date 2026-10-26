package com.uviewer_android

import com.uviewer_android.data.utils.LargeTextReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LargeTextReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun indexesChunkBoundariesAndReusesCacheAndRebuildsCorruption() = runBlocking {
        val file = temp.newFile("large.txt")
        val lines = "a".repeat(256 * 1024 - 1) + "\n둘째\nlast"
        file.writeText(lines)
        val cache = temp.newFolder("indexes")
        val reader = LargeTextReader(file, cache)
        reader.indexFile("UTF-8") {}
        assertEquals(3, reader.getTotalLines())
        reader.prefetchLines(2, 2)
        assertEquals("둘째\nlast", reader.readLines(2, 2))
        assertEquals("last", reader.readLines(3, 1))
        assertEquals("둘째\nlast", reader.readLines(2, 2))
        assertEquals("", reader.readLines(0, 1))
        assertEquals("", reader.readLines(4, 1))
        val progress = mutableListOf<Float>()
        val reopened = LargeTextReader(file, cache)
        reopened.indexFile("UTF-8") { progress.add(it) }
        assertEquals(listOf(1f), progress)
        assertEquals("last", reopened.readLines(3, 1))
        cache.listFiles()!!.single().writeText("broken index")
        val rebuilt = LargeTextReader(file, cache)
        rebuilt.indexFile("UTF-8") {}
        assertEquals(3, rebuilt.getTotalLines())
        file.appendText("\nnew")
        val changed = LargeTextReader(file, cache)
        changed.indexFile("UTF-8") {}
        assertEquals("new", changed.readLines(4, 1))
    }

    @Test fun utf16IndexesCodeUnitsAndChunksDecodeWithoutBom() = runBlocking {
        for (encoding in listOf("UTF-16LE", "UTF-16BE", "UTF-16")) {
            val file = temp.newFile("$encoding.txt")
            // U+0A41 contains 0x0A but is not a newline.
            file.writeBytes("aੁ\n한글\n".toByteArray(java.nio.charset.Charset.forName(encoding)))
            val reader = LargeTextReader(file)
            reader.indexFile(encoding) {}
            assertEquals(3, reader.getTotalLines())
            assertEquals("한글\n", reader.readLines(2, 1))
        }
    }

    @Test fun emptyFilesReportFinishedAndDoNotReadOutOfBounds() = runBlocking {
        val reader = LargeTextReader(temp.newFile())
        val progress = mutableListOf<Float>()
        reader.indexFile { progress.add(it) }
        assertEquals(listOf(1f), progress)
        assertEquals(0, reader.getTotalLines())
        assertEquals("", reader.readLines(1, 100))
    }

    @Test fun webDavLruTouchesDoNotInvalidateTheIndex() = runBlocking {
        val file = temp.newFile("webdav_book.txt").apply { writeText("first\nsecond") }
        val stamp = java.io.File(file.parentFile, "${file.name}.validated").apply { writeText("") }
        val cache = temp.newFolder("remote_indexes")
        val version = com.uviewer_android.data.utils.WebDavFileCache.contentTimestamp(file)
        LargeTextReader(file, cache, version).indexFile("UTF-8") {}
        assertTrue(file.setLastModified(file.lastModified() + 10000))
        val progress = mutableListOf<Float>()
        val reopened = LargeTextReader(file, cache, com.uviewer_android.data.utils.WebDavFileCache.contentTimestamp(file))
        reopened.indexFile("UTF-8") { progress.add(it) }
        assertEquals(listOf(1f), progress)
        assertEquals(1, cache.listFiles()!!.size)
        assertEquals("second", reopened.readLines(2, 1))
        assertTrue(stamp.setLastModified(stamp.lastModified() + 20000))
        LargeTextReader(file, cache, com.uviewer_android.data.utils.WebDavFileCache.contentTimestamp(file)).indexFile("UTF-8") {}
        assertEquals(2, cache.listFiles()!!.size)
    }
}
