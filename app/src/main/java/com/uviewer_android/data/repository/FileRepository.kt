package com.uviewer_android.data.repository

import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.data.utils.EncodingDetector
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
            extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heif", "heic", "avif") -> FileEntry.FileType.IMAGE
            extension in listOf("txt", "md", "aozora", "csv", "log") -> FileEntry.FileType.TEXT
            extension in listOf("html", "htm", "xhtml") -> FileEntry.FileType.HTML
            extension == "pdf" -> FileEntry.FileType.PDF
            extension == "epub" -> FileEntry.FileType.EPUB
            extension in listOf("zip", "rar", "cbz") -> FileEntry.FileType.ZIP
            extension in listOf("mp3", "wav", "ogg", "flac", "m4a", "aac") -> FileEntry.FileType.AUDIO
            extension in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp") -> FileEntry.FileType.VIDEO
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

    companion object {
        fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
        }
    }

    suspend fun readFileContent(path: String, manualCharset: String? = null): String {
        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) throw java.io.FileNotFoundException("File not found: $path")
            
            // Limit to 4MB for text
            val limit = 4 * 1024 * 1024L
            val length = file.length()
            val bytes = if (length > limit) {
                val buffer = ByteArray(limit.toInt())
                java.io.FileInputStream(file).use { it.read(buffer) }
                buffer
            } else {
                file.readBytes()
            }
            
            if (manualCharset != null) {
                return@withContext String(bytes, java.nio.charset.Charset.forName(manualCharset))
            }

            // Use improved EncodingDetector
            val charset = EncodingDetector.detectEncoding(bytes)
            return@withContext String(bytes, charset)
        }
    }

    fun csvToHtml(csvContent: String): String {
        val lines = csvContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        
        val sb = StringBuilder()
        sb.append("<table border='1' style='border-collapse: collapse; width: 100%;'>")
        lines.forEach { line ->
            sb.append("<tr>")
            // Simple split, doesn't handle escaped commas in quotes perfectly but good enough for now
            val cols = line.split(",")
            cols.forEach { col ->
                sb.append("<td style='padding: 8px;'>").append(col.trim()).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    suspend fun getFileCharset(path: String): java.nio.charset.Charset = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext java.nio.charset.StandardCharsets.UTF_8
        
        val buffer = ByteArray(4096)
        val read = java.io.FileInputStream(file).use { it.read(buffer) }
        val bytes = if (read < 4096) buffer.copyOf(read) else buffer
        
        return@withContext EncodingDetector.detectEncoding(bytes)
    }

    suspend fun readFileChunk(path: String, offset: Long, size: Int): String {
         // Not recommended for variable-width text files without byte-mapping.
         // Better to use readLinesChunk.
         return ""
    }

    suspend fun readLinesChunk(path: String, startLine: Int, count: Int, manualCharset: String? = null): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext "" to false
        
        val charset = if (manualCharset != null) {
            try { java.nio.charset.Charset.forName(manualCharset) } catch (e: Exception) { getFileCharset(path) }
        } else {
            getFileCharset(path)
        }
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
