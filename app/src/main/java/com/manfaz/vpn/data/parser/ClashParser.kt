package com.manfaz.vpn.data.parser

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * Parses a Clash / Mihomo YAML config's `proxies:` list into [ServerConfig]s.
 * Only the Xray-family protocols are converted (ss/vmess/vless/trojan/socks/http);
 * others (hysteria2/tuic/wireguard) are skipped.
 */
object ClashParser {

    fun looksLikeClash(text: String): Boolean {
        val t = text.trimStart()
        return t.contains("proxies:") && !t.startsWith("{") && !t.startsWith("[")
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): List<ServerConfig> {
        val yaml = Yaml(LoaderOptions().apply { maxAliasesForCollections = 200 })
        val root = runCatching { yaml.load<Any>(text) as? Map<String, Any> }.getOrNull() ?: return emptyList()
        val proxies = root["proxies"] as? List<Map<String, Any>> ?: return emptyList()
        return proxies.mapNotNull { runCatching { convert(it) }.getOrNull() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convert(p: Map<String, Any>): ServerConfig? {
        val name = p["name"]?.toString() ?: "Clash"
        val server = p["server"]?.toString() ?: return null
        val port = (p["port"] as? Number)?.toInt() ?: p["port"]?.toString()?.toIntOrNull() ?: return null
        val type = p["type"]?.toString()?.lowercase() ?: return null

        val network = p["network"]?.toString() ?: "tcp"
        val tls = (p["tls"] as? Boolean) == true
        val sni = (p["servername"] ?: p["sni"])?.toString() ?: ""
        val fp = p["client-fingerprint"]?.toString() ?: ""

        // Transport opts
        var host = ""; var path = ""; var serviceName = ""
        (p["ws-opts"] as? Map<String, Any>)?.let { ws ->
            path = ws["path"]?.toString() ?: ""
            host = (ws["headers"] as? Map<String, Any>)?.get("Host")?.toString() ?: ""
        }
        (p["grpc-opts"] as? Map<String, Any>)?.let { g ->
            serviceName = g["grpc-service-name"]?.toString() ?: ""
        }
        // Reality
        var security = if (tls) "tls" else "none"
        var publicKey = ""; var shortId = ""
        (p["reality-opts"] as? Map<String, Any>)?.let { r ->
            security = "reality"
            publicKey = r["public-key"]?.toString() ?: ""
            shortId = r["short-id"]?.toString() ?: ""
        }

        val base = ServerConfig(
            name = name, address = server, port = port, protocol = Protocol.UNKNOWN,
            network = network, security = security, sni = sni, host = host, path = path,
            serviceName = serviceName, fingerprint = fp, publicKey = publicKey, shortId = shortId,
        )

        return when (type) {
            "ss", "shadowsocks" -> base.copy(
                protocol = Protocol.SHADOWSOCKS,
                method = p["cipher"]?.toString() ?: "",
                password = p["password"]?.toString() ?: "",
                security = "none",
            )
            "vmess" -> base.copy(
                protocol = Protocol.VMESS,
                uuid = p["uuid"]?.toString() ?: "",
            )
            "vless" -> base.copy(
                protocol = Protocol.VLESS,
                uuid = p["uuid"]?.toString() ?: "",
                flow = p["flow"]?.toString() ?: "",
            )
            "trojan" -> base.copy(
                protocol = Protocol.TROJAN,
                password = p["password"]?.toString() ?: "",
                security = if (security == "none") "tls" else security,
            )
            "socks5", "socks" -> base.copy(
                protocol = Protocol.SOCKS,
                uuid = p["username"]?.toString() ?: "",
                password = p["password"]?.toString() ?: "",
            )
            "http" -> base.copy(
                protocol = Protocol.HTTP,
                uuid = p["username"]?.toString() ?: "",
                password = p["password"]?.toString() ?: "",
            )
            else -> null // hysteria2/tuic/wireguard etc. — not supported by the Xray core
        }
    }
}
