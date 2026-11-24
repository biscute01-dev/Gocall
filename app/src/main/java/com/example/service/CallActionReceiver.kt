package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.viewmodel.CallViewModel

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_STOP_STREAM = "com.example.action.STOP_STREAM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        if (action == ACTION_STOP_STREAM) {
            CallForegroundService.stopService(context)
            CallViewModel.activeInstance?.stopSession()
        }
    }
}
