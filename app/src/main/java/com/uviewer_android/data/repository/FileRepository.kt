package com.uviewer_android.data.repository

import com.uviewer_android.data.model.FileEntry
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
            // Simple robust read: Try UTF-8. 
            // Better: libraries like UniversalDetector. For now simple standard.
            file.readText(java.nio.charset.Charset.defaultCharset()) 
        }
    }
}
