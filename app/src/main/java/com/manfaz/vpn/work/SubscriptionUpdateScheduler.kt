package com.manfaz.vpn.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.manfaz.vpn.data.Prefs
import com.manfaz.vpn.data.ServerRepository
import com.manfaz.vpn.data.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubscriptionUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val shouldRetry = runCatching {
                val prefs = Prefs(this@SubscriptionUpdateJobService)
                if (prefs.subAutoUpdate) {
                    ServerRepository.init(this@SubscriptionUpdateJobService)
                    SubscriptionRepository.init(this@SubscriptionUpdateJobService)
                    SubscriptionRepository.autoUpdateIfDue(prefs.subUpdateHours)
                }
            }.isFailure
            jobFinished(params, shouldRetry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return true
    }
}

object SubscriptionWorkScheduler {
    private const val JOB_ID = 2401

    fun sync(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java)
        scheduler.cancel(JOB_ID)
        val prefs = Prefs(app)
        if (!prefs.subAutoUpdate) return
        val interval = prefs.subUpdateHours.coerceAtLeast(1) * 3_600_000L
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(app, SubscriptionUpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(interval.coerceAtLeast(15 * 60_000L))
                .setPersisted(true)
                .build()
        )
    }
}
