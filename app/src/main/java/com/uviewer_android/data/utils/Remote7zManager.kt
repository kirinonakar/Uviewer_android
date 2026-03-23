package com.uviewer_android.data.utils

import android.util.Log
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

data class Remote7zEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val index: Int
)

class Remote7zManager(
    private val webDavRepository: WebDavRepository,
    private val serverId: Int,
    private val path: String,
    private val fileSize: Long
) {
    private var entries: List<Remote7zEntry>? = null
    private val mutex = Mutex()

    suspend fun getEntries(): List<Remote7zEntry> = mutex.withLock {
        if (entries != null) return@withLock entries!!
        
        Log.d("Remote7z", "Listing entries for: $path (fileSize=$fileSize)")
        withContext(Dispatchers.IO) {
            try {
                WebDavSeekableByteChannel(webDavRepository, serverId, path, fileSize).use { channel ->
                    val inStream = SeekableByteChannelInStream(channel)
                    SevenZip.openInArchive(null, inStream).use { inArchive ->
                        val count = inArchive.numberOfItems
                        val list = mutableListOf<Remote7zEntry>()
                        for (i in 0 until count) {
                            val entryPath = inArchive.getProperty(i, PropID.PATH) as? String ?: ""
                            val entrySize = (inArchive.getProperty(i, PropID.SIZE) as? Number)?.toLong() ?: 0L
                            val isDir = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                            list.add(Remote7zEntry(entryPath, entrySize, isDir, i))
                        }
                        entries = list
                        Log.d("Remote7z", "Successfully listed ${list.size} entries for $path")
                    }
                }
            } catch (e: Exception) {
                Log.e("Remote7z", "Error listing 7z entries for $path", e)
                throw IOException("Archive open error: ${e.message}", e)
            }
        }
        entries!!
    }

    suspend fun getEntryData(entryName: String): ByteArray? = withContext(Dispatchers.IO) {
        val targetName = entryName.replace('\\', '/')
        Log.d("Remote7z", "Requesting entry data: $targetName from $path")
        
        try {
            WebDavSeekableByteChannel(webDavRepository, serverId, path, fileSize).use { channel ->
                val inStream = SeekableByteChannelInStream(channel)
                SevenZip.openInArchive(null, inStream).use { inArchive ->
                    val count = inArchive.numberOfItems
                    var targetIndex = -1
                    for (i in 0 until count) {
                        val currentName = (inArchive.getProperty(i, PropID.PATH) as? String ?: "").replace('\\', '/')
                        if (currentName == targetName) {
                            targetIndex = i
                            break
                        }
                    }

                    if (targetIndex != -1) {
                        val out = ByteArrayOutputStream()
                        inArchive.extract(intArrayOf(targetIndex), false, object : IArchiveExtractCallback {
                            override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                                if (extractAskMode != ExtractAskMode.EXTRACT) return null
                                return ISequentialOutStream { data ->
                                    out.write(data)
                                    data.size
                                }
                            }
                            override fun prepareOperation(extractAskMode: ExtractAskMode) {}
                            override fun setOperationResult(result: ExtractOperationResult) {}
                            override fun setTotal(total: Long) {}
                            override fun setCompleted(complete: Long) {}
                        })
                        val data = out.toByteArray()
                        Log.d("Remote7z", "Successfully read ${data.size} bytes for $targetName")
                        return@withContext data
                    }
                    Log.e("Remote7z", "Entry NOT found in 7z: $targetName")
                }
            }
        } catch (e: Exception) {
            Log.e("Remote7z", "Error reading 7z entry $targetName from $path", e)
        }
        null
    }
}

class SeekableByteChannelInStream(private val channel: SeekableByteChannel) : IInStream {
    override fun seek(offset: Long, seekOrigin: Int): Long {
        val newPos = when (seekOrigin) {
            IInStream.SEEK_SET -> offset
            IInStream.SEEK_CUR -> channel.position() + offset
            IInStream.SEEK_END -> channel.size() + offset
            else -> throw RuntimeException("Unknown seek origin: $seekOrigin")
        }
        channel.position(newPos)
        return newPos
    }

    override fun read(data: ByteArray): Int {
        val buffer = ByteBuffer.wrap(data)
        val read = channel.read(buffer)
        return if (read == -1) 0 else read
    }

    override fun close() {
        channel.close()
    }
}
