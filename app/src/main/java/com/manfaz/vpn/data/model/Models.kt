package com.manfaz.vpn.data.model

import java.util.UUID

enum class Protocol(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
    WIREGUARD("WireGuard"),
    HYSTERIA("Hysteria"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    UNKNOWN("Unknown");
}

/** A single server configuration parsed from a URI/subscription/manual entry. */
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val uuid: String = "",           // id / user id
    val alterId: Int = 0,            // legacy VMess alterId
    val encryption: String = "",     // VMess security / VLESS encryption (incl. post-quantum)
    val password: String = "",       // trojan/ss password
    val method: String = "",         // ss cipher
    val network: String = "tcp",     // tcp/ws/grpc/h2/httpupgrade/xhttp/quic...
    val security: String = "none",   // none/tls/reality
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",    // gRPC service name
    val mode: String = "",           // gRPC multi mode / xhttp mode (auto/packet-up/stream-up)
    val flow: String = "",
    val alpn: String = "",
    val fingerprint: String = "",    // uTLS fingerprint (chrome/firefox/...)
    val publicKey: String = "",      // REALITY public key (pbk)
    val shortId: String = "",        // REALITY short id (sid)
    val spiderX: String = "",        // REALITY spiderX (spx)
    val mldsa65Verify: String = "",  // REALITY post-quantum verify key (pqv), Xray 25.10+
    val extra: String = "",          // XHTTP "extra" JSON blob
    val group: String = "",          // subscription / manual group
    val favorite: Boolean = false,
    val pingMs: Int? = null,         // last measured delay, null = untested/unreachable
    val latencyTested: Boolean = false, // distinguishes an actual failed probe from never tested
    val rawUri: String = "",
) {
    /** Stable protocol identity used when a subscription is refreshed. */
    val identityKey: String get() = listOf(
        protocol.name, address.trim().lowercase(), port.toString(), uuid, password,
        method.lowercase(), network.lowercase(), security.lowercase(), sni.lowercase(),
        host.lowercase(), path, serviceName, flow, publicKey, shortId,
    ).joinToString(separator = "|")

    private val country: Country get() = Countries.detect(name)
    val flagEmoji: String get() = country.flag
    val displayCountry: String get() = country.faName
    val isoCode: String get() = country.iso

    /** Name shown to the user everywhere. */
    val displayLabel: String get() = name.ifBlank { "$address:$port" }

    /** Short technical summary used in list rows and on the connection card. */
    val transportLabel: String get() = buildString {
        append(protocol.label)
        val net = network.lowercase().ifBlank { "tcp" }
        if (net != "tcp") append(" - ").append(net.uppercase())
        when (security.lowercase()) {
            "tls" -> append(" - TLS")
            "reality" -> append(" - REALITY")
        }
    }
}
