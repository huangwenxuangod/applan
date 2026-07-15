package com.applan.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 跳转到各大厂商自启动管理页面
 *
 * 由于Android没有统一API，只能硬编码各厂商路径。
 * 跳转到厂商自启动管理页后，用户需要手动在列表中找到applan并开启。
 * 如果跳转失败/页面不存在，则fallback到应用详情页，用户手动找。
 */
object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    private data class AutoStartTarget(
        val pkg: String,
        val cls: String,
        val extra: String? = null
    )

    /**
     * 更新2024-2025各厂商最新路径，增加多个fallback
     */
    private val VENDOR_TARGETS = mapOf(
        "xiaomi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        ),
        "redmi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),
        "huawei" to listOf(
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstartup.BootStartupActivity")
        ),
        "honor" to listOf(
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        ),
        "oppo" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")
        ),
        "realme" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
        ),
        "oneplus" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
        ),
        "vivo" to listOf(
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartControlActivity"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            AutoStartTarget("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        ),
        "iqoo" to listOf(
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        "samsung" to listOf(
            AutoStartTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            AutoStartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            AutoStartTarget("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        ),
        "meizu" to listOf(
            AutoStartTarget("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            AutoStartTarget("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
        ),
        "smartisan" to listOf(
            AutoStartTarget("com.smartisanos.security", "com.smartisanos.security.PermissionActivity")
        ),
        "lenovo" to listOf(
            AutoStartTarget("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
            AutoStartTarget("com.zui.safecenter", "com.zui.safecenter.permission.autostart.AutoStartManagementActivity")
        ),
        "letv" to listOf(
            AutoStartTarget("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        ),
        "zte" to listOf(
            AutoStartTarget("com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager"),
            AutoStartTarget("com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity")
        ),
        "nubia" to listOf(
            AutoStartTarget("com.nubia.security", "com.nubia.security.privacymanage.activity.AutoStartActivity"),
            AutoStartTarget("com.nubia.security2", "com.nubia.security.privacymanage.activity.AutoStartActivity")
        )
    )

    fun jumpToAutoStartSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        Log.d(TAG, "jumpToAutoStartSetting: manufacturer=$manufacturer, brand=$brand")

        // 尝试厂商路径
        val targets = VENDOR_TARGETS[manufacturer] ?: VENDOR_TARGETS[brand]
        if (targets != null) {
            for (target in targets) {
                if (tryStartActivity(context, target)) {
                    Log.d(TAG, "Successfully jumped to ${target.pkg}/${target.cls}")
                    return
                }
            }
        }

        // Fallback1: 尝试跳应用详情页，用户自己找自启动/电池管理
        Log.d(TAG, "No vendor target worked, falling back to app details")
        if (tryJumpToAppDetails(context)) return

        // Fallback2: 跳系统设置主页
        try {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open settings", e)
        }
    }

    private fun tryStartActivity(context: Context, target: AutoStartTarget): Boolean {
        return try {
            val intent = Intent()
            intent.component = ComponentName(target.pkg, target.cls)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target.extra != null) {
                intent.putExtra(target.extra, context.packageName)
            } else {
                intent.putExtra("package_name", context.packageName)
                intent.putExtra("packagename", context.packageName)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Failed to start ${target.pkg}/${target.cls}: ${e.message}")
            false
        }
    }

    private fun tryJumpToAppDetails(context: Context): Boolean {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", context.packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
