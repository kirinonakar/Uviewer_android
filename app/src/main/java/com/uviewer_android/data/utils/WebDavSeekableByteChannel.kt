package com.uviewer_android.data.utils

import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

class WebDavSeekableByteChannel(
    private val webDavRepository: WebDavRepository,
    private val serverId: Int,
    private val path: String,
    private val fileSize: Long
) : SeekableByteChannel {

    private var position: Long = 0
    private var isOpen: Boolean = true

    private var buffer: ByteArray? = null
    private var bufferStart: Long = -1L
    private val bufferSize = 64 * 1024 // 64KB buffer

    override fun read(dst: ByteBuffer): Int {
        if (!isOpen) throw java.nio.channels.ClosedChannelException()
        if (position >= fileSize) return -1

        val remaining = dst.remaining()
        if (remaining == 0) return 0

        // Check if we can satisfy the read from the buffer
        if (buffer != null && position >= bufferStart && position < bufferStart + buffer!!.size) {
            val offsetInBuffer = (position - bufferStart).toInt()
            val availableInBuffer = buffer!!.size - offsetInBuffer
            val toCopy = minOf(remaining, availableInBuffer)
            
            dst.put(buffer!!, offsetInBuffer, toCopy)
            position += toCopy
            return toCopy
        }

        // Buffer miss or empty, do a new range read
        // Fetch more than needed to fill the buffer
        val fetchSize = maxOf(remaining.toLong(), bufferSize.toLong())
        val end = minOf(fileSize - 1, position + fetchSize - 1)
        
        val bytes = try {
            runBlocking {
                webDavRepository.readRange(serverId, path, position, end)
            }
        } catch (e: Exception) {
            throw java.io.IOException("WebDAV read error: ${e.message}", e)
        }

        if (bytes.isEmpty()) {
            if (position < fileSize) throw java.io.IOException("Unexpected EOF at $position")
            return -1
        }

        // Store in buffer
        buffer = bytes
        bufferStart = position

        val toCopy = minOf(remaining, bytes.size)
        dst.put(bytes, 0, toCopy)
        position += toCopy
        return toCopy
    }

    override fun write(src: ByteBuffer): Int {
        throw UnsupportedOperationException("ReadOnly channel")
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        position = newPosition
        return this
    }

    override fun size(): Long = fileSize

    override fun truncate(size: Long): SeekableByteChannel {
        throw UnsupportedOperationException("ReadOnly channel")
    }

    override fun isOpen(): Boolean = isOpen

    override fun close() {
        isOpen = false
    }
}
