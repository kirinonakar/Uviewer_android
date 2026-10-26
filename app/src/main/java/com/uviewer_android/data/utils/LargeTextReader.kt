package com.uviewer_android.data.utils

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LargeTextReader(private val file: File, private val indexDir: File? = null, private val contentVersion: Long? = null) {
    // Entries below count are immutable. Publish the array and its valid count together so
    // readers can use completed lines while the IO coroutine appends the remaining offsets.
    private data class IndexState(
        val offsets: LongArray = LongArray(0),
        val count: Int = 0,
        val complete: Boolean = true,
        val failure: Throwable? = null
    )
    private val indexState = MutableStateFlow(IndexState())
    private var charsetName: String = "UTF-8"
    private data class PrefetchedLines(val start: Int, val count: Int, val content: String)
    @Volatile private var prefetchedLines: PrefetchedLines? = null

    suspend fun indexFile(
        manualEncoding: String? = null,
        initialLine: Int? = null,
        linesPerChunk: Int = 2000,
        onInitialChunkReady: suspend () -> Unit = {},
        onProgress: (Float) -> Unit
    ) {
        require(linesPerChunk > 0)
        val initialChunkEnd = initialLine?.coerceAtLeast(1)?.let {
            ((it.toLong() - 1) / linesPerChunk + 1) * linesPerChunk
        }
        var initialChunkPublished = false
        suspend fun publishInitialChunk() {
            if (!initialChunkPublished && initialChunkEnd != null &&
                (isIndexComplete() || getTotalLines().toLong() >= initialChunkEnd)) {
                initialChunkPublished = true
                onInitialChunkReady()
            }
        }
        indexState.value = IndexState(complete = false)
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                prefetchedLines = null
                val length = file.length()
                val modified = file.lastModified()
                val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("${file.canonicalPath}\n$length\n${contentVersion ?: modified}\n$manualEncoding\nv2".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val indexFile = indexDir?.let { File(it, "$cacheKey.idx") }
                if (indexFile != null && restoreIndex(indexFile, length)) {
                    publishInitialChunk()
                    onProgress(1f)
                    return@withContext
                }
                if (length == 0L) {
                    charsetName = manualEncoding ?: "UTF-8"
                    indexState.value = IndexState()
                    publishInitialChunk()
                    onProgress(1f)
                    return@withContext
                }

                file.inputStream().buffered(256 * 1024).use { input ->
                    input.mark(65536)
                    val sample = ByteArray(length.coerceAtMost(65536).toInt())
                    var sampleCount = 0
                    while (sampleCount < sample.size) {
                        val read = input.read(sample, sampleCount, sample.size - sampleCount)
                        if (read < 0) break
                        sampleCount += read
                    }
                    input.reset()
                    charsetName = manualEncoding ?: EncodingDetector.detectEncodingName(sample.copyOf(sampleCount))
                    val utf16 = charsetName.equals("UTF-16", true) || charsetName.equals("UTF-16LE", true) || charsetName.equals("UTF-16BE", true)
                    val littleEndian = charsetName.equals("UTF-16LE", true) ||
                        (charsetName.equals("UTF-16", true) && sampleCount >= 2 && sample[0] == 0xff.toByte())
                    // Resolve the BOM-dependent charset once; later chunks do not contain the BOM.
                    if (charsetName.equals("UTF-16", true)) charsetName = if (littleEndian) "UTF-16LE" else "UTF-16BE"
                    var offsets = LongArray(4096)
                    var lineCount = 1
                    var position = 0L
                    var previous = -1
                    var lastProgress = 0f
                    val bytes = ByteArray(256 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (!utf16) {
                            // The common path needs only a byte comparison; avoid per-byte Long
                            // increments and UTF-16 alignment/endianness checks for large UTF-8 files.
                            for (i in 0 until count) {
                                if (bytes[i] == 0x0a.toByte()) {
                                    if (lineCount == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                                    offsets[lineCount++] = position + i + 1
                                }
                            }
                            position += count
                        } else {
                            for (i in 0 until count) {
                                val value = bytes[i].toInt() and 0xff
                                val newline = position % 2L == 1L &&
                                    if (littleEndian) previous == 0x0a && value == 0 else previous == 0 && value == 0x0a
                                if (newline) {
                                    if (lineCount == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                                    offsets[lineCount++] = position + 1
                                }
                                previous = value
                                position++
                            }
                        }
                        indexState.value = IndexState(offsets, lineCount, complete = false)
                        publishInitialChunk()
                        val progress = position.toFloat() / length
                        if (progress - lastProgress >= 0.05f) {
                            onProgress(progress.coerceAtMost(1f))
                            lastProgress = progress
                        }
                    }
                    if (file.length() != length || file.lastModified() != modified) throw java.io.IOException("Text file changed while indexing")
                    indexState.value = IndexState(offsets, lineCount)
                }
                publishInitialChunk()
                indexFile?.let { saveIndex(it) }
                onProgress(1f)
            }
        } catch (failure: Throwable) {
            indexState.value = indexState.value.copy(failure = failure)
            throw failure
        }
    }

    private suspend fun restoreIndex(index: File, length: Long): Boolean = try {
        java.io.DataInputStream(index.inputStream().buffered(256 * 1024)).use { input ->
            require(input.readInt() == 0x55565432)
            val encoding = input.readUTF()
            val count = input.readInt()
            require(count >= 0 && count.toLong() <= length + 1 && index.length() >= count.toLong() * 8 + 10)
            val offsets = LongArray(count)
            for (i in offsets.indices) {
                if (i % 4096 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                offsets[i] = input.readLong()
                require(offsets[i] in 0..length && (if (i == 0) offsets[i] == 0L else offsets[i] > offsets[i - 1]))
            }
            require(length == 0L || count > 0)
            charsetName = encoding
            indexState.value = IndexState(offsets, count)
            true
        }
    } catch (_: java.io.IOException) { false } catch (_: IllegalArgumentException) { false }

    private suspend fun saveIndex(index: File) {
        val pending = File(index.parentFile, "${index.name}.${java.util.UUID.randomUUID()}.part")
        try {
            index.parentFile?.mkdirs()
            val state = indexState.value
            java.io.DataOutputStream(pending.outputStream().buffered(256 * 1024)).use { output ->
                output.writeInt(0x55565432)
                output.writeUTF(charsetName)
                output.writeInt(state.count)
                for (i in 0 until state.count) {
                    if (i % 4096 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    output.writeLong(state.offsets[i])
                }
            }
            java.nio.file.Files.move(pending.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.io.IOException) {
            // A disposable index must never prevent opening the document.
        } finally {
            pending.delete()
        }
    }

    fun getTotalLines(): Int = indexState.value.let { if (it.complete) it.count else (it.count - 1).coerceAtLeast(0) }

    fun isIndexComplete(): Boolean = indexState.value.complete

    /** Whole-document operations must not silently search/scan only the published prefix. */
    suspend fun awaitTotalLines(): Int {
        val state = indexState.first { it.complete || it.failure != null }
        state.failure?.let { throw it }
        return state.count
    }

    /** One bounded chunk ahead; TOC/search reads do not evict it. */
    suspend fun prefetchLines(startLine: Int, count: Int) {
        val state = indexState.value
        val availableLines = if (state.complete) state.count else (state.count - 1).coerceAtLeast(0)
        if (startLine < 1 || count <= 0 || startLine > availableLines) return
        val endLine = (startLine.toLong() + count - 1).coerceAtMost(availableLines.toLong()).toInt()
        // Wait for the next request instead of caching a truncated, still-growing chunk.
        if (!state.complete && startLine.toLong() + count > state.count) return
        val endOffset = if (endLine < state.count) state.offsets[endLine] else file.length()
        if (endOffset - state.offsets[startLine - 1] > 1024 * 1024) return
        val content = readLines(startLine, count)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        prefetchedLines = PrefetchedLines(startLine, count, content)
    }

    suspend fun readLines(startLine: Int, count: Int): String {
        if (startLine < 1 || count <= 0) return ""
        val requestedEnd = startLine.toLong() + count - 1
        val state = indexState.first { it.complete || it.failure != null || it.count.toLong() > requestedEnd }
        state.failure?.let { throw it }
        if (startLine > state.count) return ""
        
        prefetchedLines?.takeIf { it.start == startLine && it.count == count }?.let { return it.content }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startOffset = state.offsets[startLine - 1]
            val endLine = requestedEnd.coerceAtMost(state.count.toLong()).toInt()
            val endOffset = if (endLine < state.count) state.offsets[endLine] else file.length()
            
            val byteCount = endOffset - startOffset
            if (byteCount > Int.MAX_VALUE) throw java.io.IOException("Text chunk is too large")
            val size = byteCount.toInt()
            if (size <= 0) return@withContext ""
            
            val bytes = ByteArray(size)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(startOffset)
                raf.readFully(bytes)
            }
            
            val decoded = if (charsetName.equals("JO-HAB", ignoreCase = true) || charsetName.equals("x-Johab", ignoreCase = true)) {
                JohabConverter.decode(bytes)
            } else {
                val cs = try { Charset.forName(charsetName) } catch (e: Exception) { StandardCharsets.UTF_8 }
                String(bytes, cs)
            }
            java.text.Normalizer.normalize(decoded, java.text.Normalizer.Form.NFC)
        }
    }

    fun getCharsetName(): String = charsetName
}
