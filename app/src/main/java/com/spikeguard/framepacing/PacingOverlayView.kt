package com.spikeguard.framepacing

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.spikeguard.util.LogManager

/**
 * 帧调速全屏 Overlay 承载层
 *
 * 使用 TextureView 承载 MediaProjection 捕获并调速后的画面。
 *
 * Android 无 Root 帧节奏输出方案：
 * 1. 创建全屏 WindowManager Overlay（TYPE_APPLICATION_OVERLAY）
 * 2. Overlay 内放一个 TextureView
 * 3. TextureView 的 SurfaceTexture 作为 VirtualDisplay 输出目标
 * 4. PacingDisplayEngine 通过控制 updateTexImage() 时序实现帧延迟
 *
 * 注意：Overlay 覆盖在游戏画面之上，用户看到的是经过调速的画面。
 * 这是无 Root 环境下唯一可行的帧节奏控制方式。
 */
class PacingOverlayView(private val context: Context) {

    private val logManager = LogManager.getInstance(context)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile private var showing = false
    private var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    /**
     * 显示全屏 Overlay
     *
     * @param surfaceReadyCallback SurfaceTexture 就绪回调，用于绑定到 PacingDisplayEngine
     */
    fun show(surfaceReadyCallback: (SurfaceTexture) -> Unit) {
        if (showing) {
            logManager.w(TAG, "Overlay already showing")
            return
        }

        this.onSurfaceTextureReady = surfaceReadyCallback

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 创建全屏容器
            val container = FrameLayout(context)

            // 创建 TextureView
            textureView = TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        logManager.i(TAG, "SurfaceTexture available: ${width}x${height}")
                        onSurfaceTextureReady?.invoke(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        logManager.d(TAG, "SurfaceTexture size changed: ${width}x${height}")
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        logManager.i(TAG, "SurfaceTexture destroyed")
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        // 帧更新时触发（updateTexImage后）
                    }
                }
            }

            container.addView(
                textureView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // 全屏窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            overlayView = container
            layoutParams = params

            windowManager?.addView(container, params)
            showing = true

            logManager.i(TAG, "Pacing overlay shown (fullscreen, touch passthrough)")

        } catch (e: Exception) {
            logManager.e(TAG, "Failed to show overlay", e)
        }
    }

    /**
     * 隐藏 Overlay
     */
    fun hide() {
        if (!showing) return
        showing = false

        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to remove overlay", e)
        }

        overlayView = null
        textureView = null
        onSurfaceTextureReady = null

        logManager.i(TAG, "Pacing overlay hidden")
    }

    fun isShowing(): Boolean = showing
    fun getTextureView(): TextureView? = textureView

    companion object {
        private const val TAG = "PacingOverlayView"
    }
}
