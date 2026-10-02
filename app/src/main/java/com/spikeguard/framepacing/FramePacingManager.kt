package com.spikeguard.framepacing

import android.content.Context
import android.content.Intent
import com.spikeguard.util.LogManager

/**
 * 帧级自适应调速器（Frame Pacing Governor）— 主管理器
 *
 * 项目代号：伪小脑 V3
 *
 * 3层架构（V3 文档第二部分）：
 * 1. 采集层：FrameCaptureEngine（MediaProjection + VirtualDisplay + ImageReader）
 * 2. 决策层：FramePacingGovernor（双路 EWMA + 尖峰判定 + 延迟计算）
 * 3. 执行层：PacingDisplayEngine（TextureView 输出 + 看门狗容错）
 *
 * 数据流向：
 *   采集层 → 帧时间数据 → 决策层（EWMA计算延迟）→ 执行层（延迟输出）
 *
 * 核心目标（V3 修订）：
 * 在不改变渲染内容的前提下，平滑帧的呈现节奏，降低帧时间方差。
 * 插入延迟仅改变画面呈现时机，不会增删画面内容。
 *
 * 明确非目标（V3 第六部分）：
 * 1. 不尝试重排 GPU 命令缓冲区
 * 2. 不在竞技网络游戏使用
 * 3. 不复用 DLSS-NR-on-AMD 任何代码
 *
 * Android 平台限制（V3 第四部分）：
 * Android 12+ 无 Root 环境下，无法注入游戏进程，不能 Hook Swapchain。
 * 本方案通过 MediaProjection 画面捕获在系统 Overlay 层完成帧节奏调节。
 */
class FramePacingManager(private val context: Context) {

    private val logManager = LogManager.getInstance(context)

    // 三层模块
    private var captureEngine: FrameCaptureEngine? = null
    private var governor: FramePacingGovernor? = null
    private var displayEngine: PacingDisplayEngine? = null

    @Volatile private var running = false
    @Volatile private var enabled = false

    /**
     * 请求屏幕捕获权限 Intent
     * 调用方在 Activity 中使用 startActivityForResult 启动
     */
    fun requestCapturePermission(): Intent? {
        if (captureEngine == null) {
            captureEngine = FrameCaptureEngine(context, logManager, ::onFrameCaptured)
        }
        return captureEngine?.createScreenCaptureIntent()
    }

    /**
     * 用权限结果启动调速器
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

        // 初始化决策层
        governor = FramePacingGovernor(logManager)
        governor?.setPacingEnabled(true)

        // 初始化执行层
        displayEngine = PacingDisplayEngine(logManager, governor!!) { null }
        displayEngine?.start()

        // 初始化采集层（最后启动，避免丢失帧）
        if (captureEngine == null) {
            captureEngine = FrameCaptureEngine(context, logManager, ::onFrameCaptured)
        }
        captureEngine?.startCaptureWithPermission(resultCode, data)

        running = true
        logManager.i(TAG, "Frame pacing governor V3 started successfully")
    }

    /**
     * 停止调速器
     */
    fun stop() {
        if (!running) return
        logManager.i(TAG, "Stopping frame pacing governor...")

        // 按依赖关系逆序停止：采集→执行→决策
        captureEngine?.stopCapture()
        displayEngine?.stop()
        governor?.setPacingEnabled(false)

        captureEngine = null
        displayEngine = null
        governor = null

        running = false
        logManager.i(TAG, "Frame pacing governor stopped")
    }

    /**
     * 帧捕获回调：采集层 → 决策层 → 执行层
     */
    private fun onFrameCaptured(frameTimeMs: Float, timestampNs: Long) {
        if (!running) return

        // 决策层计算延迟
        val delayMs = governor?.onFrame(frameTimeMs) ?: 0f

        // 执行层输出
        displayEngine?.submitFrame(frameTimeMs, timestampNs)
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
        governor?.setLowLatencyMode(enabled)
    }

    /**
     * 重置调速器（基线参数重置）
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
            "enabled" to enabled
        )
        governor?.let { stats.putAll(it.getStats()) }
        displayEngine?.let { stats.putAll(it.getStats()) }
        captureEngine?.let { stats["captured_frames"] = it.getFrameCount() }
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
