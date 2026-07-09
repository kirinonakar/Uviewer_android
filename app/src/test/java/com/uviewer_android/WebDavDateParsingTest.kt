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
}
