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
    const val TUN_IPV6 = "fd00:1:2:3::1"
    const val DEFAULT_MTU = 1500

    @Volatile private var running = false

    val isRunning: Boolean get() = running

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

    /**
     * Builds the tunnel's YAML configuration.
     *
     * Both the historical (`tcp-read-write-timeout`) and the current (`read-write-timeout`)
     * timeout keys are emitted: the bundled native library ignores the one it does not know,
     * so the tunnel keeps sane timeouts across library versions instead of silently falling
     * back to "never time out", which strands half-open sockets after a network handover.
     */
    internal fun buildYaml(socksPort: Int, mtu: Int, tunnelIpv6: Boolean): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: manfaz")
        appendLine("  mtu: $mtu")
        appendLine("  multi-queue: false")
        appendLine("  ipv4: $TUN_IPV4")
        if (tunnelIpv6) appendLine("  ipv6: '$TUN_IPV6'")
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        appendLine("  pipeline: false")
        appendLine("misc:")
        appendLine("  task-stack-size: 20480")
        appendLine("  connect-timeout: 5000")
        appendLine("  read-write-timeout: 60000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  limit-nofile: 65535")
        appendLine("  log-level: warn")
    }
}
