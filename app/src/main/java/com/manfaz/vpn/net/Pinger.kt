package com.manfaz.vpn.net

import com.manfaz.vpn.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Real TCP-handshake latency to a server's address:port.
 *
 * This is the cheap half of the latency pipeline: no proxy core is started, so a whole
 * subscription can be measured in a couple of seconds and sorted immediately. It answers
 * "is this endpoint reachable from my network right now, and how far away is it", which is
 * the question that actually decides which servers are worth a full end-to-end probe.
 */
object Pinger {

    private const val TIMEOUT_MS = 2_500
    private const val DEFAULT_CONCURRENCY = 16

    /** Measures one server. Returns milliseconds, or null when unreachable. */
    suspend fun tcpPing(server: ServerConfig): Int? = withContext(Dispatchers.IO) {
        if (server.address.isBlank() || server.port !in 1..65535) return@withContext null
        // Resolve once so a slow resolver is not counted twice into the handshake time.
        val resolved = runCatching { InetAddress.getByName(server.address) }.getOrNull()
            ?: return@withContext null
        attempt(resolved, server.port) ?: attempt(resolved, server.port)
    }

    /**
     * Measures a whole list concurrently and reports progress as results land, so a long run
     * shows movement instead of an indeterminate spinner.
     */
    suspend fun tcpPingAll(
        servers: List<ServerConfig>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, Int?> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyMap()
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        var completed = 0
        val lock = Any()
        servers.map { server ->
            async(Dispatchers.IO) {
                val ms = gate.withPermit { tcpPing(server) }
                synchronized(lock) { onProgress(++completed, servers.size) }
                server.id to ms
            }
        }.awaitAll().toMap()
    }

    private fun attempt(host: InetAddress, port: Int): Int? {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: UnknownHostException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
