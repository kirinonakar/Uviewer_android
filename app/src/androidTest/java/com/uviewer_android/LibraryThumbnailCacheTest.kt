package com.uviewer_android

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.asImage
import coil3.fetch.ImageFetchResult
import coil3.fetch.Fetcher
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import com.uviewer_android.data.utils.LibraryThumbnail
import com.uviewer_android.data.utils.LibraryThumbnailCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryThumbnailCacheTest {
    @Test
    fun wholeFolderSurvivesMemoryEvictionAndNewCacheReader() = runBlocking {
        withCache { cache, disk, source, renderCount ->
            val items = (1..40).map { LibraryThumbnail("/test/image-$it.png", 1, 100) }
            items.forEach { cache.preload(it) }
            assertEquals(40, renderCount.get())

            // A new reader/loader has no decoded images in memory.
            val reader = LibraryThumbnailCache(context, source, disk)
            val loader = thumbnailLoader(reader)
            try {
                for (item in items.reversed()) assertTrue(loader.execute(request(item)) is SuccessResult)
                assertEquals(40, renderCount.get())
                assertTrue(loader.execute(request(items.first().copy(modified = 2))) is SuccessResult)
                assertEquals(41, renderCount.get())
                cache.clear()
                assertTrue(loader.execute(request(items.first())) is SuccessResult)
                assertEquals(42, renderCount.get())
            } finally {
                loader.shutdown()
            }
        }
    }

    @Test
    fun concurrentPreloadAndDisplayGenerateOnlyOnce() = runBlocking {
        withCache { cache, _, _, renderCount ->
            val item = LibraryThumbnail("/test/shared.png", 1, 100)
            val loader = thumbnailLoader(cache)
            try {
                coroutineScope {
                    (1..12).map { index ->
                        async {
                            if (index % 2 == 0) cache.preload(item)
                            else assertTrue(loader.execute(request(item)) is SuccessResult)
                        }
                    }.awaitAll()
                }
                assertEquals(1, renderCount.get())
            } finally {
                loader.shutdown()
            }
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(item: LibraryThumbnail) = ImageRequest.Builder(context)
        .data(item).size(512).scale(Scale.FIT)
        .memoryCachePolicy(CachePolicy.DISABLED).build()

    private fun thumbnailLoader(cache: LibraryThumbnailCache) = ImageLoader.Builder(context)
        .components { add(LibraryThumbnailCache.Factory(cache)) }.build()

    private suspend fun withCache(
        block: suspend (LibraryThumbnailCache, DiskCache, ImageLoader, AtomicInteger) -> Unit
    ) {
        val renderCount = AtomicInteger()
        val source = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<coil3.Uri> { _, _, _ ->
                Fetcher {
                    renderCount.incrementAndGet()
                    ImageFetchResult(
                        BitmapDrawable(context.resources, Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)).asImage(),
                        true, DataSource.DISK
                    )
                }
            })
        }.build()
        val disk = DiskCache.Builder()
            .directory(File(context.cacheDir, "thumbnail-test-${UUID.randomUUID()}").toOkioPath())
            .maxSizeBytes(16L * 1024 * 1024).build()
        try {
            block(LibraryThumbnailCache(context, source, disk), disk, source, renderCount)
        } finally {
            source.shutdown()
            disk.clear()
        }
    }
}
