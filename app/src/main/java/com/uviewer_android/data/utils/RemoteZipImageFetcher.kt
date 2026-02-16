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

class RemoteZipImageFetcherFactory(private val webDavRepository: WebDavRepository) : Fetcher.Factory<android.net.Uri> {
    private val managers = mutableMapOf<String, RemoteZipManager>()

    override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "webdav-zip") return null
        
        val serverId = data.host?.toIntOrNull() ?: return null
        val zipPath = data.path ?: return null
        val entryName = data.getQueryParameter("entry") ?: return null
        
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
                    
                    return SourceResult(
                        source = coil.decode.ImageSource(
                            source = Buffer().write(dataBytes),
                            context = options.context
                        ),
                        mimeType = options.context.contentResolver.getType(android.net.Uri.parse(entryName)),
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
