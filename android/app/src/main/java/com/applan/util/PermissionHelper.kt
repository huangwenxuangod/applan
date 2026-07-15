package com.applan.util

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.applan.service.AppDeviceAdminReceiver
import com.applan.service.LockAccessibilityService

object PermissionHelper {

    /**
     * 检查设备管理员是否已启用（防卸载）
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(context, AppDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(cn)
    }

    /**
     * 请求设备管理员权限（启用后用户无法直接卸载App）
     */
    fun requestDeviceAdmin(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            val cn = ComponentName(context, AppDeviceAdminReceiver::class.java)
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "启用设备管理员后，applan将无法被直接卸载，防止你意志不坚定时绕开锁屏。\n" +
                "如需卸载，请先在此页面关闭设备管理员权限。"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 检查是否有悬浮窗权限
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 跳转悬浮窗设置
     */
    fun jumpToOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    /**
     * 检查电池优化白名单
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求电池优化白名单
     */
    fun requestBatteryOptimization(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 检查无障碍服务是否开启
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return LockAccessibilityService.isEnabled(context)
    }

    /**
     * 跳转无障碍设置
     */
    fun jumpToAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 检查是否为默认Launcher
     */
    fun isDefaultLauncher(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultLauncher = context.packageManager.resolveActivity(intent, 0)
        return defaultLauncher?.activityInfo?.packageName == context.packageName
    }

    /**
     * 请求设置默认Launcher
     */
    fun requestDefaultLauncher(context: Context) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pickerIntent = Intent(Intent.ACTION_PICK_ACTIVITY).apply {
            putExtra(Intent.EXTRA_INTENT, launcherIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(pickerIntent)
    }

    /**
     * 跳转应用详情设置
     */
    fun jumpToAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 跳转通知设置
     */
    fun jumpToNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    /**
     * 检查悬浮窗权限是否开启（严格模式检查用别名）
     */
    fun isOverlayEnabled(context: Context): Boolean {
        return canDrawOverlays(context)
    }

    /**
     * 检查电池优化白名单（严格模式检查用别名）
     */
    fun isBatteryWhitelisted(context: Context): Boolean {
        return isIgnoringBatteryOptimizations(context)
    }

    /**
     * 检查使用情况访问权限是否已授权
     */
    fun isUsageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检查后台启动权限是否允许（Android 12+）
     * 通过检查精确闹钟权限和电池白名单综合判断
     */
    fun isBackgroundStartEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        // Android 12+ 检查精确闹钟权限（用于服务重启）+ 电池白名单
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        // 默认桌面应用不受后台启动限制
        return canScheduleExact || isDefaultLauncher(context)
    }
}
