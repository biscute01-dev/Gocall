package com.example.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.auth.CallInvitation
import com.example.service.CallActionReceiver

object NotificationHelper {
    const val CHANNEL_INCOMING_CALLS = "incoming_calls_channel"
    const val CHANNEL_ACTIVE_CALL = "active_call_channel"
    const val CHANNEL_DEFAULT = "app_notifications_channel"

    const val NOTIFICATION_ID_INCOMING_CALL = 2001
    const val NOTIFICATION_ID_ACTIVE_CALL = 2002
    const val NOTIFICATION_ID_MESSAGE = 2003

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. High Priority Incoming Call Channel (Sound & Vibration)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val callChannel = NotificationChannel(
                CHANNEL_INCOMING_CALLS,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming 1-on-1 video calls"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(ringtoneUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 2. Ongoing Active Call Channel (Low priority, persistent during call)
            val activeCallChannel = NotificationChannel(
                CHANNEL_ACTIVE_CALL,
                "Ongoing Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of ongoing video calls with quick controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 3. Default Notifications Channel
            val defaultChannel = NotificationChannel(
                CHANNEL_DEFAULT,
                "Messages & Requests",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Friend requests and system notifications"
            }

            notificationManager.createNotificationChannel(callChannel)
            notificationManager.createNotificationChannel(activeCallChannel)
            notificationManager.createNotificationChannel(defaultChannel)
        }
    }

    /**
     * Builds the high-priority Incoming Call notification with Accept and Decline actions.
     */
    fun showIncomingCallNotification(context: Context, invitation: CallInvitation) {
        createNotificationChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val callerName = invitation.callerDisplayName.ifBlank { "@${invitation.callerUsername}".ifBlank { "Someone" } }

        // Full screen / Content Intent to open MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = CallActionReceiver.ACTION_INCOMING_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, invitation.callId)
            putExtra(CallActionReceiver.EXTRA_CALLER_UID, invitation.callerUid)
            putExtra(CallActionReceiver.EXTRA_CALLER_NAME, invitation.callerDisplayName)
            putExtra(CallActionReceiver.EXTRA_CALLER_USERNAME, invitation.callerUsername)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            invitation.callId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept Action Intent
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            action = CallActionReceiver.ACTION_ACCEPT_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, invitation.callId)
            putExtra(CallActionReceiver.EXTRA_CALLER_UID, invitation.callerUid)
            putExtra(CallActionReceiver.EXTRA_CALLER_NAME, invitation.callerDisplayName)
            putExtra(CallActionReceiver.EXTRA_CALLER_USERNAME, invitation.callerUsername)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            invitation.callId.hashCode() + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline Action Intent
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, invitation.callId)
            putExtra(CallActionReceiver.EXTRA_CALLER_UID, invitation.callerUid)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            invitation.callId.hashCode() + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decode avatar bitmap for large icon if available
        val largeIconBitmap = if (!invitation.callerAvatarBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(invitation.callerAvatarBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_stat_videocam)
            .setContentTitle("Incoming Video Call")
            .setContentText("$callerName is calling you...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .addAction(R.drawable.ic_stat_call_decline, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_stat_call_accept, "Accept", acceptPendingIntent)

        if (largeIconBitmap != null) {
            builder.setLargeIcon(largeIconBitmap)
        }

        notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, builder.build())
    }

    fun cancelIncomingCallNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_INCOMING_CALL)
    }

    /**
     * Builds the persistent Foreground Notification for active calls with an "End Call" button.
     */
    fun buildOngoingCallNotification(
        context: Context,
        peerDisplayName: String,
        roomId: String,
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

        // End Call Broadcast Intent
        val endCallIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END_CALL
        }
        val endCallPendingIntent = PendingIntent.getBroadcast(
            context,
            201,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (peerDisplayName.isNotBlank()) "Call with $peerDisplayName" else "Video Call (Room $roomId)"
        val text = durationText ?: "Tap to return to ongoing call"

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_stat_videocam)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_stat_call_decline, "End Call", endCallPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
