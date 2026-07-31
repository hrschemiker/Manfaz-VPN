package com.manfaz.vpn.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.manfaz.vpn.core.ServerCodec
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
import kotlin.random.Random

/**
 * Coordinates connection state between the UI and [ManfazVpnService].
 *
 * Real servers run through the Xray core inside the VPN service (which owns the TUN).
 * The built-in "example.com" sample servers use a simulated path so the app can be
 * demoed without a real subscription.
 */
object VpnController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockWorker: Job? = null
    private var watchdog: Job? = null
    private var appContext: Context? = null

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Server the service should connect (read by ManfazVpnService on start). */
    @Volatile var pendingServer: ServerConfig? = null
        private set

    // Cloudflare clean-IP fallback bookkeeping.
    @Volatile private var originalServer: ServerConfig? = null
    @Volatile private var usedCleanIp = false
    @Volatile private var triedOriginal = false

    private fun isSample(s: ServerConfig) = s.address.endsWith("example.com")

    fun connect(context: Context, server: ServerConfig) {
        mockWorker?.cancel()
        // Switching while already up: tear the current tunnel down first for a clean restart.
        if (_state.value.status != ConnStatus.DISCONNECTED && _state.value.status != ConnStatus.FAILED) {
            stopService(context.applicationContext)
        }
        pendingServer = server
        originalServer = server
        usedCleanIp = false
        triedOriginal = false

        // Guard: protocols the Xray core cannot handle
        if (!isSample(server) && !XrayConfig.isSupportedByXray(server.protocol)) {
            _state.value = ConnectionState(
                status = ConnStatus.FAILED, server = server,
                error = "پروتکل ${server.protocol.label} در این نسخه پشتیبانی نمی‌شود.",
            )
            return
        }

        appContext = context.applicationContext

        if (isSample(server)) {
            _state.value = ConnectionState(status = ConnStatus.CONNECTING, server = server)
            startMock(server)
            return
        }

        val prefs = Prefs(context.applicationContext)
        // Auto Cloudflare clean-IP scan for eligible (CDN) configs.
        if (prefs.cloudflareScan && CloudflareScanner.isCdnEligible(server)) {
            _state.value = ConnectionState(status = ConnStatus.SCANNING, server = server)
            scope.launch {
                val verified = runCatching { CloudflareScanner.isVerifiedCloudflare(server) }.getOrDefault(false)
                val ip = if (verified) runCatching { CloudflareScanner.findBest() }.getOrNull() else null
                val target = if (ip != null) {
                    usedCleanIp = true
                    CloudflareScanner.withCleanIp(server, ip)
                } else server
                _state.update { it.copy(status = ConnStatus.CONNECTING) }
                startRealService(prefs, target)
                startWatchdog()
            }
        } else {
            _state.value = ConnectionState(status = ConnStatus.CONNECTING, server = server)
            startRealService(prefs, server)
            startWatchdog()
        }
    }

    private fun startRealService(prefs: Prefs, target: ServerConfig) {
        val ctx = appContext ?: return
        pendingServer = target
        val intent = Intent(ctx, ManfazVpnService::class.java)
            .setAction(ManfazVpnService.ACTION_START)
            .putExtra(ManfazVpnService.EXTRA_SERVER, ServerCodec.toJson(target))
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
        startForeground(ctx, intent)
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(15_000)
            if (_state.value.status == ConnStatus.CONNECTING) {
                onCoreFailed("هسته پاسخ نداد. لطفاً دوباره تلاش کنید یا کانفیگ دیگری را امتحان کنید.")
                appContext?.let { stopService(it) }
            }
        }
    }

    fun disconnect(context: Context) {
        mockWorker?.cancel(); mockWorker = null
        watchdog?.cancel(); watchdog = null
        pendingServer = null
        stopService(context.applicationContext)
        _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
    }

    fun toggle(context: Context, server: ServerConfig?) {
        when (_state.value.status) {
            ConnStatus.CONNECTED, ConnStatus.CONNECTING, ConnStatus.SCANNING -> disconnect(context)
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

    fun onCoreFailed(message: String) {
        watchdog?.cancel(); watchdog = null
        // If a clean-IP attempt failed, silently fall back to the original config once.
        val ctx = appContext
        val orig = originalServer
        if (usedCleanIp && !triedOriginal && orig != null && ctx != null) {
            triedOriginal = true
            usedCleanIp = false
            stopService(ctx)
            _state.value = ConnectionState(status = ConnStatus.CONNECTING, server = orig)
            startRealService(Prefs(ctx), orig)
            startWatchdog()
            return
        }
        _state.update { it.copy(status = ConnStatus.FAILED, error = message) }
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

    fun onServiceStopped() {
        if (_state.value.status != ConnStatus.FAILED) {
            _state.value = ConnectionState(status = ConnStatus.DISCONNECTED)
        }
    }

    // ---- Simulated path for sample servers ----
    private fun startMock(server: ServerConfig) {
        mockWorker = scope.launch {
            delay(1200)
            _state.update {
                it.copy(status = ConnStatus.CONNECTED, ip = fakeIp(),
                    pingMs = server.pingMs ?: Random.nextInt(40, 160),
                    connectedSinceMs = SystemClock.elapsedRealtime())
            }
            var down = 0L; var up = 0L
            while (true) {
                delay(1000)
                val d = Random.nextLong(200_000, 3_500_000)
                val u = Random.nextLong(60_000, 900_000)
                down += d; up += u
                _state.update {
                    if (it.status != ConnStatus.CONNECTED) return@launch
                    it.copy(downloadSpeedBps = d, uploadSpeedBps = u,
                        totalDownloaded = down, totalUploaded = up,
                        pingMs = (it.pingMs + Random.nextInt(-6, 7)).coerceIn(30, 260))
                }
            }
        }
    }

    private fun startForeground(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopService(context: Context) {
        context.startService(
            Intent(context, ManfazVpnService::class.java).setAction(ManfazVpnService.ACTION_STOP)
        )
    }

    private fun fakeIp() = "${Random.nextInt(11, 223)}.${Random.nextInt(0, 255)}." +
        "${Random.nextInt(0, 255)}.${Random.nextInt(1, 254)}"
}
