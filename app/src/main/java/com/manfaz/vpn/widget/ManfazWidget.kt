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
import com.manfaz.vpn.data.model.ServerConfig
import com.manfaz.vpn.ui.MainActivity
import com.manfaz.vpn.vpn.VpnController

/** Slim branded home-screen widget for reconnecting to the last selected server. */
class ManfazWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ServerRepository.init(context)
        val saved = widgetPrefs(context)
        val server = lastServer(context)
        val connected = saved.getBoolean(KEY_CONNECTED, false)
        ids.forEach { manager.updateAppWidget(it, views(context, connected, server)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_DISCONNECT) {
            context.startService(
                Intent(context, com.manfaz.vpn.vpn.ManfazVpnService::class.java)
                    .setAction(com.manfaz.vpn.vpn.ManfazVpnService.ACTION_STOP),
            )
            updateAll(context, false, lastServer(context))
            return
        }
        if (intent.action != ACTION_CONNECT_LAST) return

        if (VpnService.prepare(context) != null) {
            openForConsent(context)
            return
        }

        ServerRepository.init(context)
        val target = lastServer(context)
        if (target == null) {
            openForConsent(context)
            return
        }
        updateAll(context, false, target, true)
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
        private const val ACTION_DISCONNECT = "com.manfaz.vpn.widget.DISCONNECT"
        private const val WIDGET_PREFS = "manfaz_widget_state"
        private const val KEY_CONNECTED = "connected"

        fun updateAll(
            context: Context,
            connected: Boolean,
            server: ServerConfig?,
            connecting: Boolean = false,
        ) {
            widgetPrefs(context).edit().putBoolean(KEY_CONNECTED, connected).commit()
            val effectiveServer = server ?: lastServer(context)
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ManfazWidget::class.java)
            manager.updateAppWidget(component, views(context, connected, effectiveServer, connecting))
        }

        private fun views(
            context: Context,
            connected: Boolean,
            server: ServerConfig?,
            connecting: Boolean = false,
        ): RemoteViews {
            val remote = RemoteViews(context.packageName, R.layout.widget_manfaz)
            remote.setImageViewResource(R.id.widget_country_art, countryArt(server))
            remote.setTextViewText(
                R.id.widget_status,
                when {
                    connecting -> "در حال اتصال…"
                    connected && server != null -> "متصل به ${server.displayLabel}"
                    server != null -> server.displayLabel
                    else -> "آخرین سرور موجود نیست"
                },
            )
            remote.setTextViewText(R.id.widget_action, if (connected) "خاموش" else "روشن")
            val toggle = PendingIntent.getBroadcast(
                context,
                if (connected) 4103 else 4102,
                Intent(context, ManfazWidget::class.java)
                    .setAction(if (connected) ACTION_DISCONNECT else ACTION_CONNECT_LAST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            remote.setOnClickPendingIntent(R.id.widget_root, toggle)
            remote.setOnClickPendingIntent(R.id.widget_action, toggle)
            return remote
        }

        private fun widgetPrefs(context: Context) =
            context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)

        private fun lastServer(context: Context): ServerConfig? {
            ServerRepository.init(context)
            val id = Prefs(context).lastServerId
            return ServerRepository.servers.value.firstOrNull { it.id == id }
                ?: ServerRepository.servers.value.firstOrNull()
        }

        private fun countryArt(server: ServerConfig?): Int =
            when (server?.isoCode.orEmpty()) {
                "DE" -> R.drawable.country_de
                "FR" -> R.drawable.country_fr
                "NL" -> R.drawable.country_nl
                "FI" -> R.drawable.country_fi
                "US" -> R.drawable.country_us
                "AE" -> R.drawable.country_ae
                "PL" -> R.drawable.country_pl
                "RU" -> R.drawable.country_ru
                "AZ" -> R.drawable.country_az
                "TR" -> R.drawable.country_tr
                else -> R.drawable.country_generic_network
            }
    }
}
