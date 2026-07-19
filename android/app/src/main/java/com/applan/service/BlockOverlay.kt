package com.applan.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.applan.MainActivity

/**
 * AppBlock式全局遮罩拦截器
 *
 * 核心原理：使用TYPE_APPLICATION_OVERLAY在所有App之上显示一个全屏遮罩
 * - 遮罩拦截所有触摸/按键事件，用户无法操作底层App
 * - 遮罩上只有一个"返回applan"按钮，必须点击才能返回
 * - 毫秒级显示，没有Activity启动的延迟和闪屏
 * - 用户点击按钮后才拉起MainActivity，避免闪屏
 * - Activity到前台后延迟300ms自动隐藏遮罩（等渲染完成）
 */
object BlockOverlay {

    private const val TAG = "BlockOverlay"
    private const val HIDE_DELAY_MS = 300L // Activity到前台后延迟隐藏遮罩，避免闪屏

    @Volatile
    private var isShowing = false

    @Volatile
    private var lastShowTime = 0L // 上次show的时间戳，用于防止遮罩自身的a11y事件导致闪屏

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    /**
     * 遮罩是否刚显示（在指定时间窗口内）
     * 用于防止遮罩自身的TYPE_WINDOW_STATE_CHANGED事件被误判为Activity回到前台
     */
    fun wasJustShown(withinMs: Long = 1500L): Boolean {
        return isShowing && (System.currentTimeMillis() - lastShowTime < withinMs)
    }

    /**
     * 显示全局拦截遮罩（毫秒级响应）
     * 只显示遮罩，不自动拉起Activity - 用户必须点击按钮才返回
     */
    fun show(context: Context) {
        if (isShowing) {
            // 已经在显示，不需要重复操作
            Log.d(TAG, "Overlay already showing, skip")
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission, falling back to startActivity")
            launchActivity(context)
            return
        }

        // 取消之前的hideRunnable（防止快速show/hide竞态）
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            // 创建全屏遮罩布局
            val layout = createOverlayView(context)

            // WindowManager参数 - 全屏覆盖所有内容
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 不获取焦点，避免抢占输入法
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            wm.addView(layout, params)
            overlayView = layout
            isShowing = true
            lastShowTime = System.currentTimeMillis()
            Log.d(TAG, "Block overlay SHOWN - waiting for user to tap return button")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            // 失败降级：直接启动Activity
            launchActivity(context)
        }
    }

    /**
     * 隐藏遮罩（MainActivity到前台时调用，延迟300ms避免闪屏）
     */
    fun hide() {
        if (!isShowing) return
        // 取消之前的延迟隐藏
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = Runnable {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide overlay", e)
            } finally {
                overlayView = null
                windowManager = null
                isShowing = false
                hideRunnable = null
                Log.d(TAG, "Block overlay HIDDEN")
            }
        }
        mainHandler.postDelayed(hideRunnable!!, HIDE_DELAY_MS)
    }

    /**
     * 立即隐藏遮罩（不延迟），用于exit_app/紧急解锁放行时
     */
    fun hideImmediately() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!isShowing) return
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay immediately", e)
        } finally {
            overlayView = null
            windowManager = null
            isShowing = false
            hideRunnable = null
            Log.d(TAG, "Block overlay HIDDEN immediately")
        }
    }

    fun isShowing(): Boolean = isShowing

    /**
     * 创建遮罩View - 黑色半透明背景 + 锁图标 + 提示文字 + 返回按钮
     */
    private fun createOverlayView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val pad16 = (16 * density).toInt()
        val pad24 = (24 * density).toInt()
        val pad32 = (32 * density).toInt()
        val baseTextSize = 18f
        val btnTextSize = 16f

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2000000")) // 95%黑，几乎不透明
            setPadding(pad32, pad32, pad32, pad32)

            // 拦截所有触摸事件（包括按钮区域外的触摸）
            setOnTouchListener { _, _ -> true }
        }

        // 锁图标
        val lockIcon = TextView(context).apply {
            text = "🔒"
            this.textSize = 56f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad24)
        }
        root.addView(lockIcon)

        // 标题
        val titleText = TextView(context).apply {
            text = "applan 守护中"
            setTextColor(Color.WHITE)
            this.textSize = baseTextSize + 6
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad16)
            paint?.isFakeBoldText = true
        }
        root.addView(titleText)

        // 提示文字
        val descText = TextView(context).apply {
            text = "你的注意力防线已激活\n请完成当前任务后通过AI对话放行\n或点击下方按钮返回 applan"
            setTextColor(Color.parseColor("#CCFFFFFF")) // 80%白
            this.textSize = baseTextSize - 2
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad32)
            setLineSpacing(6f * density, 1.3f)
        }
        root.addView(descText)

        // 返回按钮 - 点击后才拉起MainActivity
        val backBtn = Button(context).apply {
            text = "返回 applan"
            this.textSize = btnTextSize
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF1A73E8"))
            setPadding(pad32, pad16, pad32, pad16)
            isAllCaps = false
            elevation = 4 * density
            setOnClickListener {
                Log.d(TAG, "User tapped return button, launching MainActivity")
                launchActivity(context)
            }
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(backBtn, btnParams)

        return root
    }

    /**
     * 拉起MainActivity到前台
     */
    private fun launchActivity(context: Context) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity", e)
        }
    }
}
