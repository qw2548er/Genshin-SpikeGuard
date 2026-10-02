package com.spikeguard.framepacing

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.spikeguard.util.LogManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 执行层：帧节奏输出引擎 + 看门狗容错
 *
 * Android 无 Root 帧节奏控制原理：
 * MediaProjection 将屏幕画面渲染到一个 SurfaceTexture，
 * 我们通过控制 SurfaceTexture.updateTexImage() 的调用时机来实现帧延迟呈现。
 *
 * 关键：updateTexImage() 是 SurfaceTexture 的"呈现"动作。
 * 帧到达（onFrameAvailable）与 updateTexImage() 之间的时间差 = 调速延迟。
 *
 * 这相当于 DX12 的 IDXGISwapChain::Present Hook：
 * - onFrameAvailable = 游戏调用 Present
 * - updateTexImage() = 我们延迟后的实际呈现
 *
 * 不重排 GPU 命令缓冲区，不修改渲染内容，只改变帧呈现时机。
 *
 * 容错降级（看门狗机制，V3 修订版）：
 * 1. 独立监测线程生成心跳包，不依赖帧调用
 * 2. 连续 N 次（N=3~5）未收到心跳 → 判定逻辑卡死 → 直通旁路
 * 3. 直通模式下立即 updateTexImage()，不延迟，保证游戏画面不卡顿
 */
