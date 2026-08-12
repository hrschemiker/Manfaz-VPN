package com.manfaz.vpn.net

import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.model.Subscription
import com.manfaz.vpn.data.parser.ClashParser
import com.manfaz.vpn.data.parser.ConfigParser
import com.manfaz.vpn.data.parser.SingBoxSubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a subscription URL over the network, parses its servers, and reads the
 * standard `subscription-userinfo` header (upload/download/total/expire) if present.
 */
object SubscriptionFetcher {

    data class Fetched(
        val servers: List<ServerConfig>,
        val upload: Long,
        val download: Long,
        val total: Long,
        val expire: Long,
        val error: String? = null,
    )

    // Panels frequently return different bodies per UA; try the ones that yield raw configs.
    private val userAgents = listOf(
        "v2rayNG/1.9.5",
        "ManfazVPN/1.0",
        "Mozilla/5.0 (compatible; ManfazVPN)",
    )

    suspend fun fetch(sub: Subscription): Fetched = withContext(Dispatchers.IO) {
        val initial = runCatching { URL(sub.url) }.getOrNull()
            ?: return@withContext Fetched(emptyList(), 0, 0, 0, 0, "آدرس اشتراک معتبر نیست.")
        if (initial.protocol.lowercase() != "https") {
            return@withContext Fetched(
                emptyList(), 0, 0, 0, 0,
                "برای امنیت، لینک اشتراک باید با https:// آغاز شود.",
            )
        }
        val agents = (listOf(sub.userAgent) + userAgents).filter { it.isNotBlank() }.distinct()
        var lastError: String? = "هیچ سروری در این اشتراک یافت نشد."
        for (ua in agents) {
            try {
                val conn = (URL(sub.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (conn.url.protocol.lowercase() != "https") {
                    conn.disconnect()
                    lastError = "تغییر مسیر ناامن HTTP مسدود شد."
                    continue
                }
                if (code !in 200..299) { lastError = "کد پاسخ سرور: $code"; conn.disconnect(); continue }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val userInfo = conn.getHeaderField("subscription-userinfo")
                    ?: conn.getHeaderField("Subscription-Userinfo")
                conn.disconnect()

                val parsed = when {
                    ClashParser.looksLikeClash(body) -> ClashParser.parse(body)
                    SingBoxSubParser.looksLikeSingBox(body) -> SingBoxSubParser.parse(body)
                    else -> ConfigParser.parseMany(body).servers
                }
                val tagged = parsed.map { it.copy(group = sub.name) }
                if (tagged.isNotEmpty()) {
                    val usage = parseUserInfo(userInfo)
                    return@withContext Fetched(tagged, usage[0], usage[1], usage[2], usage[3], null)
                }
            } catch (e: Exception) {
                lastError = "اشتراک به‌روزرسانی نشد: ${e.message ?: "خطای شبکه"}"
            }
        }
        Fetched(emptyList(), 0, 0, 0, 0, lastError)
    }

    // "upload=123; download=456; total=789; expire=1700000000"
    private fun parseUserInfo(header: String?): LongArray {
        val out = longArrayOf(0, 0, 0, 0)
        if (header.isNullOrBlank()) return out
        header.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val v = kv[1].trim().toLongOrNull() ?: 0L
                when (kv[0].trim().lowercase()) {
                    "upload" -> out[0] = v
                    "download" -> out[1] = v
                    "total" -> out[2] = v
                    "expire" -> out[3] = v
                }
            }
        }
        return out
    }
}
