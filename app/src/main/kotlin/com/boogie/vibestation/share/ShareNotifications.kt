package com.boogie.vibestation.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.boogie.vibestation.MainActivity
import com.boogie.vibestation.R

/**
 * The ongoing notification that Android requires while [ShareService] runs in the foreground.
 *
 * @param context Context used to build notifications and reach the notification manager.
 */
internal class ShareNotifications(private val context: Context) {

    /** Registers the low-priority channel on Android 8+, where notifications need one. */
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VibeStation Share Mode", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Makes [service] a foreground service of type dataSync, which Android 14 requires for long transfers.
     *
     * @param service The running service.
     * @param text    What to show as the notification text.
     */
    fun enterForeground(service: Service, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            service.startForeground(NOTIFICATION_ID, build(text))
        }
    }

    /**
     * Updates the text of the visible notification.
     *
     * @param text The new text.
     */
    fun update(text: String) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_bubbly)
            .setContentTitle("Share Mode")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "vibe_share_channel"
        const val NOTIFICATION_ID = 2
    }
}
