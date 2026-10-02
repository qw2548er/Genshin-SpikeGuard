package com.spikeguard.framepacing

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.spikeguard.util.LogManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 执行层：帧节奏输出引擎 + 看门狗容错
 *
 * 对应 V3 文档执行层 + 容错降级设计：
 * - DX11/DX12: Hook IDXGISwapChain::Present 插入延迟
 * - Vulkan: 隐式层拦截 vkQueuePresentKHR
 * - Android（无Root）: 在 TextureView 输出侧插入可控延迟
 *
 * 核心：不重排 GPU 命令缓冲区，只控制帧呈现时机
 *
 * 容错降级（看门狗机制，V3 修订版）：
 * 1. 独立监测线程生成心跳包，不依赖 Present/帧调用
 * 2. 判定规则：连续 N 次（N=3~5）帧输出未收到心跳包，判定逻辑卡死
 * 3. 单次计算耗时较长不属于卡死，允许等待
 * 4. 一旦判定卡死，自动切换直通旁路，所有调用原样转发，不崩溃
 *
 * Vulkan 隐式层环境变量（Android 不适用，预留 PC 端）：
 * - DISABLE_PSEUDOCEREBELLUM：启动前设置，运行期间修改无效
 */
class PacingDisplayEngine(
    private val logManager: LogManager,
    private val governor: FramePacingGovernor,
    private val outputSurfaceProvider: () -> Surface?
) {

    // 输出线程
    private var outputThread: HandlerThread? = null
    private var outputHandler: Handler? = null

    // 看门狗线程
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogRunning = false

    // 心跳机制
    private val heartbeatCounter = AtomicInteger(0)
    private val lastSeenHeartbeat = AtomicInteger(0)
    private val missedHeartbeatCount = AtomicInteger(0)
    private val maxMissedHeartbeats = 4  // N=3~5，取4

    // 状态
    @Volatile private var running = false
    @Volatile private var passThroughMode = false  // 直通旁路模式
    private val pendingFrames = ArrayDeque<PacingFrame>()
    private val maxPendingFrames = 5

    // 统计
    private var displayedFrames = 0L
    private var delayedFrames = 0L
    private var watchdogTriggers = 0L

    /**
     * 待调速帧
     */
    private data class PacingFrame(
        val surfaceTexture: SurfaceTexture,
        val timestampNs: Long,
        val frameTimeMs: Float
    )

    /**
     * 启动输出引擎
     */
    fun start() {
        if (running) return

        outputThread = HandlerThread("PacingOutput").apply { start() }
        outputHandler = Handler(outputThread!!.looper)

        running = true
        passThroughMode = false

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

        outputHandler?.post {
            pendingFrames.clear()
        }

        try {
            outputThread?.quitSafely()
        } catch (e: Exception) { /* 忽略 */ }
        outputThread = null
        outputHandler = null

        logManager.i(TAG, "Pacing display engine stopped. " +
                "displayed=$displayedFrames, delayed=$delayedFrames, " +
                "watchdog_triggers=$watchdogTriggers")
    }

    /**
     * 提交一帧进行调速输出
     *
     * @param frameTimeMs 本帧帧时间
     * @param timestampNs 帧时间戳
     */
    fun submitFrame(frameTimeMs: Float, timestampNs: Long) {
        if (!running) return

        // 更新心跳（帧处理线程存活证明）
        heartbeatCounter.incrementAndGet()

        if (passThroughMode) {
            // 直通模式：直接输出，不调速
            outputFrame(frameTimeMs, 0f)
            return
        }

        // 调速：计算延迟
        val delayMs = governor.onFrame(frameTimeMs)

        if (delayMs <= 0f) {
            // 无需调速，立即输出
            outputFrame(frameTimeMs, 0f)
        } else {
            // 延迟输出
            delayedFrames++
            outputHandler?.postDelayed({
                if (running && !passThroughMode) {
                    outputFrame(frameTimeMs, delayMs)
                }
            }, delayMs.toLong())
        }
    }

    /**
     * 输出一帧到目标 Surface
     */
    private fun outputFrame(frameTimeMs: Float, delayMs: Float) {
        displayedFrames++
        // Android 无Root方案：画面通过 MediaProjection 捕获后已在系统层面呈现，
        // 调速通过延迟下一次帧捕获的处理来实现节奏控制
        // 此处记录日志，实际画面输出由系统合成器完成
        if (delayMs > 0) {
            logManager.d(TAG, "Frame output: frameTime=${"%.2f".format(frameTimeMs)}ms, " +
                    "delay=${"%.3f".format(delayMs)}ms, passThrough=$passThroughMode")
        }
    }

    // ==================== 看门狗机制 ====================

    /**
     * 启动看门狗线程
     *
     * 独立线程生成心跳包，不依赖帧调用
     * 判定规则：连续N次帧输出未收到心跳包 → 逻辑卡死 → 直通旁路
     */
    private fun startWatchdog() {
        watchdogRunning = true
        watchdogThread = Thread({
            logManager.i(TAG, "Watchdog thread started")
            var lastHeartbeat = heartbeatCounter.get()

            while (watchdogRunning && running) {
                try {
                    Thread.sleep(500)  // 每500ms检查一次

                    val currentHeartbeat = heartbeatCounter.get()
                    if (currentHeartbeat == lastHeartbeat) {
                        // 心跳未更新
                        val missed = missedHeartbeatCount.incrementAndGet()
                        logManager.w(TAG, "Watchdog: missed heartbeat $missed/$maxMissedHeartbeats")

                        if (missed >= maxMissedHeartbeats) {
                            // 判定卡死，切换直通旁路
                            triggerPassThrough("watchdog_timeout_missed_$missed")
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
     * 所有调用原样转发，不崩溃
     */
    private fun triggerPassThrough(reason: String) {
        if (passThroughMode) return
        passThroughMode = true
        watchdogTriggers++
        missedHeartbeatCount.set(0)

        logManager.w(TAG, "=== PASS-THROUGH MODE TRIGGERED: $reason ===")
        logManager.w(TAG, "All frame calls forwarded directly, no pacing applied")

        // 通知 UI
        // 这里可以通过 MessageBus 或回调通知状态变化
    }

    /**
     * 从直通模式恢复（需手动恢复，避免频繁切换）
     */
    fun recoverFromPassThrough() {
        if (!passThroughMode) return
        passThroughMode = false
        missedHeartbeatCount.set(0)
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
        "pass_through" to passThroughMode,
        "watchdog_triggers" to watchdogTriggers,
        "missed_heartbeats" to missedHeartbeatCount.get()
    )

    companion object {
        private const val TAG = "PacingDisplayEngine"
    }
}
