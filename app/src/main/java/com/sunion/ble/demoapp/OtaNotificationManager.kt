package com.sunion.ble.demoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object OtaNotificationManager {
    private const val CHANNEL_ID = "ota_channel"

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OTA任務通知",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, progress: Int, max: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("OTA執行中")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .build()
    }

    fun buildCompletedNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("完成")
            .setContentText("OTA已完成")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .build()
    }

    fun buildFailNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentText("OTA失敗")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
    }
}