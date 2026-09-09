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
import android.net.ConnectivityManager
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
import com.manfaz.vpn.core.TunnelOptions
import com.manfaz.vpn.core.XrayConfig
import com.manfaz.vpn.data.Ipv6Mode
import com.manfaz.vpn.core.XrayCore
import com.manfaz.vpn.net.ProxyHealthProbe
import com.manfaz.vpn.ui.MainActivity
import com.manfaz.vpn.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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

        /**
         * Kill-switch escape hatch. Tears the blocking tunnel down so the device is online
         * again; surfaced both in the ongoing notification and on the home screen.
         */
        const val ACTION_RELEASE = "com.manfaz.vpn.RELEASE_KILL_SWITCH"

        /** Retry the last connection attempt without leaving the kill-switch hold. */
        const val ACTION_RETRY = "com.manfaz.vpn.RETRY"

        const val EXTRA_EPOCH = "connect_epoch"
        const val EXTRA_SERVER = "server_json"
        const val EXTRA_OPTIONS = "tunnel_options"

        private const val CHANNEL_ID = "manfaz_vpn_status"
        private const val CHANNEL_ID_ALERT = "manfaz_vpn_alerts"
        private const val NOTIF_ID = 1001
        private const val TAG = "ManfazVpnService"
        private const val NOTIF_CONNECTING = "connecting"
        private const val NOTIF_CONNECTED = "connected"
        private const val NOTIF_HOLDING = "holding"
        private const val HEALTH_INITIAL_DELAY_MS = 45_000L
        private const val HEALTH_INTERVAL_MS = 45_000L
        private const val HEALTH_FAILURE_THRESHOLD = 3
        private const val HEALTH_RECOVERY_COOLDOWN_MS = 5 * 60_000L
    }

    private var tun: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var connectJob: Job? = null
    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private var upstreamMonitor: UpstreamNetworkMonitor? = null
    private var lastStartIntent: Intent? = null
    @Volatile private var reloadingUpstream = false
    @Volatile private var serverName: String = "Manfaz VPN"
    @Volatile private var options: TunnelOptions = TunnelOptions()
    @Volatile private var activeServer: com.manfaz.vpn.data.model.ServerConfig? = null
    @Volatile private var connectedSince: Long = 0L
    @Volatile private var lastExitIp: String = "متصل"
    @Volatile private var lastHeartbeatAt: Long = 0L
    @Volatile private var notifState = NOTIF_CONNECTING
    @Volatile private var holdReason: String = ""
    @Volatile private var lastEpoch = 0L
    @Volatile private var activeMtu = HevTunnel.DEFAULT_MTU
    @Volatile private var consecutiveHealthFailures = 0
    @Volatile private var lastHealthRecoveryAt = 0L

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            upstreamMonitor = UpstreamNetworkMonitor(
                connectivity = getSystemService(ConnectivityManager::class.java),
                scope = scope,
                onNetworksChanged = { networks -> runCatching { setUnderlyingNetworks(networks) } },
                onHandover = ::reloadForUpstreamHandover,
            ).also { it.register() }
        } else {
            // Legacy fallback: Android O does not support NET_CAPABILITY_NOT_VPN on requests,
            // but binding the VPN to the current physical network still improves handover.
            runCatching {
                getSystemService(ConnectivityManager::class.java).activeNetwork?.let {
                    setUnderlyingNetworks(arrayOf(it))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP, ACTION_RELEASE -> {
                // Record the disconnect marker so any stale START (e.g. a scan that finished
                // after the user hit disconnect) arriving later is dropped, never resurrecting
                // the VPN behind the user's back.
                lastEpoch = maxOf(lastEpoch + 1L, intent?.getLongExtra(EXTRA_EPOCH, 0L) ?: 0L)
                if (action == ACTION_RELEASE) {
                    LogBuffer.log(this, "kill-switch", "user released the traffic block")
                }
                connectJob?.cancel(); connectJob = null
                teardown(); stopSelf(startId)
            }
            ACTION_RETRY -> retryLastConnection()
            else -> startTunnel(intent)
        }
        // If Android reclaims this foreground process, redeliver the last connection intent.
        return if (action == ACTION_STOP || action == ACTION_RELEASE) {
            START_NOT_STICKY
        } else {
            START_REDELIVER_INTENT
        }
    }

    /** Re-runs the last START intent, used by the "try again" notification action. */
    private fun retryLastConnection() {
        val previous = lastStartIntent ?: return
        val retry = Intent(previous).putExtra(EXTRA_EPOCH, SystemClock.elapsedRealtimeNanos())
        startTunnel(retry)
    }

    private fun startTunnel(intent: Intent?) {
        // Drop stale start requests (older than the last STOP, or superseded by a newer START).
        val epoch = intent?.getLongExtra(EXTRA_EPOCH, 0L) ?: 0L
        if (epoch <= lastEpoch) {
            Log.w(TAG, "ignoring stale start request (epoch $epoch <= $lastEpoch)")
            return
        }
        lastEpoch = maxOf(lastEpoch, epoch)
        lastStartIntent = intent?.let(::Intent)

        connectJob?.cancel()
        createChannels()
        notifState = NOTIF_CONNECTING
        startForeground(NOTIF_ID, buildNotification())
        // Clean restart if a previous tunnel is still up (switching servers).
        if (tun != null) {
            statsJob?.cancel(); statsJob = null
            healthJob?.cancel(); healthJob = null
            HevTunnel.stop(); XrayCore.stop()
            runCatching { tun?.close() }; tun = null
        }

        val server = intent?.getStringExtra(EXTRA_SERVER)
            ?.let { runCatching { ServerCodec.fromJson(it) }.getOrNull() }
        options = TunnelOptions.fromJson(intent?.getStringExtra(EXTRA_OPTIONS))
        if (server == null) {
            StateBridge.sendFailed(this, "سروری انتخاب نشده است."); teardown(); stopSelf(); return
        }
        serverName = server.displayLabel
        activeServer = server
        val mtu = if (options.mtu == 0) autoMtu() else options.mtu.coerceIn(1280, 1500)
        notifyStatus()

        connectJob = scope.launch {
            try {
                XrayCore.initEnv(this@ManfazVpnService)
                validateDns(options.remoteDns, options.dnsBootstrap)
                val config = XrayConfig.build(server, options)

                val pfd = establishTun(mtu)
                tun = pfd
                activeMtu = mtu

                LogBuffer.log(
                    this@ManfazVpnService,
                    "connect",
                    "starting ${server.displayLabel} (${server.transportLabel})",
                )
                // 1) Start Xray core with the SOCKS inbound (tunFd=0 — hev drives the TUN)
                XrayCore.start(config, 0) { code, msg ->
                    Log.i(TAG, "core status $code: $msg")
                    if (msg.isNotBlank()) LogBuffer.log(this@ManfazVpnService, "core", msg)
                }
                // 2) Start tun2socks: pump the TUN into Xray's SOCKS inbound
                HevTunnel.start(
                    this@ManfazVpnService, pfd.fd, XrayConfig.SOCKS_PORT, mtu,
                    tunnelIpv6 = options.ipv6Mode == Ipv6Mode.TUNNEL,
                )

                connectedSince = SystemClock.elapsedRealtime()
                lastHeartbeatAt = connectedSince
                lastExitIp = "متصل"
                holdReason = ""
                notifState = NOTIF_CONNECTED
                notifyStatus()
                StateBridge.sendConnected(
                    this@ManfazVpnService, server, lastExitIp, server.pingMs ?: 0, connectedSince,
                )
                ConnectionSnapshotStore.writeConnected(
                    this@ManfazVpnService, server, lastExitIp, server.pingMs ?: 0, connectedSince,
                )
                com.manfaz.vpn.widget.ManfazWidget.updateAll(this@ManfazVpnService, true, server)
                startStatsPolling()
                startHealthMonitoring()
                // Resolve the real exit IP + country through the proxy.
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
                LogBuffer.log(this@ManfazVpnService, "error", e.message ?: e.toString())
                val message = friendlyError(e)
                if (options.killSwitch) {
                    engageKillSwitch(message, mtu)
                } else {
                    StateBridge.sendFailed(this@ManfazVpnService, message)
                    teardown(); stopSelf()
                }
            }
        }
    }

    /**
     * Builds the TUN interface. Everything the tunnel needs to be leak-free lives here, so a
     * kill-switch hold can reuse exactly the same routing without a working proxy behind it.
     */
    private fun establishTun(mtu: Int): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Manfaz VPN")
            .setMtu(mtu)
            .addAddress(HevTunnel.TUN_IPV4, 30)
            .addRoute("0.0.0.0", 0)
        // A custom resolver is opt-in. Without it Android inherits the physical network's DNS
        // instead of silently forcing one.
        if (options.dnsLeakProtection) builder.addDnsServer(options.dnsBootstrap)
        when (options.ipv6Mode) {
            Ipv6Mode.BLOCK -> Unit // No IPv6 family configured: Android blocks it cleanly.
            Ipv6Mode.TUNNEL -> {
                builder.addAddress(HevTunnel.TUN_IPV6, 64)
                builder.addRoute("::", 0)
            }
            Ipv6Mode.DIRECT -> builder.allowFamily(OsConstants.AF_INET6)
        }
        configurePerApp(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
            ?: throw IllegalStateException("VPN permission is required.")
    }

    /**
     * Kill switch: keep (or install) a tunnel that swallows every packet, so a failed or
     * dropped proxy can never fall back to the unprotected network. The ongoing notification
     * then carries an explicit "release the internet" action, because a phone that is
     * silently offline with no way back is worse than a leak the user chose to accept.
     */
    private fun engageKillSwitch(reason: String, mtu: Int) {
        HevTunnel.stop()
        XrayCore.stop()
        statsJob?.cancel(); statsJob = null
        healthJob?.cancel(); healthJob = null
        if (tun == null) {
            // The failure happened before the interface existed (bad config, DNS, consent).
            // Install the blocking interface now so the switch means what it promises.
            tun = runCatching { establishTun(if (mtu in 1280..1500) mtu else HevTunnel.DEFAULT_MTU) }
                .onFailure { Log.w(TAG, "kill switch could not install a blocking tunnel", it) }
                .getOrNull()
        }
        if (tun == null) {
            // Nothing can be blocked without an interface; fail honestly instead of pretending.
            StateBridge.sendFailed(this, reason)
            teardown(); stopSelf()
            return
        }
        connectedSince = 0L
        holdReason = reason
        notifState = NOTIF_HOLDING
        Log.w(TAG, "kill switch active — holding TUN to block traffic")
        LogBuffer.log(this, "kill-switch", "traffic blocked: $reason")
        notifyStatus()
        ConnectionSnapshotStore.writeBlocked(this, activeServer, reason)
        StateBridge.sendBlocked(this, reason, activeServer)
        com.manfaz.vpn.widget.ManfazWidget.updateAll(this, false, activeServer)
    }

    /**
     * Rebuilds only the proxy side after a physical-network handover. Keeping the TUN descriptor
     * open avoids a visible Android VPN disconnect while stale Xray sockets and DNS state are
     * replaced for the new Wi-Fi/mobile network.
     */
    private fun reloadForUpstreamHandover() {
        val pfd = tun ?: return
        val server = activeServer ?: return
        if (!XrayCore.isRunning || reloadingUpstream) return
        reloadingUpstream = true
        scope.launch {
            runCatching {
                val config = XrayConfig.build(server, options)

                statsJob?.cancel(); statsJob = null
                HevTunnel.stop()
                XrayCore.stop()
                XrayCore.start(config, 0) { code, msg ->
                    Log.i(TAG, "core handover status $code: $msg")
                }
                HevTunnel.start(
                    this@ManfazVpnService, pfd.fd, XrayConfig.SOCKS_PORT, activeMtu,
                    tunnelIpv6 = options.ipv6Mode == Ipv6Mode.TUNNEL,
                )
                startStatsPolling()
                sendAuthoritativeState()
                LogBuffer.log(
                    this@ManfazVpnService, "network", "proxy reloaded after upstream handover",
                )
            }.onFailure {
                Log.e(TAG, "upstream handover reload failed", it)
                val message = "بازیابی اتصال ناموفق بود."
                if (options.killSwitch) {
                    // Never drop back to the raw network behind the user's back.
                    engageKillSwitch(message, activeMtu)
                } else {
                    // The TUN is intentionally kept alive during reload. If recovery fails, tear
                    // it down explicitly so UI, notification and Android's VPN icon agree.
                    teardown(notifyStopped = false)
                    StateBridge.sendFailed(
                        this@ManfazVpnService,
                        "$message سرور جایگزین بررسی می‌شود.",
                    )
                    stopSelf()
                }
            }
            reloadingUpstream = false
        }
    }

    /** Per-app split tunneling. The app itself is always excluded to avoid a routing loop. */
    private fun configurePerApp(builder: Builder) {
        val apps = options.perAppList
        if (!options.perAppEnabled || apps.isEmpty()) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }
        val bypassMode = options.perAppBypass
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
        // Once a VPN is active, activeNetwork may be the VPN itself. Prefer a validated,
        // non-VPN physical network so reconnects do not accidentally switch cellular MTU
        // back to the Wi-Fi default.
        val physical = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let { caps ->
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
        val caps = connectivity.getNetworkCapabilities(physical ?: connectivity.activeNetwork)
        return if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) 1400 else 1500
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
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
                // Live speed in the ongoing notification.
                if (options.showSpeedInNotification && notifState == NOTIF_CONNECTED) {
                    notifyStatus("↓ ${speed(down)}   ↑ ${speed(up)}")
                }
            }
        }
    }

    /**
     * Detects silent half-open tunnels (VPN icon is present but traffic no longer passes).
     * Three consecutive end-to-end failures are required and automatic recovery is rate
     * limited, so a blocked connectivity-check provider cannot create a reconnect loop.
     */
    private fun startHealthMonitoring() {
        healthJob?.cancel()
        consecutiveHealthFailures = 0
        healthJob = scope.launch {
            delay(HEALTH_INITIAL_DELAY_MS)
            while (isActive) {
                if (!reloadingUpstream && tun != null && XrayCore.isRunning) {
                    val healthy = ProxyHealthProbe.isHealthy(XrayConfig.SOCKS_PORT)
                    if (healthy) {
                        consecutiveHealthFailures = 0
                    } else {
                        consecutiveHealthFailures++
                        val now = SystemClock.elapsedRealtime()
                        if (consecutiveHealthFailures >= HEALTH_FAILURE_THRESHOLD &&
                            now - lastHealthRecoveryAt >= HEALTH_RECOVERY_COOLDOWN_MS
                        ) {
                            consecutiveHealthFailures = 0
                            lastHealthRecoveryAt = now
                            LogBuffer.log(
                                this@ManfazVpnService,
                                "health",
                                "end-to-end probe failed repeatedly; recovering proxy",
                            )
                            reloadForUpstreamHandover()
                        }
                    }
                }
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun sendAuthoritativeState() {
        val server = activeServer
        when {
            tun != null && XrayCore.isRunning && server != null && connectedSince > 0L -> {
                ConnectionSnapshotStore.writeConnected(this, server, lastExitIp, server.pingMs ?: 0, connectedSince)
                StateBridge.sendConnected(this, server, lastExitIp, server.pingMs ?: 0, connectedSince)
            }
            notifState == NOTIF_HOLDING && tun != null -> {
                ConnectionSnapshotStore.writeBlocked(this, server, holdReason)
                StateBridge.sendBlocked(this, holdReason, server)
            }
            else -> {
                ConnectionSnapshotStore.writeStopped(this)
                StateBridge.sendStopped(this)
            }
        }
    }

    private fun speed(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        val u = listOf("KB/s", "MB/s", "GB/s"); var v = bps.toDouble() / 1024; var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i])
    }

    private fun teardown(notifyStopped: Boolean = true) {
        val lastServer = activeServer
        statsJob?.cancel(); statsJob = null
        healthJob?.cancel(); healthJob = null
        HevTunnel.stop()
        XrayCore.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        activeServer = null
        connectedSince = 0L
        lastHeartbeatAt = 0L
        lastStartIntent = null
        reloadingUpstream = false
        consecutiveHealthFailures = 0
        activeMtu = HevTunnel.DEFAULT_MTU
        notifState = NOTIF_CONNECTING
        holdReason = ""
        stopForegroundCompat()
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        com.manfaz.vpn.widget.ManfazWidget.updateAll(this, false, lastServer)
        ConnectionSnapshotStore.writeStopped(this)
        if (notifyStopped) StateBridge.sendStopped(this)
    }

    private fun friendlyError(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.contains("permission", true) -> "اجازهٔ VPN لازم است."
            m.contains("پشتیبانی") || m.contains("معتبر") -> m
            else -> "اتصال برقرار نشد: ${m.take(80)}"
        }
    }

    private fun notifyStatus(speedLine: String? = null) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(speedLine))
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, ManfazVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(speedLine: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val holding = notifState == NOTIF_HOLDING
        // The notification must tell the truth about the tunnel state: it is posted before
        // the core connects, and during a kill-switch hold the tunnel is deliberately down.
        val (title, base) = when (notifState) {
            NOTIF_HOLDING -> "اینترنت مسدود شد — کلید قطع اضطراری" to
                (holdReason.ifBlank { "برای جلوگیری از نشت، همهٔ ترافیک متوقف شده است" })
            NOTIF_CONNECTED -> "متصل به منفذ" to
                (if (options.showServerInNotification) serverName else "اتصال محافظت‌شده")
            else -> "در حال اتصال به منفذ…" to "در حال برقراری اتصال امن…"
        }
        val text = if (speedLine != null) "$base\n$speedLine" else base
        val builder = NotificationCompat.Builder(
            this,
            if (holding) CHANNEL_ID_ALERT else CHANNEL_ID,
        )
            .setContentTitle(title)
            .setContentText(if (speedLine != null) speedLine else base)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)

        if (holding) {
            // Order matters: the escape hatch is the first, most reachable action.
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.addAction(0, "آزاد کردن اینترنت", servicePendingIntent(ACTION_RELEASE, 2))
            builder.addAction(0, "تلاش دوباره", servicePendingIntent(ACTION_RETRY, 3))
        } else {
            builder.addAction(0, "قطع اتصال", servicePendingIntent(ACTION_STOP, 1))
        }
        return builder.build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "وضعیت اتصال", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        // A blocked device must be able to break through Do Not Disturb-style quieting;
        // the user has no other way to learn why the internet stopped working.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALERT,
                "هشدار کلید قطع اضطراری",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(true)
                enableVibration(true)
                description = "زمانی که اینترنت برای جلوگیری از نشت مسدود می‌شود"
            },
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onRevoke() { teardown(); stopSelf() }

    override fun onDestroy() {
        connectJob?.cancel(); connectJob = null
        upstreamMonitor?.unregister(); upstreamMonitor = null
        runCatching { unregisterReceiver(stateQueryReceiver) }
        teardown()
        serviceJob.cancel()
        super.onDestroy()
    }
}
