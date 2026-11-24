package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.notification.NotificationHelper

class CallForegroundService : Service() {
    companion object {
        private const val TAG = "CallForegroundService"

        const val ACTION_START_STREAM = "com.example.service.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.example.service.action.STOP_STREAM"
        const val ACTION_UPDATE_STREAM = "com.example.service.action.UPDATE_STREAM"

        const val EXTRA_IS_BROADCASTER = "extra_is_broadcaster"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_DURATION_TEXT = "extra_duration_text"

        fun startService(context: Context, isBroadcaster: Boolean, channelId: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_STREAM
                putExtra(EXTRA_IS_BROADCASTER, isBroadcaster)
                putExtra(EXTRA_CHANNEL_ID, channelId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground service: ${e.message}")
            }
        }

        fun updateService(context: Context, isBroadcaster: Boolean, channelId: String, durationText: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_UPDATE_STREAM
                putExtra(EXTRA_IS_BROADCASTER, isBroadcaster)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_DURATION_TEXT, durationText)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_STREAM
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop foreground service: ${e.message}")
            }
        }
    }

    private var currentIsBroadcaster = false
    private var currentChannelId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_STREAM -> {
                currentIsBroadcaster = intent.getBooleanExtra(EXTRA_IS_BROADCASTER, false)
                currentChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
                startInForeground(currentIsBroadcaster, currentChannelId, null)
            }

            ACTION_UPDATE_STREAM -> {
                currentIsBroadcaster = intent.getBooleanExtra(EXTRA_IS_BROADCASTER, currentIsBroadcaster)
                currentChannelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: currentChannelId
                val durationText = intent.getStringExtra(EXTRA_DURATION_TEXT)
                startInForeground(currentIsBroadcaster, currentChannelId, durationText)
            }

            ACTION_STOP_STREAM -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startInForeground(isBroadcaster: Boolean, channelId: String, durationText: String?) {
        val notification = NotificationHelper.buildActiveStreamNotification(
            context = this,
            isBroadcaster = isBroadcaster,
            channelId = channelId,
            durationText = durationText
        )

        try {
            var serviceType = 0
            if (isBroadcaster) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (hasCamera) {
                        serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    }
                    if (hasMic) {
                        serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                }
                if (serviceType == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
            } else {
                // Viewer mode uses mediaPlayback (zero camera/mic permissions required)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID_ACTIVE_STREAM,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID_ACTIVE_STREAM, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
            try {
                startForeground(NotificationHelper.NOTIFICATION_ID_ACTIVE_STREAM, notification)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback startForeground failed: ${fallbackEx.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
