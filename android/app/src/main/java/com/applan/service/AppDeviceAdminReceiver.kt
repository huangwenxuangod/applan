package com.applan.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器
 * 启用后：用户无法直接卸载App，必须先在设置中取消设备管理员
 * 这是AppBlock等应用使用的防卸载方案
 *
 * 用户必须手动在 设置→安全→设备管理员 中启用applan
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled - uninstall blocked")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled - app can now be uninstalled")
    }
}
