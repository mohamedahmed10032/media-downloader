package com.melmeligy.mediadownloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.melmeligy.mediadownloader.MainActivity
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts all download notifications and owns the notification channel. */
@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.DOWNLOAD_CHANNEL_ID,
                context.getString(R.string.notif_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_downloads_desc)
                setShowBadge(false)
            }
            val system = context.getSystemService(NotificationManager::class.java)
            system?.createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    /** The ongoing notification shown while the foreground download service runs. */
    fun buildForeground(title: String, text: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(context, Constants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun notifyComplete(downloadId: Long, title: String) {
        val notification = NotificationCompat.Builder(context, Constants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_download_complete))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(Constants.COMPLETION_NOTIFICATION_BASE_ID + downloadId.toInt(), notification)
    }

    fun notifyFailed(downloadId: Long, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, Constants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_download_failed))
            .setContentText("$title — $message")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$message"))
            .build()
        safeNotify(Constants.COMPLETION_NOTIFICATION_BASE_ID + downloadId.toInt(), notification)
    }

    private fun safeNotify(id: Int, notification: Notification) {
        // On Android 13+ posting requires POST_NOTIFICATIONS; if denied this no-ops instead of crashing.
        try {
            if (manager.areNotificationsEnabled()) manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val FOREGROUND_ID = Constants.FOREGROUND_NOTIFICATION_ID
    }
}
