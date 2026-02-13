package com.uviewer_android.data.model

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val type: FileType,
    val lastModified: Long,
    val size: Long,
    val serverId: Int? = null, // ID of WebDavServer for remote files
    val isWebDav: Boolean = false
) {
    enum class FileType {
        FOLDER, IMAGE, TEXT, EPUB, AUDIO, VIDEO, UNKNOWN, ZIP
    }
}
