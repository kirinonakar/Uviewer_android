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
            }
            .crossfade(true)
            .build()
    }
}
