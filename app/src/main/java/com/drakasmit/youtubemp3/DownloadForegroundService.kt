package com.drakasmit.youtubemp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class DownloadForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // A foreground service must publish its notification immediately.
        startForegroundCompat(buildNotification(DEFAULT_STATUS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote/update before inspecting the action or any other branch.
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: DEFAULT_STATUS
        startForegroundCompat(buildNotification(status))

        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузка MP3",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус загрузки и конвертации аудио"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(status: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YouTube MP3")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ytdwnld_download"
        private const val NOTIFICATION_ID = 7314
        private const val ACTION_STOP = "com.drakasmit.youtubemp3.STOP_DOWNLOAD_SERVICE"
        private const val EXTRA_STATUS = "download_status"
        private const val DEFAULT_STATUS = "Загрузка и конвертация MP3…"

        fun start(context: Context, status: String) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_STATUS, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, status: String) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_STATUS, status)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
