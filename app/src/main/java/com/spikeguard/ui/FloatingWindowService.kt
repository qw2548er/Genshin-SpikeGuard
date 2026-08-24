package com.spikeguard.ui

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.spikeguard.R
import com.spikeguard.core.UiStateBridge
import kotlin.math.abs

/**
 * 悬浮窗服务
 *
 * 功能：
 * 1. 小悬浮球显示当前模式、GPU尖峰次数、防护触发次数
 * 2. 可拖拽移动
 * 3. 支持最小化
 * 4. 支持透明度调节
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingBall: View? = null
    private var minimized = false
    private var alpha = 0.9f

    private lateinit var uiStateReceiver: BroadcastReceiver

    // 拖拽相关
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    // UI 组件
    private var tvMode: TextView? = null
    private var tvSpikes: TextView? = null
    private var tvProtections: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingBall()
        registerUiStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(uiStateReceiver)
        } catch (e: Exception) {
            // 忽略
        }
        removeFloatingBall()
    }

    /**
     * 创建悬浮球
     */
    private fun createFloatingBall() {
        val layoutParams = WindowManager.LayoutParams(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 200

        floatingBall = createBallView()
        floatingBall?.alpha = alpha

        try {
            windowManager.addView(floatingBall, layoutParams)
        } catch (e: Exception) {
            // 权限不足等情况
            stopSelf()
        }
    }

    /**
     * 创建悬浮球视图
     */
    private fun createBallView(): View {
        val context = this

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setBackgroundResource(R.drawable.floating_ball_bg)
        }

        // 模式文本
        tvMode = TextView(context).apply {
            text = "日志模式"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(resources.getColor(R.color.white, null))
            paint.isFakeBoldText = true
        }
        container.addView(tvMode)

        // 尖峰次数
        tvSpikes = TextView(context).apply {
            text = "尖峰: 0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(resources.getColor(R.color.white, null))
        }
        container.addView(tvSpikes)

        // 保护次数
        tvProtections = TextView(context).apply {
            text = "防护: 0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(resources.getColor(R.color.white, null))
        }
        container.addView(tvProtections)

        // 拖拽处理
        container.setOnTouchListener { view, event ->
            handleTouch(event, view)
            true
        }

        // 点击切换最小化
        container.setOnClickListener {
            if (!isDragging) {
                toggleMinimize()
            }
        }

        return container
    }

    /**
     * 处理触摸事件
     */
    private fun handleTouch(event: MotionEvent, view: View) {
        val params = view.layoutParams as WindowManager.LayoutParams

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchX = event.rawX
                touchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY

                if (abs(dx) > 5 || abs(dy) > 5) {
                    isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // 忽略
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // 吸附到屏幕边缘
                val screenWidth = resources.displayMetrics.widthPixels
                if (params.x < screenWidth / 2) {
                    params.x = 0
                } else {
                    params.x = screenWidth - view.width
                }

                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }

    /**
     * 切换最小化状态
     */
    private fun toggleMinimize() {
        minimized = !minimized
        val ball = floatingBall ?: return

        if (minimized) {
            // 最小化：只显示一个小点
            tvSpikes?.visibility = View.GONE
            tvProtections?.visibility = View.GONE
            tvMode?.text = "●"
            tvMode?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        } else {
            // 恢复
            tvSpikes?.visibility = View.VISIBLE
            tvProtections?.visibility = View.VISIBLE
            tvMode?.text = if (tvMode?.text == "●") "日志模式" else tvMode?.text
            tvMode?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
    }

    /**
     * 注册跨进程UI状态广播接收器
     */
    private fun registerUiStateReceiver() {
        uiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    UiStateBridge.ACTION_UI_STATE_UPDATE -> {
                        val extras = intent.extras ?: return
                        val gpuSpikes = extras.getInt("gpu_spikes", 0)
                        val totalProtections = extras.getInt("total_protections", 0)
                        val silentMode = extras.getBoolean("silent_mode", false)

                        tvSpikes?.text = "尖峰: $gpuSpikes"
                        tvProtections?.text = "防护: $totalProtections"

                        if (silentMode) {
                            tvMode?.text = "静默中"
                        }
                    }
                    UiStateBridge.ACTION_PROTECTION_EVENT -> {
                        val eventType = intent.getStringExtra("event_type")
                        val logOnly = intent.getBooleanExtra("log_only", false)
                        when (eventType) {
                            "triggered" -> {
                                tvMode?.text = if (logOnly) "日志模式" else "保护中"
                            }
                            "released" -> {
                                tvMode?.text = "监控中"
                            }
                        }
                    }
                    UiStateBridge.ACTION_MODE_CHANGED -> {
                        val runMode = intent.getStringExtra("run_mode") ?: "LOG_ONLY"
                        tvMode?.text = if (runMode == "FULL_PROTECT") "完整防护" else "日志模式"
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UiStateBridge.ACTION_UI_STATE_UPDATE)
            addAction(UiStateBridge.ACTION_PROTECTION_EVENT)
            addAction(UiStateBridge.ACTION_MODE_CHANGED)
            addAction(UiStateBridge.ACTION_SILENT_MODE_CHANGED)
        }
        // Android 13+ 必须指定 exported 标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiStateReceiver, filter)
        }
    }

    /**
     * 移除悬浮球
     */
    private fun removeFloatingBall() {
        try {
            floatingBall?.let {
                windowManager.removeView(it)
            }
            floatingBall = null
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
