package com.spikeguard.framepacing

import android.util.Log
import com.spikeguard.util.LogManager
import kotlin.math.sqrt

/**
 * 帧级自适应调速器（Frame Pacing Governor）— 决策层
 *
 * 项目代号：伪小脑 V3
 *
 * 核心原理：
 * 不重排 GPU 命令缓冲区，不修改渲染内容，只在帧呈现（Present）阶段插入可变延迟，
 * 平滑帧输出节奏，降低帧时间方差。
 *
 * 决策算法：双路 EWMA（指数加权移动平均）
 * - 快速通道 EWMA：时间常数 ≈ 10 帧，快速识别负载尖峰
 * - 慢速通道 EWMA：时间常数 ≈ 60 帧，判定负载恢复时机
 *
 * 相比普通滑动平均，EWMA 对最新数据权重更高，相位滞后更小，
 * 避免过平滑导致延迟无法及时回落。
 *
 * 尖峰判定：滑动窗口统计，连续 N 帧帧时间超过滚动均值 1.5 倍标准差，判定负载尖峰。
 *
 * 延迟约束：
 * - 默认模式：端到端输入延迟增量上限 1.5ms
 * - 低延迟模式：上限 0.5ms
 *
 * 重要限制：不重排 GPU 命令缓冲区，只控制帧呈现时机
 */
