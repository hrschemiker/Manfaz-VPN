package com.manfaz.vpn.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tracks the physical network beneath the VPN and debounces Wi-Fi/mobile handovers. */
class UpstreamNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
    private val onNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
) {
    private var upstream: Network? = null
    private val candidates = LinkedHashMap<Network, NetworkCapabilities>()
    private var handoverJob: Job? = null
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Availability alone does not mean the network has working internet. Selection is
            // deferred until capabilities report VALIDATED, avoiding reloads into captive or
            // half-configured Wi-Fi during a mobile/Wi-Fi handover.
            connectivity.getNetworkCapabilities(network)?.let { caps ->
                candidates[network] = caps
                chooseValidatedUpstream()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            candidates[network] = caps
            chooseValidatedUpstream()
        }

        override fun onLost(network: Network) {
            candidates.remove(network)
            if (network == upstream) {
                upstream = null
                chooseValidatedUpstream()
                if (upstream != null) scheduleHandover()
            }
        }
    }

    fun register() {
        if (registered) return
        runCatching { connectivity.requestNetwork(request, callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "unable to monitor upstream network", it) }
    }

    fun unregister() {
        handoverJob?.cancel(); handoverJob = null
        candidates.clear()
        upstream = null
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun chooseValidatedUpstream() {
        val selected = candidates.entries.firstOrNull { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }?.key

        val previous = upstream
        if (selected == previous) {
            selected?.let { onNetworksChanged(arrayOf(it)) }
            return
        }
        upstream = selected
        onNetworksChanged(selected?.let { arrayOf(it) })
        // Losing the old network before the replacement validates is common. Reload only when
        // a usable replacement exists; otherwise the established TUN remains intact and waits.
        if (previous != null && selected != null) scheduleHandover()
    }

    private fun scheduleHandover() {
        handoverJob?.cancel()
        handoverJob = scope.launch {
            delay(HANDOVER_DEBOUNCE_MS)
            onHandover()
        }
    }

    private companion object {
        const val TAG = "UpstreamNetworkMonitor"
        const val HANDOVER_DEBOUNCE_MS = 1_000L
    }
}
