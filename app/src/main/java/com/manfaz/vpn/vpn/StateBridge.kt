package com.manfaz.vpn.vpn

import android.content.Context
import android.content.Intent

/**
 * Cross-process bridge: the VPN core runs in the ":core" process and reports state to the
 * UI (main) process via app-internal broadcasts. Keeps a native core crash from taking
 * down the UI.
 */
object StateBridge {
    const val ACTION = "com.manfaz.vpn.CORE_STATE"
    const val EXTRA_EVENT = "event"
    const val EXTRA_IP = "ip"
    const val EXTRA_PING = "ping"
    const val EXTRA_UP = "up"
    const val EXTRA_DOWN = "down"
    const val EXTRA_ERROR = "error"
    const val EXTRA_COUNTRY = "country"

    const val EVENT_CONNECTED = "connected"
    const val EVENT_FAILED = "failed"
    const val EVENT_TRAFFIC = "traffic"
    const val EVENT_STOPPED = "stopped"
    const val EVENT_IPINFO = "ipinfo"

    fun sendConnected(ctx: Context, ip: String, ping: Int) = send(ctx) {
        putExtra(EXTRA_EVENT, EVENT_CONNECTED); putExtra(EXTRA_IP, ip); putExtra(EXTRA_PING, ping)
    }

    fun sendFailed(ctx: Context, error: String) = send(ctx) {
        putExtra(EXTRA_EVENT, EVENT_FAILED); putExtra(EXTRA_ERROR, error)
    }

    fun sendTraffic(ctx: Context, up: Long, down: Long) = send(ctx) {
        putExtra(EXTRA_EVENT, EVENT_TRAFFIC); putExtra(EXTRA_UP, up); putExtra(EXTRA_DOWN, down)
    }

    fun sendStopped(ctx: Context) = send(ctx) { putExtra(EXTRA_EVENT, EVENT_STOPPED) }

    fun sendIpInfo(ctx: Context, ip: String, country: String) = send(ctx) {
        putExtra(EXTRA_EVENT, EVENT_IPINFO); putExtra(EXTRA_IP, ip); putExtra(EXTRA_COUNTRY, country)
    }

    private inline fun send(ctx: Context, block: Intent.() -> Unit) {
        val intent = Intent(ACTION).setPackage(ctx.packageName).apply(block)
        ctx.sendBroadcast(intent)
    }

    /** Applies an incoming broadcast to [VpnController] state (called in the UI process). */
    fun apply(intent: Intent) {
        when (intent.getStringExtra(EXTRA_EVENT)) {
            EVENT_CONNECTED -> VpnController.onCoreConnected(
                intent.getStringExtra(EXTRA_IP) ?: "متصل", intent.getIntExtra(EXTRA_PING, 0))
            EVENT_FAILED -> VpnController.onCoreFailed(intent.getStringExtra(EXTRA_ERROR) ?: "اتصال ناموفق")
            EVENT_TRAFFIC -> VpnController.onTraffic(
                intent.getLongExtra(EXTRA_UP, 0), intent.getLongExtra(EXTRA_DOWN, 0))
            EVENT_STOPPED -> VpnController.onServiceStopped()
            EVENT_IPINFO -> VpnController.onIpInfo(
                intent.getStringExtra(EXTRA_IP) ?: "—", intent.getStringExtra(EXTRA_COUNTRY) ?: "")
        }
    }
}
