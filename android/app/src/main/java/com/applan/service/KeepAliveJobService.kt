package com.applan.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.applan.util.AppConfig
import com.applan.util.AppState

/**
 * JobScheduler保活兜底服务
 *
 * 国产ROM（ColorOS/MIUI等）在用户从最近任务划掉App时，
 * 可能直接killProcessGroup导致onTaskRemoved/onDestroy都不被调用，
 * AlarmManager的PendingIntent也可能被拦截。
 *
 * JobScheduler是系统级调度，比AlarmManager更难被杀死。
 * 当Job被触发时，检查并重启双Service，必要时拉起Activity/显示遮罩。
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job started - checking services")

        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                // 重启双Service
                if (AppState.canStartKeepAlive()) {
                    KeepAliveService.start(this)
                }
                if (AppState.canStartDaemon()) {
                    DaemonService.start(this)
                }

                // 如果应该拦截但Activity不在前台，显示遮罩
                if (AppState.shouldBlock() && !AppState.isActivityInForeground && !AppConfig.isExitGranted()) {
                    handler.post {
                        try {
                            if (!BlockOverlay.isShowing() && !BlockOverlay.wasJustShown(2000)
                                && android.provider.Settings.canDrawOverlays(this)) {
                                BlockOverlay.show(this)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to show overlay from job", e)
                        }
                    }
                }

                Log.d(TAG, "Job completed - services restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Job execution failed", e)
            }

            jobFinished(params, false)
        }.start()

        return true // 异步执行
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job stopped by system")
        return true // 需要重新调度
    }
}
