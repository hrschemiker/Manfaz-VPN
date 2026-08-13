package com.manfaz.vpn.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Lightweight end-to-end checks through the already-running local SOCKS proxy. */
object ProxyHealthProbe {
    private val endpoints = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "http://www.msftconnecttest.com/connecttest.txt",
    )

    suspend fun isHealthy(socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved("127.0.0.1", socksPort),
        )
        endpoints.any { endpoint -> probe(endpoint, proxy) }
    }

    private fun probe(endpoint: String, proxy: Proxy): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Manfaz-Health/1")
            }
            connection.responseCode in 200..399
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
