package com.applan.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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
 * - 遮罩上只有一个"返回applan"按钮
 * - 毫秒级显示，没有Activity启动的延迟和闪屏
 * - Activity到前台后自动隐藏遮罩
 */
object BlockOverlay {

    private const val TAG = "BlockOverlay"

    @Volatile
    private var isShowing = false

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    /**
     * 显示全局拦截遮罩（毫秒级响应）
     * 同时后台拉起MainActivity
     */
    fun show(context: Context) {
        if (isShowing) {
            // 已经在显示，直接尝试拉起Activity即可
            launchActivity(context)
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission, falling back to startActivity only")
            launchActivity(context)
            return
        }

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 高优先级，覆盖在一切之上
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // TYPE_APPLICATION_OVERLAY已经是最高层级的应用窗口
                }
            }

            // FLAG_NOT_FOCUSABLE会导致back键不被拦截，需要去掉
            // 但为了让按钮能点击，我们需要处理焦点
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND

            wm.addView(layout, params)
            overlayView = layout
            isShowing = true
            Log.d(TAG, "Block overlay SHOWN - instant block activated")

            // 后台拉起MainActivity
            launchActivity(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            // 失败降级：直接启动Activity
            launchActivity(context)
        }
    }

    /**
     * 隐藏遮罩（MainActivity到前台时调用）
     */
    fun hide() {
        if (!isShowing) return
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
            Log.d(TAG, "Block overlay HIDDEN")
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
            setBackgroundColor(Color.parseColor("#E6000000")) // 90%黑
            setPadding(pad32, pad32, pad32, pad32)

            // 拦截所有触摸事件
            setOnTouchListener { _, _ -> true }
        }

        // 锁图标（用文字emoji代替，避免资源依赖）
        val lockIcon = TextView(context).apply {
            text = "🔒"
            this.textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad16)
        }
        root.addView(lockIcon)

        // 标题
        val titleText = TextView(context).apply {
            text = "applan 守护中"
            setTextColor(Color.WHITE)
            this.textSize = baseTextSize + 4
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad16)
            paint?.isFakeBoldText = true
        }
        root.addView(titleText)

        // 提示文字
        val descText = TextView(context).apply {
            text = "你的注意力防线已激活\n完成任务后通过AI放行，或点击下方按钮返回"
            setTextColor(Color.parseColor("#B3FFFFFF")) // 70%白
            this.textSize = baseTextSize - 2
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad32)
            setLineSpacing(6f * density, 1.2f)
        }
        root.addView(descText)

        // 返回按钮
        val backBtn = Button(context).apply {
            text = "返回 applan"
            this.textSize = btnTextSize
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF1A73E8"))
            setPadding(pad24, pad16, pad24, pad16)
            isAllCaps = false
            setOnClickListener {
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
