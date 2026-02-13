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
    private val server: WebDavServer,
    private val username: String,
    private val passwordEncrypted: String // Assuming this is decrypted password or encrypted if handling differently? Wait, OkHttp needs plain password for Basic Auth. So I should rename to password and pass plain text.
) {
    // Actually, let's rename to passwordPlain and clarify.
    // Or just password. CredentialsManager returns decrypted string? Yes, sharedPreferences.getString returns the value stored.
    // EncryptedSharedPreferences handles encryption transparently.
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getAuthHeader(): String {
        val credentials = "$username:$passwordEncrypted" // It should be plain password here if valid for Basic Auth
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

    private fun buildUrl(path: String): String {
        return try {
            val baseHttpUrl = server.url.trimEnd('/').toHttpUrl()
            val builder = baseHttpUrl.newBuilder()
            
            val segments = path.split("/").filter { it.isNotEmpty() }
            
            segments.forEach { segment ->
                builder.addPathSegment(segment) 
            }
            
            if (path.endsWith("/") && segments.isNotEmpty()) {
                builder.addPathSegment("") 
            }
            
            builder.build().toString()
        } catch (e: Exception) {
            val baseUrl = server.url.trimEnd('/')
            val formattedPath = if (path.startsWith("/")) path else "/$path"
            baseUrl + formattedPath
        }
    }

    suspend fun listFiles(path: String = "/"): List<WebDavFile> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Depth", "1")
                .header("Authorization", getAuthHeader())
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
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun parseWebDavXml(xml: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true // important for DAV: namespace
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
                        // Ignore namespace, just check local name
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
                                    // simplified date parsing or just raw
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
            e.printStackTrace()
        }
        return files
    }

    suspend fun checkConnection(): Boolean {
        return try {
            val url = buildUrl("/")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader())
                .header("Depth", "0")
                .method("PROPFIND", null) // Use PROPFIND with Depth 0 for checking existence/auth
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    suspend fun downloadContent(path: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader())
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Failed to download file ($url): ${response.code}")
            
            response.body?.string() ?: ""
        }
    }

    suspend fun downloadToFile(path: String, destinationFile: java.io.File) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = buildUrl(path)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", getAuthHeader())
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
