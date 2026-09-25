package com.uviewer_android.data.utils

import android.graphics.drawable.BitmapDrawable
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.asImage
import coil3.fetch.ImageFetchResult
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import java.io.File

class ZipThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = ThumbnailUtils.getFirstImageFromZip(file, 512) ?: return null
        return ImageFetchResult(
            image = BitmapDrawable(options.context.resources, bitmap).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            val path = data.path ?: return null
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension == "zip" || extension == "cbz") {
                return ZipThumbnailFetcher(File(path), options)
            }
            return null
        }
    }
}
