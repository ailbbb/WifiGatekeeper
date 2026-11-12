package com.wifigatekeeper.background

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifigatekeeper.R
import com.wifigatekeeper.WifiGatekeeperApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that polls the router every [com.wifigatekeeper.data.settings.SettingsRepository.MonitoringSettings.pollIntervalSeconds]
 * seconds so a new device raises an alert within seconds, not minutes.
 */
class MonitoringForegroundService : LifecycleService() {

    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val settings = container().settingsRepository.snapshot()
            if (!settings.monitoringEnabled || !container().credentialsStore.hasCredentials()) break
            container().repository.pollNow(notify = true)
            delay(settings.pollIntervalSeconds * 1_000L)
        }
        stopSelf()
    }

    private fun container() = (application as WifiGatekeeperApp).container

    private fun startForegroundCompat() {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MonitoringForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannelsId.MONITORING)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(getString(R.string.monitoring_title))
            .setContentText(getString(R.string.monitoring_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.monitoring_stop), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 42
        const val ACTION_STOP = "com.wifigatekeeper.STOP_MONITORING"

        // Reuse the channel id from NotificationChannels without importing it here.
        object NotificationChannelsId {
            const val MONITORING = "monitoring"
        }

        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

/** WorkManager fallback poll - runs at the Android minimum of 15 minutes. */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WifiGatekeeperApp
        app.container.repository.pollNow(notify = true)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "router_poll"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** Reschedules the fallback poll after a reboot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PollWorker.schedulePeriodic(context)
        }
    }
}
