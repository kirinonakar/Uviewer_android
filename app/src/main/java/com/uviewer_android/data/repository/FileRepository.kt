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
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) throw java.io.FileNotFoundException("File not found: $path")
            
            val bytes = file.readBytes()
            
            // Try UTF-8 first with strict decoding
            try {
                val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                return@withContext decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                // Ignore, try next
            }
            
            // Try EUC-KR (common for Korean legacy)
            try {
                return@withContext String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
            } catch (e: Exception) {}

            // Try Shift_JIS (Japanese)
            try {
                return@withContext String(bytes, java.nio.charset.Charset.forName("Shift_JIS"))
            } catch (e: Exception) {}
            
            // Try Windows-1252 (Western)
             try {
                return@withContext String(bytes, java.nio.charset.Charset.forName("windows-1252"))
            } catch (e: Exception) {}

            // Fallback to platform default (likely UTF-8 non-strict) or just UTF-8 replace
            return@withContext String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        }
    }
}
