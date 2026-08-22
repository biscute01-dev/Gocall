package com.example.notification

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.auth.CallInvitation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "MyFirebaseMsgService"
        const val PREFS_FCM = "fcm_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")

        // Save token in shared preferences
        getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE).edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()

        // If user is currently signed in, sync token to Firebase Realtime Database
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            try {
                FirebaseDatabase.getInstance().getReference("users")
                    .child(currentUser.uid)
                    .child("fcmToken")
                    .setValue(token)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update FCM token in database: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}, data: ${remoteMessage.data}")

        val data = remoteMessage.data
        val messageType = data["type"] ?: data["action"] ?: ""

        when (messageType) {
            "incoming_call", "direct_call" -> {
                val callId = data["callId"] ?: data["roomId"] ?: ""
                val callerUid = data["callerUid"] ?: ""
                val callerDisplayName = data["callerDisplayName"] ?: data["callerName"] ?: "Friend"
                val callerUsername = data["callerUsername"] ?: ""
                val callerPhotoUrl = data["callerPhotoUrl"]
                val callerAvatarBase64 = data["callerAvatarBase64"]

                val invitation = CallInvitation(
                    callId = callId,
                    callerUid = callerUid,
                    callerDisplayName = callerDisplayName,
                    callerUsername = callerUsername,
                    callerPhotoUrl = callerPhotoUrl,
                    callerAvatarBase64 = callerAvatarBase64,
                    calleeUid = FirebaseAuth.getInstance().currentUser?.uid ?: "",
                    status = "ringing",
                    timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
                )

                NotificationHelper.showIncomingCallNotification(applicationContext, invitation)
            }

            "call_cancelled", "call_ended", "call_rejected" -> {
                NotificationHelper.cancelIncomingCallNotification(applicationContext)
            }

            "friend_request" -> {
                val senderName = data["senderDisplayName"] ?: data["senderName"] ?: "Someone"
                NotificationHelper.createNotificationChannels(applicationContext)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_DEFAULT)
                    .setSmallIcon(R.drawable.ic_stat_videocam)
                    .setContentTitle("New Friend Request")
                    .setContentText("$senderName sent you a friend request")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(NotificationHelper.NOTIFICATION_ID_MESSAGE, notification)
            }

            else -> {
                // If standard notification payload exists
                remoteMessage.notification?.let { notif ->
                    NotificationHelper.createNotificationChannels(applicationContext)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notification = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_DEFAULT)
                        .setSmallIcon(R.drawable.ic_stat_videocam)
                        .setContentTitle(notif.title ?: "Messenger Call")
                        .setContentText(notif.body ?: "")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(NotificationHelper.NOTIFICATION_ID_MESSAGE, notification)
                }
            }
        }
    }
}
