package com.manfaz.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.core.TunnelOptions
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository

/**
 * Auto-connect on device boot. Only fires if the user enabled it and VPN permission was
 * previously granted (starting the service from boot then succeeds).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        com.manfaz.vpn.work.SubscriptionWorkScheduler.sync(context)
        if (!prefs.connectOnBoot) return

        ServerRepository.init(context)
        val server = ServerRepository.servers.value.firstOrNull { it.id == prefs.lastServerId }
            ?: return

        // Options travel as one snapshot, so this path can never drift out of sync with the
        // set of settings the in-app connect path sends.
        val start = Intent(context, ManfazVpnService::class.java)
            .setAction(ManfazVpnService.ACTION_START)
            .putExtra(ManfazVpnService.EXTRA_EPOCH, SystemClock.elapsedRealtimeNanos())
            .putExtra(ManfazVpnService.EXTRA_SERVER, ServerCodec.toJson(server))
            .putExtra(ManfazVpnService.EXTRA_OPTIONS, TunnelOptions.from(prefs).toJson())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(start)
            else context.startService(start)
        }
    }
}
