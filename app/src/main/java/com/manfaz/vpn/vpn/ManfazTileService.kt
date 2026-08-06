package com.manfaz.vpn.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.manfaz.vpn.core.ServerCodec
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.ui.MainActivity

/**
 * C#11: Quick Settings tile — one-tap connect/disconnect to the last-used server.
 * If VPN permission hasn't been granted yet, it opens the app so the user can grant it.
 */
class ManfazTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val connected = ConnectionSnapshotStore.read(this)?.connected == true
        qsTile?.apply {
            state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (connected) "منفذ: متصل" else "منفذ: قطع"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val connected = ConnectionSnapshotStore.read(this)?.connected == true
        if (connected) {
            startService(
                Intent(this, ManfazVpnService::class.java).setAction(ManfazVpnService.ACTION_STOP),
            )
            onStartListening()
            return
        }
        // Need VPN consent granted already; otherwise open the app to grant it.
        if (VpnService.prepare(this) != null) {
            openApp(); return
        }
        ServerRepository.init(applicationContext)
        val prefs = Prefs(applicationContext)
        val server = ServerRepository.servers.value.firstOrNull { it.id == prefs.lastServerId }
            ?: ServerRepository.servers.value.minByOrNull { it.pingMs ?: Int.MAX_VALUE }
        if (server == null) { openApp(); return }
        VpnController.connect(applicationContext, server)
        onStartListening()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
