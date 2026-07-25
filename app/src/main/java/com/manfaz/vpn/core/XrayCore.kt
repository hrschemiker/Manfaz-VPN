package com.manfaz.vpn.core

import android.content.Context
import android.util.Base64
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Thin wrapper around AndroidLibXrayLite (Xray core).
 *
 * Lifecycle: [initEnv] once (copies geoip/geosite assets + sets XUDP key), then
 * [start] with a generated config and the VPN TUN file descriptor, [stop] to tear down.
 * Traffic counters come straight from the running core via [queryTraffic].
 */
object XrayCore {

    private const val TAG = "XrayCore"
    private var controller: CoreController? = null
    @Volatile private var initialized = false

    val isRunning: Boolean get() = controller?.isRunning == true

    fun version(): String = try { Libv2ray.checkVersionX() } catch (e: Throwable) { "?" }

    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    // Returns ~2 KB of real body → a success proves data actually downloads through the config.
    const val DOWNLOAD_TEST_URL = "https://speed.cloudflare.com/__down?bytes=2000"

    /**
     * Real HTTP-through-proxy latency for a single server's config, measured by spinning a
     * temporary outbound in the core (does NOT require the VPN to be running). The core reads
     * the full response body, so with [DOWNLOAD_TEST_URL] a success means real download works.
     * Returns ms, or null on failure/timeout. Must run off the main thread.
     */
    fun measureDelay(context: Context, configContent: String, url: String = DELAY_TEST_URL): Int? {
        return try {
            initEnv(context)
            val ms = Libv2ray.measureOutboundDelay(configContent, url)
            if (ms in 1..12_000) ms.toInt() else null
        } catch (e: Throwable) {
            Log.w(TAG, "measureDelay failed: ${e.message}")
            null
        }
    }

    /** Copy bundled geoip/geosite from assets to filesDir and initialize the core env. */
    fun initEnv(context: Context) {
        if (initialized) return
        val dir = context.filesDir
        copyAsset(context, "geoip.dat", File(dir, "geoip.dat"))
        copyAsset(context, "geosite.dat", File(dir, "geosite.dat"))
        // XUDP base key: 32 bytes, URL-safe Base64, NO padding — the format the Go core
        // decodes with (RawURLEncoding). A padded/standard key panics the core (SIGABRT).
        val keyBytes = "android_id".toByteArray(Charsets.UTF_8).copyOf(32)
        val xudpKey = Base64.encodeToString(
            keyBytes, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP
        )
        Libv2ray.initCoreEnv(dir.absolutePath, xudpKey)
        initialized = true
    }

    /**
     * Start the core. [onStatus] receives core status callbacks (code, message).
     * Throws if the core fails to start.
     */
    fun start(configContent: String, tunFd: Int, onStatus: (Long, String) -> Unit) {
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long {
                onStatus(code, message ?: "")
                return 0
            }
        }
        val c = Libv2ray.newCoreController(handler)
        controller = c
        c.startLoop(configContent, tunFd)
    }

    fun stop() {
        try { controller?.stopLoop() } catch (e: Throwable) { Log.w(TAG, "stopLoop", e) }
        controller = null
    }

    /** Returns pair(uploadBytes, downloadBytes) accumulated since last query (core resets counters). */
    fun queryTraffic(): Pair<Long, Long> {
        val c = controller ?: return 0L to 0L
        return try {
            val up = c.queryStats("proxy", "uplink")
            val down = c.queryStats("proxy", "downlink")
            up to down
        } catch (e: Throwable) { 0L to 0L }
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        try {
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "asset $name not found: ${e.message}")
        }
    }
}
