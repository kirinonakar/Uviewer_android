package com.uviewer_android.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class WebDavCertificateRejectedException : SSLPeerUnverifiedException("WebDAV connection cancelled because of a certificate error")

/** Network workers wait here until the user chooses Connect or Cancel. */
object WebDavCertificateConsent {
    private val approved = mutableSetOf<String>()
    private val decisions = mutableMapOf<String, CompletableFuture<Boolean>>()
    private val pending = MutableStateFlow<List<String>>(emptyList())
    val pendingServers = pending.asStateFlow()

    internal fun awaitApproval(server: String): Boolean {
        val decision = synchronized(this) {
            if (server in approved) return true
            decisions.getOrPut(server) {
                CompletableFuture<Boolean>().also { pending.value = pending.value + server }
            }
        }
        return try {
            decision.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for WebDAV certificate approval")
        }
    }

    @Synchronized
    fun resolve(server: String, connect: Boolean) {
        val decision = decisions.remove(server) ?: return
        if (connect) approved.add(server)
        pending.value = pending.value - server
        decision.complete(connect)
    }
}

/** Only registered WebDAV origins use the certificate-warning policy. */
object WebDavHttpClient : Call.Factory {
    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun forServer(serverUrl: String): OkHttpClient {
        val origin = serverUrl.toHttpUrl().origin()
        return clients.computeIfAbsent(origin) {
            createClient(origin, WebDavCertificateConsent::awaitApproval)
        }
    }

    override fun newCall(request: Request): Call =
        (clients[request.url.origin()] ?: defaultClient).newCall(request)

    internal fun createClient(
        server: String,
        onCertificateError: (String) -> Boolean,
        platformTrust: X509TrustManager = platformTrustManager()
    ): OkHttpClient {
        val warningTrust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = platformTrust.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                platformTrust.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    platformTrust.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    if (!onCertificateError(server)) {
                        throw CertificateException("WebDAV certificate rejected", WebDavCertificateRejectedException())
                    }
                }
            }
        }
        val tls = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(warningTrust), null)
        }
        return defaultClient.newBuilder()
            .sslSocketFactory(tls.socketFactory, warningTrust)
            .hostnameVerifier { hostname, session ->
                if (!defaultClient.hostnameVerifier.verify(hostname, session)) {
                    if (!onCertificateError(server)) throw WebDavCertificateRejectedException()
                }
                true
            }
            .build()
    }

    private fun platformTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()

    private fun HttpUrl.origin(): String = newBuilder()
        .username("").password("").encodedPath("/").query(null).fragment(null)
        .build().toString()
}
