package com.applan.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 跳转到各大厂商自启动管理页面 + 后台弹出界面权限
 *
 * 2025年7月更新：基于多源交叉验证的最新Activity路径
 * 由于Android没有统一API，只能硬编码各厂商路径。
 * 每个厂商提供多个fallback路径，按优先级排序。
 *
 * 路径来源：CSDN技术博客(2024-10更新)、Xamarin社区(2026-07更新)、
 * 腾讯云开发者社区、华为开发者论坛等。
 */
object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    private data class AutoStartTarget(
        val pkg: String,
        val cls: String,
        val action: String? = null,
        val extraKey: String? = null,
        val extraValue: String? = null
    )

    // ==================== 自启动管理路径（2025年验证） ====================

    private val VENDOR_AUTOSTART_TARGETS = mapOf(
        // ===== 小米/红米 - MIUI/HyperOS =====
        "xiaomi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),
        "redmi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),

        // ===== 华为 - EMUI/HarmonyOS =====
        "huawei" to listOf(
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")
        ),

        // ===== 荣耀 - MagicOS (独立后使用hihonor包名) =====
        "honor" to listOf(
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        ),

        // ===== OPPO - ColorOS 14/15 (oplus包名) / 旧版 (coloros包名) =====
        "oppo" to listOf(
            // ColorOS 14/15 最新路径（含.view子包）
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            // ColorOS 7-13
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            // 极旧版
            AutoStartTarget("com.color.safecenter", "com.color.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        ),

        // ===== Realme - realme UI (基于ColorOS) =====
        "realme" to listOf(
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        ),

        // ===== OnePlus - 氢OS/OxygenOS (ColorOS 13+使用oplus包名) =====
        "oneplus" to listOf(
            // ColorOS 13+ 新路径
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            // 旧版氢OS
            AutoStartTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        ),

        // ===== Vivo/iQOO - OriginOS/FuntouchOS =====
        "vivo" to listOf(
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartControlActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            AutoStartTarget("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        ),
        "iqoo" to listOf(
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")
        ),

        // ===== 三星 - OneUI (国行sm_cn / 国际版sm) =====
        "samsung" to listOf(
            // 国行自动运行
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.appmanagement.AppManagementActivity"),
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity"),
            // 国际版自动运行
            AutoStartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
            AutoStartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.appmanagement.AppManagementActivity"),
            AutoStartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity"),
            // 电池优化
            AutoStartTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        ),

        // ===== 魅族 - Flyme =====
        "meizu" to listOf(
            AutoStartTarget("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            AutoStartTarget("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
        ),

        // ===== 锤子/坚果 - SmartisanOS =====
        "smartisan" to listOf(
            AutoStartTarget("com.smartisanos.security", "com.smartisanos.security.PermissionActivity")
        ),

        // ===== 联想/ZUI =====
        "lenovo" to listOf(
            AutoStartTarget("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
            AutoStartTarget("com.zui.safecenter", "com.zui.safecenter.permission.autostart.AutoStartManagementActivity")
        ),
        "zuk" to listOf(
            AutoStartTarget("com.zui.safecenter", "com.zui.safecenter.permission.autostart.AutoStartManagementActivity")
        ),

        // ===== 乐视 - EUI =====
        "letv" to listOf(
            AutoStartTarget("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        ),
        "leeco" to listOf(
            AutoStartTarget("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        ),

        // ===== 中兴 - MiFavor =====
        "zte" to listOf(
            AutoStartTarget("com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager"),
            AutoStartTarget("com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity")
        ),

        // ===== 努比亚 - nubia UI =====
        "nubia" to listOf(
            AutoStartTarget("com.nubia.security", "com.nubia.security.privacymanage.activity.AutoStartActivity"),
            AutoStartTarget("com.nubia.security2", "com.nubia.security.privacymanage.activity.AutoStartActivity")
        ),

        // ===== OPPO一加的品牌名兜底 =====
        "oplus" to listOf(
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity")
        )
    )

    // ==================== 后台弹出界面权限路径（2025年验证） ====================
    // 这个权限决定了Service能否在后台startActivity/显示悬浮窗
    // 不同ROM实现差异巨大，部分ROM无独立页面

    private val VENDOR_BG_POPUP_TARGETS = mapOf(
        // ===== 小米/红米 - 后台弹出界面在权限编辑器中 =====
        "xiaomi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        ),
        "redmi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),

        // ===== OPPO/OnePlus/Realme - ColorOS无独立BgPopUp页面 =====
        // 网传的ui.bgpopup.BgPopUpManagerActivity经多源验证不存在
        // 正确策略：引导到权限隐私主页 / 悬浮窗权限页
        "oppo" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowPermissionActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity")
        ),
        "oneplus" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity")
        ),
        "realme" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity")
        ),

        // ===== Vivo/iQOO - 后台弹出界面有独立页面 =====
        "vivo" to listOf(
            // 需要特殊action的权限详情页（最直接）
            AutoStartTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                action = "secure.intent.action.softPermissionDetail"
            ),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.StartBgActivityControlActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            // iQOO旧版
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity")
        ),
        "iqoo" to listOf(
            AutoStartTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                action = "secure.intent.action.softPermissionDetail"
            ),
            AutoStartTarget("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
        ),

        // ===== 华为/荣耀 - 无独立后台弹出页面，通过自启动+电池优化管理 =====
        "huawei" to listOf(
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        ),
        "honor" to listOf(
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity")
        ),

        // ===== 三星 - 通过自动运行+电池优化管理 =====
        "samsung" to listOf(
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
            AutoStartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
            AutoStartTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        ),

        // ===== 魅族 =====
        "meizu" to listOf(
            AutoStartTarget("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        )
    )

    // ==================== 公开API ====================

    /**
     * 跳转到自启动管理页面
     * 路径策略：厂商专属页面 → 应用管理列表页 → 应用详情页 → 系统设置主页
     */
    fun jumpToAutoStartSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase().trim()
        val brand = Build.BRAND.lowercase().trim()

        Log.d(TAG, "jumpToAutoStartSetting: manufacturer=$manufacturer, brand=$brand")

        // 策略1：尝试厂商专属自启动页面（同时匹配manufacturer和brand）
        val targets = VENDOR_AUTOSTART_TARGETS[manufacturer]
            ?: VENDOR_AUTOSTART_TARGETS[brand]

        if (targets != null) {
            for (target in targets) {
                if (tryStartActivity(context, target)) {
                    Log.d(TAG, "AutoStart: jumped to ${target.pkg}/${target.cls}")
                    return
                }
            }
        }

        // 策略2：跳转到应用管理列表页
        Log.d(TAG, "No vendor autostart target worked, trying app list settings")
        if (tryJumpToAppListSettings(context)) return

        // 策略3：跳转到应用详情页（用户可手动找权限）
        if (tryJumpToAppDetails(context)) return

        // 策略4：跳系统设置主页
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open settings", e)
        }
    }

    /**
     * 跳转到后台弹出界面权限设置
     * 这个权限决定了Service能否在后台startActivity/显示悬浮窗
     *
     * 重要说明：
     * - ColorOS/OPPO：网传BgPopUpManagerActivity不存在，实际通过权限隐私主页/悬浮窗页引导
     * - Vivo/iQOO：有独立页面，SoftPermissionDetailActivity需特殊action
     * - MIUI：在PermissionsEditorActivity中
     * - 华为/三星：无独立页面，通过自启动+电池优化管理
     */
    fun jumpToBgPopupSetting(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase().trim()
        val brand = Build.BRAND.lowercase().trim()

        Log.d(TAG, "jumpToBgPopupSetting: manufacturer=$manufacturer, brand=$brand")

        val targets = VENDOR_BG_POPUP_TARGETS[manufacturer]
            ?: VENDOR_BG_POPUP_TARGETS[brand]

        if (targets != null) {
            for (target in targets) {
                if (tryStartActivity(context, target)) {
                    Log.d(TAG, "BgPopup: jumped to ${target.pkg}/${target.cls}")
                    return
                }
            }
        }

        // fallback到自启动页面
        Log.d(TAG, "No bg popup target, fallback to autostart")
        jumpToAutoStartSetting(context)
    }

    /**
     * 跳转到悬浮窗权限设置页（通用）
     */
    fun jumpToOverlaySetting(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay setting", e)
        }
        // fallback到应用详情
        tryJumpToAppDetails(context)
    }

    /**
     * 跳转到电池优化白名单设置
     */
    fun jumpToBatteryOptimization(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization", e)
        }

        // 厂商电池优化页面
        val manufacturer = Build.MANUFACTURER.lowercase().trim()
        val batteryTargets = mapOf(
            "xiaomi" to listOf(
                AutoStartTarget("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ),
            "samsung" to listOf(
                AutoStartTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")
            ),
            "vivo" to listOf(
                AutoStartTarget("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                AutoStartTarget("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
            ),
            "honor" to listOf(
                AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity")
            )
        )
        val targets = batteryTargets[manufacturer]
        if (targets != null) {
            for (target in targets) {
                if (tryStartActivity(context, target)) return
            }
        }

        // fallback
        jumpToAutoStartSetting(context)
    }

    // ==================== 内部工具方法 ====================

    private fun tryStartActivity(context: Context, target: AutoStartTarget): Boolean {
        return try {
            val intent = Intent()
            if (target.action != null) {
                intent.action = target.action
            }
            intent.component = ComponentName(target.pkg, target.cls)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 传递包名extra（不同ROM读取不同key，全部带上）
            intent.putExtra("package_name", context.packageName)
            intent.putExtra("packagename", context.packageName)
            intent.putExtra("packageName", context.packageName)

            if (target.extraKey != null && target.extraValue != null) {
                intent.putExtra(target.extraKey, target.extraValue)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Failed: ${target.pkg}/${target.cls}: ${e.message}")
            false
        }
    }

    private fun tryJumpToAppListSettings(context: Context): Boolean {
        val intents = listOf(
            // ColorOS/OPPO 应用管理
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.applications.ManageApplications")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // 通用应用列表
            Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // ACTION_APPLICATION_DETAILS_SETTINGS 不带data = 应用列表
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun tryJumpToAppDetails(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", context.packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Failed to jump to app details", e)
            false
        }
    }
}
