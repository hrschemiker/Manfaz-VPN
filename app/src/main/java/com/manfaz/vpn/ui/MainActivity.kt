package com.manfaz.vpn.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.manfaz.vpn.ui.theme.ManfazTheme
import com.manfaz.vpn.ui.theme.ThemeState
import com.manfaz.vpn.vpn.ConnStatus

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private var pendingSpecific = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { applyNetworkRules(caps) }
        }
    }

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pendingSpecific) { pendingSpecific = false; vm.connectSelected() } else vm.toggleConnection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeState.load(this)
        handleImportIntent(intent)
        handleShortcutIntent(intent)
        maybeAutoConnect()

        setContent {
            val appearance by ThemeState.appearance.collectAsState()
            ManfazTheme(themeMode = appearance.mode, dynamicColor = appearance.dynamicColor) {
                // Force whole app Right-To-Left (Farsi-first)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AppRoot(
                            vm = vm,
                            onToggleConnection = ::onToggleConnection,
                            onConnectServer = ::onConnectServer,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Feature #4: offer to import a config found in the clipboard.
        vm.checkClipboard(this)
    }

    override fun onStart() {
        super.onStart()
        if (!networkCallbackRegistered) runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onStop() {
        if (networkCallbackRegistered) runCatching {
            connectivity.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    private fun applyNetworkRules(caps: NetworkCapabilities) {
        val prefs = com.manfaz.vpn.data.Prefs(this)
        val status = vm.connection.value.status
        val active = status == ConnStatus.CONNECTED || status == ConnStatus.CONNECTING ||
            status == ConnStatus.SCANNING
        val action = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> prefs.wifiAction
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> prefs.mobileAction
            else -> com.manfaz.vpn.data.NetworkAction.NONE
        }
        when (action) {
            com.manfaz.vpn.data.NetworkAction.CONNECT ->
                if (!active && vm.selected.value != null) requestConnect()
            com.manfaz.vpn.data.NetworkAction.FASTEST ->
                if (!active) { vm.pickFastest(); if (vm.selected.value != null) requestConnect() }
            com.manfaz.vpn.data.NetworkAction.DISCONNECT ->
                if (active) vm.disconnectNow()
            com.manfaz.vpn.data.NetworkAction.NONE -> Unit
        }
    }

    /** Feature #5: launcher long-press shortcuts. */
    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_SHORTCUT)) {
            "connect", "fastest" -> { vm.pickFastest(); if (!isConnectedOrConnecting()) requestConnect() }
            "disconnect" -> vm.disconnectNow()
        }
    }

    /** C#12: auto-connect to the last server when the app opens, if enabled and not already up. */
    private fun maybeAutoConnect() {
        val prefs = com.manfaz.vpn.data.Prefs(this)
        if (!prefs.autoConnectOnOpen) return
        val status = vm.connection.value.status
        if (status == ConnStatus.CONNECTED || status == ConnStatus.CONNECTING) return
        if (vm.selected.value == null) return
        onToggleConnection()
    }

    private fun handleImportIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.isNotBlank()) vm.importText(data)
    }

    private fun isConnectedOrConnecting(): Boolean {
        val s = vm.connection.value.status
        return s == ConnStatus.CONNECTED || s == ConnStatus.CONNECTING || s == ConnStatus.SCANNING
    }

    /** Requests VPN consent the first time, then connects; disconnects if already up. */
    private fun onToggleConnection() {
        if (isConnectedOrConnecting()) { vm.toggleConnection(); return }
        requestConnect()
    }

    /** Consent-aware connect of the currently selected server (used by all connect entry points). */
    private fun requestConnect() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else vm.toggleConnection()
    }

    /** Row taps: always connect the tapped (selected) server, switching if already connected. */
    private fun onConnectServer() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) { pendingSpecific = true; vpnConsent.launch(prepare) } else vm.connectSelected()
    }

    companion object {
        const val EXTRA_SHORTCUT = "manfaz_shortcut"
    }
}
