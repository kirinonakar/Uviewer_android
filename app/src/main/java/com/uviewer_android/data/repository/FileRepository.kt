package com.uviewer_android.data.repository

import com.uviewer_android.data.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileRepository {

    fun listFiles(path: String, includeHidden: Boolean = false): List<FileEntry> {
        val directory = File(path)
        if (!directory.exists() || !directory.isDirectory) return emptyList()

        return directory.listFiles()?.filter {
            includeHidden || !it.isHidden
        }?.map { file ->
            mapToFileEntry(file)
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    private fun mapToFileEntry(file: File): FileEntry {
        val extension = file.extension.lowercase()
        val type = when {
            file.isDirectory -> FileEntry.FileType.FOLDER
            extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> FileEntry.FileType.IMAGE
            extension in listOf("txt", "md", "aozora") -> FileEntry.FileType.TEXT
            extension in listOf("html", "htm", "xhtml") -> FileEntry.FileType.HTML
            extension == "pdf" -> FileEntry.FileType.PDF
            extension == "epub" -> FileEntry.FileType.EPUB
            extension in listOf("zip", "rar", "cbz") -> FileEntry.FileType.ZIP
            extension in listOf("mp3", "wav", "ogg", "flac") -> FileEntry.FileType.AUDIO
            extension in listOf("mp4", "mkv", "avi", "mov", "webm") -> FileEntry.FileType.VIDEO
            else -> FileEntry.FileType.UNKNOWN
        }

        return FileEntry(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            type = type,
            lastModified = file.lastModified(),
            size = file.length()
        )
    }

    suspend fun readFileContent(path: String): String {
        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) throw java.io.FileNotFoundException("File not found: $path")
            
            // Limit to 2MB to prevent OOM/Performance issues
            val limit = 2 * 1024 * 1024L
            val length = file.length()
            val bytes = if (length > limit) {
                val buffer = ByteArray(limit.toInt())
                java.io.FileInputStream(file).use { it.read(buffer) }
                buffer
            } else {
                file.readBytes()
            }
            
            // 1. Detect if it's UTF-8 with BOM or UTF-16 with BOM
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            }
            if (bytes.size >= 2) {
                if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                    return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_16BE)
                }
                if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                    return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_16LE)
                }
            }

            // 2. Try strict UTF-8
            if (tryDecode(bytes, java.nio.charset.StandardCharsets.UTF_8)) {
                return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            }

            // 3. Heuristic / Priority detection for CJK
            // User specifically asked for SJIS, EUC-KR, JOJAB support.
            
            // Try EUC-KR
            if (tryDecode(bytes, java.nio.charset.Charset.forName("EUC-KR"))) {
                return@withContext String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
            }

            // Try Shift_JIS
            if (tryDecode(bytes, java.nio.charset.Charset.forName("Shift_JIS"))) {
                return@withContext String(bytes, java.nio.charset.Charset.forName("Shift_JIS"))
            }

            // Try Johab (x-johab or ksc5601-1992) - common for legacy Korean
            try {
                if (tryDecode(bytes, java.nio.charset.Charset.forName("x-Johab"))) {
                    return@withContext String(bytes, java.nio.charset.Charset.forName("x-Johab"))
                }
            } catch (e: Exception) {
                // Charset might not be supported on this Android version or JVM
            }
            
             // Try Windows-1252 (Western) as last resort for 8-bit
             if (tryDecode(bytes, java.nio.charset.Charset.forName("windows-1252"))) {
                return@withContext String(bytes, java.nio.charset.Charset.forName("windows-1252"))
            }

            // Fallback
            return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        }
    }

    suspend fun getFileCharset(path: String): java.nio.charset.Charset = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext java.nio.charset.StandardCharsets.UTF_8
        
        val buffer = ByteArray(4096)
        java.io.FileInputStream(file).use { it.read(buffer) }
        val bytes = buffer
        
        // 1. BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return@withContext java.nio.charset.StandardCharsets.UTF_8
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return@withContext java.nio.charset.StandardCharsets.UTF_16BE
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return@withContext java.nio.charset.StandardCharsets.UTF_16LE
        }
        
        // 2. Strict UTF-8
        if (tryDecode(bytes, java.nio.charset.StandardCharsets.UTF_8)) return@withContext java.nio.charset.StandardCharsets.UTF_8
        
        // 3. Priorities
         if (tryDecode(bytes, java.nio.charset.Charset.forName("EUC-KR"))) return@withContext java.nio.charset.Charset.forName("EUC-KR")
         if (tryDecode(bytes, java.nio.charset.Charset.forName("Shift_JIS"))) return@withContext java.nio.charset.Charset.forName("Shift_JIS")
         try {
             if (tryDecode(bytes, java.nio.charset.Charset.forName("x-Johab"))) return@withContext java.nio.charset.Charset.forName("x-Johab")
         } catch (e: Exception) {}
         if (tryDecode(bytes, java.nio.charset.Charset.forName("windows-1252"))) return@withContext java.nio.charset.Charset.forName("windows-1252")
         
         return@withContext java.nio.charset.StandardCharsets.UTF_8
    }

    suspend fun readFileChunk(path: String, offset: Long, size: Int): String {
         // Not recommended for variable-width text files without byte-mapping.
         // Better to use readLinesChunk.
         return ""
    }

    suspend fun readLinesChunk(path: String, startLine: Int, count: Int): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext "" to false
        
        val charset = getFileCharset(path)
        val sb = StringBuilder()
        var linesRead = 0
        var hasMore = false
        
        try {
            java.io.FileInputStream(file).bufferedReader(charset).use { reader ->
                // Skip
                var skipped = 0
                while (skipped < startLine && reader.readLine() != null) {
                    skipped++
                }
                
                // Read
                var line = reader.readLine()
                while (line != null && linesRead < count) {
                     sb.append(line).append("\n")
                     linesRead++
                     line = reader.readLine()
                }
                if (line != null) hasMore = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext sb.toString() to hasMore
    }

    private fun tryDecode(bytes: ByteArray, charset: java.nio.charset.Charset): Boolean {
        try {
            val decoder = charset.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
