package com.wifigatekeeper.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wifigatekeeper.MainActivity
import com.wifigatekeeper.R
import com.wifigatekeeper.WifiGatekeeperApp
import com.wifigatekeeper.core.router.DeviceScanEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationChannels {
    const val DEVICE_ALERTS = "device_alerts"
    const val MONITORING = "monitoring"

    fun ensure(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                DEVICE_ALERTS,
                "New device alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when an unknown device tries to join the network." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MONITORING,
                "Monitoring service",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Persistent notification of the background monitor."
                setShowBadge(false)
            },
        )
    }
}

/** Builds and cancels the interactive new-device notifications. */
class Notifier(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun notifyNewDevice(mac: String, name: String?, ip: String?) {
        val id = notificationId(mac)
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val accept = PendingIntent.getBroadcast(
            context,
            id + 1,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_ACCEPT)
                .putExtra(NotificationActionReceiver.EXTRA_MAC, mac),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            context,
            id + 2,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REJECT)
                .putExtra(NotificationActionReceiver.EXTRA_MAC, mac),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildString {
            append(context.getString(R.string.notify_device_body_prefix, mac))
            name?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
            ip?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.DEVICE_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(context.getString(R.string.notify_new_device_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.action_accept), accept)
            .addAction(0, context.getString(R.string.action_reject), reject)
            .build()
        manager.notify(id, notification)
    }

    fun cancelDeviceNotification(mac: String) {
        manager.cancel(notificationId(mac))
    }

    private fun notificationId(mac: String): Int = mac.hashCode()

    /** Shown when Accept/Reject pressed from the notification did not succeed. */
    fun notifyActionFailed(mac: String, title: String, reason: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(mac) + 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.DEVICE_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(title)
            .setContentText("MAC: $mac - $reason. Tap to retry from the dashboard.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("MAC: $mac - $reason. Tap to retry from the dashboard."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(notificationId(mac) + 3, notification)
    }
}

/** Executes Accept / Reject straight from the notification buttons. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mac = intent.getStringExtra(EXTRA_MAC) ?: return
        val pending = goAsync()
        val app = context.applicationContext as WifiGatekeeperApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_ACCEPT -> {
                        val result = app.container.repository.acceptDevice(mac)
                        result.fold(
                            onSuccess = { },
                            // Silent failure here is how "I pressed accept and
                            // nothing happened" happens - surface the reason.
                            onFailure = { error ->
                                app.container.notifier.notifyActionFailed(
                                    mac,
                                    context.getString(R.string.notify_accept_failed),
                                    error.message ?: "unknown error",
                                )
                            },
                        )
                    }
                    ACTION_REJECT -> app.container.repository.rejectDevice(mac)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.wifigatekeeper.ACTION_ACCEPT"
        const val ACTION_REJECT = "com.wifigatekeeper.ACTION_REJECT"
        const val EXTRA_MAC = "extra_mac"
    }
}
