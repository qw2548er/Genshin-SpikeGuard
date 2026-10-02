package com.spikeguard.framepacing

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.spikeguard.util.LogManager

/**
 * Android 无 Root 帧采集层
 *
 * V3 架构：MediaProjection + VirtualDisplay + SurfaceTexture
 *
 * 核心改进（相比 ImageReader 方案）：
 * - VirtualDisplay 直接渲染到 SurfaceTexture（而非 ImageReader）
 * - 帧节奏控制由 PacingDisplayEngine 通过 updateTexImage() 时序实现
 * - 本模块只负责建立 MediaProjection → VirtualDisplay → Surface 管道
 *
 * 数据流：
 *   屏幕 → MediaProjection → VirtualDisplay → Surface(SurfaceTexture)
 *                                              ↓
 *                              PacingDisplayEngine.onFrameAvailable
 *                                              ↓
 *                              governor.onFrame → 计算延迟
 *                                              ↓
 *                              updateTexImage() → TextureView 呈现
 *
 * 前置声明：Android 12+ 无 Root 无法注入游戏进程，不能 Hook Swapchain。
 * 本方案通过 MediaProjection 在系统输出侧做帧节奏调节。
 */
class FrameCaptureEngine(
    private val context: Context,
    private val logManager: LogManager,
    private val outputSurfaceProvider: () -> Surface?
) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false

    // 屏幕尺寸
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var screenDensity = 320

    /**
     * 请求屏幕捕获权限 Intent
     */
    fun createScreenCaptureIntent(): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    /**
     * 用权限结果启动采集
     * 将屏幕画面渲染到 outputSurfaceProvider 提供的 Surface
     *
     * @param resultCode onActivityResult 的 resultCode
     * @param data onActivityResult 的 data Intent
     */
    fun startCaptureWithPermission(resultCode: Int, data: Intent) {
        if (running) {
            logManager.w(TAG, "Capture already running")
            return
        }

        val outputSurface = outputSurfaceProvider()
        if (outputSurface == null) {
            logManager.e(TAG, "Output surface is null, cannot start capture")
            return
        }

        try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                logManager.e(TAG, "Failed to get MediaProjection")
                return
            }

            // 获取屏幕尺寸
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            logManager.i(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")

            // 启动工作线程
            handlerThread = HandlerThread("FrameCapture").apply { start() }
            handler = Handler(handlerThread!!.looper)

            // 创建 VirtualDisplay，渲染到 SurfaceTexture Surface
            // 帧节奏控制由 PacingDisplayEngine.onFrameAvailable 处理
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PseudoCerebellumCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                outputSurface,
                null,
                handler
            )

            running = true
            logManager.i(TAG, "Frame capture started: ${screenWidth}x${screenHeight} -> SurfaceTexture")

        } catch (e: Exception) {
            logManager.e(TAG, "Failed to start capture", e)
            release()
        }
    }

    /**
     * 停止采集
     */
    fun stopCapture() {
        if (!running) return
        running = false
        logManager.i(TAG, "Stopping frame capture")

        handler?.post {
            release()
        }
    }

    /**
     * 释放资源
     */
    private fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { /* 忽略 */ }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) { /* 忽略 */ }
        mediaProjection = null

        try {
            handlerThread?.quitSafely()
        } catch (e: Exception) { /* 忽略 */ }
        handlerThread = null
        handler = null

        logManager.i(TAG, "Capture resources released")
    }

    fun isRunning(): Boolean = running

    companion object {
        private const val TAG = "FrameCaptureEngine"
    }
}
