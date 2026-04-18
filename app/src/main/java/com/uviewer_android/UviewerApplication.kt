package com.uviewer_android

import android.app.Application
import com.uviewer_android.data.AppDatabase
import com.uviewer_android.data.repository.CredentialsManager

import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.Build

class UviewerApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer

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

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
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
