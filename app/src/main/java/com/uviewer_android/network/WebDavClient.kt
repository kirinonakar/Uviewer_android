package com.uviewer_android.network

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.uviewer_android.data.WebDavServer
import android.util.Base64

class WebDavClient(
    private val server: com.uviewer_android.data.WebDavServer,
    private val username: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getAuthHeader(password: String): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    data class WebDavFile(
        val href: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val contentType: String?,
        val etag: String? = null
    )

    fun buildUrl(path: String): String {
        val baseHttpUrl = server.url.trimEnd('/').toHttpUrl()
        val builder = baseHttpUrl.newBuilder()
        
        val segments = path.split("/").filter { it.isNotEmpty() }
        
        segments.forEach { segment ->
            // Robustly handle '+' by manually encoding it as %2B
            // addPathSegment doesn't encode '+' because it's technically allowed in paths,
            // but many servers treat it as space.
            val tempUrl = "http://localhost/".toHttpUrl().newBuilder()
                .addPathSegment(segment)
                .build()
            
            val encodedSegment = tempUrl.encodedPathSegments[0].replace("+", "%2B")
            builder.addEncodedPathSegment(encodedSegment)
        }
        
        if (path.endsWith("/") && segments.isNotEmpty()) {
            builder.addEncodedPathSegment("") 
        }
        
        return builder.build().toString()
    }

    suspend fun listFiles(password: String, path: String = "/"): List<WebDavFile> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Depth", "1")
                .header("Authorization", getAuthHeader(password))
                .method("PROPFIND", null)
                .build()

            try {
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        return@withContext emptyList()
                    }
                    val body = it.body?.string() ?: return@withContext emptyList()
                    parseWebDavXml(body)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getFileSize(password: String, path: String): Long {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Depth", "0")
                .header("Authorization", getAuthHeader(password))
                .method("PROPFIND", null)
                .build()

            try {
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) return@withContext 0L
                    val body = it.body?.string() ?: return@withContext 0L
                    val files = parseWebDavXml(body)
                    files.firstOrNull()?.size ?: 0L
                }
            } catch (e: Exception) {
                0L
            }
        }
    }

    suspend fun getFileInfo(password: String, path: String): WebDavFile? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(path))
                .header("Depth", "0")
                .header("Authorization", getAuthHeader(password))
                .method("PROPFIND", null)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to inspect file: ${response.code}")
                val body = response.body.string()
                parseWebDavXml(body).firstOrNull() ?: throw IOException("Missing WebDAV file metadata")
            }
        }

    internal fun parseWebDavXml(xml: String): List<WebDavFile> {
        fun org.jsoup.nodes.Element.localName() = tagName().substringAfter(':').lowercase(java.util.Locale.ROOT)
        fun org.jsoup.nodes.Element.child(name: String) = children().firstOrNull { it.localName() == name }
        val document = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        return document.getAllElements().filter { it.localName() == "response" }.mapNotNull { response ->
            val href = response.child("href")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val properties = response.children().filter { it.localName() == "propstat" }.filter { propstat ->
                val status = propstat.child("status")?.text()
                status == null || Regex("HTTP/[^ ]+\\s+2[0-9]{2}(?:\\s|$)").containsMatchIn(status)
            }.flatMap { it.child("prop")?.children()?.toList() ?: emptyList() }
            fun values(name: String) = properties.filter { it.localName() == name }
            fun text(name: String) = values(name).firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
            val name = text("displayname") ?: runCatching {
                java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8").trimEnd('/').substringAfterLast('/')
            }.getOrDefault(href.trimEnd('/').substringAfterLast('/'))
            WebDavFile(
                href = href,
                name = name,
                isDirectory = values("resourcetype").any { type -> type.children().any { it.localName() == "collection" } },
                size = values("getcontentlength").firstNotNullOfOrNull { it.text().trim().toLongOrNull()?.takeIf { size -> size >= 0 } } ?: 0L,
                // A missing/failed property must not overwrite a valid timestamp from another propstat.
                lastModified = values("getlastmodified").firstNotNullOfOrNull { parseWebDavDate(it.text()).takeIf { time -> time > 0L } } ?: 0L,
                contentType = text("getcontenttype"),
                etag = text("getetag")
            )
        }
    }

    suspend fun checkConnection(password: String): Boolean {
        return try {
            val url = buildUrl("/")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader(password))
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()

            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun downloadContent(password: String, path: String): ByteArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader(password))
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) throw IOException("Failed to download file ($url): ${it.code}")
                it.body?.bytes() ?: byteArrayOf()
            }
        }
    }

    suspend fun downloadRange(password: String, path: String, start: Long, end: Long): ByteArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader(password))
                .header("Range", "bytes=$start-$end")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful && it.code != 206) {
                    throw IOException("Failed to download range ($url): ${it.code}")
                }
                it.body?.bytes() ?: byteArrayOf()
            }
        }
    }

    suspend fun downloadToFile(password: String, path: String, destinationFile: java.io.File, progress: ((Float) -> Unit)? = null) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader(password))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to download file ($url): ${response.code}")
                val body = response.body
                val totalBytes = body.contentLength()
                body.byteStream().use { input ->
                    java.io.FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                progress?.invoke(totalRead.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun parseWebDavDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val value = dateStr.trim()
        try {
            return java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            return java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            // Ignore
        }
        // Some WebDAV servers use older HTTP date representations.
        for (pattern in listOf("EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
            val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("GMT")
                set2DigitYearStart(java.util.Date(0))
            }
            val position = java.text.ParsePosition(0)
            val parsed = format.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return 0L
    }
}
