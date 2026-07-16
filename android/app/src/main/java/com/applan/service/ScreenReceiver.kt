package com.applan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.applan.MainActivity
import com.applan.util.AppState

class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received: $action")

        if (action == Intent.ACTION_USER_PRESENT) {
            if (AppState.shouldBlock()) {
                Log.d(TAG, "USER_PRESENT - user unlocked, pulling up applan immediately")
                try {
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        putExtra("auto_launch", true)
                        putExtra("unlock_triggered", true)
                    }
                    context.startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch on USER_PRESENT", e)
                }
            } else {
                Log.d(TAG, "USER_PRESENT - not blocking, no action needed")
            }
        }
    }
}
