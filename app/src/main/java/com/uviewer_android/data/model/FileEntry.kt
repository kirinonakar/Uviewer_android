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
    val isPinned: Boolean = false,
    val position: Int = -1,
    val positionTitle: String? = null,
    val progress: Float = 0f,
    val pinOrder: Int = 0
) {
    enum class FileType {
        FOLDER, IMAGE, TEXT, EPUB, AUDIO, VIDEO, UNKNOWN, ZIP, HTML, PDF, WEBP, CSV, IMAGE_ZIP, RAR, SEVEN_ZIP
    }
}
