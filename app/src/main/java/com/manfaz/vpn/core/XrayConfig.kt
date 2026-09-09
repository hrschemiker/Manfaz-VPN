package com.manfaz.vpn.core

import com.manfaz.vpn.data.FragmentMode
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.data.model.Protocol
import com.manfaz.vpn.data.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds an Xray JSON configuration from a [ServerConfig].
 *
 * Design notes that matter for correctness:
 *
 * - **No geo assets.** `geoip:`/`geosite:` rules require geoip.dat/geosite.dat next to the
 *   core; the app does not ship them, and a missing file makes Xray refuse the whole
 *   configuration. Every rule below therefore uses explicit CIDRs and domain matchers.
 * - **DNS never leaks and never deadlocks.** The proxy endpoint's own hostname and the DoH
 *   resolver's hostname are pinned to the numeric bootstrap resolver, everything else is
 *   resolved by the remote resolver through the tunnel.
 * - **The core process is excluded from the TUN** (see ManfazVpnService.configurePerApp), so
 *   the bootstrap resolver and the fragmenting dialer reach the physical network directly.
 */
object XrayConfig {

    const val SOCKS_PORT = 10808

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_BLOCK = "block"
    private const val TAG_DNS_OUT = "dns-out"
    private const val TAG_FRAGMENT = "fragment"
    private const val TAG_DNS_MODULE = "dns-module"

