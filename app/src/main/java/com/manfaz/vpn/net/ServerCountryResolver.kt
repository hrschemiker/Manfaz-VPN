package com.manfaz.vpn.net

import android.content.Context
import com.manfaz.vpn.data.model.Countries
import com.manfaz.vpn.data.model.Country
import com.manfaz.vpn.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest

/**
 * Country inference is conservative: local name/flag/domain detection first, then a cached
 * GeoIP lookup for otherwise unknown server addresses. Failure always falls back to generic.
 */
object ServerCountryResolver {
    suspend fun resolve(context: Context, server: ServerConfig): Country {
        val local = Countries.detect("${server.name} ${server.group} ${server.address}")
        if (local.iso.isNotBlank()) return local

        val prefs = context.applicationContext
            .getSharedPreferences("server_country_cache", Context.MODE_PRIVATE)
        val key = "country_${sha256(server.address).take(24)}"
        prefs.getString(key, null)?.let { return Countries.fromIso(it) }

        val iso = withContext(Dispatchers.IO) {
            runCatching {
                val ip = InetAddress.getByName(server.address).hostAddress ?: return@runCatching null
                val conn = URL("https://ipwho.is/$ip?fields=success,country_code")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.setRequestProperty("User-Agent", "ManfazVPN/1.1")
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    json.optString("country_code").takeIf { json.optBoolean("success", true) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (!iso.isNullOrBlank()) prefs.edit().putString(key, iso).apply()
        return Countries.fromIso(iso)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
