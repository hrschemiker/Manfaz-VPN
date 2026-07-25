package com.manfaz.vpn.data.parser

import android.net.Uri
import android.util.Base64
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses proxy configuration URIs into [ServerConfig].
 * Supported: vless:// vmess:// trojan:// ss://
 * Also handles multi-line input and Base64-encoded subscription blobs.
 */
object ConfigParser {

    data class Result(
        val servers: List<ServerConfig>,
        val errors: List<String>,
    )

    /** Parse an arbitrary blob: may contain many links, or be Base64 (subscription). */
    fun parseMany(input: String): Result {
        val text = input.trim()
        val lines = mutableListOf<String>()

        // Direct links present?
        if (looksLikeLink(text)) {
            lines += text.lines()
        } else {
            // Try Base64 subscription decode
            val decoded = tryBase64(text)
            if (decoded != null && looksLikeLink(decoded)) {
                lines += decoded.lines()
            } else {
                lines += text.lines()
            }
        }

        val servers = mutableListOf<ServerConfig>()
        val errors = mutableListOf<String>()
        for (raw in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            try {
                val s = parseSingle(raw)
                if (s != null) servers += s
            } catch (e: Exception) {
                errors += "این لینک نامعتبر است: ${raw.take(24)}…"
            }
        }
        // De-duplicate by address:port:uuid/password
        val seen = HashSet<String>()
        val unique = servers.filter { seen.add("${it.protocol}|${it.address}|${it.port}|${it.uuid}${it.password}") }
        return Result(unique, errors)
    }

    fun parseSingle(uri: String): ServerConfig? = when {
        uri.startsWith("vmess://", true) -> parseVmess(uri)
        uri.startsWith("vless://", true) -> parseVless(uri)
        uri.startsWith("trojan://", true) -> parseTrojan(uri)
        uri.startsWith("ss://", true) -> parseShadowsocks(uri)
        uri.startsWith("hysteria2://", true) || uri.startsWith("hy2://", true) ->
            parseGeneric(uri, Protocol.HYSTERIA2, defaultPort = 443)
        uri.startsWith("hysteria://", true) || uri.startsWith("hy://", true) ->
            parseGeneric(uri, Protocol.HYSTERIA, defaultPort = 443)
        uri.startsWith("tuic://", true) -> parseGeneric(uri, Protocol.TUIC, defaultPort = 443)
        uri.startsWith("socks://", true) || uri.startsWith("socks5://", true) ->
            parseGeneric(uri, Protocol.SOCKS, defaultPort = 1080, credsMayBeBase64 = true)
        uri.startsWith("http://", true) || uri.startsWith("https://", true) ->
            parseGeneric(uri, Protocol.HTTP, defaultPort = 8080)
        uri.startsWith("wireguard://", true) || uri.startsWith("wg://", true) ->
            parseGeneric(uri, Protocol.WIREGUARD, defaultPort = 51820)
        else -> null
    }

    private fun looksLikeLink(t: String) =
        Regex("(?i)(vmess|vless|trojan|ss|socks5?|https?|hy2?|hysteria2?|tuic|wireguard|wg)://").containsMatchIn(t)

    /**
     * Generic `scheme://[userinfo@]host:port?params#name` parser used for
     * hysteria/hysteria2/tuic/socks/http/wireguard. userinfo becomes uuid or
     * password depending on protocol; query params fill sni/host/path/security.
     */
    private fun parseGeneric(
        uri: String,
        protocol: Protocol,
        defaultPort: Int,
        credsMayBeBase64: Boolean = false,
    ): ServerConfig {
        val u = Uri.parse(uri)
        val name = decode(u.fragment ?: protocol.label).ifBlank { protocol.label }
        var userInfo = u.userInfo ?: ""
        if (credsMayBeBase64 && userInfo.isNotBlank() && !userInfo.contains(":")) {
            userInfo = tryBase64(userInfo) ?: userInfo
        }
        // For socks/http userinfo is user:pass; keep pass. For others it's an id/auth token.
        val cred = if (userInfo.contains(":")) userInfo.substringAfter(":") else userInfo
        val usesUuid = protocol == Protocol.TUIC
        return ServerConfig(
            name = name,
            protocol = protocol,
            address = u.host ?: "",
            port = if (u.port > 0) u.port else defaultPort,
            uuid = if (usesUuid) userInfo.substringBefore(":") else "",
            password = if (usesUuid) cred else cred,
            network = u.getQueryParameter("type") ?: "tcp",
            security = u.getQueryParameter("security")
                ?: if (protocol == Protocol.HYSTERIA2 || protocol == Protocol.TUIC) "tls" else "none",
            sni = u.getQueryParameter("sni") ?: u.getQueryParameter("peer") ?: "",
            host = u.getQueryParameter("host") ?: "",
            path = decode(u.getQueryParameter("path") ?: ""),
            alpn = u.getQueryParameter("alpn") ?: "",
            fingerprint = u.getQueryParameter("fp") ?: "",
            rawUri = uri,
        )
    }

