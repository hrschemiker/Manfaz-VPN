package com.manfaz.vpn.net

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.data.parser.ConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Incrementally reads Telegram public-channel previews through the active VPN SOCKS proxy.
 * A channel checkpoint is advanced only after a page was downloaded and parsed successfully.
 */
object FreeConfigFetcher {
    val channels: List<String> = listOf("Spotify_Porteghali", "best_internet_iran")

    data class Result(
        val configs: List<ServerConfig>,
        val newestPostByChannel: Map<String, Long>,
    )

    private val uriRegex = Regex(
        "(?i)\\b(?:vless|vmess|trojan|ss|socks|hysteria2?|tuic)://[A-Za-z0-9+/=._:@\\-?&#%!,;()\\[\\]]+"
    )
    private val postIdRegex = Regex("data-post=\"([^\"/]+)/([0-9]+)\"")

    suspend fun fetchAll(
        socksPort: Int,
        checkpoints: Map<String, Long>,
        initialPostLimit: Int = 200,
    ): Result = withContext(Dispatchers.IO) {
        val all = LinkedHashMap<String, ServerConfig>()
        val newest = LinkedHashMap<String, Long>()
        channels.forEach { channel ->
            runCatching {
                fetchChannel(channel, socksPort, checkpoints[channel] ?: 0L, initialPostLimit)
            }.getOrNull()?.let { result ->
                result.configs.forEach { all.putIfAbsent(dedupKey(it), it) }
                if (result.newestPost > 0) newest[channel] = result.newestPost
            }
        }
        Result(all.values.toList(), newest)
    }

    private data class ChannelResult(val configs: List<ServerConfig>, val newestPost: Long)

    private fun fetchChannel(
        channel: String,
        socksPort: Int,
        checkpoint: Long,
        initialPostLimit: Int,
    ): ChannelResult {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val found = LinkedHashMap<String, ServerConfig>()
        var before: Long? = null
        var newestSeen = checkpoint
        var postsExamined = 0
        var pages = 0

        while (pages < 20 && (checkpoint > 0 || postsExamined < initialPostLimit)) {
            pages++
            val url = buildString {
                append("https://t.me/s/").append(channel)
                before?.let { append("?before=").append(it) }
            }
            val html = httpGet(url, proxy) ?: break
            val matches = postIdRegex.findAll(html).toList()
            if (matches.isEmpty()) break

            val ids = matches.mapNotNull { it.groupValues[2].toLongOrNull() }
            newestSeen = maxOf(newestSeen, ids.maxOrNull() ?: checkpoint)

            matches.forEachIndexed { index, match ->
                val id = match.groupValues[2].toLongOrNull() ?: return@forEachIndexed
                if (id <= checkpoint || postsExamined >= initialPostLimit && checkpoint == 0L) {
                    return@forEachIndexed
                }
                postsExamined++
                val end = matches.getOrNull(index + 1)?.range?.first ?: html.length
                val messageHtml = html.substring(match.range.first, end)
                val text = unescapeHtml(stripTags(messageHtml))
                uriRegex.findAll(text).forEach { uriMatch ->
                    val uri = uriMatch.value.trim().trimEnd('.', ',', ')', ']')
                    runCatching { ConfigParser.parseSingle(uri) }.getOrNull()
                        ?.takeIf { it.protocol != Protocol.UNKNOWN }
                        ?.let {
                            found.putIfAbsent(
                                dedupKey(it),
                                it.copy(group = "رایگان", rawUri = uri),
                            )
                        }
                }
            }

            val oldest = ids.minOrNull() ?: break
            if (oldest <= checkpoint || (checkpoint == 0L && postsExamined >= initialPostLimit)) break
            if (before != null && oldest >= before!!) break
            before = oldest
        }
        return ChannelResult(found.values.toList(), newestSeen)
    }

    private fun httpGet(url: String, proxy: Proxy): String? = try {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ManfazVPN")
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun stripTags(html: String) = html.replace(Regex("<[^>]+>"), " ")

    private fun unescapeHtml(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    private fun dedupKey(s: ServerConfig) =
        "${s.protocol}|${s.address}|${s.port}|${s.uuid}${s.password}"
}
