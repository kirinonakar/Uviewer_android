package com.uviewer_android

import com.uviewer_android.data.WebDavServer
import com.uviewer_android.network.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavDateParsingTest {

    private val server = WebDavServer(id = 1, name = "Test Server", url = "http://example.com/dav")
    private val client = WebDavClient(server, "user")

    @Test
    fun testParseRfc1123Date() {
        // Thursday, 09 Jul 2026 11:27:00 GMT -> 1783596420000 ms
        val dateStr = "Thu, 09 Jul 2026 11:27:00 GMT"
        val parsed = client.parseWebDavDate(dateStr)
        assertEquals(1783596420000L, parsed)
    }

    @Test
    fun testParseIso8601Date() {
        // 2026-07-09T11:27:00Z -> 1783596420000 ms
        val dateStr = "2026-07-09T11:27:00Z"
        val parsed = client.parseWebDavDate(dateStr)
        assertEquals(1783596420000L, parsed)
    }

    @Test
    fun testParseInvalidDateReturnsZero() {
        val dateStr = "invalid-date-string"
        val parsed = client.parseWebDavDate(dateStr)
        assertEquals(0L, parsed)
    }

    @Test
    fun testParseNullOrBlankReturnsZero() {
        assertEquals(0L, client.parseWebDavDate(null))
        assertEquals(0L, client.parseWebDavDate(""))
        assertEquals(0L, client.parseWebDavDate("   "))
    }

    @Test
    fun serverWhitespaceOffsetsAndLegacyHttpDatesAreParsed() {
        val expected = 1783596420000L
        for (date in listOf("  Thu, 09 Jul 2026 11:27:00 GMT \n", "2026-07-09T20:27:00+09:00",
            "Thursday, 09-Jul-26 11:27:00 GMT", "Thu Jul 9 11:27:00 2026")) {
            assertEquals(date, expected, client.parseWebDavDate(date))
        }
    }

    @Test
    fun failedPropertiesDoNotEraseSuccessfulRemoteDates() {
        val files = client.parseWebDavXml("""
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/book2.jpg</D:href>
                <D:propstat><D:prop><D:displayname>book2.jpg</D:displayname>
                  <D:getlastmodified>Thu, 09 Jul 2026 11:27:00 GMT</D:getlastmodified>
                  <D:getcontentlength>123</D:getcontentlength><D:getetag>v1</D:getetag>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                <D:propstat><D:prop><D:getlastmodified/><D:getcontentlength/>
                  <D:getetag>invalid</D:getetag></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
              </D:response>
              <D:response><D:href>/folder10/</D:href><D:propstat><D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:getlastmodified>2026-07-09T12:27:00Z</D:getlastmodified>
              </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent())
        assertEquals(2, files.size)
        assertEquals(1783596420000L, files[0].lastModified)
        assertEquals(123L, files[0].size)
        assertEquals("v1", files[0].etag)
        assertEquals(true, files[1].isDirectory)
        assertEquals("folder10", files[1].name)
        val entries = files.map { com.uviewer_android.data.model.FileEntry(it.name, it.href, it.isDirectory,
            com.uviewer_android.data.model.FileEntry.FileType.IMAGE, it.lastModified, it.size, isWebDav = true) }
        assertEquals(listOf("folder10", "book2.jpg"), entries.sortedWith(com.uviewer_android.data.utils.FileEntrySort.comparator(
            com.uviewer_android.data.model.SortOption.DATE_DESC, directoriesFirst = false)).map { it.name })
        assertEquals(listOf("book2.jpg", "folder10"), entries.sortedWith(com.uviewer_android.data.utils.FileEntrySort.comparator(
            com.uviewer_android.data.model.SortOption.DATE_ASC, directoriesFirst = false)).map { it.name })
    }

    @Test
    fun missingDatesRemainUnknownAndEncodedPlusNamesArePreserved() {
        val files = client.parseWebDavXml("""
            <multistatus xmlns="DAV:"><response><href>/book+2%20test.jpg</href>
              <propstat><prop><getlastmodified>invalid-date</getlastmodified></prop>
                <status>HTTP/1.1 200 OK</status></propstat>
            </response></multistatus>
        """.trimIndent())
        assertEquals("book+2 test.jpg", files.single().name)
        assertEquals(0L, files.single().lastModified)
    }
}
