package com.uviewer_android.data.model

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val type: FileType,
    val lastModified: Long,
    val size: Long,
    val isWebDav: Boolean = false,
    val serverId: Int? = null,
    val isPinned: Boolean = false
) {
    enum class FileType {
        FOLDER, IMAGE, TEXT, EPUB, AUDIO, VIDEO, UNKNOWN, ZIP, HTML, PDF, WEBP, CSV, IMAGE_ZIP, RAR, SEVEN_ZIP
    }
}