    /** Replaces `geoip:private`, which would require a bundled geoip.dat. */
    private val PRIVATE_CIDRS = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
        "240.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fc00::/7", "fe80::/10",
    )

    /**
     * Domestic services that must stay outside the tunnel: they geo-block foreign exit IPs,
     * so tunnelling them is both slower and frequently broken. `.ir` is matched by regex, the
     * rest are the widely used Iranian services that live on generic TLDs.
     */
    private val IRAN_DOMAINS = listOf(
        "regexp:\\.ir$",
        "domain:aparat.com", "domain:digikala.com", "domain:divar.ir", "domain:snapp.ir",
        "domain:cafebazaar.ir", "domain:myket.ir", "domain:filimo.com", "domain:namava.ir",
        "domain:varzesh3.com", "domain:blogfa.com", "domain:shaparak.ir", "domain:sep.ir",
        "domain:sadad.ir", "domain:asanpardakht.ir", "domain:tgju.org", "domain:zarinpal.com",
        "domain:snapp.market", "domain:tapsi.ir", "domain:torob.com", "domain:basalam.com",
        "domain:eitaa.com", "domain:rubika.ir", "domain:bale.ai", "domain:soroush-hamrah.ir",
        "domain:arvancloud.ir", "domain:arvancloud.com", "domain:parspack.com",
        "domain:iranserver.com", "domain:mci.ir", "domain:irancell.ir", "domain:rightel.ir",
    )

    /** Transports the bundled core understands. */
    private val SUPPORTED_NETWORKS = setOf(
        "tcp", "ws", "grpc", "http", "httpupgrade", "xhttp",
    )

    fun isSupportedByXray(p: Protocol) = p in setOf(
        Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN,
        Protocol.SHADOWSOCKS, Protocol.SOCKS, Protocol.HTTP,
    )

    /**
     * Builds the full configuration.
     *
     * [forLatencyProbe] drops the local inbound and the stats plumbing, which is what
     * `Libv2ray.measureOutboundDelay` expects when it spins up a throwaway instance.
     */
    fun build(
        server: ServerConfig,
        options: TunnelOptions = TunnelOptions(),
        forLatencyProbe: Boolean = false,
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        if (!forLatencyProbe) {
            // Traffic statistics, so XrayCore.queryTraffic() reports real numbers.
            root.put("stats", JSONObject())
            root.put(
                "policy",
                JSONObject().put(
                    "system",
                    JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true),
                ),
            )
            root.put("inbounds", JSONArray().put(socksInbound()))
        }

        root.put("outbounds", outbounds(server, options))
        root.put("dns", dns(server, options))
        root.put("routing", routing(options))
        return root.toString()
    }

    /** Backwards-compatible entry point used by the unit tests and the latency probe. */
    fun build(
        server: ServerConfig,
        remoteDns: String,
        dnsBootstrap: String,
        dnsLeakProtection: Boolean = true,
        allowLan: Boolean = true,
        ipv6Mode: Ipv6Mode = Ipv6Mode.BLOCK,
    ): String = build(
        server,
        TunnelOptions(
            remoteDns = remoteDns,
            dnsBootstrap = dnsBootstrap,
            dnsLeakProtection = dnsLeakProtection,
            allowLan = allowLan,
            ipv6Mode = ipv6Mode,
        ),
    )

    private fun socksInbound(): JSONObject = JSONObject()
        .put("tag", "socks-in")
        .put("port", SOCKS_PORT)
        .put("listen", "127.0.0.1")
        .put("protocol", "socks")
        .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                // Observe the domain for routing but keep the application's original
                // destination; rewriting it breaks some push/CDN/QUIC flows.
                .put("routeOnly", true),
        )

    // ---------------------------------------------------------------- outbounds

    private fun outbounds(server: ServerConfig, options: TunnelOptions): JSONArray {
        val list = JSONArray()
        list.put(proxyOutbound(server, options))

        val directSettings = JSONObject().put("domainStrategy", freedomStrategy(options.ipv6Mode))
        list.put(
            JSONObject()
                .put("tag", TAG_DIRECT)
                .put("protocol", "freedom")
                .put("settings", directSettings),
        )
        list.put(JSONObject().put("tag", TAG_DNS_OUT).put("protocol", "dns"))
        list.put(JSONObject().put("tag", TAG_BLOCK).put("protocol", "blackhole"))

        fragmentOutbound(server, options)?.let(list::put)
        return list
    }

    private fun freedomStrategy(ipv6Mode: Ipv6Mode) = when (ipv6Mode) {
        Ipv6Mode.BLOCK -> "UseIPv4"
        Ipv6Mode.TUNNEL -> "UseIPv4v6"
        Ipv6Mode.DIRECT -> "UseIP"
    }

    /**
     * TLS ClientHello fragmentation. Splitting the handshake across several TCP segments
     * stops SNI-based DPI from reassembling the hostname, which is currently the single most
     * effective countermeasure on Iranian networks. The proxy outbound dials through this
     * one via `sockopt.dialerProxy`.
     */
    private fun fragmentOutbound(server: ServerConfig, options: TunnelOptions): JSONObject? {
        if (!usesFragment(server, options)) return null
        val aggressive = options.fragmentMode == FragmentMode.AGGRESSIVE
        val fragment = JSONObject()
            .put("packets", if (aggressive) "1-3" else "tlshello")
            .put("length", if (aggressive) "10-20" else "100-200")
            .put("interval", if (aggressive) "10-20" else "10-20")
        val settings = JSONObject()
            .put("domainStrategy", freedomStrategy(options.ipv6Mode))
            .put("fragment", fragment)
        if (aggressive) {
            // Padding noise ahead of the handshake defeats simple packet-size fingerprinting.
            settings.put(
                "noises",
                JSONArray().put(
                    JSONObject()
                        .put("type", "rand")
                        .put("packet", "10-20")
                        .put("delay", "10-16"),
                ),
            )
        }
        return JSONObject()
            .put("tag", TAG_FRAGMENT)
            .put("protocol", "freedom")
            .put("settings", settings)
            .put(
                "streamSettings",
                JSONObject().put(
                    "sockopt",
                    JSONObject().put("tcpNoDelay", true).put("tcpKeepAliveIdle", 100),
                ),
            )
    }

    private fun usesFragment(server: ServerConfig, options: TunnelOptions): Boolean =
        when (options.fragmentMode) {
            FragmentMode.OFF -> false
            // REALITY already conceals the real SNI, so AUTO leaves its handshake untouched.
            FragmentMode.AUTO -> server.security.equals("tls", true)
            FragmentMode.ALWAYS, FragmentMode.AGGRESSIVE ->
                server.security.equals("tls", true) || server.security.equals("reality", true)
        }

    private fun proxyOutbound(s: ServerConfig, options: TunnelOptions): JSONObject {
        val out = JSONObject().put("tag", TAG_PROXY)
        when (s.protocol) {
            Protocol.VLESS -> {
                out.put("protocol", "vless")
                val user = JSONObject()
                    .put("id", s.uuid)
                    // Xray 25.9+ accepts post-quantum VLESS encryption strings here; older
                    // cores only ever see "none". Passing the value straight through keeps
                    // both working without a version check.
                    .put("encryption", s.encryption.ifBlank { "none" })
                if (s.flow.isNotBlank()) user.put("flow", s.flow)
                out.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject().put("address", s.address).put("port", s.port)
                                .put("users", JSONArray().put(user)),
                        ),
                    ),
                )
            }
            Protocol.VMESS -> {
                out.put("protocol", "vmess")
                val user = JSONObject().put("id", s.uuid).put("alterId", s.alterId)
                    .put("security", s.encryption.ifBlank { "auto" })
                out.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject().put("address", s.address).put("port", s.port)
                                .put("users", JSONArray().put(user)),
                        ),
                    ),
                )
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
                out.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject().put("address", s.address).put("port", s.port)
                                .put("method", normalizeSsMethod(s.method))
                                .put("password", s.password)
                                .put("uot", true),
                        ),
                    ),
                )
            }
            Protocol.SOCKS -> {
                out.put("protocol", "socks")
                val srv = JSONObject().put("address", s.address).put("port", s.port)
                if (s.uuid.isNotBlank() || s.password.isNotBlank()) {
                    srv.put(
                        "users",
                        JSONArray().put(
                            JSONObject().put("user", s.uuid).put("pass", s.password),
                        ),
                    )
                }
                out.put("settings", JSONObject().put("servers", JSONArray().put(srv)))
            }
            Protocol.HTTP -> {
                out.put("protocol", "http")
                val srv = JSONObject().put("address", s.address).put("port", s.port)
                if (s.uuid.isNotBlank() || s.password.isNotBlank()) {
                    srv.put(
                        "users",
                        JSONArray().put(
                            JSONObject().put("user", s.uuid).put("pass", s.password),
                        ),
                    )
                }
                out.put("settings", JSONObject().put("servers", JSONArray().put(srv)))
            }
            else -> throw IllegalArgumentException(
                "پروتکل ${s.protocol.label} در هستهٔ Xray پشتیبانی نمی‌شود",
            )
        }
        out.put("streamSettings", streamSettings(s, options))
        muxSettings(s, options)?.let { out.put("mux", it) }
        return out
    }

    /**
     * Mux.Cool multiplexes many streams over one connection and tunnels UDP over TCP (XUDP),
     * which helps when a carrier throttles UDP. It is incompatible with XTLS Vision flow
     * control, so it is skipped there instead of producing a config the core rejects.
     */
    private fun muxSettings(s: ServerConfig, options: TunnelOptions): JSONObject? {
        if (!options.muxEnabled) return null
        if (s.flow.contains("vision", true)) return null
        return JSONObject()
            .put("enabled", true)
            .put("concurrency", 8)
            .put("xudpConcurrency", 16)
            .put("xudpProxyUDP443", "reject")
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
    internal fun normalizeNetwork(net: String): String = when (net.lowercase().trim()) {
        "", "raw", "tcp" -> "tcp"
        "h2", "h3", "http" -> "http"
        "splithttp", "xhttp" -> "xhttp"
        "ws", "websocket" -> "ws"
        "httpupgrade" -> "httpupgrade"
        "grpc", "gun" -> "grpc"
        else -> net.lowercase().trim()
    }

    private fun streamSettings(s: ServerConfig, options: TunnelOptions): JSONObject {
        val requested = normalizeNetwork(s.network)
        val net = if (requested in SUPPORTED_NETWORKS) requested else "tcp"
        val endpointStrategy = when {
            s.address.contains(':') -> "UseIPv6"
            options.ipv6Mode == Ipv6Mode.TUNNEL -> "UseIPv4v6"
            else -> "UseIPv4"
        }
        val sockopt = JSONObject()
            .put("domainStrategy", endpointStrategy)
            // Interactive traffic dominates on mobile; Nagle only adds latency here.
            .put("tcpNoDelay", true)
            .put("tcpKeepAliveIdle", 100)
        if (usesFragment(s, options)) sockopt.put("dialerProxy", TAG_FRAGMENT)

        val ss = JSONObject().put("network", net).put("sockopt", sockopt)

        when (net) {
            "ws" -> ss.put(
                "wsSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply {
                        if (s.host.isNotBlank()) {
                            put("host", s.host)
                            put("headers", JSONObject().put("Host", s.host))
                        }
                    },
            )
            "httpupgrade" -> ss.put(
                "httpupgradeSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply { if (s.host.isNotBlank()) put("host", s.host) },
            )
            "xhttp" -> ss.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply {
                        if (s.host.isNotBlank()) put("host", s.host)
                        if (s.mode.isNotBlank()) put("mode", s.mode)
                        // "extra" carries the per-provider XHTTP tuning block verbatim.
                        runCatching { JSONObject(s.extra) }.getOrNull()?.let { put("extra", it) }
                    },
            )
            "grpc" -> ss.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", s.serviceName.ifBlank { s.path.ifBlank { "" } })
                    .apply {
                        if (s.host.isNotBlank()) put("authority", s.host)
                        if (s.mode.equals("multi", true)) put("multiMode", true)
                    },
            )
            "http" -> ss.put(
                "httpSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .put("host", JSONArray().apply { if (s.host.isNotBlank()) put(s.host) }),
            )
            else -> { /* tcp: nothing extra */ }
        }

        when (s.security.lowercase()) {
            "tls" -> {
                ss.put("security", "tls")
                val tlsName = s.sni.ifBlank { s.host }.ifBlank { s.address }
                val tls = JSONObject().put("allowInsecure", false)
                if (tlsName.isNotBlank() && !isNumericHost(tlsName)) tls.put("serverName", tlsName)
                if (s.alpn.isNotBlank()) {
                    tls.put(
                        "alpn",
                        JSONArray(s.alpn.split(",").map(String::trim).filter(String::isNotEmpty)),
                    )
                }
                tls.put("fingerprint", fingerprintOf(s, options))
                ss.put("tlsSettings", tls)
            }
            "reality" -> {
                ss.put("security", "reality")
                val reality = JSONObject()
                    .put("serverName", s.sni)
                    .put("publicKey", s.publicKey)
                    .put("shortId", s.shortId)
                    .put("fingerprint", fingerprintOf(s, options))
                    .put("spiderX", s.spiderX.ifBlank { "/" })
                // Post-quantum REALITY verification (Xray 25.10+). Older cores ignore the key.
                if (s.mldsa65Verify.isNotBlank()) reality.put("mldsa65Verify", s.mldsa65Verify)
                ss.put("realitySettings", reality)
            }
            else -> ss.put("security", "none")
        }
        return ss
    }

    /**
     * uTLS fingerprint. A config that pins one wins; otherwise the user's preference applies,
     * because a mismatched ClientHello is itself a fingerprint DPI can act on.
     */
    private fun fingerprintOf(s: ServerConfig, options: TunnelOptions): String =
        s.fingerprint.ifBlank { options.tlsFingerprint }.ifBlank { "chrome" }

    private fun isNumericHost(value: String): Boolean =
        value.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(value)

    // ---------------------------------------------------------------------- dns

    /**
     * DNS resolution order:
     *
     * 1. the proxy endpoint's own hostname and the DoH resolver's hostname go to the numeric
     *    bootstrap resolver — resolving them through the tunnel would be circular;
     * 2. Iranian domains go to the bootstrap resolver as well, so domestic sites keep seeing
     *    a domestic IP and do not geo-block the user;
     * 3. everything else is resolved by the remote resolver, which travels inside the tunnel.
     */
    private fun dns(server: ServerConfig, options: TunnelOptions): JSONObject {
        val bootstrap = options.dnsBootstrap.ifBlank { "1.1.1.1" }
        val remote = options.remoteDns.ifBlank { bootstrap }
        val dohHost = dohHost(remote)
        val servers = JSONArray()

        val local = buildList {
            if (server.address.isNotBlank() && !isNumericHost(server.address)) {
                add("full:${server.address}")
            }
            dohHost?.let { add("full:$it") }
            if (options.iranDirect) addAll(IRAN_DOMAINS)
        }
        if (local.isNotEmpty()) {
            val domains = JSONArray(local)
            servers.put(
                JSONObject()
                    .put("address", bootstrap)
                    .put("domains", domains)
                    .put("skipFallback", true),
            )
            // Fallback for networks that poison or drop queries to public resolvers: the
            // device's own resolver still answers, and these names are not the sensitive ones.
            servers.put(
                JSONObject()
                    .put("address", "localhost")
                    .put("domains", JSONArray(local))
                    .put("skipFallback", true),
            )
        }
        servers.put(remote)

        val dns = JSONObject()
            .put("servers", servers)
            .put("queryStrategy", if (options.ipv6Mode == Ipv6Mode.BLOCK) "UseIPv4" else "UseIP")
            .put("disableCache", false)
            .put("tag", TAG_DNS_MODULE)
        // A DoH hostname must not depend on the very resolver it is trying to reach.
        dohHost?.let { dns.put("hosts", JSONObject().put(it, bootstrap)) }
        return dns
    }

    private fun dohHost(resolver: String): String? = runCatching { java.net.URI(resolver) }
        .getOrNull()
        ?.takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() }
        ?.host

    // ------------------------------------------------------------------ routing

    private fun routing(options: TunnelOptions): JSONObject {
        val rules = JSONArray()

        // 1. The DNS module's own upstream queries must be routed before the generic port-53
        //    rule below, otherwise they would be handed back to the DNS outbound forever.
        //    Plain DNS (the numeric bootstrap) goes direct so it still answers while the
        //    tunnel is being established; the encrypted remote resolver goes through it.
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray(listOf(TAG_DNS_MODULE)))
                .put("port", "53")
                .put("outboundTag", TAG_DIRECT),
        )
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray(listOf(TAG_DNS_MODULE)))
                .put("outboundTag", TAG_PROXY),
        )

        // 2. Every DNS query from the device is answered by the DNS module above. Without
        //    this rule, plain port-53 traffic escapes to whatever resolver the app asked for.
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("outboundTag", if (options.dnsLeakProtection) TAG_DNS_OUT else TAG_DIRECT)
                .put("port", "53"),
        )

        // 3. QUIC is frequently throttled rather than blocked outright; dropping it makes
        //    browsers fall back to TCP+TLS instead of stalling on a black-holed UDP flow.
        if (options.blockQuic) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("outboundTag", TAG_BLOCK)
                    .put("network", "udp")
                    .put("port", "443"),
            )
        }

        // 4. LAN / loopback / CGNAT.
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("outboundTag", if (options.allowLan) TAG_DIRECT else TAG_BLOCK)
                .put("ip", JSONArray(PRIVATE_CIDRS)),
        )

        // 5. Domestic services stay off the tunnel.
        if (options.iranDirect) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("outboundTag", TAG_DIRECT)
                    .put("domain", JSONArray(IRAN_DOMAINS)),
            )
        }

        return JSONObject()
            // AsIs keeps the sniffed hostname and avoids resolving every destination twice.
            .put("domainStrategy", "AsIs")
            .put("rules", rules)
    }
}
