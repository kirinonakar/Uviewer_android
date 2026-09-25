package com.uviewer_android

import android.app.Application
import com.uviewer_android.data.AppDatabase
import com.uviewer_android.data.repository.CredentialsManager

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import com.uviewer_android.data.utils.AnimatedAvifDecoder
import android.os.Build

class UviewerApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer

    val libraryThumbnailCache by lazy {
        com.uviewer_android.data.utils.LibraryThumbnailCache(this, newImageLoader(this))
    }

    // Keep small library previews alive independently of full-resolution viewer images.
    val thumbnailImageLoader: ImageLoader by lazy {
        newImageLoader(this).newBuilder()
            .components { add(com.uviewer_android.data.utils.LibraryThumbnailCache.Factory(libraryThumbnailCache)) }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizeBytes(minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4))
                    .build()
            }
            .crossfade(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        
        // Setup global crash reporting to file
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(getExternalFilesDir(null), "crash_logs.txt")
                java.io.FileOutputStream(logFile, true).use { fos ->
                    java.io.PrintWriter(fos).use { pw ->
                        pw.println("\n--- CRASH REPORT ---")
                        pw.println("Date: ${java.util.Date()}")
                        pw.println("Thread: ${thread.name}")
                        pw.println("Throwable: ${throwable.javaClass.name}: ${throwable.message}")
                        throwable.printStackTrace(pw)
                        pw.println("---------------------\n")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedAvifDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(com.uviewer_android.data.utils.ZipThumbnailFetcher.Factory())
                add(com.uviewer_android.data.utils.RemoteZipImageFetcherFactory(container.webDavRepository))
                add(com.uviewer_android.data.utils.Remote7zImageFetcherFactory(container.webDavRepository))
                add(com.uviewer_android.data.utils.WaitingFileFetcherFactory())
            }
            .crossfade(true)
            .build()
    }
}
