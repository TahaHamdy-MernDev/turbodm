package com.turbodm.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.turbodm.android.MainActivity
import com.turbodm.android.R
import com.turbodm.android.TurboDMApplication
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.util.Formatters

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ACTIVE = "turbodm_active_downloads"
        const val CHANNEL_COMPLETE = "turbodm_completed_downloads"
        const val NOTIF_ID_ACTIVE = 1001
        const val ACTION_PAUSE_ALL = "com.turbodm.android.ACTION_PAUSE_ALL"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val active = NotificationChannel(
                CHANNEL_ACTIVE,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download speed and progress"
                setShowBadge(false)
            }
            val complete = NotificationChannel(
                CHANNEL_COMPLETE,
                "Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads finish"
            }
            nm.createNotificationChannel(active)
            nm.createNotificationChannel(complete)
        }

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }

        fun updateNotification(context: Context, active: List<DownloadEntity>) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (active.isEmpty()) return
            nm.notify(NOTIF_ID_ACTIVE, buildActiveNotification(context, active))
        }

        fun notifyCompleted(context: Context, item: DownloadEntity) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val openIntent = PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download Complete")
                .setContentText("${item.filename} (${Formatters.formatBytes(item.totalSize)})")
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(item.id.hashCode(), notif)
        }

        private fun buildActiveNotification(context: Context, active: List<DownloadEntity>): Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val pauseAllIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, DownloadForegroundService::class.java).setAction(ACTION_PAUSE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val totalSpeed = active.sumOf { it.speedBytesPerSec }
            val primary = active.firstOrNull()
            val title = when {
                active.isEmpty() -> "TurboDM running"
                active.size == 1 -> primary!!.filename
                else -> "Downloading ${active.size} files"
            }
            val pct = if (primary != null && (primary.totalSize ?: 0L) > 0L) {
                Formatters.progressPercent(primary.downloadedSize, primary.totalSize).toInt()
            } else -1

            val text = if (active.isEmpty()) {
                "Preparing download..."
            } else {
                "${Formatters.formatSpeed(totalSpeed)} • ${if (pct >= 0) "$pct%" else "Streaming"}"
            }

            return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .addAction(android.R.drawable.ic_media_pause, "Pause All", pauseAllIntent)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        if (intent?.action == ACTION_PAUSE_ALL) {
            (application as? TurboDMApplication)?.coordinator?.pauseAll()
            stopSelf()
            return START_NOT_STICKY
        }

        val active = (application as? TurboDMApplication)?.coordinator?.activeSummary?.value.orEmpty()
        val notif = buildActiveNotification(this, active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_ACTIVE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID_ACTIVE, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