class PacingDisplayEngine(
    private val logManager: LogManager,
    private val governor: FramePacingGovernor
) : SurfaceTexture.OnFrameAvailableListener {

    // 输出 SurfaceTexture（MediaProjection 渲染目标）
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null

    // 输出线程（处理 updateTexImage 时序）
    private var outputThread: HandlerThread? = null
    private var outputHandler: Handler? = null

    // 看门狗线程
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogRunning = false

    // 心跳机制
    private val heartbeatCounter = AtomicInteger(0)
    private val missedHeartbeatCount = AtomicInteger(0)
    private val maxMissedHeartbeats = 4  // N=3~5，取4

    // 状态
    @Volatile private var running = false
    @Volatile private var passThroughMode = false  // 直通旁路模式
    @Volatile private var lastFrameTimestampNs = 0L
    @Volatile private var pendingFrameAvailable = false

    // 帧队列（仅在直通模式下处理延迟帧）
    private val maxPendingFrames = 3

    // 统计
    private var displayedFrames = 0L
    private var delayedFrames = 0L
    private var watchdogTriggers = 0L
    private var totalFrameTimeMs = 0f

    /**
     * 绑定输出 SurfaceTexture
     * MediaProjection 将通过此 SurfaceTexture 输出画面
     *
     * @param texture TextureView 的 SurfaceTexture
     */
    fun attachSurfaceTexture(texture: SurfaceTexture) {
        this.surfaceTexture = texture
        this.outputSurface = Surface(texture)
        texture.setOnFrameAvailableListener(this, outputHandler)
        logManager.i(TAG, "SurfaceTexture attached, output Surface created")
    }

    /**
     * 获取输出 Surface（供 MediaProjection VirtualDisplay 使用）
     */
    fun getOutputSurface(): Surface? = outputSurface

    /**
     * 启动输出引擎
     */
    fun start() {
        if (running) return

        outputThread = HandlerThread("PacingOutput").apply { start() }
        outputHandler = Handler(outputThread!!.looper)

        // 如果 SurfaceTexture 已绑定，重新设置监听器到输出线程
        surfaceTexture?.let {
            it.setOnFrameAvailableListener(this, outputHandler)
        }

        running = true
        passThroughMode = false
        lastFrameTimestampNs = 0L

        // 启动看门狗
        startWatchdog()

        logManager.i(TAG, "Pacing display engine started, watchdog N=$maxMissedHeartbeats")
    }

    /**
     * 停止输出引擎
     */
    fun stop() {
        running = false
        watchdogRunning = false

        try {
            watchdogThread?.join(1000)
        } catch (e: Exception) { /* 忽略 */ }
        watchdogThread = null

        outputHandler?.removeCallbacksAndMessages(null)

        try {
            outputThread?.quitSafely()
        } catch (e: Exception) { /* 忽略 */ }
        outputThread = null
        outputHandler = null

        // 解绑
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
        } catch (e: Exception) { /* 忽略 */ }

        logManager.i(TAG, "Pacing display engine stopped. " +
                "displayed=$displayedFrames, delayed=$delayedFrames, " +
                "watchdog_triggers=$watchdogTriggers")
    }

    /**
     * SurfaceTexture 帧可用回调
     *
     * 这是帧节奏控制的核心入口：
     * - 帧到达时记录时间戳，计算帧时间
     * - 通过 governor 计算应插入的延迟
     * - 延迟后调用 updateTexImage() 完成呈现
     */
    override fun onFrameAvailable(st: SurfaceTexture) {
        if (!running) return

        // 更新心跳（帧处理线程存活证明）
        heartbeatCounter.incrementAndGet()

        val now = System.nanoTime()
        val frameTimeMs = if (lastFrameTimestampNs > 0) {
            (now - lastFrameTimestampNs) / 1_000_000f
        } else {
            16.67f  // 初始帧假设60fps
        }
        lastFrameTimestampNs = now
        totalFrameTimeMs += frameTimeMs

        if (passThroughMode) {
            // 直通旁路：立即呈现，不调速
            presentFrame(st, frameTimeMs, 0f)
            return
        }

        // 调速：计算延迟
        val delayMs = governor.onFrame(frameTimeMs)

        if (delayMs <= 0.01f) {
            // 无需调速，立即呈现
            presentFrame(st, frameTimeMs, 0f)
        } else {
            // 延迟呈现：在输出线程上延时调用 updateTexImage
            delayedFrames++
            outputHandler?.postDelayed({
                if (running) {
                    if (passThroughMode) {
                        // 延迟期间切换到直通，立即呈现
                        presentFrame(st, frameTimeMs, 0f)
                    } else {
                        presentFrame(st, frameTimeMs, delayMs)
                    }
                }
            }, delayMs.toLong().coerceAtLeast(1L))
        }
    }

    /**
     * 呈现一帧：调用 updateTexImage()
     *
     * 这是 Android SurfaceTexture 的"Present"动作。
     * updateTexImage() 将最新的图像帧更新到 GL 纹理，
     * 绑定的 TextureView 会自动渲染。
     */
    private fun presentFrame(st: SurfaceTexture, frameTimeMs: Float, delayMs: Float) {
        try {
            st.updateTexImage()
            displayedFrames++

            if (delayMs > 0 && displayedFrames % 60 == 0L) {
                val avgFrameTime = totalFrameTimeMs / displayedFrames
                logManager.d(TAG, "Present: frameTime=${"%.2f".format(frameTimeMs)}ms, " +
                        "delay=${"%.3f".format(delayMs)}ms, " +
                        "avgFrameTime=${"%.2f".format(avgFrameTime)}ms, " +
                        "passThrough=$passThroughMode")
            }
        } catch (e: Exception) {
            logManager.e(TAG, "updateTexImage failed", e)
            // updateTexImage 失败不应该导致崩溃，忽略本帧
        }
    }

    // ==================== 看门狗机制 ====================

    /**
     * 启动看门狗线程
     *
     * V3 修订：废弃固定50ms超时，改用与帧率解耦的心跳机制
     * - 独立线程每500ms检查一次心跳
     * - 连续N=4次心跳未更新 → 逻辑卡死 → 直通旁路
     * - 单次计算耗时较长不属于卡死
     */
    private fun startWatchdog() {
        watchdogRunning = true
        watchdogThread = Thread({
            logManager.i(TAG, "Watchdog thread started")
            var lastHeartbeat = heartbeatCounter.get()

            while (watchdogRunning && running) {
                try {
                    Thread.sleep(500)

                    val currentHeartbeat = heartbeatCounter.get()
                    if (currentHeartbeat == lastHeartbeat) {
                        // 心跳未更新（没有新帧到达或处理线程阻塞）
                        val missed = missedHeartbeatCount.incrementAndGet()
                        logManager.w(TAG, "Watchdog: missed heartbeat $missed/$maxMissedHeartbeats")

                        if (missed >= maxMissedHeartbeats) {
                            triggerPassThrough("watchdog_missed_$missed")
                        }
                    } else {
                        // 心跳正常，重置计数
                        missedHeartbeatCount.set(0)
                        lastHeartbeat = currentHeartbeat
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logManager.e(TAG, "Watchdog error", e)
                }
            }
            logManager.i(TAG, "Watchdog thread exited")
        }, "PacingWatchdog").apply { isDaemon = true }

        watchdogThread?.start()
    }

    /**
     * 触发直通旁路模式
     * 所有帧立即 updateTexImage()，不延迟，保证画面不卡顿不崩溃
     */
    private fun triggerPassThrough(reason: String) {
        if (passThroughMode) return
        passThroughMode = true
        watchdogTriggers++
        missedHeartbeatCount.set(0)

        logManager.w(TAG, "=== PASS-THROUGH MODE TRIGGERED: $reason ===")
        logManager.w(TAG, "All frames presented immediately, no pacing applied")

        // 清空所有待延迟的帧回调，立即呈现
        outputHandler?.removeCallbacksAndMessages(null)
    }

    /**
     * 从直通模式恢复（需手动恢复，避免频繁切换）
     */
    fun recoverFromPassThrough() {
        if (!passThroughMode) return
        passThroughMode = false
        missedHeartbeatCount.set(0)
        lastFrameTimestampNs = 0L  // 重置帧时间基准
        logManager.i(TAG, "Recovered from pass-through mode")
    }

    // ==================== 状态查询 ====================

    fun isRunning(): Boolean = running
    fun isPassThrough(): Boolean = passThroughMode
    fun getDisplayedFrames(): Long = displayedFrames
    fun getDelayedFrames(): Long = delayedFrames
    fun getWatchdogTriggers(): Long = watchdogTriggers

    fun getStats(): Map<String, Any> = mapOf(
        "displayed_frames" to displayedFrames,
        "delayed_frames" to delayedFrames,
        "delay_ratio" to if (displayedFrames > 0) delayedFrames.toFloat() / displayedFrames else 0f,
        "avg_frame_time_ms" to if (displayedFrames > 0) totalFrameTimeMs / displayedFrames else 0f,
        "pass_through" to passThroughMode,
        "watchdog_triggers" to watchdogTriggers,
        "missed_heartbeats" to missedHeartbeatCount.get()
    )

    companion object {
        private const val TAG = "PacingDisplayEngine"
    }
}
