package com.uviewer_android.data.repository

import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

class WebDavRepository(
    private val webDavServerDao: WebDavServerDao, // For server info
    private val credentialsManager: CredentialsManager
) {

    private val clientCache = mutableMapOf<Int, WebDavClient>()

    suspend fun listFiles(serverId: Int, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val client = getClient(serverId) ?: return@withContext emptyList()
        val server = webDavServerDao.getById(serverId) ?: return@withContext emptyList()
        val webDavFiles = client.listFiles(path)

        val serverPath = java.net.URL(server.url).path.trimEnd('/')
        
        // Normalize requested path for filtering
        val requestedPath = if (path.endsWith("/")) path.dropLast(1) else path

        webDavFiles.mapNotNull { file ->
            // file.href can be a full URL (https://...) or an absolute path (/...) or even a relative path (file.txt)
            val decodedHref = java.net.URLDecoder.decode(file.href, "UTF-8")
            
            // Extract path part if it's a full URL
            var cleanPath = if (decodedHref.startsWith("http://", true) || decodedHref.startsWith("https://", true)) {
                try {
                    java.net.URL(decodedHref).path
                } catch (e: Exception) {
                    decodedHref.substringAfter("://").substringAfter("/")
                }
            } else {
                decodedHref
            }

            if (!cleanPath.startsWith("/")) cleanPath = "/" + cleanPath
            
            // Calculate relative path from server root
            var relativePath = cleanPath
            if (serverPath.isNotEmpty() && relativePath.startsWith(serverPath, ignoreCase = true)) {
                relativePath = relativePath.substring(serverPath.length)
            }
            if (!relativePath.startsWith("/")) relativePath = "/" + relativePath

            // Filter out the directory itself
            // Normalized comparison
            val normRelative = relativePath.trimEnd('/')
            val normRequested = requestedPath.trimEnd('/')
            if (normRelative == normRequested || (normRequested.isEmpty() && normRelative == "/")) {
                return@mapNotNull null
            }

            // Determine FileType
            val type = when {
                file.isDirectory -> FileEntry.FileType.FOLDER
                file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) -> FileEntry.FileType.IMAGE
                file.name.endsWith(".txt", true) -> FileEntry.FileType.TEXT
                file.name.endsWith(".epub", true) -> FileEntry.FileType.EPUB
                file.name.endsWith(".mp3", true) || file.name.endsWith(".wav", true) -> FileEntry.FileType.AUDIO
                file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) -> FileEntry.FileType.VIDEO
                file.name.endsWith(".zip", true) -> FileEntry.FileType.ZIP
                else -> FileEntry.FileType.UNKNOWN
            }

            FileEntry(
                name = file.name,
                path = relativePath, // This path is relative to server root, ready for next listFiles call
                isDirectory = file.isDirectory,
                type = type,
                lastModified = file.lastModified,
                size = file.size,
                serverId = serverId,
                isWebDav = true
            )
        }
    }

    private suspend fun getClient(serverId: Int): WebDavClient? {
        if (clientCache.containsKey(serverId)) {
            return clientCache[serverId]
        }
        
        val server = webDavServerDao.getById(serverId) ?: return null
        val client = createClientFor(serverId, server.name, server.url)
        if (client != null) {
            clientCache[serverId] = client
        }
        return client
    }

    suspend fun createClientFor(serverId: Int, serverName: String, serverUrl: String): WebDavClient? {
        val username = credentialsManager.getUsername(serverId) ?: return null
        val password = credentialsManager.getPassword(serverId) ?: return null
        // Reconstruct basic server object for client
        val server = com.uviewer_android.data.WebDavServer(id = serverId, name = serverName, url = serverUrl)
        return WebDavClient(server, username, password)
    }

    suspend fun getAuthHeader(serverId: Int): String? {
        val username = credentialsManager.getUsername(serverId) ?: return null
        val password = credentialsManager.getPassword(serverId) ?: return null
        val credentials = "$username:$password"
        return "Basic " + android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)
    }

    suspend fun getServer(serverId: Int): com.uviewer_android.data.WebDavServer? {
        return webDavServerDao.getById(serverId)
    }

    suspend fun readFileContent(serverId: Int, path: String): String {
        return withContext(Dispatchers.IO) {
            val client = getClient(serverId) ?: throw Exception("Client not found for server $serverId")
            client.downloadContent(path)
        }
    }

    suspend fun downloadFile(serverId: Int, path: String, targetFile: java.io.File) {
        withContext(Dispatchers.IO) {
            val client = getClient(serverId) ?: throw Exception("Client not found for server $serverId")
            client.downloadToFile(path, targetFile)
        }
    }
}
