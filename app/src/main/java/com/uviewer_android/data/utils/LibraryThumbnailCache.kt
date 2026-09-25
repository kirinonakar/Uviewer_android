package com.uviewer_android.data.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.asDrawable
import coil3.asImage
import coil3.fetch.ImageFetchResult
import coil3.request.allowHardware
import coil3.fetch.Fetcher
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Scale
import com.uviewer_android.data.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

data class LibraryThumbnail(val path: String, val modified: Long, val size: Long) {
    val key: String get() = "library-thumbnail-v1:$path:$modified:$size"

    companion object {
        fun from(entry: FileEntry) = LibraryThumbnail(entry.path, entry.lastModified, entry.size)

        fun supports(entry: FileEntry) = !entry.isWebDav && !entry.isDirectory &&
            entry.type in setOf(FileEntry.FileType.IMAGE, FileEntry.FileType.ZIP, FileEntry.FileType.IMAGE_ZIP)
    }
}

/** Stores generated previews, not original images, so memory eviction never requires source decoding. */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class LibraryThumbnailCache(
    private val context: Context,
    private val sourceLoader: ImageLoader,
    private val diskCache: DiskCache = DiskCache.Builder()
        .directory(File(context.cacheDir, DIRECTORY).toOkioPath())
        .maxSizeBytes(512L * 1024 * 1024)
        .build()
) {
    private val locks = Array(64) { Mutex() }
    private val generationSlots = Semaphore(2)

    suspend fun preload(thumbnail: LibraryThumbnail) {
        load(thumbnail, preloadOnly = true)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { diskCache.clear() }

    private suspend fun load(thumbnail: LibraryThumbnail, preloadOnly: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            locks[(thumbnail.key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                diskCache.openSnapshot(thumbnail.key)?.use { snapshot ->
                    if (preloadOnly) return@withLock null
                    BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath)?.let { return@withLock it }
                }
                generationSlots.withPermit {
                    val result = sourceLoader.execute(
                        ImageRequest.Builder(context)
                            .data(File(thumbnail.path))
                            .size(512)
                            .scale(Scale.FIT)
                            .allowHardware(false)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                    if (result !is SuccessResult) return@withPermit null
                    val drawable = result.image.asDrawable(context.resources)
                    val width = drawable.intrinsicWidth.coerceAtLeast(1)
                    val height = drawable.intrinsicHeight.coerceAtLeast(1)
                    val scale = minOf(1f, 512f / maxOf(width, height))
                    val bitmap = drawable.toBitmap(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    // Atomic edits prevent visible requests from reading incomplete previews.
                    val editor = diskCache.openEditor(thumbnail.key)
                    if (editor != null) {
                        try {
                            editor.metadata.toFile().writeText("")
                            editor.data.toFile().outputStream().use {
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                                    throw IOException("Could not encode thumbnail")
                                }
                            }
                            editor.commit()
                        } catch (exception: IOException) {
                            editor.abort()
                            // Still display the preview when storage is full/unavailable.
                        }
                    }
                    bitmap.takeUnless { preloadOnly }
                }
            }
        }

    class Factory(private val cache: LibraryThumbnailCache) : Fetcher.Factory<LibraryThumbnail> {
        override fun create(data: LibraryThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            Fetcher {
                cache.load(data, preloadOnly = false)?.let { bitmap ->
                    ImageFetchResult(BitmapDrawable(options.context.resources, bitmap).asImage(), true, DataSource.DISK)
                }
            }
    }

    companion object {
        const val DIRECTORY = "library_thumbnails"
    }
}
