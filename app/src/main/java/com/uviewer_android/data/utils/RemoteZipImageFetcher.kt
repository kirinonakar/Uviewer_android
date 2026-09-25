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

class RemoteZipImageFetcherFactory(private val webDavRepository: WebDavRepository) : Fetcher.Factory<coil3.Uri> {
    private val managers = mutableMapOf<String, RemoteZipManager>()

    override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val androidUri = data.toAndroidUri()
        if (androidUri.scheme != "webdav-zip") return null
        
        val serverId = androidUri.host?.toIntOrNull() ?: return null
        val zipPath = androidUri.path ?: return null
        val entryName = androidUri.getQueryParameter("entry") ?: return null
        
        val managerKey = "$serverId:$zipPath"
        
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? {
                Log.d("RemoteZipFetcher", "Fetching $entryName from $zipPath (Server $serverId)")
                
                try {
                    val zipSize = webDavRepository.getFileSize(serverId, zipPath)
                    if (zipSize <= 0) return null

                    val manager = synchronized(managers) {
                        managers.getOrPut(managerKey) {
                             RemoteZipManager(webDavRepository, serverId, zipPath, zipSize)
                        }
                    }
                    
                    val entries = manager.getEntries()
                    val entry = entries.find { it.name == entryName } ?: return null
                    val dataBytes = manager.getEntryData(entry) ?: return null
                    
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
                    Log.e("RemoteZipFetcher", "Error fetching entry $entryName", e)
                    return null
                }
            }
        }
    }
}
