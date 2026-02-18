package com.uviewer_android.data.utils

import android.content.Context
import com.uviewer_android.data.repository.UserPreferencesRepository
import java.io.File
import kotlinx.coroutines.flow.first
import android.util.Log

class CacheManager(
    private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    /**
     * Ensures that there is enough space in the cache by deleting old files (LRU).
     * @param neededBytes The amount of bytes we are about to add to the cache.
     */
    suspend fun ensureCapacity(neededBytes: Long) {
        val maxCacheSize = userPreferencesRepository.maxCacheSize.first()
        
        // We collect top-level cache items (files or directories)
        val cacheItems = mutableListOf<File>()
        
        fun collectTopItems(dir: File) {
            dir.listFiles()?.forEach { 
                cacheItems.add(it)
            }
        }
        
        collectTopItems(context.cacheDir)
        context.externalCacheDir?.let { collectTopItems(it) }
        
        var currentSize = calculateCurrentSize(cacheItems)
        
        Log.d("CacheManager", "Current size: $currentSize, Needed: $neededBytes, Max: $maxCacheSize")
        
        if (currentSize + neededBytes <= maxCacheSize) return
        
        // Sort by lastModified - oldest first
        cacheItems.sortBy { it.lastModified() }
        
        for (item in cacheItems) {
            val itemSize = getDirSize(item)
            if (item.deleteRecursively()) {
                currentSize -= itemSize
                Log.d("CacheManager", "Deleted ${item.name} to free $itemSize bytes. Current size: $currentSize")
                if (currentSize + neededBytes <= maxCacheSize) break
            }
        }
    }

    /**
     * Updates the last modified time of a file or directory to mark it as recently used.
     */
    fun touch(file: File) {
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
        }
    }

    private fun calculateCurrentSize(items: List<File>): Long {
        return items.sumOf { getDirSize(it) }
    }

    private fun getDirSize(file: File): Long {
        if (file.isFile) return file.length()
        var size: Long = 0
        file.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }
}
