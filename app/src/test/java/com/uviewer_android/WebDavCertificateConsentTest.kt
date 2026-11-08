package com.uviewer_android

import com.uviewer_android.network.WebDavCertificateConsent
import com.uviewer_android.network.WebDavCertificateRejectedException
import com.uviewer_android.network.WebDavHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class WebDavCertificateConsentTest {
    @Test
    fun selfSignedCertificateWaitsForConnectBeforeSendingRequest() {
        LocalTlsServer().use { server ->
            val client = WebDavHttpClient.forServer(server.url)
            val result = server.executor.submit<String> {
                client.newCall(Request.Builder().url(server.url).method("PROPFIND", null).build())
                    .execute().use { it.body.string() }
            }
            awaitPrompt(server.url)
            assertFalse(result.isDone)
            assertFalse(server.receivedRequest.isDone)
            WebDavCertificateConsent.resolve(server.url, true)
            assertEquals("connected", result.get(5, TimeUnit.SECONDS))
            assertTrue(server.receivedRequest.get(5, TimeUnit.SECONDS).startsWith("PROPFIND "))
            assertFalse(server.url in WebDavCertificateConsent.pendingServers.value)
            // Chain and hostname errors share the same approval for this app session.
            assertTrue(WebDavCertificateConsent.awaitApproval(server.url))
        }
    }

    @Test
    fun cancelRejectsCertificateWithoutSendingRequest() {
        LocalTlsServer().use { server ->
            val result = server.executor.submit<IOException?> {
                try {
                    WebDavHttpClient.forServer(server.url)
                        .newCall(Request.Builder().url(server.url).build()).execute().close()
                    null
                } catch (error: IOException) {
                    error
                }
            }
            awaitPrompt(server.url)
            WebDavCertificateConsent.resolve(server.url, false)
            val failure = result.get(5, TimeUnit.SECONDS)
            assertNotNull(failure)
            assertTrue(generateSequence<Throwable>(failure!!) { it.cause }.any {
                it is WebDavCertificateRejectedException
            })
            assertFalse(server.receivedRequest.isDone)
        }
    }

    @Test
    fun hostnameMismatchAloneRequiresApproval() {
        LocalTlsServer().use { server ->
            val client = WebDavHttpClient.createClient(
                server.url, WebDavCertificateConsent::awaitApproval, trustedFixture()
            )
            val result = server.executor.submit<String> {
                client.newCall(Request.Builder().url(server.url).build()).execute().use { it.body.string() }
            }
            awaitPrompt(server.url)
            assertFalse(server.receivedRequest.isDone)
            WebDavCertificateConsent.resolve(server.url, true)
            assertEquals("connected", result.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancellingHostnameMismatchDoesNotSendRequest() {
        LocalTlsServer().use { server ->
            val client = WebDavHttpClient.createClient(server.url, { false }, trustedFixture())
            try {
                client.newCall(Request.Builder().url(server.url).build()).execute().close()
                fail("A cancelled hostname mismatch must reject the connection")
            } catch (_: WebDavCertificateRejectedException) {
                assertFalse(server.receivedRequest.isDone)
            }
        }
    }

    @Test
    fun validTrustedCertificateConnectsWithoutPrompt() {
        LocalTlsServer().use { server ->
            val url = server.url.replace("127.0.0.1", "localhost")
            val client = WebDavHttpClient.createClient(url, { fail("Unexpected certificate prompt"); false }, trustedFixture())
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals("connected", it.body.string())
            }
        }
    }

    @Test
    fun unregisteredHttpsOriginStillRejectsInvalidCertificate() {
        LocalTlsServer().use { server ->
            try {
                WebDavHttpClient.newCall(Request.Builder().url(server.url).build()).execute().close()
                fail("Unregistered HTTPS origin must retain normal certificate validation")
            } catch (_: IOException) {
                assertFalse(server.receivedRequest.isDone)
                assertFalse(server.url in WebDavCertificateConsent.pendingServers.value)
            }
        }
    }

    private fun awaitPrompt(server: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (server !in WebDavCertificateConsent.pendingServers.value) {
            if (System.nanoTime() >= deadline) fail("Missing certificate prompt for $server")
            Thread.sleep(10)
        }
    }

    private fun trustedFixture(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(fixture())
        }.trustManagers.filterIsInstance<X509TrustManager>().single()

    private class LocalTlsServer : Closeable {
        val executor = Executors.newCachedThreadPool()
        val receivedRequest = CompletableFuture<String>()
        private val socket: SSLServerSocket
        val url: String

        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(fixture(), "test-fixture".toCharArray())
            }
            val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            socket = tls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            url = "https://127.0.0.1:${socket.localPort}/"
            executor.submit {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@submit
                        receivedRequest.complete(requestLine)
                        while (!reader.readLine().isNullOrEmpty()) { /* Read headers. */ }
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nconnected".toByteArray())
                            flush()
                        }
                    }
                } catch (_: IOException) {
                    // Expected when the user cancels or a strict client rejects the certificate.
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        // Self-signed localhost certificate and test-only private key; never used by the app.
        private fun fixture(): KeyStore = KeyStore.getInstance("PKCS12").apply {
            WebDavCertificateConsentTest::class.java.getResourceAsStream("/webdav-localhost.p12").use {
                load(it, "test-fixture".toCharArray())
            }
        }
    }
}
