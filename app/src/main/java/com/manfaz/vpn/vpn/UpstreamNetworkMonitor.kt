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
    private var handoverJob: Job? = null
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            upstream = network
            onNetworksChanged(arrayOf(network))
            if (previous != null && previous != network) scheduleHandover()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == upstream) onNetworksChanged(arrayOf(network))
        }

        override fun onLost(network: Network) {
            if (network == upstream) {
                upstream = null
                onNetworksChanged(null)
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
        upstream = null
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
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
