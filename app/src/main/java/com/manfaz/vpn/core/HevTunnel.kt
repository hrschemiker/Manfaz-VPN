package com.manfaz.vpn.core

import android.content.Context
import com.v2ray.ang.service.TProxyService
import java.io.File

/**
 * Drives the hev-socks5-tunnel native tun2socks: pumps the VPN TUN interface into
 * Xray's local SOCKS inbound. This is what actually routes device traffic.
 */
object HevTunnel {

    const val TUN_IPV4 = "10.10.14.1"
    const val DEFAULT_MTU = 1500

    @Volatile private var running = false

    /** Starts tun2socks against [tunFd], forwarding to 127.0.0.1:[socksPort]. */
    fun start(context: Context, tunFd: Int, socksPort: Int, mtu: Int, tunnelIpv6: Boolean) {
        TProxyService.ensureLoaded()
        val yaml = buildYaml(socksPort, mtu, tunnelIpv6)
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply { writeText(yaml) }
        TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
        running = true
    }

    fun stop() {
        if (!running) return
        runCatching { TProxyService.TProxyStopService() }
        running = false
    }

    private fun buildYaml(socksPort: Int, mtu: Int, tunnelIpv6: Boolean): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $mtu")
        appendLine("  ipv4: $TUN_IPV4")
        if (tunnelIpv6) appendLine("  ipv6: 'fd00:1:2:3::1'")
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }
}
