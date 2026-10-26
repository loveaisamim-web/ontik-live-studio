package com.ontik.livestudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "ontik_stream"
    private const val NOTIF_ID = 1001

    fun createChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live Streaming", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun build(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ONTIK Live Studio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    fun id() = NOTIF_ID
}
