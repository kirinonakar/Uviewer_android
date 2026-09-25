package com.uviewer_android.data.utils

import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import coil3.toAndroidUri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.File
import kotlinx.coroutines.delay

/**
 * A fetcher that waits for a local file to appear.
 * Used for archives being extracted in the background.
 */
class WaitingFileFetcherFactory : Fetcher.Factory<coil3.Uri> {
    override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val androidUri = data.toAndroidUri()
        if (androidUri.scheme != "waiting-file") return null
        
        val filePath = androidUri.path ?: return null
        
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                val file = File(filePath)
                Log.d("WaitingFileFetcher", "Waiting for file: $filePath")
                
                // Wait up to 10 minutes (Archive extraction can take time)
                var attempts = 0
                while (!(file.exists() && file.canRead() && file.length() > 0) && attempts < 1200) {
                    delay(500)
                    attempts++
                }
                
                if (!file.exists() || !file.canRead()) {
                    Log.e("WaitingFileFetcher", "Timeout or missing file: $filePath (exists=${file.exists()}, canRead=${file.canRead()}, checked $attempts times)")
                    return null
                }
                
                Log.d("WaitingFileFetcher", "File available: $filePath")
                
                return SourceFetchResult(
                    source = coil3.decode.ImageSource(
                        source = file.source().buffer(),
                        fileSystem = options.fileSystem
                    ),
                    mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()),
                    dataSource = DataSource.DISK
                )
            }
        }
    }
}
