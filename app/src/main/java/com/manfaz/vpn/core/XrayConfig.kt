package com.manfaz.vpn.core

import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a v2rayNG-style Xray JSON config from a [ServerConfig].
 * Supports the Xray-family protocols: VLESS, VMess, Trojan, Shadowsocks, SOCKS, HTTP.
 */
object XrayConfig {

    const val SOCKS_PORT = 10808

    fun isSupportedByXray(p: Protocol) = p in setOf(
        Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN,
        Protocol.SHADOWSOCKS, Protocol.SOCKS, Protocol.HTTP,
    )

    fun build(
        server: ServerConfig,
        remoteDns: String = "1.1.1.1",
        dnsLeakProtection: Boolean = true,
        allowLan: Boolean = true,
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        // Enable traffic statistics (so XrayCore.queryTraffic works)
        root.put("stats", JSONObject())
        root.put("policy", JSONObject()
            .put("system", JSONObject()
                .put("statsOutboundUplink", true)
                .put("statsOutboundDownlink", true)))

        // Local SOCKS inbound the TUN bridge feeds into
        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("port", SOCKS_PORT)
            .put("listen", "127.0.0.1")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        root.put("inbounds", JSONArray().put(inbound))

        // Outbounds: proxy, direct, dns
        val outbounds = JSONArray()
        outbounds.put(proxyOutbound(server))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        // Routing rules
        val rules = JSONArray()
        rules.put(JSONObject()
            .put("type", "field")
            .put("outboundTag", if (dnsLeakProtection) "dns-out" else "direct")
            .put("port", "53"))
        rules.put(JSONObject()
            .put("type", "field")
            .put("outboundTag", if (allowLan) "direct" else "block")
            .put("ip", JSONArray(listOf("geoip:private"))))
        root.put("routing", JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules))

        // DNS: resolve through the proxy (remote resolver)
        val dnsServers = JSONArray().put(remoteDns)
        root.put("dns", JSONObject()
            .put("servers", dnsServers)
            // Preserve dual-stack answers. Android/Xray can then choose the reachable
            // address instead of losing services that publish IPv6-sensitive records.
            .put("queryStrategy", "UseIP"))

        return root.toString()
    }

    private fun proxyOutbound(s: ServerConfig): JSONObject {
        val out = JSONObject().put("tag", "proxy")
        when (s.protocol) {
            Protocol.VLESS -> {
                out.put("protocol", "vless")
                val user = JSONObject()
                    .put("id", s.uuid)
                    .put("encryption", "none")
                if (s.flow.isNotBlank()) user.put("flow", s.flow)
                out.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", s.address).put("port", s.port)
                        .put("users", JSONArray().put(user)))))
            }
            Protocol.VMESS -> {
                out.put("protocol", "vmess")
                val user = JSONObject().put("id", s.uuid).put("alterId", 0).put("security", "auto")
                out.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", s.address).put("port", s.port)
                        .put("users", JSONArray().put(user)))))
            }
            Protocol.TROJAN -> {
                out.put("protocol", "trojan")
                val srv = JSONObject().put("address", s.address).put("port", s.port)
                    .put("password", s.password)
                if (s.flow.isNotBlank()) srv.put("flow", s.flow)
                out.put("settings", JSONObject().put("servers", JSONArray().put(srv)))
            }
            Protocol.SHADOWSOCKS -> {
                out.put("protocol", "shadowsocks")
                out.put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", s.address).put("port", s.port)
                        .put("method", normalizeSsMethod(s.method)).put("password", s.password))))
            }
            Protocol.SOCKS -> {
                out.put("protocol", "socks")
                out.put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", s.address).put("port", s.port))))
            }
            Protocol.HTTP -> {
                out.put("protocol", "http")
                out.put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", s.address).put("port", s.port))))
            }
            else -> throw IllegalArgumentException("پروتکل ${s.protocol.label} در هسته Xray پشتیبانی نمی‌شود")
        }
        out.put("streamSettings", streamSettings(s))
        return out
    }

    /**
     * Normalize a Shadowsocks cipher name to a value Xray-core accepts.
     * Xray accepts the `aead_*` and `*-ietf-*` aliases directly; 2022-blake3 ciphers and
     * anything unrecognized pass through unchanged.
     */
    private fun normalizeSsMethod(method: String): String {
        val m = method.trim().lowercase()
        return when (m) {
            "aead_aes_128_gcm" -> "aes-128-gcm"
            "aead_aes_256_gcm" -> "aes-256-gcm"
            "aead_chacha20_poly1305" -> "chacha20-ietf-poly1305"
            "aead_xchacha20_poly1305" -> "xchacha20-ietf-poly1305"
            "" -> "aes-256-gcm"
            else -> m
        }
    }

    /** Normalize share-link transport names to the values Xray-core's config parser accepts. */
    private fun normalizeNetwork(net: String): String = when (net.lowercase()) {
        "", "raw", "tcp" -> "tcp"
        "h2", "h3", "http" -> "http"
        "splithttp", "xhttp" -> "xhttp"
        "ws", "websocket" -> "ws"
        else -> net.lowercase()
    }

    private fun streamSettings(s: ServerConfig): JSONObject {
        val net = normalizeNetwork(s.network)
        val ss = JSONObject().put("network", net)

        // Transport
        when (net) {
            "ws" -> ss.put("wsSettings", JSONObject()
                .put("path", s.path.ifBlank { "/" })
                .apply { if (s.host.isNotBlank()) put("host", s.host) }
                .put("headers", JSONObject().apply { if (s.host.isNotBlank()) put("Host", s.host) }))
            "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject()
                .put("path", s.path.ifBlank { "/" })
                .apply { if (s.host.isNotBlank()) put("host", s.host) })
            "xhttp" -> ss.put("xhttpSettings", JSONObject()
                .put("path", s.path.ifBlank { "/" })
                .apply {
                    if (s.host.isNotBlank()) put("host", s.host)
                    if (s.mode.isNotBlank()) put("mode", s.mode)
                })
            "grpc" -> ss.put("grpcSettings", JSONObject()
                .put("serviceName", s.serviceName.ifBlank { s.path.ifBlank { s.host } })
                .apply { if (s.mode.equals("multi", true)) put("multiMode", true) })
            "http" -> ss.put("httpSettings", JSONObject()
                .put("path", s.path.ifBlank { "/" })
                .put("host", JSONArray().apply { if (s.host.isNotBlank()) put(s.host) }))
            else -> { /* tcp: nothing extra */ }
        }

        // Security
        when (s.security) {
            "tls" -> {
                ss.put("security", "tls")
                val tls = JSONObject().put("allowInsecure", false)
                if (s.sni.isNotBlank()) tls.put("serverName", s.sni)
                if (s.alpn.isNotBlank()) tls.put("alpn", JSONArray(s.alpn.split(",")))
                if (s.fingerprint.isNotBlank()) tls.put("fingerprint", s.fingerprint)
                ss.put("tlsSettings", tls)
            }
            "reality" -> {
                ss.put("security", "reality")
                val reality = JSONObject()
                    .put("serverName", s.sni)
                    .put("publicKey", s.publicKey)
                    .put("shortId", s.shortId)
                    .put("fingerprint", s.fingerprint.ifBlank { "chrome" })
                    .put("spiderX", "")
                ss.put("realitySettings", reality)
            }
            else -> ss.put("security", "none")
        }
        return ss
    }
}
