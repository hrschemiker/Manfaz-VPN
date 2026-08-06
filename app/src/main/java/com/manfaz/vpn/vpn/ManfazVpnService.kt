package com.manfaz.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.manfaz.vpn.R
import com.manfaz.vpn.core.HevTunnel
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.core.XrayConfig
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.core.XrayCore
import com.manfaz.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The real VPN service: builds the TUN interface, hands its fd to the Xray core via the
 * hev-socks5-tunnel bridge, and streams live traffic stats back to the UI via [StateBridge].
 * Runs in its own ":core" process so a native core crash cannot take down the UI.
 */
class ManfazVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.manfaz.vpn.START"
        const val ACTION_STOP = "com.manfaz.vpn.STOP"
        const val EXTRA_SERVER = "server_json"
        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_DNS_PROTECT = "dns_protect"
        const val EXTRA_IPV6_MODE = "ipv6_mode"
        const val EXTRA_REMOTE_DNS = "remote_dns"
        const val EXTRA_DNS_BOOTSTRAP = "dns_bootstrap"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_ALLOW_LAN = "allow_lan"
        const val EXTRA_NOTIFY_SERVER = "notify_server"
        const val EXTRA_NOTIFY_SPEED = "notify_speed"
        const val EXTRA_PERAPP_ENABLED = "perapp_enabled"
        const val EXTRA_PERAPP_BYPASS = "perapp_bypass"
        const val EXTRA_PERAPP_LIST = "perapp_list"
        private const val CHANNEL_ID = "manfaz_vpn_status"
        private const val NOTIF_ID = 1001
        private const val TAG = "ManfazVpnService"
    }

    private var tun: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var connectJob: Job? = null
    private var statsJob: Job? = null
    @Volatile private var serverName: String = "Manfaz VPN"
    @Volatile private var killSwitch: Boolean = false
    @Volatile private var showServerInNotification = true
    @Volatile private var showSpeedInNotification = true
    @Volatile private var activeServer: com.manfaz.vpn.data.model.ServerConfig? = null
    @Volatile private var connectedSince: Long = 0L
    @Volatile private var lastExitIp: String = "متصل"
    @Volatile private var lastHeartbeatAt: Long = 0L

    private val stateQueryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StateBridge.ACTION_QUERY) sendAuthoritativeState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            stateQueryReceiver,
            IntentFilter(StateBridge.ACTION_QUERY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                connectJob?.cancel(); connectJob = null
                teardown(); stopSelf(startId)
            }
            else -> startTunnel(intent)
        }
        // If Android reclaims this foreground process, redeliver the last connection intent.
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_REDELIVER_INTENT
    }

    private fun startTunnel(intent: Intent?) {
        connectJob?.cancel()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        // Clean restart if a previous tunnel is still up (switching servers).
        if (tun != null) {
            statsJob?.cancel(); statsJob = null
            HevTunnel.stop(); XrayCore.stop()
            runCatching { tun?.close() }; tun = null
        }

        val server = intent?.getStringExtra(EXTRA_SERVER)
            ?.let { runCatching { ServerCodec.fromJson(it) }.getOrNull() }
        if (server == null) {
            StateBridge.sendFailed(this, "سروری انتخاب نشده است."); teardown(); stopSelf(); return
        }
        serverName = server.displayLabel
        activeServer = server
        killSwitch = intent.getBooleanExtra(EXTRA_KILL_SWITCH, false)
        val dnsProtect = intent.getBooleanExtra(EXTRA_DNS_PROTECT, false)
        val ipv6Mode = runCatching {
            Ipv6Mode.valueOf(intent.getStringExtra(EXTRA_IPV6_MODE) ?: Ipv6Mode.DIRECT.name)
        }.getOrDefault(Ipv6Mode.DIRECT)
        val remoteDns = intent.getStringExtra(EXTRA_REMOTE_DNS) ?: "1.1.1.1"
        val dnsBootstrap = intent.getStringExtra(EXTRA_DNS_BOOTSTRAP) ?: "1.1.1.1"
        val requestedMtu = intent.getIntExtra(EXTRA_MTU, 0)
        val mtu = if (requestedMtu == 0) autoMtu() else requestedMtu.coerceIn(1280, 1500)
        val allowLan = intent.getBooleanExtra(EXTRA_ALLOW_LAN, true)
        showServerInNotification = intent.getBooleanExtra(EXTRA_NOTIFY_SERVER, true)
        showSpeedInNotification = intent.getBooleanExtra(EXTRA_NOTIFY_SPEED, true)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        val perAppEnabled = intent.getBooleanExtra(EXTRA_PERAPP_ENABLED, false)
        val perAppBypass = intent.getBooleanExtra(EXTRA_PERAPP_BYPASS, true)
        val perAppList = intent.getStringArrayExtra(EXTRA_PERAPP_LIST)?.toList() ?: emptyList()

        connectJob = scope.launch {
            try {
                XrayCore.initEnv(this@ManfazVpnService)
                validateDns(remoteDns, dnsBootstrap)
                val config = XrayConfig.build(
                    server, remoteDns = remoteDns,
                    dnsLeakProtection = dnsProtect, allowLan = allowLan, ipv6Mode = ipv6Mode,
                )

                val builder = Builder()
                    .setSession("Manfaz VPN")
                    .setMtu(mtu)
                    .addAddress(HevTunnel.TUN_IPV4, 30)
                    .addRoute("0.0.0.0", 0)
                // A custom resolver is opt-in. Without it Android inherits the physical
                // network's DNS instead of silently forcing 1.1.1.1.
                if (dnsProtect) builder.addDnsServer(dnsBootstrap)
                when (ipv6Mode) {
                    Ipv6Mode.BLOCK -> Unit // No IPv6 family configured: Android blocks it cleanly.
                    Ipv6Mode.TUNNEL -> {
                        builder.addAddress("fd00:1:2:3::1", 64)
                        builder.addRoute("::", 0)
                    }
                    Ipv6Mode.DIRECT -> builder.allowFamily(OsConstants.AF_INET6)
                }
                // A5: per-app split tunnel (or just exclude ourselves to avoid a loop)
                configurePerApp(builder, perAppEnabled, perAppBypass, perAppList)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

                val pfd = builder.establish()
                    ?: throw IllegalStateException("VPN permission is required.")
                tun = pfd

                com.manfaz.vpn.util.LogBuffer.log(this@ManfazVpnService, "connect", "starting ${server.displayLabel} (${server.protocol.label})")
                // 1) Start Xray core with the SOCKS inbound (tunFd=0 — hev drives the TUN)
                XrayCore.start(config, 0) { code, msg ->
                    Log.i(TAG, "core status $code: $msg")
                    if (msg.isNotBlank()) com.manfaz.vpn.util.LogBuffer.log(this@ManfazVpnService, "core", msg)
                }
                // 2) Start tun2socks: pump the TUN into Xray's SOCKS inbound
                HevTunnel.start(
                    this@ManfazVpnService, pfd.fd, XrayConfig.SOCKS_PORT, mtu,
                    tunnelIpv6 = ipv6Mode == Ipv6Mode.TUNNEL,
                )

                connectedSince = SystemClock.elapsedRealtime()
                lastHeartbeatAt = connectedSince
                lastExitIp = "متصل"
                StateBridge.sendConnected(
                    this@ManfazVpnService, server, lastExitIp, server.pingMs ?: 0, connectedSince,
                )
                ConnectionSnapshotStore.writeConnected(
                    this@ManfazVpnService, server, lastExitIp, server.pingMs ?: 0, connectedSince,
                )
                com.manfaz.vpn.widget.ManfazWidget.updateAll(this@ManfazVpnService, true, server)
                startStatsPolling()
                // C#9: fetch the real exit IP + country through the proxy
                launch {
                    com.manfaz.vpn.net.ExitIp.fetch(XrayConfig.SOCKS_PORT)?.let {
                        lastExitIp = it.ip
                        ConnectionSnapshotStore.writeConnected(
                            this@ManfazVpnService, server, lastExitIp, server.pingMs ?: 0, connectedSince,
                        )
                        StateBridge.sendIpInfo(this@ManfazVpnService, it.ip, it.country)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "startTunnel failed", e)
                com.manfaz.vpn.util.LogBuffer.log(this@ManfazVpnService, "error", e.message ?: e.toString())
                StateBridge.sendFailed(this@ManfazVpnService, friendlyError(e))
                if (killSwitch && tun != null) {
                    // A2 kill switch: keep the TUN up so traffic is blocked, not leaked.
                    HevTunnel.stop(); XrayCore.stop()
                    Log.w(TAG, "kill switch active — holding TUN to block traffic")
                } else {
                    teardown(); stopSelf()
                }
            }
        }
    }

    /** A5: configure per-app split tunneling on the TUN builder. */
    private fun configurePerApp(
        builder: Builder, enabled: Boolean, bypassMode: Boolean, apps: List<String>,
    ) {
        if (!enabled) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }
        require(bypassMode || apps.isNotEmpty()) {
            "در حالت «فقط برنامه‌های انتخابی»، حداقل یک برنامه را انتخاب کنید."
        }
        if (apps.isEmpty()) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }
        val set = apps.toMutableSet()
        if (bypassMode) set.add(packageName) else set.remove(packageName)
        set.forEach { pkg ->
            try {
                if (bypassMode) builder.addDisallowedApplication(pkg)
                else builder.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "per-app: package not found $pkg")
            }
        }
    }

    private fun validateDns(remote: String, bootstrap: String) {
        val numericBootstrap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.net.InetAddresses.isNumericAddress(bootstrap)
        } else {
            bootstrap.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) &&
                bootstrap.split('.').all { it.toIntOrNull() in 0..255 }
        }
        require(numericBootstrap) {
            "آدرس راه‌انداز DNS معتبر نیست."
        }
        if (remote.startsWith("http", true)) {
            val uri = android.net.Uri.parse(remote)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
                "آدرس DoH باید با https:// شروع شود."
            }
        } else {
            require(runCatching { java.net.InetAddress.getByName(remote) }.isSuccess) {
                "آدرس DNS معتبر نیست."
            }
        }
    }

    private fun autoMtu(): Int {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) 1400 else 1500
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                delay(1000)
                val (up, down) = XrayCore.queryTraffic()
                StateBridge.sendTraffic(this@ManfazVpnService, up, down)
                // Periodic state heartbeat repairs UI state if Android recreated the app process.
                val now = SystemClock.elapsedRealtime()
                if (now - lastHeartbeatAt >= 5_000L) {
                    lastHeartbeatAt = now
                    sendAuthoritativeState()
                }
                // C#10: live speed in the ongoing notification
                if (showSpeedInNotification) runCatching {
                    nm.notify(NOTIF_ID, buildNotification("↓ ${speed(down)}   ↑ ${speed(up)}"))
                }
            }
        }
    }

    private fun sendAuthoritativeState() {
        val server = activeServer
        if (tun != null && server != null && connectedSince > 0L) {
            ConnectionSnapshotStore.writeConnected(this, server, lastExitIp, server.pingMs ?: 0, connectedSince)
            StateBridge.sendConnected(this, server, lastExitIp, server.pingMs ?: 0, connectedSince)
        } else {
            ConnectionSnapshotStore.writeStopped(this)
            StateBridge.sendStopped(this)
        }
    }

    private fun speed(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        val u = listOf("KB/s", "MB/s", "GB/s"); var v = bps.toDouble() / 1024; var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i])
    }

    private fun teardown() {
        val lastServer = activeServer
        statsJob?.cancel(); statsJob = null
        HevTunnel.stop()
        XrayCore.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        activeServer = null
        connectedSince = 0L
        lastHeartbeatAt = 0L
        stopForegroundCompat()
        com.manfaz.vpn.widget.ManfazWidget.updateAll(this, false, lastServer)
        ConnectionSnapshotStore.writeStopped(this)
        StateBridge.sendStopped(this)
    }

    private fun friendlyError(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.contains("permission", true) -> "VPN permission is required."
            m.contains("پشتیبانی") -> m
            else -> "اتصال برقرار نشد: ${m.take(80)}"
        }
    }

    private fun buildNotification(speedLine: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ManfazVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val visibleServer = if (showServerInNotification) serverName else "اتصال محافظت‌شده"
        val text = if (speedLine != null) "$visibleServer\n$speedLine" else visibleServer
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("متصل به منفذ")
            .setContentText(if (speedLine != null) speedLine else visibleServer)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "قطع اتصال", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "وضعیت اتصال", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onRevoke() { teardown(); stopSelf() }

    override fun onDestroy() {
        connectJob?.cancel(); connectJob = null
        serviceJob.cancel()
        runCatching { unregisterReceiver(stateQueryReceiver) }
        teardown()
        super.onDestroy()
    }
}
