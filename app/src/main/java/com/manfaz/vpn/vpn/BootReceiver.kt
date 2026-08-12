package com.manfaz.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository

/**
 * C#12: auto-connect on device boot. Only fires if the user enabled it and a VPN
 * permission was previously granted (starting the service from boot then succeeds).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        com.manfaz.vpn.work.SubscriptionWorkScheduler.sync(context)
        if (!prefs.connectOnBoot) return

        ServerRepository.init(context)
        val server = ServerRepository.servers.value.firstOrNull { it.id == prefs.lastServerId } ?: return

        val start = Intent(context, ManfazVpnService::class.java)
            .setAction(ManfazVpnService.ACTION_START)
            .putExtra(ManfazVpnService.EXTRA_SERVER, ServerCodec.toJson(server))
            .putExtra(ManfazVpnService.EXTRA_KILL_SWITCH, prefs.killSwitch)
            .putExtra(ManfazVpnService.EXTRA_DNS_PROTECT, prefs.dnsLeakProtection)
            .putExtra(ManfazVpnService.EXTRA_IPV6_MODE, prefs.ipv6Mode.name)
            .putExtra(ManfazVpnService.EXTRA_REMOTE_DNS, prefs.remoteDns)
            .putExtra(ManfazVpnService.EXTRA_DNS_BOOTSTRAP, prefs.dnsBootstrap)
            .putExtra(ManfazVpnService.EXTRA_MTU, prefs.mtu)
            .putExtra(ManfazVpnService.EXTRA_ALLOW_LAN, prefs.allowLan)
            .putExtra(ManfazVpnService.EXTRA_NOTIFY_SERVER, prefs.showServerInNotification)
            .putExtra(ManfazVpnService.EXTRA_NOTIFY_SPEED, prefs.showSpeedInNotification)
            .putExtra(ManfazVpnService.EXTRA_PERAPP_ENABLED, prefs.perAppEnabled)
            .putExtra(ManfazVpnService.EXTRA_PERAPP_BYPASS, prefs.perAppBypassMode)
            .putExtra(ManfazVpnService.EXTRA_PERAPP_LIST, prefs.perAppSet.toTypedArray())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(start)
            else context.startService(start)
        }
    }
}
