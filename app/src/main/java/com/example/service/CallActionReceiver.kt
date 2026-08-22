package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.MainActivity
import com.example.notification.NotificationHelper
import com.example.viewmodel.CallViewModel

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_END_CALL = "com.example.action.END_CALL"
        const val ACTION_ACCEPT_CALL = "com.example.action.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.action.DECLINE_CALL"
        const val ACTION_INCOMING_CALL = "com.example.action.INCOMING_CALL"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_UID = "extra_caller_uid"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_USERNAME = "extra_caller_username"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        when (action) {
            ACTION_END_CALL -> {
                // 1. Stop Foreground Service
                CallForegroundService.stopService(context)
                // 2. End active call in ViewModel
                CallViewModel.activeInstance?.endCall()
                // 3. Clear notifications
                NotificationHelper.cancelIncomingCallNotification(context)
            }

            ACTION_DECLINE_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
                NotificationHelper.cancelIncomingCallNotification(context)
                CallViewModel.activeInstance?.let { vm ->
                    val incoming = vm.friendsRepository.incomingCall.value
                    if (incoming != null && incoming.callId == callId) {
                        vm.rejectIncomingCall(incoming)
                    }
                }
            }

            ACTION_ACCEPT_CALL -> {
                NotificationHelper.cancelIncomingCallNotification(context)
                // Open MainActivity to accept call
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = ACTION_ACCEPT_CALL
                    putExtras(intent)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
