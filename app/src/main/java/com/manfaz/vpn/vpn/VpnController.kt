package com.manfaz.vpn.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.core.TunnelOptions
import com.manfaz.vpn.core.XrayConfig
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.net.CloudflareScanner
import com.manfaz.vpn.data.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates connection state between the UI and [ManfazVpnService].
 *
 * The service owns the TUN and the Xray core; this object owns what the user asked for and
 * mirrors the core's authoritative reports back into a single [ConnectionState] flow.
 */
object VpnController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    private var scanJob: Job? = null
    private var appContext: Context? = null

    private const val CONNECT_TIMEOUT_MS = 20_000L

    /**
     * True while a connect is still wanted. Cleared by [disconnect] and terminal failures so
     * a Cloudflare scan that finishes late can never resurrect the VPN behind the user's back.
     */
    @Volatile private var connectWanted = false

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Server the service should connect (read by ManfazVpnService on start). */
    @Volatile var pendingServer: ServerConfig? = null
        private set

    // Cloudflare clean-IP fallback bookkeeping.
    @Volatile private var originalServer: ServerConfig? = null
    @Volatile private var usedCleanIp = false
    @Volatile private var triedOriginal = false

    fun connect(context: Context, server: ServerConfig) {
        scanJob?.cancel(); scanJob = null
        watchdog?.cancel(); watchdog = null
        connectWanted = true
        // Monotonic uptime marker: the ":core" service process survives UI-process restarts,
        // so a plain counter would reset and let a stale START through. Uptime never resets.
        val epoch = SystemClock.elapsedRealtimeNanos()
        // The service performs an ordered in-process handover. Sending STOP immediately
        // before START races stopSelf() against the next start request and creates outages.
        pendingServer = server
        originalServer = server
        usedCleanIp = false
        triedOriginal = false

        // Guard: protocols the Xray core cannot handle.
        if (!XrayConfig.isSupportedByXray(server.protocol)) {
            connectWanted = false
            _state.value = ConnectionState(
                status = ConnStatus.FAILED, server = server,
                error = "پروتکل ${server.protocol.label} در این نسخه پشتیبانی نمی‌شود.",
            )
            return
        }

        appContext = context.applicationContext

        val prefs = Prefs(context.applicationContext)
        val options = TunnelOptions.from(prefs)
        // Auto Cloudflare clean-IP scan for eligible (CDN) configs.
        if (prefs.cloudflareScan && CloudflareScanner.isCdnEligible(server)) {
            _state.value = ConnectionState(status = ConnStatus.SCANNING, server = server)
            scanJob = scope.launch {
                val verified = runCatching { CloudflareScanner.isVerifiedCloudflare(server) }.getOrDefault(false)
                val ip = if (verified) runCatching { CloudflareScanner.findBest() }.getOrNull() else null
                // A disconnect may have landed while the scan was running — never
                // start the VPN for a connection the user already cancelled.
                if (!connectWanted) return@launch
                val target = if (ip != null) {
                    usedCleanIp = true
                    CloudflareScanner.withCleanIp(server, ip)
                } else server
                _state.update { it.copy(status = ConnStatus.CONNECTING) }
                if (!connectWanted) return@launch
                startRealService(options, target, epoch)
                if (!connectWanted) {
                    stopService(appContext ?: return@launch)
                    return@launch
                }
                startWatchdog()
            }
        } else {
            _state.value = ConnectionState(status = ConnStatus.CONNECTING, server = server)
            startRealService(options, server, epoch)
            startWatchdog()
        }
    }

    private fun startRealService(options: TunnelOptions, target: ServerConfig, epoch: Long) {
        val ctx = appContext ?: return
        pendingServer = target
        val intent = Intent(ctx, ManfazVpnService::class.java)
            .setAction(ManfazVpnService.ACTION_START)
            .putExtra(ManfazVpnService.EXTRA_EPOCH, epoch)
            .putExtra(ManfazVpnService.EXTRA_SERVER, ServerCodec.toJson(target))
            .putExtra(ManfazVpnService.EXTRA_OPTIONS, options.toJson())
        startForeground(ctx, intent)
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value.status == ConnStatus.CONNECTING) {
                onCoreFailed("هسته پاسخ نداد. لطفاً دوباره تلاش کنید یا کانفیگ دیگری را امتحان کنید.")
                appContext?.let { stopService(it) }
            }
        }
    }

    fun disconnect(context: Context) {
        // Cancel any in-flight Cloudflare scan so it cannot start the VPN after we stop.
        connectWanted = false
        scanJob?.cancel(); scanJob = null
        watchdog?.cancel(); watchdog = null
        pendingServer = null
        stopService(context.applicationContext)
        _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
    }

    /**
     * Kill-switch escape hatch: tear the blocking tunnel down so the device goes back online.
     * Reachable from the ongoing notification and from the home screen.
     */
    fun releaseKillSwitch(context: Context) {
        connectWanted = false
        scanJob?.cancel(); scanJob = null
        watchdog?.cancel(); watchdog = null
        pendingServer = null
        val ctx = context.applicationContext
        ctx.startService(
            Intent(ctx, ManfazVpnService::class.java)
                .setAction(ManfazVpnService.ACTION_RELEASE)
                .putExtra(ManfazVpnService.EXTRA_EPOCH, SystemClock.elapsedRealtimeNanos()),
        )
        _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
    }

    fun toggle(context: Context, server: ServerConfig?) {
        when (_state.value.status) {
            ConnStatus.CONNECTED, ConnStatus.CONNECTING, ConnStatus.SCANNING -> disconnect(context)
            ConnStatus.BLOCKED -> releaseKillSwitch(context)
            else -> server?.let { connect(context, it) }
        }
    }

    // ---- Called (in UI process) by StateBridge when the core broadcasts ----
    fun onCoreConnected(ip: String, ping: Int, server: ServerConfig? = null, since: Long = 0L) {
        watchdog?.cancel(); watchdog = null
        _state.update {
            it.copy(
                status = ConnStatus.CONNECTED,
                server = server ?: it.server,
                ip = ip,
                pingMs = ping,
                connectedSinceMs = since.takeIf { marker -> marker > 0L }
                    ?: it.connectedSinceMs.takeIf { marker -> marker > 0L }
                    ?: SystemClock.elapsedRealtime(),
                error = null,
            )
        }
    }

    /** Restore authoritative core state after the UI process/activity was recreated. */
    fun restoreFromSnapshot(context: Context) {
        val snapshot = ConnectionSnapshotStore.read(context) ?: return
        // A connected snapshot remains authoritative until the core explicitly writes
        // STOPPED. Doze may delay heartbeats, but it does not mean the VPN disconnected.
        when {
            snapshot.connected && snapshot.server != null -> onCoreConnected(
                ip = snapshot.ip,
                ping = snapshot.ping,
                server = snapshot.server,
                since = snapshot.connectedSince,
            )
            snapshot.blocked -> {
                onKillSwitchEngaged(snapshot.reason, snapshot.server)
                verifyBlockedHold(context.applicationContext, snapshot.updatedAt)
            }
            snapshot.isFresh && _state.value.status == ConnStatus.CONNECTED -> onServiceStopped()
        }
    }

    /**
     * A blocked snapshot outlives the process that wrote it. If the core was killed without a
     * clean teardown, the blocking tunnel is gone and the user is online again — showing them
     * a permanent "internet blocked" screen would be a lie. The core answers the state query
     * by rewriting the snapshot, so an unchanged timestamp means nothing is holding anything.
     */
    private fun verifyBlockedHold(context: Context, seenAt: Long) {
        scope.launch {
            StateBridge.requestState(context)
            delay(2_000)
            if (_state.value.status != ConnStatus.BLOCKED) return@launch
            val current = ConnectionSnapshotStore.read(context)
            if (current == null || !current.blocked || current.updatedAt <= seenAt) {
                ConnectionSnapshotStore.writeStopped(context)
                _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
            }
        }
    }

    fun onCoreFailed(message: String) {
        watchdog?.cancel(); watchdog = null
        // If a clean-IP attempt failed, silently fall back to the original config once.
        val ctx = appContext
        val orig = originalServer
        if (connectWanted && usedCleanIp && !triedOriginal && orig != null && ctx != null) {
            triedOriginal = true
            usedCleanIp = false
            stopService(ctx)
            _state.value = ConnectionState(status = ConnStatus.CONNECTING, server = orig)
            startRealService(TunnelOptions.from(Prefs(ctx)), orig, SystemClock.elapsedRealtimeNanos())
            startWatchdog()
            return
        }
        connectWanted = false
        _state.update { it.copy(status = ConnStatus.FAILED, error = message) }
    }

    /** The core is holding a blocking tunnel because the kill switch is on. */
    fun onKillSwitchEngaged(reason: String, server: ServerConfig?) {
        watchdog?.cancel(); watchdog = null
        connectWanted = false
        _state.update {
            it.copy(
                status = ConnStatus.BLOCKED,
                server = server ?: it.server,
                ip = "—",
                connectedSinceMs = 0L,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                error = reason,
            )
        }
    }

    fun onIpInfo(ip: String, country: String) {
        _state.update { if (it.status == ConnStatus.CONNECTED) it.copy(ip = ip, exitCountry = country) else it }
    }

    fun onTraffic(uploadDelta: Long, downloadDelta: Long) {
        _state.update {
            if (it.status != ConnStatus.CONNECTED) return
            it.copy(
                downloadSpeedBps = downloadDelta,
                uploadSpeedBps = uploadDelta,
                totalDownloaded = it.totalDownloaded + downloadDelta,
                totalUploaded = it.totalUploaded + uploadDelta,
            )
        }
    }

    /**
     * The core tore the tunnel down. FAILED is preserved so the reason stays on screen; a
     * BLOCKED hold is not, because the blocking interface genuinely no longer exists.
     */
    fun onServiceStopped() {
        if (_state.value.status != ConnStatus.FAILED) {
            connectWanted = false
            _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
        }
    }

    private fun startForeground(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopService(context: Context) {
        // Carry the current uptime marker so the service can drop any stale START
        // (e.g. a scan-launched intent) that arrives after this STOP.
        context.startService(
            Intent(context, ManfazVpnService::class.java)
                .setAction(ManfazVpnService.ACTION_STOP)
                .putExtra(ManfazVpnService.EXTRA_EPOCH, SystemClock.elapsedRealtimeNanos()),
        )
    }
}
