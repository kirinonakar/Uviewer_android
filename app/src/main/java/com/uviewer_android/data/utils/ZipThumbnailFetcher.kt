package com.uviewer_android.data.utils

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File

class ZipThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = ThumbnailUtils.getFirstImageFromZip(file, 200) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val extension = data.extension.lowercase()
            if (extension == "zip" || extension == "cbz") {
                return ZipThumbnailFetcher(data, options)
            }
            return null
        }
    }
}