class FramePacingGovernor(
    private val logManager: LogManager
) {

    // EWMA 平滑系数
    // alpha = 2 / (N + 1)，N为时间常数（帧数）
    private val fastAlpha = 2f / (10f + 1f)   // 快速通道：10帧
    private val slowAlpha = 2f / (60f + 1f)   // 慢速通道：60帧

    // EWMA 当前值
    @Volatile private var fastEwma = 0f
    @Volatile private var slowEwma = 0f
    @Volatile private var ewmaInitialized = false

    // 帧时间滑动窗口（用于标准差计算）
    private val frameTimeWindow = ArrayDeque<Float>()
    private val windowSize = 60  // 60帧窗口

    // 尖峰判定参数
    private val spikeThresholdMultiplier = 1.5f  // 1.5倍标准差
    private val consecutiveSpikeFrames = 3       // 连续N帧判定尖峰
    private var consecutiveOverStdCount = 0

    // 延迟约束
    private var maxLatencyMs = 1.5f  // 默认1.5ms
    private var lowLatencyMode = false

    // 当前状态
    @Volatile private var pacingActive = false
    @Volatile private var currentDelayMs = 0f
    @Volatile private var isThrottling = false

    // 统计
    private var totalFrames = 0L
    private var throttledFrames = 0L
    private var frameTimeVariance = 0f

    // 延迟累积监测（防止长时间运行下延迟持续累积偏移）
    private var accumulatedLatencyDrift = 0f
    private val maxAccumulatedDrift = 5f  // 累积偏移上限5ms

    /**
     * 输入一帧的帧时间（ms），返回应该插入的延迟（ms）
     *
     * @param frameTimeMs 本帧帧时间
     * @return 应插入的延迟时间（ms），0表示不调速
     */
    fun onFrame(frameTimeMs: Float): Float {
        totalFrames++

        // 更新双路EWMA
        updateEwma(frameTimeMs)

        // 更新滑动窗口
        frameTimeWindow.addLast(frameTimeMs)
        if (frameTimeWindow.size > windowSize) {
            frameTimeWindow.removeFirst()
        }

        // 计算滚动均值和标准差
        val (mean, stdDev) = calculateRollingStats()

        // 尖峰判定
        val isSpike = detectSpike(frameTimeMs, mean, stdDev)

        // 决策：计算应插入的延迟
        val delay = calculateDelay(frameTimeMs, mean, stdDev, isSpike)

        // 延迟累积监测
        accumulatedLatencyDrift += delay
        if (accumulatedLatencyDrift > maxAccumulatedDrift) {
            // 累积偏移过大，强制归零（防止延迟持续累积）
            logManager.w(TAG, "Latency drift too high (${"%.2f".format(accumulatedLatencyDrift)}ms), resetting")
            accumulatedLatencyDrift = 0f
            return 0f
        }

        // 衰减累积偏移（不调速时缓慢回落）
        if (delay == 0f) {
            accumulatedLatencyDrift *= 0.95f
        }

        if (delay > 0) {
            throttledFrames++
        }

        currentDelayMs = delay
        return delay
    }

    /**
     * 更新双路EWMA
     */
    private fun updateEwma(frameTimeMs: Float) {
        if (!ewmaInitialized) {
            fastEwma = frameTimeMs
            slowEwma = frameTimeMs
            ewmaInitialized = true
            return
        }

        // EWMA: EWMA_t = alpha * value_t + (1 - alpha) * EWMA_{t-1}
        fastEwma = fastAlpha * frameTimeMs + (1 - fastAlpha) * fastEwma
        slowEwma = slowAlpha * frameTimeMs + (1 - slowAlpha) * slowEwma
    }

    /**
     * 计算滚动均值和标准差
     */
    private fun calculateRollingStats(): Pair<Float, Float> {
        if (frameTimeWindow.size < 5) return Pair(0f, 0f)

        val values = frameTimeWindow.toList()
        val mean = values.average().toFloat()

        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)

        frameTimeVariance = variance
        return Pair(mean, stdDev)
    }

    /**
     * 尖峰判定
     * 当连续N帧帧时间超过滚动均值1.5倍标准差，判定负载尖峰
     */
    private fun detectSpike(frameTimeMs: Float, mean: Float, stdDev: Float): Boolean {
        if (stdDev <= 0f || mean <= 0f) return false

        val threshold = mean + spikeThresholdMultiplier * stdDev
        val isOverThreshold = frameTimeMs > threshold

        if (isOverThreshold) {
            consecutiveOverStdCount++
        } else {
            consecutiveOverStdCount = 0
        }

        val spikeDetected = consecutiveOverStdCount >= consecutiveSpikeFrames
        if (spikeDetected) {
            logManager.i(TAG, "Frame spike detected: frameTime=${"%.2f".format(frameTimeMs)}ms, " +
                    "mean=${"%.2f".format(mean)}ms, " +
                    "stdDev=${"%.2f".format(stdDev)}ms, " +
                    "threshold=${"%.2f".format(threshold)}ms, " +
                    "consecutive=$consecutiveOverStdCount")
        }

        return spikeDetected
    }

    /**
     * 计算应插入的延迟
     *
     * 调速逻辑：
     * - 尖峰时：插入延迟平滑输出，延迟上限受模式约束
     * - 快速通道 > 慢速通道（负载上升）：逐步增加延迟
     * - 快速通道 < 慢速通道（负载恢复）：逐步减少延迟
     * - 延迟不能超过 maxLatencyMs
     */
    private fun calculateDelay(
        frameTimeMs: Float,
        mean: Float,
        stdDev: Float,
        isSpike: Boolean
    ): Float {
        if (!pacingActive) return 0f

        // 利用快慢通道差值判断趋势
        val trend = fastEwma - slowEwma  // >0 负载上升，<0 负载恢复

        var targetDelay = 0f

        if (isSpike) {
            // 尖峰：按超过阈值的比例计算延迟
            val excess = (frameTimeMs - mean) / stdDev.coerceAtLeast(0.1f)
            targetDelay = (excess * 0.3f).coerceAtMost(maxLatencyMs)
        } else if (trend > 0.5f) {
            // 负载上升趋势（快速通道显著高于慢速通道）
            // 渐进式增加延迟，避免突然冲击
            targetDelay = (trend * 0.1f).coerceAtMost(maxLatencyMs * 0.5f)
        } else if (trend < -0.3f) {
            // 负载恢复：减少延迟
            targetDelay = 0f
        }

        // 约束：不超过上限
        targetDelay = targetDelay.coerceIn(0f, maxLatencyMs)

        // 低延迟模式：进一步压缩
        if (lowLatencyMode) {
            targetDelay = targetDelay.coerceAtMost(maxLatencyMs)
        }

        isThrottling = targetDelay > 0f
        return targetDelay
    }

    // ==================== 配置接口 ====================

    /**
     * 启用/禁用调速
     */
    fun setPacingEnabled(enabled: Boolean) {
        pacingActive = enabled
        if (!enabled) {
            currentDelayMs = 0f
            isThrottling = false
            consecutiveOverStdCount = 0
        }
        logManager.i(TAG, "Pacing ${if (enabled) "enabled" else "disabled"}, " +
                "maxLatency=${maxLatencyMs}ms, lowLatency=$lowLatencyMode")
    }

    /**
     * 设置最大延迟上限
     * @param maxMs 延迟上限（ms），默认1.5，低延迟模式0.5
     */
    fun setMaxLatency(maxMs: Float) {
        this.maxLatencyMs = maxMs.coerceAtLeast(0f)
        logManager.i(TAG, "Max latency set to ${maxLatencyMs}ms")
    }

    /**
     * 切换低延迟模式
     * @param enabled true=0.5ms上限, false=1.5ms上限
     */
    fun setLowLatencyMode(enabled: Boolean) {
        this.lowLatencyMode = enabled
        this.maxLatencyMs = if (enabled) 0.5f else 1.5f
        logManager.i(TAG, "Low latency mode: $enabled, maxLatency=${maxLatencyMs}ms")
    }

    /**
     * 重置调速器状态（基线参数重置）
     */
    fun reset() {
        fastEwma = 0f
        slowEwma = 0f
        ewmaInitialized = false
        frameTimeWindow.clear()
        consecutiveOverStdCount = 0
        currentDelayMs = 0f
        isThrottling = false
        accumulatedLatencyDrift = 0f
        totalFrames = 0
        throttledFrames = 0
        logManager.i(TAG, "Governor reset")
    }

    // ==================== 状态查询 ====================

    fun isPacingActive(): Boolean = pacingActive
    fun isThrottling(): Boolean = isThrottling
    fun getCurrentDelayMs(): Float = currentDelayMs
    fun getFastEwma(): Float = fastEwma
    fun getSlowEwma(): Float = slowEwma
    fun getFrameTimeVariance(): Float = frameTimeVariance

    fun getStats(): Map<String, Any> = mapOf(
        "total_frames" to totalFrames,
        "throttled_frames" to throttledFrames,
        "throttle_ratio" to if (totalFrames > 0) throttledFrames.toFloat() / totalFrames else 0f,
        "current_delay_ms" to currentDelayMs,
        "fast_ewma" to fastEwma,
        "slow_ewma" to slowEwma,
        "frame_time_variance" to frameTimeVariance,
        "is_throttling" to isThrottling,
        "max_latency_ms" to maxLatencyMs,
        "low_latency_mode" to lowLatencyMode
    )

    companion object {
        private const val TAG = "FramePacingGovernor"
    }
}
