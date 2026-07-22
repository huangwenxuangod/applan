package com.applan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.applan.util.AppConfig

class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received: $action")

        if (action == Intent.ACTION_USER_PRESENT) {
            Log.d(TAG, "USER_PRESENT - passiveExit=${AppConfig.isExitGranted()}, no foreground launch")
        }
    }
}
