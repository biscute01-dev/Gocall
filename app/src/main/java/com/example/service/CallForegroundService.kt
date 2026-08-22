package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.notification.NotificationHelper

class CallForegroundService : Service() {
    companion object {
        private const val TAG = "CallForegroundService"

        const val ACTION_START_CALL = "com.example.service.action.START_CALL"
        const val ACTION_STOP_CALL = "com.example.service.action.STOP_CALL"
        const val ACTION_UPDATE_CALL = "com.example.service.action.UPDATE_CALL"

        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_DURATION_TEXT = "extra_duration_text"

        fun startService(context: Context, peerName: String, roomId: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateService(context: Context, peerName: String, roomId: String, durationText: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_UPDATE_CALL
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_DURATION_TEXT, durationText)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update call foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop call foreground service: ${e.message}")
            }
        }
    }

    private var currentPeerName = ""
    private var currentRoomId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_CALL -> {
                currentPeerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: ""
                currentRoomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
                startInForeground(currentPeerName, currentRoomId, null)
            }

            ACTION_UPDATE_CALL -> {
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: currentPeerName
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: currentRoomId
                val durationText = intent.getStringExtra(EXTRA_DURATION_TEXT)
                currentPeerName = peerName
                currentRoomId = roomId
                startInForeground(currentPeerName, currentRoomId, durationText)
            }

            ACTION_STOP_CALL -> {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startInForeground(peerName: String, roomId: String, durationText: String?) {
        val notification = NotificationHelper.buildOngoingCallNotification(
            context = this,
            peerDisplayName = peerName,
            roomId = roomId,
            durationText = durationText
        )

        try {
            var serviceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID_ACTIVE_CALL,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID_ACTIVE_CALL, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
            try {
                startForeground(NotificationHelper.NOTIFICATION_ID_ACTIVE_CALL, notification)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback startForeground failed: ${fallbackEx.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }
}
