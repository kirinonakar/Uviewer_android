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
            path.split("/").filter { it.isNotEmpty() }.forEach {
                builder.addPathSegment(it)
            }
            // If the original path ended with a slash, ensure the built URL does too
            if (path.endsWith("/") && !builder.build().toString().endsWith("/")) {
                // builder.addPathSegment("") results in a trailing slash if not already there
            }
            // Actually, WebDAV paths for files shouldn't end with slash, but for folders they should.
            // PROPFIND on folder usually needs trailing slash.
            val built = builder.build().toString()
            if (path.endsWith("/") && !built.endsWith("/")) built + "/" else built
        } catch (e: Exception) {
            server.url.trimEnd('/') + path
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
                    // 404 or other error
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
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))

            var eventType = parser.eventType
            var currentHref = ""
            var currentName = ""
            var isDir = false
            var size = 0L
            var lastMod = 0L
            var contentType: String? = null
            var inResponse = false
            var inProp = false

            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        if (name.equals("response", ignoreCase = true) || name.endsWith(":response")) {
                            inResponse = true
                            currentHref = ""
                            currentName = ""
                            isDir = false
                            size = 0L
                            lastMod = 0L
                            contentType = null
                        } else if (inResponse) {
                            if (name.equals("href", ignoreCase = true) || name.endsWith(":href")) {
                                currentHref = parser.nextText()
                                // Extract name from href
                                currentName = java.net.URLDecoder.decode(currentHref.trimEnd('/').substringAfterLast('/'), "UTF-8")
                            } else if (name.equals("collection", ignoreCase = true) || name.endsWith(":collection")) {
                                isDir = true
                            } else if (name.equals("getcontentlength", ignoreCase = true) || name.endsWith(":getcontentlength")) {
                                size = parser.nextText().toLongOrNull() ?: 0L
                            } else if (name.equals("getcontenttype", ignoreCase = true) || name.endsWith(":getcontenttype")) {
                                contentType = parser.nextText()
                            }
                            // Date parsing skipped for brevity, defaults to 0
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        if (name.equals("response", ignoreCase = true) || name.endsWith(":response")) {
                            inResponse = false
                            if (currentName.isNotEmpty()) {
                                files.add(WebDavFile(currentHref, currentName, isDir, size, lastMod, contentType))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Filter out the requested directory itself (usually first entry with same path)
        // logic needs to be robust. PROPFIND returns the dir itself.
        // We can filter by checking if href (decoded) equals the request path?
        // Let's return all and let repository filter.
        return files
    }

    suspend fun checkConnection(): Boolean {
        return try {
            val url = buildUrl("/")
            val request = Request.Builder()
                .url(url)
                // .method("PROPFIND", null) // OkHttp doesn't support PROPFIND directly without custom method
                .header("Authorization", getAuthHeader())
                .head() // Use HEAD for check connection or minimal GET
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
            if (!response.isSuccessful) throw IOException("Failed to download file: ${response.code}")
            
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
            if (!response.isSuccessful) throw IOException("Failed to download file: ${response.code}")
            
            response.body?.byteStream()?.use { input ->
                java.io.FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
