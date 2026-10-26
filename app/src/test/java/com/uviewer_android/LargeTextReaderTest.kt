package com.uviewer_android

import com.uviewer_android.data.utils.LargeTextReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LargeTextReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun firstChunkIsReadableBeforeFullIndexAndLaterReadsWaitWithoutLosingSearchResults() = runBlocking {
        withTimeout(10000) {
            val file = temp.newFile("progressive.txt")
            val line = "한글과 UTF-8 text for a large document\n"
            file.outputStream().buffered().use { output ->
                val bytes = line.toByteArray()
                repeat(100000) { output.write(bytes) }
                output.write("needle at EOF".toByteArray())
            }
            val cache = temp.newFolder("progressive_indexes")
            val reader = LargeTextReader(file, cache)
            val ready = CompletableDeferred<Unit>()
            val continueIndex = CompletableDeferred<Unit>()
            var callbacks = 0
            val indexing = launch {
                reader.indexFile("UTF-8", initialLine = 1, onInitialChunkReady = {
                    callbacks++
                    assertFalse(reader.isIndexComplete())
                    assertTrue(reader.getTotalLines() in 2000 until 100000)
                    // Reading must stop at the indexed chunk boundary, not at physical EOF.
                    assertEquals(line.repeat(2000), reader.readLines(1, 2000))
                    reader.prefetchLines(2001, 2000)
                    assertEquals(line.repeat(2000), reader.readLines(2001, 2000))
                    ready.complete(Unit)
                    continueIndex.await()
                }) {}
            }
            ready.await()
            val tail = async(start = CoroutineStart.UNDISPATCHED) { reader.readLines(100000, 2000) }
            val total = async(start = CoroutineStart.UNDISPATCHED) { reader.awaitTotalLines() }
            val search = async(start = CoroutineStart.UNDISPATCHED) {
                com.uviewer_android.ui.viewer.DocumentSearchEngine.findMatches(
                    "needle", file.path, com.uviewer_android.data.model.FileEntry.FileType.TEXT, reader, null
                )
            }
            assertFalse(tail.isCompleted)
            assertFalse(total.isCompleted)
            assertFalse(search.isCompleted)
            continueIndex.complete(Unit)
            indexing.join()
            assertEquals(1, callbacks)
            assertEquals(100001, total.await())
            assertEquals(line + "needle at EOF", tail.await())
            assertEquals(listOf(100001), search.await().map { it.line })
            assertEquals(line.repeat(2000), reader.readLines(2001, 2000))
            val reopened = LargeTextReader(file, cache)
            reopened.indexFile("UTF-8", initialLine = 1, onInitialChunkReady = {
                assertTrue(reopened.isIndexComplete())
                assertEquals(100001, reopened.getTotalLines())
                assertEquals("needle at EOF", reopened.readLines(100001, 1))
            }) {}
        }
    }

    @Test fun savedPositionPublishesItsAlignedChunkWithUtf16AndLegacyEncodings() = runBlocking {
        withTimeout(10000) {
            for (encoding in listOf("UTF-16", "UTF-16LE", "UTF-16BE", "EUC-KR", "Shift_JIS")) {
                val file = temp.newFile("progressive-$encoding.txt")
                val charset = java.nio.charset.Charset.forName(encoding)
                file.writeBytes((1..60000).joinToString("\n") { "line-$it" }.toByteArray(charset))
                val reader = LargeTextReader(file)
                var callbacks = 0
                reader.indexFile(encoding, initialLine = 3500, onInitialChunkReady = {
                    callbacks++
                    assertFalse(reader.isIndexComplete())
                    assertEquals((2001..4000).joinToString("\n", postfix = "\n") { "line-$it" }, reader.readLines(2001, 2000))
                }) {}
                assertEquals(1, callbacks)
                assertEquals("line-60000", reader.readLines(60000, 1))
            }
        }
    }

    @Test fun shortEmptyAndNoNewlineFilesPublishOnceAtEof() = runBlocking {
        for (content in listOf("", "only one line", "first\nlast", "first\n")) {
            val file = temp.newFile().apply { writeText(content) }
            val reader = LargeTextReader(file)
            var callbacks = 0
            reader.indexFile("UTF-8", initialLine = Int.MAX_VALUE, onInitialChunkReady = {
                callbacks++
                assertTrue(reader.isIndexComplete())
                assertEquals(content, reader.readLines(1, Int.MAX_VALUE))
            }) {}
            assertEquals(1, callbacks)
        }
    }

    @Test fun cancellationUnblocksReadersAndDoesNotPersistAnIncompleteIndex() = runBlocking {
        withTimeout(10000) {
            val file = temp.newFile().apply { writeText("line for cancellation\n".repeat(50000)) }
            val cache = temp.newFolder("cancelled_indexes")
            val reader = LargeTextReader(file, cache)
            val ready = CompletableDeferred<Unit>()
            val indexing = launch {
                reader.indexFile("UTF-8", initialLine = 1, onInitialChunkReady = {
                    ready.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }) {}
            }
            ready.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    reader.readLines(50000, 2000)
                    fail("Cancelled indexing must not return a truncated chunk")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Cancellation of the producer also releases pending consumers.
                }
            }
            indexing.cancelAndJoin()
            waiting.await()
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun changedFileAfterPreviewFailsPendingWholeDocumentOperations() = runBlocking {
        val file = temp.newFile().apply { writeText("line\n".repeat(100000)) }
        val cache = temp.newFolder("changed_indexes")
        val reader = LargeTextReader(file, cache)
        try {
            reader.indexFile("UTF-8", initialLine = 1, onInitialChunkReady = { file.appendText("changed") }) {}
            fail("Changes during indexing must be detected")
        } catch (_: java.io.IOException) {
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        try {
            reader.awaitTotalLines()
            fail("A failed prefix must not be treated as the whole document")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }

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
