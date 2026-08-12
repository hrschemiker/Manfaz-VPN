package com.manfaz.vpn.data.parser

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONObject

/**
 * Parses a sing-box JSON config's `outbounds` array into [ServerConfig]s.
 * Only Xray-family server outbounds are converted; direct/block/dns/selector are skipped.
 */
object SingBoxSubParser {

    fun looksLikeSingBox(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") && t.contains("\"outbounds\"")
    }

    fun parse(text: String): List<ServerConfig> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val result = mutableListOf<ServerConfig>()
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i) ?: continue
            runCatching { convert(o) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    private fun convert(o: JSONObject): ServerConfig? {
        val type = o.optString("type").lowercase()
        val server = o.optString("server").ifBlank { return null }
        val port = o.optInt("server_port").takeIf { it > 0 } ?: return null
        val name = o.optString("tag", type)

        val tls = o.optJSONObject("tls")
        val tlsEnabled = tls?.optBoolean("enabled") == true
        val sni = tls?.optString("server_name") ?: ""
        val reality = tls?.optJSONObject("reality")
        val security = when {
            reality?.optBoolean("enabled") == true -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val fp = tls?.optJSONObject("utls")?.optString("fingerprint") ?: ""

        val transport = o.optJSONObject("transport")
        val network = transport?.optString("type") ?: "tcp"
        val path = transport?.optString("path") ?: ""
        val host = transport?.optJSONObject("headers")?.optString("Host")
            ?: transport?.optString("host") ?: ""
        val serviceName = transport?.optString("service_name") ?: ""

        val base = ServerConfig(
            name = name, address = server, port = port, protocol = Protocol.UNKNOWN,
            network = if (network == "ws") "ws" else network,
            security = security, sni = sni, host = host, path = path,
            serviceName = serviceName, fingerprint = fp,
            publicKey = reality?.optString("public_key") ?: "",
            shortId = reality?.optString("short_id") ?: "",
        )

        return when (type) {
            "shadowsocks" -> base.copy(protocol = Protocol.SHADOWSOCKS,
                method = o.optString("method"), password = o.optString("password"), security = "none")
            "vmess" -> base.copy(protocol = Protocol.VMESS, uuid = o.optString("uuid"))
            "vless" -> base.copy(protocol = Protocol.VLESS,
                uuid = o.optString("uuid"), flow = o.optString("flow"))
            "trojan" -> base.copy(protocol = Protocol.TROJAN, password = o.optString("password"))
            "socks" -> base.copy(protocol = Protocol.SOCKS,
                uuid = o.optString("username"), password = o.optString("password"))
            "http" -> base.copy(protocol = Protocol.HTTP,
                uuid = o.optString("username"), password = o.optString("password"))
            else -> null
        }
    }
}
