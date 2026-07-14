package com.lockai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            try {
                KeepAliveService.start(context)
                DaemonService.start(context)
                Log.d(TAG, "Both services started after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start services on boot", e)
            }
        }
    }
}
