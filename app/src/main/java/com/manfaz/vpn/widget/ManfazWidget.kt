package com.manfaz.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.manfaz.vpn.R
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.ui.MainActivity
import com.manfaz.vpn.vpn.VpnController

/** Slim branded home-screen widget for reconnecting to the last selected server. */
class ManfazWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, false, null)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CONNECT_LAST) return

        if (VpnService.prepare(context) != null) {
            openForConsent(context)
            return
        }

        ServerRepository.init(context)
        val prefs = Prefs(context)
        val target = ServerRepository.servers.value.firstOrNull { it.id == prefs.lastServerId }
            ?: ServerRepository.servers.value.firstOrNull()
        if (target == null) {
            openForConsent(context)
            return
        }
        updateAll(context, false, "در حال اتصال به ${target.displayLabel}")
        VpnController.connect(context, target)
    }

    private fun openForConsent(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHORTCUT, "last")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        private const val ACTION_CONNECT_LAST = "com.manfaz.vpn.widget.CONNECT_LAST"

        fun updateAll(context: Context, connected: Boolean, serverName: String?) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ManfazWidget::class.java)
            manager.updateAppWidget(component, views(context, connected, serverName))
        }

        private fun views(context: Context, connected: Boolean, serverName: String?): RemoteViews {
            val remote = RemoteViews(context.packageName, R.layout.widget_manfaz)
            remote.setTextViewText(
                R.id.widget_status,
                when {
                    connected && !serverName.isNullOrBlank() -> "متصل به $serverName"
                    !serverName.isNullOrBlank() -> serverName
                    else -> "اتصال سریع به آخرین سرور"
                },
            )
            remote.setTextViewText(R.id.widget_action, if (connected) "متصل" else "اتصال")
            val connect = PendingIntent.getBroadcast(
                context,
                4102,
                Intent(context, ManfazWidget::class.java).setAction(ACTION_CONNECT_LAST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            remote.setOnClickPendingIntent(R.id.widget_root, connect)
            remote.setOnClickPendingIntent(R.id.widget_action, connect)
            return remote
        }
    }
}
