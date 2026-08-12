package com.manfaz.vpn.net

import com.manfaz.vpn.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.net.InetAddress
import kotlin.random.Random

/**
 * On-device Cloudflare "clean IP" scanner. Samples IPs from Cloudflare's published edge
 * ranges and measures TCP-connect latency to :443 from the user's current network, then
 * returns the fastest reachable one. Everything is measured live, so results reflect the
 * user's ISP/time — no stale hardcoded lists.
 *
 * Only helps CDN-fronted configs (TLS + WS/gRPC/HTTPUpgrade/XHTTP on a Cloudflare-proxied
 * domain): the dialed IP is swapped for the clean IP while the real domain is kept as the
 * TLS SNI and transport Host, which is how Cloudflare routes to the right backend.
 */
object CloudflareScanner {

    // Cloudflare's published IPv4 ranges (cloudflare.com/ips-v4).
    private val cidrs = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private val cdnNetworks = setOf("ws", "grpc", "httpupgrade", "xhttp", "http", "h2")

    /** True if swapping the dial IP can help (Cloudflare-frontable config with a real domain). */
    fun isCdnEligible(s: ServerConfig): Boolean {
        if (s.security != "tls") return false
        if (s.network.lowercase() !in cdnNetworks) return false
        return originalDomain(s) != null
    }

    /** Confirms that the config domain currently resolves into a published Cloudflare range. */
    suspend fun isVerifiedCloudflare(s: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        if (!isCdnEligible(s)) return@withContext false
        val domain = originalDomain(s) ?: return@withContext false
        runCatching {
            InetAddress.getAllByName(domain).any { address ->
                val ip = address.hostAddress ?: return@any false
                isIpv4(ip) && cidrs.any { contains(it, ip) }
            }
        }.getOrDefault(false)
    }

    /** Returns a copy that dials [cleanIp] while preserving the original domain for SNI/Host. */
    fun withCleanIp(s: ServerConfig, cleanIp: String): ServerConfig {
        val domain = originalDomain(s) ?: return s
        return s.copy(
            address = cleanIp,
            sni = domain,
            host = s.host.ifBlank { domain },
        )
    }

    private fun originalDomain(s: ServerConfig): String? =
        listOf(s.sni, s.host, s.address).firstOrNull { it.isNotBlank() && !isIpv4(it) }

    /** Scans and returns the fastest reachable Cloudflare edge IP, or null. */
    suspend fun findBest(
        sampleCount: Int = 50,
        perTimeoutMs: Int = 1300,
        overallMs: Long = 5000,
        concurrency: Int = 24,
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(overallMs) {
            val ips = (1..sampleCount).map { randomIp() }.distinct()
            val gate = Semaphore(concurrency)
            val timed = ips.map { ip ->
                async {
                    gate.withPermit {
                        val ms = tcpLatency(ip, 443, perTimeoutMs)
                        if (ms != null) ip to ms else null
                    }
                }
            }.awaitAll().filterNotNull()
            timed.minByOrNull { it.second }?.first
        }
    }

    private fun tcpLatency(ip: String, port: Int, timeoutMs: Int): Int? {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun randomIp(): String {
        val cidr = cidrs.random()
        val (base, prefix) = cidr.split("/")
        val baseInt = ipToLong(base)
        val hostBits = 32 - prefix.toInt()
        val size = 1L shl hostBits
        val offset = if (size <= 2) 0L else Random.nextLong(1, size - 1)
        return longToIp((baseInt and (0xFFFFFFFFL shl hostBits)) + offset)
    }

    private fun ipToLong(ip: String): Long {
        val p = ip.split(".")
        return (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
    }

    private fun longToIp(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private fun isIpv4(s: String): Boolean =
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(s)

    private fun contains(cidr: String, ip: String): Boolean {
        val (base, prefixText) = cidr.split("/")
        val prefix = prefixText.toInt()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (ipToLong(base) and mask) == (ipToLong(ip) and mask)
    }
}
