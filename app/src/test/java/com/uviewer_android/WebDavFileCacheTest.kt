package com.uviewer_android

import com.uviewer_android.data.utils.WebDavFileCache
import com.uviewer_android.network.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavFileCacheTest {
    private fun file(etag: String? = null, modified: Long = 0L, size: Long = 12L) =
        WebDavClient.WebDavFile("/book.pdf", "book.pdf", false, size, modified, null, etag)

    @Test
    fun sameRevisionReusesCacheAndChangesUseNewCache() {
        val original = WebDavFileCache.revision(file("v1", 1000L))
        assertEquals(WebDavFileCache.key(1, "/book.pdf", original), WebDavFileCache.key(1, "/book.pdf", original))
        assertNotEquals(WebDavFileCache.key(1, "/book.pdf", original),
            WebDavFileCache.key(1, "/book.pdf", WebDavFileCache.revision(file("v2", 1000L))))
        assertNotEquals(WebDavFileCache.key(1, "/book.pdf", original),
            WebDavFileCache.key(2, "/book.pdf", original))
    }

    @Test
    fun modifiedTimeAndSizeDetectChangesWithoutEtag() {
        val initial = WebDavFileCache.revision(file(modified = 1000L))
        assertNotEquals(initial, WebDavFileCache.revision(file(modified = 2000L)))
        assertNotEquals(initial, WebDavFileCache.revision(file(modified = 1000L, size = 13L)))
    }

    @Test
    fun missingRevisionNeverReusesAnOldDownload() {
        assertNull(WebDavFileCache.revision(file()))
        assertNotEquals(WebDavFileCache.key(1, "/book.pdf", null), WebDavFileCache.key(1, "/book.pdf", null))
    }
}
