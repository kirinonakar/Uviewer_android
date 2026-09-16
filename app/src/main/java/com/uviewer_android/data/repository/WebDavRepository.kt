package com.uviewer_android.data.repository

import com.uviewer_android.data.WebDavServerDao
import com.uviewer_android.data.model.FileEntry
import com.uviewer_android.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

private const val MAX_SEARCH_DEPTH = 64

class WebDavRepository(
    private val webDavServerDao: WebDavServerDao,
    private val credentialsManager: CredentialsManager
) {

    private val clientCache = mutableMapOf<Int, WebDavClient>()

    suspend fun listFiles(serverId: Int, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val client = getClient(serverId) ?: return@withContext emptyList()
        val password = credentialsManager.getPassword(serverId) // Removed early return if null
        val server = webDavServerDao.getById(serverId) ?: return@withContext emptyList()
        val webDavFiles = try {
            client.listFiles(password ?: "", path)
        } catch (e: Exception) {
            emptyList()
        }

        val serverPath = try { java.net.URL(server.url).path.trimEnd('/') } catch (e: Exception) { "" }
        
        val requestedPath = if (path.endsWith("/")) path.dropLast(1) else path

        webDavFiles.mapNotNull { file ->
            var decodedPath = file.href
            // Robust path decoding: only decode if it looks like it's percent-encoded.
            // Avoid java.net.URLDecoder for raw paths as it turns '+' into ' '.
            if (decodedPath.contains("%")) {
                try {
                    // Replace '+' with '%2B' before decoding to preserve '+' if it was literal
                    val safeForDecoder = decodedPath.replace("+", "%2B")
                    decodedPath = java.net.URLDecoder.decode(safeForDecoder, "UTF-8")
                } catch (e: Exception) { }
            }
            val decodedHref = decodedPath
            
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
            
            var relativePath = cleanPath
            if (serverPath.isNotEmpty() && relativePath.startsWith(serverPath, ignoreCase = true)) {
                relativePath = relativePath.substring(serverPath.length)
            }
            if (!relativePath.startsWith("/")) relativePath = "/" + relativePath

            var normRelative = relativePath
            if (!normRelative.startsWith("/")) normRelative = "/$normRelative"
            if (normRelative.length > 1) normRelative = normRelative.trimEnd('/')

            var normRequested = requestedPath
            if (!normRequested.startsWith("/")) normRequested = "/$normRequested"
            if (normRequested.length > 1) normRequested = normRequested.trimEnd('/')

            if (normRelative == normRequested) {
                return@mapNotNull null
            }

            val type = when {
                file.isDirectory -> FileEntry.FileType.FOLDER
                FileEntry.isImageFileName(file.name) -> FileEntry.FileType.IMAGE
                file.name.endsWith(".txt", true) || file.name.endsWith(".md", true) || 
                file.name.endsWith(".log", true) || file.name.endsWith(".aozora", true) ||
                file.name.endsWith(".json", true) -> FileEntry.FileType.TEXT
                file.name.endsWith(".csv", true) -> FileEntry.FileType.CSV
                file.name.endsWith(".html", true) || file.name.endsWith(".htm", true) || file.name.endsWith(".xhtml", true) -> FileEntry.FileType.HTML
                file.name.endsWith(".pdf", true) -> FileEntry.FileType.PDF
                file.name.endsWith(".epub", true) -> FileEntry.FileType.EPUB
                file.name.endsWith(".mp3", true) || file.name.endsWith(".wav", true) || 
                file.name.endsWith(".ogg", true) || file.name.endsWith(".flac", true) ||
                file.name.endsWith(".m4a", true) || file.name.endsWith(".aac", true) -> FileEntry.FileType.AUDIO
                file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) || 
                file.name.endsWith(".webm", true) || file.name.endsWith(".avi", true) || 
                file.name.endsWith(".mov", true) || file.name.endsWith(".3gp", true) -> FileEntry.FileType.VIDEO
                file.name.endsWith(".zip", true) || file.name.endsWith(".rar", true) || file.name.endsWith(".cbz", true) -> FileEntry.FileType.ZIP
                else -> FileEntry.FileType.UNKNOWN
            }

            FileEntry(
                name = file.name,
                path = relativePath,
                isDirectory = file.isDirectory,
                type = type,
                lastModified = file.lastModified,
                size = file.size,
                serverId = serverId,
                isWebDav = true
            )
        }
    }

    /**
     * Searches [rootPath] and every sub-folder beneath it on server [serverId] for entries whose
     * name contains [query], reporting each match through [onResult] in the order it is found.
     *
     * Sub-folders are listed breadth-first, reading up to [maxConcurrency] folders at once, so the
     * tree is walked with several threads while matches stream out as soon as they are discovered.
     */
    suspend fun searchFilesRecursively(
        serverId: Int,
        rootPath: String,
        query: String,
        maxConcurrency: Int = 6,
        onResult: suspend (FileEntry) -> Unit
    ) = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return@withContext

        val parallelReads = maxConcurrency.coerceAtLeast(1)
        var folders = listOf(rootPath)
        var depth = 0

        while (folders.isNotEmpty() && depth <= MAX_SEARCH_DEPTH) {
            val nextFolders = ArrayList<String>()
            for (batch in folders.chunked(parallelReads)) {
                coroutineScope {
                    val listings = batch.map { folderPath ->
                        async {
                            try {
                                listFiles(serverId, folderPath)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                    for (listing in listings) {
                        for (entry in listing.await()) {
                            if (entry.name.contains(normalizedQuery, ignoreCase = true)) {
                                onResult(entry.copy(location = relativeLocation(rootPath, entry.path)))
                            }
                            if (entry.isDirectory) nextFolders.add(entry.path)
                        }
                    }
                }
            }
            folders = nextFolders
            depth++
        }
    }

    /** Folder of [path] relative to [rootPath]; null when [path] sits directly in [rootPath]. */
    private fun relativeLocation(rootPath: String, path: String): String? {
        val parent = path.substringBeforeLast('/', "")
        val normalizedRoot = rootPath.trimEnd('/')
        val relative = if (normalizedRoot.isEmpty()) {
            parent.trimStart('/')
        } else {
            parent.removePrefix(normalizedRoot).trimStart('/')
        }
        return relative.ifEmpty { null }
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
        val server = com.uviewer_android.data.WebDavServer(id = serverId, name = serverName, url = serverUrl)
        return WebDavClient(server, username)
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

    suspend fun buildUrl(serverId: Int, path: String): String? {
        val client = getClient(serverId) ?: return null
        return client.buildUrl(path)
    }

    suspend fun getFileSize(serverId: Int, path: String): Long {
        val client = getClient(serverId) ?: return 0L
        val password = credentialsManager.getPassword(serverId) ?: return 0L
        return client.getFileSize(password, path)
    }

    suspend fun readFileContent(serverId: Int, path: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val client = getClient(serverId) ?: throw Exception("Client not found for server $serverId")
            val password = credentialsManager.getPassword(serverId) ?: throw Exception("Password not found")
            client.downloadContent(password, path)
        }
    }

    suspend fun readRange(serverId: Int, path: String, start: Long, end: Long): ByteArray {
        return withContext(Dispatchers.IO) {
            val client = getClient(serverId) ?: throw Exception("Client not found for server $serverId")
            val password = credentialsManager.getPassword(serverId) ?: throw Exception("Password not found")
            client.downloadRange(password, path, start, end)
        }
    }

    suspend fun downloadFile(serverId: Int, path: String, targetFile: java.io.File, progress: ((Float) -> Unit)? = null) {
        withContext(Dispatchers.IO) {
            val client = getClient(serverId) ?: throw Exception("Client not found for server $serverId")
            val password = credentialsManager.getPassword(serverId) ?: throw Exception("Password not found")
            client.downloadToFile(password, path, targetFile, progress)
        }
    }
}
