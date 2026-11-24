package com.example.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.service.CallActionReceiver

object NotificationHelper {
    const val CHANNEL_ACTIVE_STREAM = "active_stream_channel"
    const val NOTIFICATION_ID_ACTIVE_STREAM = 2002

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE_STREAM,
                "Active Surveillance Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status while broadcasting camera or viewing live feed"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(activeChannel)
        }
    }

    fun buildActiveStreamNotification(
        context: Context,
        isBroadcaster: Boolean,
        channelId: String,
        durationText: String? = null
    ): Notification {
        createNotificationChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            200,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_STOP_STREAM
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            201,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isBroadcaster) {
            "Broadcasting Camera • $channelId"
        } else {
            "Viewing Feed • $channelId"
        }
        val text = durationText ?: if (isBroadcaster) {
            "Camera live • Tap to open controls"
        } else {
            "Watching live stream • Tap to return"
        }

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE_STREAM)
            .setSmallIcon(R.drawable.ic_stat_videocam)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_stat_call_decline, "Stop", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
