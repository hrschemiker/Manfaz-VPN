package com.manfaz.vpn.net

import com.manfaz.vpn.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures a real TCP-connect latency to a server's address:port.
 * This is genuine network measurement (not simulated) — it opens a socket,
 * times the handshake, and closes it. Returns null on timeout/failure.
 */
object Pinger {

    private const val TIMEOUT_MS = 4000

    suspend fun tcpPing(server: ServerConfig): Int? = withContext(Dispatchers.IO) {
        if (server.address.isBlank() || server.port <= 0) return@withContext null
        repeat(2) { // best of two attempts
            val result = attempt(server.address, server.port)
            if (result != null) return@withContext result
        }
        null
    }

    private fun attempt(host: String, port: Int): Int? {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
            elapsed.coerceAtLeast(1)
        } catch (e: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
