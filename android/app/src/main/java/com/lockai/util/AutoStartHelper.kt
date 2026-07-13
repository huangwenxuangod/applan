package com.lockai.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /**
     * 跳转到对应厂商的自启动管理页面
     * 个人自用场景：只需手动设置一次即可
     */
    fun jumpToAutoStartSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        Log.d(TAG, "Manufacturer: $manufacturer")

        val intent = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        var component: ComponentName? = null

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                // 小米/红米 MIUI/HyperOS
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                // 华为/荣耀 EMUI/HarmonyOS/MagicUI
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                // OPPO/realme/一加 ColorOS
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                // vivo/iQOO OriginOS/FuntouchOS
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
            manufacturer.contains("samsung") -> {
                // 三星 - 跳电池优化
                component = null
            }
            manufacturer.contains("meizu") -> {
                component = ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity"
                )
            }
        }

        try {
            if (component != null) {
                intent.component = component
                context.startActivity(intent)
            } else {
                // 兜底：跳应用详情页
                PermissionHelper.jumpToAppDetails(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open auto-start setting", e)
            // 最终兜底：跳系统设置
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open settings", e2)
            }
        }
    }

    /**
     * 跳转到厂商后台运行/电池管理页面
     */
    fun jumpToBatterySaverSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        try {
            val intent = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            when {
                manufacturer.contains("xiaomi") -> {
                    intent.component = ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    context.startActivity(intent)
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    intent.component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                    context.startActivity(intent)
                }
                manufacturer.contains("oppo") -> {
                    intent.component = ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                    )
                    context.startActivity(intent)
                }
                manufacturer.contains("vivo") -> {
                    intent.component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                    )
                    context.startActivity(intent)
                }
                else -> {
                    PermissionHelper.requestBatteryOptimization(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery saver setting", e)
            PermissionHelper.requestBatteryOptimization(context)
        }
    }
}
