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
    val alterId: Int = 0,             // legacy VMess alterId
    val encryption: String = "",     // VMess security / VLESS encryption
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
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val group: String = "",          // subscription / manual group
    val favorite: Boolean = false,
    val pingMs: Int? = null,         // last measured delay, null = untested/unreachable
    val latencyTested: Boolean = false, // distinguishes an actual failed probe from never tested
    val noPingSinceMs: Long = 0L,    // epoch millis since it first had no ping (free configs); 0 = ok
    val rawUri: String = "",
) {
    /** Stable protocol identity used when a subscription is refreshed. */
    val identityKey: String get() = listOf(
        protocol.name, address.trim().lowercase(), port.toString(), uuid, password,
        method.lowercase(), network.lowercase(), security.lowercase(), sni.lowercase(),
        host.lowercase(), path, serviceName, flow, publicKey, shortId,
    ).joinToString("\u001f")
    private val country: Country get() = Countries.detect(name)
    val flagEmoji: String get() = country.flag
    val displayCountry: String get() = country.faName
    val isoCode: String get() = country.iso

    /** Anonymized label for free configs: country flag + a stable 6-digit code (no real name). */
    val freeAlias: String get() {
        val n = (kotlin.math.abs(id.hashCode()) % 900_000) + 100_000
        return "$flagEmoji  $n"
    }

    val isFree: Boolean get() = group == "رایگان"

    /** Name to show the user everywhere — anonymized for free configs. */
    val displayLabel: String get() = if (isFree) freeAlias else name
}
