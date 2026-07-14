package com.lockai.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃处理器 - 捕获未处理异常，保存到文件，下次启动时可查看反馈
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 10

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            saveCrash(e)
        } catch (_: Exception) {
        }
        defaultHandler?.uncaughtException(t, e)
    }

    private fun saveCrash(e: Throwable) {
        try {
            val dir = File(appContext.filesDir, CRASH_DIR)
            if (!dir.exists()) dir.mkdirs()

            // 清理旧文件，保留最近10个
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }
            files?.drop(MAX_CRASH_FILES)?.forEach { it.delete() }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "crash_$timestamp.txt")

            val info = buildString {
                appendLine("=== LockAI Crash Report ===")
                appendLine("Time: $timestamp")
                appendLine("App version: ${getVersionName()} (${getVersionCode()})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Brand: ${Build.BRAND}")
                appendLine("Product: ${Build.PRODUCT}")
                appendLine()
                appendLine("=== Stack Trace ===")
                appendLine(getStackTraceString(e))

                // 记录cause链
                var cause = e.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    appendLine()
                    appendLine("=== Caused by ($depth) ===")
                    appendLine(getStackTraceString(cause))
                    cause = cause.cause
                    depth++
                }
            }

            file.writeText(info)
        } catch (_: Exception) {
        }
    }

    private fun getStackTraceString(e: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    fun hasUnreadCrashes(): Boolean {
        val dir = File(appContext.filesDir, CRASH_DIR)
        return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
    }

    fun getLatestCrash(): String? {
        val dir = File(appContext.filesDir, CRASH_DIR)
        if (!dir.exists()) return null
        val latest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
        return try {
            latest.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun clearCrashes() {
        val dir = File(appContext.filesDir, CRASH_DIR)
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun getVersionName(): String {
        return try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    @Suppress("DEPRECATION")
    private fun getVersionCode(): Long {
        return try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
            else pInfo.versionCode.toLong()
        } catch (_: Exception) { 0L }
    }
}
