package com.uviewer_android.data.utils

import android.util.Log
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

class Remote7zManager(
    private val webDavRepository: WebDavRepository,
    private val serverId: Int,
    private val path: String,
    private val fileSize: Long
) {
    private var entries: List<SevenZArchiveEntry>? = null
    private val mutex = Mutex()

    suspend fun getEntries(): List<SevenZArchiveEntry> = mutex.withLock {
        if (entries != null) return@withLock entries!!
        
        Log.d("Remote7z", "Listing entries for: $path (fileSize=$fileSize)")
        withContext(Dispatchers.IO) {
            try {
                val channel = WebDavSeekableByteChannel(webDavRepository, serverId, path, fileSize)
                SevenZFile(channel).use { s7z ->
                    val list = s7z.entries.toList()
                    entries = list
                    Log.d("Remote7z", "Successfully listed ${list.size} entries for $path")
                }
            } catch (e: Exception) {
                Log.e("Remote7z", "Error listing 7z entries for $path", e)
                entries = emptyList()
            }
        }
        entries!!
    }

    suspend fun getEntryData(entryName: String): ByteArray? = withContext(Dispatchers.IO) {
        // Robust filename matching
        val targetName = entryName.replace('\\', '/')
        Log.d("Remote7z", "Requesting entry data: $targetName from $path")
        
        try {
            val channel = WebDavSeekableByteChannel(webDavRepository, serverId, path, fileSize)
            SevenZFile(channel).use { s7z ->
                var current = s7z.nextEntry
                while (current != null) {
                    val currentName = current.name.replace('\\', '/')
                    if (currentName == targetName) {
                        Log.d("Remote7z", "Found entry $targetName. Reading data...")
                        val out = java.io.ByteArrayOutputStream()
                        var n: Int
                        val readBuffer = ByteArray(16384)
                        while (s7z.read(readBuffer).also { n = it } != -1) {
                            out.write(readBuffer, 0, n)
                        }
                        val data = out.toByteArray()
                        Log.d("Remote7z", "Successfully read ${data.size} bytes for $targetName")
                        return@withContext data
                    }
                    current = s7z.nextEntry
                }
                Log.e("Remote7z", "Entry NOT found in 7z: $targetName (searched whole archive)")
            }
        } catch (e: Exception) {
            Log.e("Remote7z", "Error reading 7z entry $targetName from $path", e)
        }
        null
    }
}
