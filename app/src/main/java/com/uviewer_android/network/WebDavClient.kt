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
        val contentType: String?
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
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                parseWebDavXml(body)
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
                if (!response.isSuccessful) return@withContext 0L

                val body = response.body?.string() ?: return@withContext 0L
                val files = parseWebDavXml(body)
                files.firstOrNull()?.size ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    private fun parseWebDavXml(xml: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true 
            // Protect against XXE (XML External Entity) attacks
            try {
                factory.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            } catch (e: Exception) {
                // Feature not supported by current parser
            }
            
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))

            var eventType = parser.eventType
            var currentHref = ""
            var currentName = ""
            var currentIsDirectory = false
            var currentSize = 0L
            var currentLastModified = 0L
            var currentContentType: String? = null
            
            var inResponse = false

            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        val name = parser.name
                        when (name) {
                            "response" -> {
                                currentHref = ""
                                currentName = ""
                                currentIsDirectory = false
                                currentSize = 0L
                                currentLastModified = 0L
                                currentContentType = null
                                inResponse = true
                            }
                            "href" -> if (inResponse) currentHref = parser.nextText()
                            "displayname" -> if (inResponse) currentName = parser.nextText()
                            "getcontentlength" -> if (inResponse) currentSize = parser.nextText().toLongOrNull() ?: 0L
                            "getlastmodified" -> {
                                if (inResponse) {
                                    currentLastModified = 0L 
                                }
                            }
                            "collection" -> if (inResponse) currentIsDirectory = true
                            "getcontenttype" -> if (inResponse) currentContentType = parser.nextText()
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        val name = parser.name
                        if (name == "response") {
                             if (currentName.isEmpty() && currentHref.isNotEmpty()) {
                                 var decoded = java.net.URLDecoder.decode(currentHref, "UTF-8")
                                 if (decoded.endsWith("/")) decoded = decoded.dropLast(1)
                                 currentName = decoded.substringAfterLast('/')
                             }
                             files.add(WebDavFile(currentHref, currentName, currentIsDirectory, currentSize, currentLastModified, currentContentType))
                             inResponse = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Log error safely if needed
        }
        return files
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
            response.isSuccessful
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
            if (!response.isSuccessful) throw IOException("Failed to download file ($url): ${response.code}")
            
            response.body?.bytes() ?: byteArrayOf()
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
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Failed to download range ($url): ${response.code}")
            }
            
            response.body?.bytes() ?: byteArrayOf()
        }
    }

    suspend fun downloadToFile(password: String, path: String, destinationFile: java.io.File) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader(password))
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Failed to download file ($url): ${response.code}")
            
            response.body?.byteStream()?.use { input ->
                java.io.FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
