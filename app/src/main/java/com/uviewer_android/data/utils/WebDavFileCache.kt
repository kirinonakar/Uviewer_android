package com.uviewer_android.data.utils

import com.uviewer_android.data.repository.WebDavRepository
import com.uviewer_android.network.WebDavClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A remote revision is checked every time a WebDAV file is opened. */
internal object WebDavFileCache {
    internal const val FALLBACK_TTL_MS = 5 * 60 * 1000L
    private val locks = Array(32) { Mutex() }
    fun revision(info: WebDavClient.WebDavFile?): String? {
        if (info == null) return null
        val etag = info.etag?.takeIf { it.isNotBlank() }
        if (etag == null && info.lastModified <= 0L) return null
        return "$etag:${info.lastModified}:${info.size}"
    }

    fun key(serverId: Int, path: String, revision: String?): String =
        sha256("$serverId\n$path\n${revision ?: "unversioned"}")

    /** For derived caches that cannot revalidate the downloaded file themselves. */
    fun contentToken(info: WebDavClient.WebDavFile?, now: Long = System.currentTimeMillis()): String =
        revision(info) ?: "size:${info?.size ?: -1}:ttl:${now / FALLBACK_TTL_MS}"

    /** A content version must not change when LRU updates the downloaded file's mtime. */
    fun contentTimestamp(file: File): Long {
        val stamp = File(file.parentFile, "${file.name}.validated")
        return if (stamp.isFile) stamp.lastModified() else file.lastModified()
    }

    internal fun canReuse(size: Long, expectedSize: Long?, revision: String?, downloadedAt: Long, now: Long): Boolean =
        size > 0L && (expectedSize == null || expectedSize <= 0L || size == expectedSize) &&
            (revision != null || (downloadedAt > 0L && now >= downloadedAt && now - downloadedAt < FALLBACK_TTL_MS))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun resolve(
        repository: WebDavRepository,
        cacheManager: CacheManager?,
        serverId: Int,
        path: String,
        cacheDir: File
    ): File = locks[(key(serverId, path, null).hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        val info = repository.getFileInfo(serverId, path)
        val revision = revision(info)
        val token = key(serverId, path, revision)
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { ".$it" } ?: ""
        val target = File(cacheDir, "webdav_$token$extension")
        // LRU touches must not extend the freshness TTL.
        val stamp = File(cacheDir, "${target.name}.validated")
        if (target.isFile && canReuse(target.length(), info?.size, revision, stamp.lastModified(), System.currentTimeMillis())) {
            cacheManager?.touch(target)
            return@withLock target
        }

        cacheManager?.ensureCapacity(info?.size?.coerceAtLeast(0L) ?: 0L)
        cacheDir.mkdirs()
        val pending = File(cacheDir, "${target.name}.${UUID.randomUUID()}.part")
        try {
            repository.downloadFile(serverId, path, pending)
            if (!pending.isFile || (info != null && info.size > 0 && pending.length() != info.size)) {
                throw IOException("Incomplete WebDAV download: $path")
            }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            stamp.writeText("")
            stamp.setLastModified(System.currentTimeMillis())
        } finally {
            pending.delete()
        }
        target
    }
}