    // vmess://<base64 json>
    private fun parseVmess(uri: String): ServerConfig {
        val b64 = uri.removePrefix("vmess://").removePrefix("VMESS://").substringBefore("#")
        val json = tryBase64(b64) ?: throw IllegalArgumentException("bad base64")
        val o = JSONObject(json)
        val name = o.optString("ps", o.optString("add"))
        return ServerConfig(
            name = name.ifBlank { "VMess" },
            protocol = Protocol.VMESS,
            address = o.optString("add"),
            port = o.optString("port", "0").toIntOrNull() ?: 0,
            uuid = o.optString("id"),
            network = o.optString("net", "tcp"),
            security = o.optString("tls").ifBlank { "none" },
            sni = o.optString("sni", o.optString("host")),
            host = o.optString("host"),
            path = o.optString("path"),
            alpn = o.optString("alpn"),
            rawUri = uri,
        )
    }

    // vless://uuid@host:port?params#name
    private fun parseVless(uri: String): ServerConfig {
        val u = Uri.parse(uri)
        val name = decode(u.fragment ?: "VLESS")
        return ServerConfig(
            name = name.ifBlank { "VLESS" },
            protocol = Protocol.VLESS,
            address = u.host ?: "",
            port = if (u.port > 0) u.port else 443,
            uuid = u.userInfo ?: "",
            network = u.getQueryParameter("type") ?: "tcp",
            security = u.getQueryParameter("security") ?: "none",
            sni = u.getQueryParameter("sni") ?: "",
            host = u.getQueryParameter("host") ?: "",
            path = decode(u.getQueryParameter("path") ?: ""),
            serviceName = decode(u.getQueryParameter("serviceName") ?: ""),
            mode = u.getQueryParameter("mode") ?: "",
            flow = u.getQueryParameter("flow") ?: "",
            alpn = u.getQueryParameter("alpn") ?: "",
            fingerprint = u.getQueryParameter("fp") ?: "",
            publicKey = u.getQueryParameter("pbk") ?: "",
            shortId = u.getQueryParameter("sid") ?: "",
            rawUri = uri,
        )
    }

    // trojan://password@host:port?params#name
    private fun parseTrojan(uri: String): ServerConfig {
        val u = Uri.parse(uri)
        return ServerConfig(
            name = decode(u.fragment ?: "Trojan").ifBlank { "Trojan" },
            protocol = Protocol.TROJAN,
            address = u.host ?: "",
            port = if (u.port > 0) u.port else 443,
            password = u.userInfo ?: "",
            network = u.getQueryParameter("type") ?: "tcp",
            security = u.getQueryParameter("security") ?: "tls",
            sni = u.getQueryParameter("sni") ?: "",
            host = u.getQueryParameter("host") ?: "",
            path = decode(u.getQueryParameter("path") ?: ""),
            serviceName = decode(u.getQueryParameter("serviceName") ?: ""),
            mode = u.getQueryParameter("mode") ?: "",
            alpn = u.getQueryParameter("alpn") ?: "",
            rawUri = uri,
        )
    }

    // ss://base64(method:password)@host:port#name  OR  ss://base64(method:password@host:port)#name
    private fun parseShadowsocks(uri: String): ServerConfig {
        val withoutScheme = uri.removePrefix("ss://").removePrefix("SS://")
        val name = decode(withoutScheme.substringAfter("#", "Shadowsocks"))
        val body = withoutScheme.substringBefore("#")

        val method: String; val password: String; val host: String; val port: Int
        if (body.contains("@")) {
            // ss://base64(method:password)@host:port
            val userInfoPart = body.substringBefore("@")
            val hostPart = body.substringAfter("@").substringBefore("?")
            val creds = tryBase64(userInfoPart) ?: decode(userInfoPart)
            method = creds.substringBefore(":")
            password = creds.substringAfter(":")
            host = hostPart.substringBeforeLast(":")
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 0
        } else {
            // ss://base64(method:password@host:port)
            val decoded = tryBase64(body.substringBefore("?")) ?: throw IllegalArgumentException("bad ss")
            val credsPart = decoded.substringBefore("@")
            val hostPart = decoded.substringAfter("@")
            method = credsPart.substringBefore(":")
            password = credsPart.substringAfter(":")
            host = hostPart.substringBeforeLast(":")
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 0
        }
        return ServerConfig(
            name = name.ifBlank { "Shadowsocks" },
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            password = password,
            method = method,
            rawUri = uri,
        )
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) { s }

    /** Try to Base64-decode (URL-safe & standard, with/without padding). Returns null if not valid text. */
    private fun tryBase64(s: String): String? {
        val cleaned = s.trim().replace("\n", "").replace("\r", "")
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                val bytes = Base64.decode(cleaned, flags or Base64.NO_WRAP)
                val str = String(bytes, Charsets.UTF_8)
                if (str.isNotBlank() && str.all { it.code in 9..126 || it.code > 160 }) return str
            } catch (_: Exception) {}
        }
        return null
    }
}
