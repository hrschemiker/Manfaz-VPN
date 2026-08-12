package com.manfaz.vpn.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Fetches the real exit IP + country by querying an IP-info endpoint THROUGH the local
 * SOCKS proxy (so the result reflects the server, not the device).
 */
object ExitIp {

    data class Info(val ip: String, val country: String)

    suspend fun fetch(socksPort: Int): Info? = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = (URL("https://ipwho.is/")
                .openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val ip = o.optString("ip")
            val country = o.optString("country_code")
            if (ip.isBlank()) null else Info(ip, country)
        } catch (e: Exception) {
            null
        }
    }
}
