package com.spikeguard.framepacing

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.spikeguard.util.LogManager

/**
 * Android 无 Root 帧采集层
 *
 * 前置声明（V3 文档第四部分）：
 * Android 12+，无 Root 环境下，无法向非 debuggable 第三方游戏进程注入外部代码，
 * 不能直接 Hook 游戏 Swapchain。
 *
 * 唯一可行无 Root 方案：
 * MediaProjection 画面捕获 + VirtualDisplay + ImageReader 捕获游戏画面，
 * 在系统 Overlay 层完成帧节奏调节，TextureView 输出画面。
 *
 * 特点：仅在画面输出侧做节流，无法进入游戏进程内部渲染管线干预提交。
 *
 * 采集指标：
 * - 帧时间：ImageReader.OnImageAvailableListener 回调间隔
 * - GPU 利用率：复用现有 GpuFrameCollector（sysfs）
 * - 提交队列深度：通过帧到达频率估算
 *
 * 采样间隔：10ms（帧时间测量由 ImageReader 回调驱动，非轮询）
 */
class FrameCaptureEngine(
    private val context: Context,
    private val logManager: LogManager,
    private val onFrameCaptured: (frameTimeMs: Float, timestampNs: Long) -> Unit
) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false
    @Volatile private var lastFrameTimestampNs = 0L

    // 屏幕尺寸
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var screenDensity = 320

    // 统计
    private var frameCount = 0L
    private var captureStarted = false

    /**
     * 请求屏幕捕获权限
     * 调用方需在 onActivityResult 中调用 startCaptureWithPermission
     *
     * @return MediaProjectionManager.createScreenCaptureIntent() 的 Intent
     */
    fun createScreenCaptureIntent(): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    /**
     * 用权限结果启动采集
     *
     * @param resultCode onActivityResult 的 resultCode
     * @param data onActivityResult 的 data Intent
     */
    fun startCaptureWithPermission(resultCode: Int, data: Intent) {
        if (running) {
            logManager.w(TAG, "Capture already running")
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

            // 创建 ImageReader
            // 使用 RGB_565 降低内存开销，RGBA_8888 质量更高但占用大
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                3  // 最大缓冲帧数
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!running) return@setOnImageAvailableListener

                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val now = System.nanoTime()
                        if (lastFrameTimestampNs > 0) {
                            val frameTimeMs = (now - lastFrameTimestampNs) / 1_000_000f
                            onFrameCaptured(frameTimeMs, now)
                        }
                        lastFrameTimestampNs = now
                        frameCount++
                    }
                } catch (e: Exception) {
                    logManager.e(TAG, "Image acquire error", e)
                } finally {
                    // 必须关闭image，否则缓冲区耗尽
                    image?.close()
                }
            }, handler)

            // 创建 VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PseudoCerebellumCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            running = true
            captureStarted = true
            logManager.i(TAG, "Frame capture started: ${screenWidth}x${screenHeight}")

        } catch (e: Exception) {
            logManager.e(TAG, "Failed to start capture", e)
            release()
        }
    }

    /**
     * 停止采集
     */
    fun stopCapture() {
        if (!running && !captureStarted) return
        running = false
        logManager.i(TAG, "Stopping frame capture, total frames=$frameCount")

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
            imageReader?.close()
        } catch (e: Exception) { /* 忽略 */ }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) { /* 忽略 */ }
        mediaProjection = null

        try {
            handlerThread?.quitSafely()
        } catch (e: Exception) { /* 忽略 */ }
        handlerThread = null
        handler = null

        lastFrameTimestampNs = 0L
        captureStarted = false
        logManager.i(TAG, "Capture resources released")
    }

    fun isRunning(): Boolean = running
    fun getFrameCount(): Long = frameCount

    companion object {
        private const val TAG = "FrameCaptureEngine"
    }
}
