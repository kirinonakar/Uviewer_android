package com.uviewer_android.data.utils

import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.network.WebDavClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** A remote revision is checked every time a WebDAV file is opened. */
internal object WebDavFileCache {
    fun revision(info: WebDavClient.WebDavFile?): String? {
        if (info == null) return null
        val etag = info.etag?.takeIf { it.isNotBlank() }
        if (etag == null && info.lastModified <= 0L) return null
        return "$etag:${info.lastModified}:${info.size}"
    }

    fun key(serverId: Int, path: String, revision: String?): String =
        sha256("$serverId\n$path\n${revision ?: UUID.randomUUID()}")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun resolve(
        repository: WebDavRepository,
        cacheManager: CacheManager?,
        serverId: Int,
        path: String,
        cacheDir: File
    ): File {
        val info = repository.getFileInfo(serverId, path)
        val token = key(serverId, path, revision(info))
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { ".$it" } ?: ""
        val target = File(cacheDir, "webdav_$token$extension")
        if (target.isFile && target.length() > 0L) {
            cacheManager?.touch(target)
            return target
        }

        cacheManager?.ensureCapacity(info?.size?.coerceAtLeast(0L) ?: 0L)
        cacheDir.mkdirs()
        val pending = File(cacheDir, "${target.name}.${UUID.randomUUID()}.part")
        try {
            repository.downloadFile(serverId, path, pending)
            if (!pending.isFile || (info != null && info.size > 0 && pending.length() != info.size)) {
                throw IOException("Incomplete WebDAV download: $path")
            }
            if (!pending.renameTo(target)) {
                if (!target.isFile || target.length() == 0L) throw IOException("Could not cache WebDAV file: $path")
            }
        } finally {
            pending.delete()
        }
        return target
    }
}
