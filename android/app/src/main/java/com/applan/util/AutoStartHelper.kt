package com.applan.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Opens the system Settings home and vendor-specific background-popup settings.
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

    // ==================== 自启动管理路径（2025年7月验证） ====================

    private val VENDOR_AUTOSTART_TARGETS = mapOf(
        // ===== 小米/红米 - MIUI/HyperOS =====
        // 最主要路径：AutoStartManagementActivity（MIUI10到HyperOS3均可用）
        "xiaomi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // MIUI专属Action（无需ComponentName）
            AutoStartTarget("com.miui.securitycenter", "", action = "miui.intent.action.OP_AUTO_START"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),
        "redmi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),

        // ===== 华为 - EMUI/HarmonyOS =====
        // 最主要路径：StartupNormalAppListActivity（EMUI9+验证最多）
        "huawei" to listOf(
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")
        ),

        // ===== 荣耀 - MagicOS =====
        // 荣耀独立后仍大量使用com.huawei.systemmanager，com.hihonor作为fallback
        "honor" to listOf(
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        ),

        // ===== OPPO - ColorOS =====
        // 最主要路径（验证最多）：com.coloros.safecenter/.startupapp.StartupAppListActivity
        // 注意：之前错误地加了.view子包，正确路径没有.view
        // ColorOS 12+新包名：com.oplus.safecenter
        // ColorOS新版安全中心可能叫 com.coloros.securitycenter
        "oppo" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivityForExternal"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AssociateStartActivity"),
            AutoStartTarget("com.coloros.securitycenter", "com.coloros.securitycenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.color.safecenter", "com.color.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // 尝试通过Action启动（类似MIUI的OP_AUTO_START）
            AutoStartTarget("com.coloros.safecenter", "", action = "com.coloros.safecenter.startupapp")
        ),

        // ===== Realme - realme UI (基于ColorOS) =====
        "realme" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.securitycenter", "com.coloros.securitycenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.color.safecenter", "com.color.safecenter.permission.startup.StartupAppListActivity")
        ),

        // ===== OnePlus - 氢OS/OxygenOS =====
        // ColorOS 13+使用coloros/oplus包名，旧版氢OS使用oneplus.security
        "oneplus" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            AutoStartTarget("com.coloros.securitycenter", "com.coloros.securitycenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        ),

        // ===== Vivo/iQOO - OriginOS/FuntouchOS =====
        // 主要路径：BgStartUpManagerActivity
        // 新版OriginOS新增：VivoAutoLaunchManagerActivity
        "vivo" to listOf(
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            AutoStartTarget("com.vivo.abeui", "com.vivo.abeui.manager.VivoAutoLaunchManagerActivity"),
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
            // 国行优先
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.appmanagement.AppManagementActivity"),
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity"),
            AutoStartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"),
            // 国际版
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

        // ===== OPPO/OnePlus品牌名兜底 =====
        "oplus" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.securitycenter", "com.coloros.securitycenter.startupapp.StartupAppListActivity")
        ),
        "coloros" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            AutoStartTarget("com.coloros.securitycenter", "com.coloros.securitycenter.startupapp.StartupAppListActivity")
        )
    )

    // ==================== 后台弹出界面权限路径 ====================
    // 这个权限决定了Service能否在后台startActivity/显示悬浮窗

    private val VENDOR_BG_POPUP_TARGETS = mapOf(
        // ===== 小米/红米 =====
        "xiaomi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            AutoStartTarget("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        ),
        "redmi" to listOf(
            AutoStartTarget("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        ),

        // ===== OPPO/OnePlus/Realme =====
        // ColorOS没有独立的BgPopUpManager页面（网传的ui.bgpopup.BgPopUpManagerActivity不存在）
        // 正确策略：引导到权限隐私主页 / 悬浮窗权限页
        "oppo" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowPermissionActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        ),
        "oneplus" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        ),
        "realme" to listOf(
            AutoStartTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            AutoStartTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        ),

        // ===== Vivo/iQOO =====
        "vivo" to listOf(
            AutoStartTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                action = "secure.intent.action.softPermissionDetail"
            ),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.StartBgActivityControlActivity"),
            AutoStartTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
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
            AutoStartTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            AutoStartTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity")
        ),

        // ===== 三星 =====
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
     * Opens only the Settings home page. The user then follows Settings > Apps > Auto-start.
     * Do not replace this with app-management or vendor-specific intents: those skip the Apps parent page.
     */
    fun jumpToAutoStartSetting(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "AutoStart: opened Settings home")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open Settings home", e)
        }
    }

    /**
     * 跳转到后台弹出界面权限设置
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

        val manufacturer = Build.MANUFACTURER.lowercase().trim()
        val batteryTargets = mapOf(
            "xiaomi" to listOf(
                AutoStartTarget("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                AutoStartTarget("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
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

        jumpToAutoStartSetting(context)
    }

    // ==================== 内部工具方法 ====================

    private fun tryStartActivity(context: Context, target: AutoStartTarget): Boolean {
        return try {
            val intent = Intent()
            if (target.action != null) {
                intent.action = target.action
            }
            if (target.cls.isNotEmpty()) {
                intent.component = ComponentName(target.pkg, target.cls)
            } else if (target.pkg.isNotEmpty()) {
                // 有action无cls时设置package
                intent.`package` = target.pkg
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 传递包名extra（不同ROM读取不同key，全部带上）
            intent.putExtra("package_name", context.packageName)
            intent.putExtra("packagename", context.packageName)
            intent.putExtra("packageName", context.packageName)
            intent.putExtra("extra_pkgname", context.packageName)

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
