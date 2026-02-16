package com.uviewer_android.data.utils

import android.util.Log
import com.uviewer_android.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteZipEntry(
    val name: String,
    val offset: Long, // Offset of local header
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compressionMethod: Int,
    val isDirectory: Boolean
)

class RemoteZipManager(
    private val webDavRepository: WebDavRepository,
    private val serverId: Int,
    private val zipPath: String,
    private val zipSize: Long
) {
    private val entries = mutableListOf<RemoteZipEntry>()
    private val mutex = Mutex()

    suspend fun getEntries(): List<RemoteZipEntry> = mutex.withLock {
        if (entries.isNotEmpty()) return@withLock entries
        
        withContext(Dispatchers.IO) {
            try {
                // 1. Read the end of the file to find EOCD
                val readSize = minOf(zipSize, 65536L + 22L)
                val start = zipSize - readSize
                val bytes = webDavRepository.readRange(serverId, zipPath, start, zipSize - 1)
                
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                
                // 2. Find EOCD signature: 0x06054b50
                var eocdPos = -1
                for (i in bytes.size - 22 downTo 0) {
                    if (bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && 
                        bytes[i+2] == 0x05.toByte() && bytes[i+3] == 0x06.toByte()) {
                        eocdPos = i
                        break
                    }
                }
                
                if (eocdPos == -1) throw Exception("EOCD not found")
                
                buffer.position(eocdPos + 10)
                val entryCount = buffer.short.toInt() and 0xFFFF
                val cdSize = buffer.int.toLong() and 0xFFFFFFFFL
                val cdOffset = buffer.int.toLong() and 0xFFFFFFFFL
                
                Log.d("RemoteZip", "Found $entryCount entries, CD size: $cdSize, CD offset: $cdOffset. ZIP Size: $zipSize")
                
                // 3. Read Central Directory
                // CD might be shifted if there's a comment or ZIP64, but for standard ZIP it's cdOffset.
                // We read exactly cdSize.
                val cdBytes = webDavRepository.readRange(serverId, zipPath, cdOffset, cdOffset + cdSize - 1)
                val cdBuffer = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
                
                while (cdBuffer.remaining() >= 46) {
                    val sig = cdBuffer.int
                    if (sig != 0x02014b50) {
                        Log.w("RemoteZip", "Unexpected signature at pointer ${cdBuffer.position() - 4}: ${Integer.toHexString(sig)}")
                        break
                    }
                    
                    cdBuffer.position(cdBuffer.position() + 4) // Skip version (2+2)
                    val gpFlag = cdBuffer.short.toInt() and 0xFFFF
                    val method = cdBuffer.short.toInt() and 0xFFFF
                    cdBuffer.position(cdBuffer.position() + 8) // Skip time, date, crc
                    val compSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
                    val uncompSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
                    val nameLen = cdBuffer.short.toInt() and 0xFFFF
                    val extraLen = cdBuffer.short.toInt() and 0xFFFF
                    val commentLen = cdBuffer.short.toInt() and 0xFFFF
                    
                    cdBuffer.position(cdBuffer.position() + 8) // Skip disk (2), attr (2+4)
                    val localOffset = cdBuffer.int.toLong() and 0xFFFFFFFFL
                    
                    val nameBytes = ByteArray(nameLen)
                    cdBuffer.get(nameBytes)
                    
                    // Bit 11: Language encoding flag (EFS). If set, filename and comment are UTF-8.
                    val isUtf8 = (gpFlag and 0x800) != 0
                    val name = if (isUtf8) String(nameBytes, Charsets.UTF_8) else String(nameBytes) // Fallback to default
                    
                    cdBuffer.position(cdBuffer.position() + extraLen + commentLen)
                    
                    val entry = RemoteZipEntry(
                        name = name,
                        offset = localOffset,
                        compressedSize = compSize,
                        uncompressedSize = uncompSize,
                        compressionMethod = method,
                        isDirectory = name.endsWith("/")
                    )
                    entries.add(entry)
                }
                Log.d("RemoteZip", "Parsed total ${entries.size} entries from Central Directory.")
            } catch (e: Exception) {
                Log.e("RemoteZip", "Error parsing remote zip: ${e.message}", e)
            }
        }
        entries.toList()
    }

    suspend fun getEntryData(entry: RemoteZipEntry): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // 1. Read Local Header to find actual data offset
            // Local header is 30 bytes + nameLen + extraLen
            val headerBytes = webDavRepository.readRange(serverId, zipPath, entry.offset, entry.offset + 30 + 512) // read a bit more for name/extra
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val sig = buffer.int
            if (sig != 0x04034b50) return@withContext null
            
            buffer.position(26)
            val nameLen = buffer.short.toInt() and 0xFFFF
            val extraLen = buffer.short.toInt() and 0xFFFF
            val dataOffset = entry.offset + 30 + nameLen + extraLen
            
            // 2. Read compressed data
            val compressedBytes = webDavRepository.readRange(serverId, zipPath, dataOffset, dataOffset + entry.compressedSize - 1)
            
            if (entry.compressionMethod == 0) { // Stored
                return@withContext compressedBytes
            } else if (entry.compressionMethod == 8) { // Deflated
                val inflater = Inflater(true)
                val input = ByteArrayInputStream(compressedBytes)
                val output = java.io.ByteArrayOutputStream()
                InflaterInputStream(input, inflater).use { it.copyTo(output) }
                return@withContext output.toByteArray()
            }
        } catch (e: Exception) {
            Log.e("RemoteZip", "Error reading entry ${entry.name}", e)
        }
        null
    }
}
