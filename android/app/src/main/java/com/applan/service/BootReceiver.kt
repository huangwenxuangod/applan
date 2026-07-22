package com.applan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.applan.MainActivity
import com.applan.util.AppConfig
import com.applan.util.AppState

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received: $action")

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        if (isBoot) {
            try {
                KeepAliveService.start(context)
                DaemonService.start(context)
                ScheduleBoundaryReceiver.scheduleNext(context)
                Log.d(TAG, "Services started after boot")

                AppConfig.setExitGranted(false)
                AppState.resetGrant()
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra("boot_triggered", true)
                    }
                )
                Log.d(TAG, "MainActivity launched after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start services on boot", e)
            }
        }
    }
}
