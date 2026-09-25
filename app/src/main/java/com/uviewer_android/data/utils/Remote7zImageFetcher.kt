package com.uviewer_android.data.utils

import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import coil3.toAndroidUri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.uviewer_android.data.repository.WebDavRepository
import okio.Buffer

class Remote7zImageFetcherFactory(private val webDavRepository: WebDavRepository) : Fetcher.Factory<coil3.Uri> {
    private val managers = mutableMapOf<String, Remote7zManager>()

    override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val androidUri = data.toAndroidUri()
        if (androidUri.scheme != "webdav-7z") return null
        
        val serverId = androidUri.host?.toIntOrNull() ?: return null
        val path = androidUri.path ?: return null
        val entryName = androidUri.getQueryParameter("entry") ?: return null
        
        val managerKey = "$serverId:$path"
        
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                Log.d("Remote7zFetcher", "Fetching $entryName from $path (Server $serverId)")
                
                try {
                    val fileSize = webDavRepository.getFileSize(serverId, path)
                    if (fileSize <= 0) return null

                    val manager = synchronized(managers) {
                        managers.getOrPut(managerKey) {
                             Remote7zManager(webDavRepository, serverId, path, fileSize)
                        }
                    }
                    
                    val dataBytes = manager.getEntryData(entryName) ?: return null
                    
                    val extension = entryName.substringAfterLast('.', "").lowercase()
                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/*"
                    
                    return SourceFetchResult(
                        source = coil3.decode.ImageSource(
                            source = Buffer().write(dataBytes),
                            fileSystem = options.fileSystem
                        ),
                        mimeType = mimeType,
                        dataSource = DataSource.NETWORK
                    )
                } catch (e: Exception) {
                    Log.e("Remote7zFetcher", "Error fetching entry $entryName", e)
                    return null
                }
            }
        }
    }
}
