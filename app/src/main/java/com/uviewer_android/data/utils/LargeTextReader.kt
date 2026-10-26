package com.uviewer_android.data.utils

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LargeTextReader(private val file: File, private val indexDir: File? = null, private val contentVersion: Long? = null) {
    private var lineOffsets = LongArray(0)
    private var charsetName: String = "UTF-8"
    private var isIndexed = false
    private data class PrefetchedLines(val start: Int, val count: Int, val content: String)
    @Volatile private var prefetchedLines: PrefetchedLines? = null

    suspend fun indexFile(manualEncoding: String? = null, onProgress: (Float) -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            isIndexed = false
            prefetchedLines = null
            lineOffsets = LongArray(0)
            val length = file.length()
            val modified = file.lastModified()
            val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
                .digest("${file.canonicalPath}\n$length\n${contentVersion ?: modified}\n$manualEncoding\nv2".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val indexFile = indexDir?.let { File(it, "$cacheKey.idx") }
            if (indexFile != null && restoreIndex(indexFile, length)) {
                isIndexed = true
                onProgress(1f)
                return@withContext
            }
            if (length == 0L) {
                charsetName = manualEncoding ?: "UTF-8"
                isIndexed = true
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
                    for (i in 0 until count) {
                        val value = bytes[i].toInt() and 0xff
                        val newline = if (!utf16) value == 0x0a else
                            position % 2L == 1L && if (littleEndian) previous == 0x0a && value == 0 else previous == 0 && value == 0x0a
                        if (newline) {
                            if (lineCount == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                            offsets[lineCount++] = position + 1
                        }
                        previous = value
                        position++
                    }
                    val progress = position.toFloat() / length
                    if (progress - lastProgress >= 0.05f) {
                        onProgress(progress.coerceAtMost(1f))
                        lastProgress = progress
                    }
                }
                if (file.length() != length || file.lastModified() != modified) throw java.io.IOException("Text file changed while indexing")
                lineOffsets = offsets.copyOf(lineCount)
            }
            isIndexed = true
            indexFile?.let { saveIndex(it) }
            onProgress(1f)
        }
    }

    private fun restoreIndex(index: File, length: Long): Boolean = try {
        java.io.DataInputStream(index.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x55565432)
            val encoding = input.readUTF()
            val count = input.readInt()
            require(count >= 0 && count.toLong() <= length + 1 && index.length() >= count.toLong() * 8 + 10)
            val offsets = LongArray(count)
            for (i in offsets.indices) {
                offsets[i] = input.readLong()
                require(offsets[i] in 0..length && (if (i == 0) offsets[i] == 0L else offsets[i] > offsets[i - 1]))
            }
            require(length == 0L || count > 0)
            charsetName = encoding
            lineOffsets = offsets
            true
        }
    } catch (_: java.io.IOException) { false } catch (_: IllegalArgumentException) { false }

    private fun saveIndex(index: File) {
        val pending = File(index.parentFile, "${index.name}.${java.util.UUID.randomUUID()}.part")
        try {
            index.parentFile?.mkdirs()
            java.io.DataOutputStream(pending.outputStream().buffered()).use { output ->
                output.writeInt(0x55565432)
                output.writeUTF(charsetName)
                output.writeInt(lineOffsets.size)
                for (offset in lineOffsets) output.writeLong(offset)
            }
            java.nio.file.Files.move(pending.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.io.IOException) {
            // A disposable index must never prevent opening the document.
        } finally {
            pending.delete()
        }
    }

    fun getTotalLines(): Int = lineOffsets.size

    /** One bounded chunk ahead; TOC/search reads do not evict it. */
    suspend fun prefetchLines(startLine: Int, count: Int) {
        if (!isIndexed || startLine < 1 || count <= 0 || startLine > lineOffsets.size) return
        val endLine = (startLine.toLong() + count - 1).coerceAtMost(lineOffsets.size.toLong()).toInt()
        val endOffset = if (endLine < lineOffsets.size) lineOffsets[endLine] else file.length()
        if (endOffset - lineOffsets[startLine - 1] > 1024 * 1024) return
        val content = readLines(startLine, count)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        prefetchedLines = PrefetchedLines(startLine, count, content)
    }

    suspend fun readLines(startLine: Int, count: Int): String {
        if (!isIndexed || startLine < 1 || count <= 0 || startLine > lineOffsets.size) return ""
        
        prefetchedLines?.takeIf { it.start == startLine && it.count == count }?.let { return it.content }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startOffset = lineOffsets[startLine - 1]
            val endLine = (startLine.toLong() + count - 1).coerceAtMost(lineOffsets.size.toLong()).toInt()
            val endOffset = if (endLine < lineOffsets.size) lineOffsets[endLine] else file.length()
            
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
