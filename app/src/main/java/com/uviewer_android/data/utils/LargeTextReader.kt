package com.uviewer_android.data.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LargeTextReader(private val file: File) {
    private var lineOffsets = LongArray(0)
    private var charset: Charset = StandardCharsets.UTF_8
    private var isIndexed = false

    suspend fun indexFile(manualEncoding: String? = null, onProgress: (Float) -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val length = file.length()
            if (length == 0L) {
                isIndexed = true
                return@withContext
            }

            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                
                // Detect encoding from first 64KB
                val sampleSize = length.coerceAtMost(65536).toInt()
                val sample = ByteArray(sampleSize)
                buffer.get(sample)
                buffer.position(0)
                
                charset = if (manualEncoding != null) EncodingDetector.getCharset(manualEncoding) else EncodingDetector.detectEncoding(sample)

                val offsets = mutableListOf<Long>()
                offsets.add(0L)
                
                var lastProgress = 0f
                for (i in 0 until length) {
                    if (buffer.get().toInt() == 0x0A) { // '\n'
                        offsets.add(i + 1)
                    }
                    
                    if (i % 1048576 == 0L) { // Update progress every 1MB
                        val progress = i.toFloat() / length
                        if (progress - lastProgress > 0.05f) {
                            onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
                lineOffsets = offsets.toLongArray()
                isIndexed = true
                onProgress(1f)
            }
        }
    }

    fun getTotalLines(): Int = lineOffsets.size

    suspend fun readLines(startLine: Int, count: Int): String {
        if (!isIndexed || startLine > lineOffsets.size) return ""
        
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startOffset = lineOffsets[startLine - 1]
            val endLine = (startLine + count - 1).coerceAtMost(lineOffsets.size)
            val endOffset = if (endLine < lineOffsets.size) lineOffsets[endLine] else file.length()
            
            val size = (endOffset - startOffset).toInt()
            if (size <= 0) return@withContext ""
            
            val bytes = ByteArray(size)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(startOffset)
                raf.readFully(bytes)
            }
            
            val decoded = String(bytes, charset)
            java.text.Normalizer.normalize(decoded, java.text.Normalizer.Form.NFC)
        }
    }

    fun getCharset(): Charset = charset
}
