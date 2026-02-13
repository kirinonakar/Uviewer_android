package com.uviewer_android.data.model

data class EpubBook(
    val title: String,
    val author: String?,
    val coverPath: String?,
    val spine: List<EpubSpineItem>,
    val rootDir: String // Absolute path to unzipped EPUB root
)

data class EpubSpineItem(
    val href: String, // Relative to rootDir
    val id: String,
    val title: String? = null // Optional chapter title from TOC
)
