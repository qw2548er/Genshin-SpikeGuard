package com.spikeguard.framepacing

import android.content.Context
import android.content.Intent
import com.spikeguard.util.LogManager

/**
 * 帧级自适应调速器（Frame Pacing Governor）— 主管理器 V3
 *
 * 3层架构完整串联：
 *
 *   屏幕画面
 *      ↓ MediaProjection
 *   FrameCaptureEngine（采集层）
 *      ↓ VirtualDisplay → Surface
 *   PacingDisplayEngine（执行层）← SurfaceTexture
 *      ↓ onFrameAvailable → 延迟
 *   FramePacingGovernor（决策层）
 *      ↓ updateTexImage()
 *   PacingOverlayView（TextureView 全屏呈现）
 *
 * 核心原理：
 * - MediaProjection 将屏幕渲染到 SurfaceTexture
 * - SurfaceTexture.onFrameAvailable 触发时，通过 governor 计算延迟
 * - 延迟后调用 updateTexImage()，帧才真正呈现在 TextureView 上
 * - 帧到达与呈现之间的时间差 = 调速延迟
 *
 * 不重排 GPU 命令缓冲区，不修改渲染内容，只控制帧呈现时机。
 */
class FramePacingManager(private val context: Context) {

    private val logManager = LogManager.getInstance(context)

    // 三层模块
    private var captureEngine: FrameCaptureEngine? = null
    private var governor: FramePacingGovernor? = null
    private var displayEngine: PacingDisplayEngine? = null
    private var overlayView: PacingOverlayView? = null

    @Volatile private var running = false
    @Volatile private var enabled = false

    // 待启动的权限结果（等待 SurfaceTexture 就绪）
    private var pendingPermission: Pair<Int, Intent>? = null

    /**
     * 请求屏幕捕获权限 Intent
     */
    fun requestCapturePermission(): Intent? {
        if (captureEngine == null) {
            captureEngine = FrameCaptureEngine(context, logManager) {
                displayEngine?.getOutputSurface()
            }
        }
        return captureEngine?.createScreenCaptureIntent()
    }

    /**
     * 用权限结果启动调速器
     *
     * 启动流程：
     * 1. 显示全屏 Overlay（TextureView）
     * 2. SurfaceTexture 就绪 → 绑定到 PacingDisplayEngine
     * 3. 启动 PacingDisplayEngine（决策层+执行层）
     * 4. 启动 FrameCaptureEngine（MediaProjection → Surface）
     */
    fun startWithPermission(resultCode: Int, data: Intent) {
        if (running) {
            logManager.w(TAG, "Frame pacing already running")
            return
        }
        if (!enabled) {
            logManager.w(TAG, "Frame pacing disabled by master switch")
            return
        }

        logManager.i(TAG, "Starting frame pacing governor V3...")

        // 初始化决策层 + 执行层
        governor = FramePacingGovernor(logManager)
        governor?.setPacingEnabled(true)
        // 应用低延迟模式
        governor?.setLowLatencyMode(isLowLatencyMode)

        displayEngine = PacingDisplayEngine(logManager, governor!!)

        // 保存权限结果，等 SurfaceTexture 就绪后再启动采集
        pendingPermission = Pair(resultCode, data)

        // 显示 Overlay，SurfaceTexture 就绪后回调
        overlayView = PacingOverlayView(context)
        overlayView?.show { surfaceTexture ->
            logManager.i(TAG, "SurfaceTexture ready, wiring up pipeline...")

            // 绑定 SurfaceTexture 到执行层
            displayEngine?.attachSurfaceTexture(surfaceTexture)
            // 启动执行层（含看门狗）
            displayEngine?.start()

            // 启动采集层（MediaProjection → SurfaceTexture Surface）
            pendingPermission?.let { (rc, dt) ->
                captureEngine?.startCaptureWithPermission(rc, dt)
            }
            pendingPermission = null
        }

        running = true
        logManager.i(TAG, "Frame pacing governor V3 pipeline started")
    }

    /**
     * 停止调速器
     */
    fun stop() {
        if (!running) return
        logManager.i(TAG, "Stopping frame pacing governor...")

        // 逆序停止：采集→执行→决策→Overlay
        captureEngine?.stopCapture()
        displayEngine?.stop()
        governor?.setPacingEnabled(false)
        overlayView?.hide()

        captureEngine = null
        displayEngine = null
        governor = null
        overlayView = null
        pendingPermission = null

        running = false
        logManager.i(TAG, "Frame pacing governor stopped")
    }

    // ==================== 配置接口 ====================

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled && running) {
            stop()
        }
        logManager.i(TAG, "Frame pacing master switch: $enabled")
    }

    fun isEnabled(): Boolean = enabled
    fun isRunning(): Boolean = running

    /**
     * 设置最大延迟上限
     */
    fun setMaxLatency(maxMs: Float) {
        governor?.setMaxLatency(maxMs)
    }

    /**
     * 切换低延迟模式（0.5ms上限）
     */
    fun setLowLatencyMode(enabled: Boolean) {
        isLowLatencyMode = enabled
        governor?.setLowLatencyMode(enabled)
    }

    private var isLowLatencyMode = false

    /**
     * 重置调速器
     */
    fun reset() {
        governor?.reset()
        logManager.i(TAG, "Frame pacing governor reset to baseline")
    }

    /**
     * 从直通模式恢复
     */
    fun recoverFromPassThrough() {
        displayEngine?.recoverFromPassThrough()
    }

    // ==================== 状态查询 ====================

    fun getStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>(
            "running" to running,
            "enabled" to enabled,
            "overlay_showing" to (overlayView?.isShowing() ?: false)
        )
        governor?.let { stats.putAll(it.getStats()) }
        displayEngine?.let { stats.putAll(it.getStats()) }
        return stats
    }

    companion object {
        private const val TAG = "FramePacingManager"

        @Volatile
        private var instance: FramePacingManager? = null

        fun getInstance(context: Context): FramePacingManager {
            return instance ?: synchronized(this) {
                instance ?: FramePacingManager(context.applicationContext).also { instance = it }
            }
        }

        fun destroy() {
            instance?.stop()
            instance = null
        }
    }
}
