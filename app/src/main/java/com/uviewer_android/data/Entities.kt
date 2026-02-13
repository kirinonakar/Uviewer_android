package com.uviewer_android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val path: String, // Full path or WebDAV URL
    val isWebDav: Boolean = false,
    val serverId: Int? = null, // Link to WebDavServer if applicable
    val type: String, // "image", "document", "folder"
    val isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey val path: String, // Use path as primary key to avoid duplicates
    val title: String,
    val isWebDav: Boolean = false,
    val serverId: Int? = null,
    val type: String,
    val lastAccessed: Long = System.currentTimeMillis(),
    val progress: Float = 0f, // Reading progress (0.0 to 1.0)
    val pageIndex: Int = 0 // Page number or scroll position
)

@Entity(tableName = "webdav_servers")
data class WebDavServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String // Base URL
)

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val path: String,
    val isWebDav: Boolean = false,
    val serverId: Int? = null,
    val type: String, // "IMAGE", "DOCUMENT", "PDF", "VIDEO"
    val position: Int = 0, // Line number or page index
    val timestamp: Long = System.currentTimeMillis()
)
