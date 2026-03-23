package com.uviewer_android.data.utils

import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.SourceResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.uviewer_android.data.repository.WebDavRepository
import okio.Buffer

class Remote7zImageFetcherFactory(private val webDavRepository: WebDavRepository) : Fetcher.Factory<android.net.Uri> {
    private val managers = mutableMapOf<String, Remote7zManager>()

    override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "webdav-7z") return null
        
        val serverId = data.host?.toIntOrNull() ?: return null
        val path = data.path ?: return null
        val entryName = data.getQueryParameter("entry") ?: return null
        
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
                    
                    return SourceResult(
                        source = coil.decode.ImageSource(
                            source = Buffer().write(dataBytes),
                            context = options.context
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
