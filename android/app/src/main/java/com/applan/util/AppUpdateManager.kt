package com.applan.util

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.applan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内自动更新管理器
 *
 * 版本检查协议（在服务器放一个version.json）：
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "apkUrl": "http://129.204.200.38:8787/applan-latest.apk",
 *   "releaseNotes": "修复防划掉问题,优化无障碍拦截"
 * }
 *
 * 服务器端：把编译好的APK放到applan 服务器的static文件目录即可
 */
object AppUpdateManager {

    private const val TAG = "AppUpdate"
    private const val VERSION_CHECK_URL = "${BuildConfig.SERVER_URL}/version.json"
    private const val APK_FILENAME = "applan-update.apk"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    /**
     * 检查更新
     * @return UpdateInfo if update available, null if already latest
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(VERSION_CHECK_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Version check HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val remoteVersionCode = json.getInt("versionCode")
            val currentVersionCode = BuildConfig.VERSION_CODE

            if (remoteVersionCode > currentVersionCode) {
                UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.getString("apkUrl"),
                    releaseNotes = json.optString("releaseNotes", "")
                )
            } else {
                Log.d(TAG, "Already latest version ($currentVersionCode)")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * 下载并安装APK
     */
    suspend fun downloadAndInstall(activity: Activity, updateInfo: UpdateInfo) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("正在更新")
            setMessage("下载中...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(updateInfo.apkUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(activity, "下载失败: HTTP ${response.code}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(activity, "下载失败: 响应体为空",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext
                }
                val contentLength = body.contentLength()
                val apkFile = File(activity.cacheDir, "apk/$APK_FILENAME")
                apkFile.parentFile?.mkdirs()
                if (apkFile.exists()) apkFile.delete()

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            val progress = if (contentLength > 0) {
                                (totalBytes * 100 / contentLength).toInt()
                            } else 0
                            withContext(Dispatchers.Main) {
                                progressDialog.progress = progress
                                progressDialog.setMessage("下载中... $progress%")
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("安装中...")
                    installApk(activity, apkFile)
                    progressDialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(activity, "更新失败: ${e.message}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 获取当前版本号
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            @Suppress("DEPRECATION")
            pInfo.versionCode
        } catch (_: Exception) {
            BuildConfig.VERSION_CODE
        }
    }
}
