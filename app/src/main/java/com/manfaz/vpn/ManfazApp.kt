package com.manfaz.vpn

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.data.SubscriptionRepository
import com.manfaz.vpn.util.CrashLogger
import com.manfaz.vpn.vpn.StateBridge
import kotlinx.coroutines.launch
import java.io.File

class ManfazApp : Application() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { StateBridge.apply(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        com.manfaz.vpn.util.LogBuffer.init(this)
        // Repositories and the state receiver live only in the main (UI) process;
        // the ":core" process only runs the VPN service.
        if (isMainProcess()) {
            ServerRepository.init(this)
            SubscriptionRepository.init(this)
            com.manfaz.vpn.data.FreeConfigRepository.init(this)
            ContextCompat.registerReceiver(
                this, stateReceiver, IntentFilter(StateBridge.ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // C#14: refresh subscriptions on startup if auto-update is on and they're due
            val prefs = com.manfaz.vpn.data.Prefs(this)
            com.manfaz.vpn.work.SubscriptionWorkScheduler.sync(this)
            if (prefs.subAutoUpdate) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        com.manfaz.vpn.data.SubscriptionRepository.autoUpdateIfDue(prefs.subUpdateHours)
                    }
                }
            }
        }
    }

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
    } else {
        runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' } }
            .getOrDefault(packageName)
    }
}
