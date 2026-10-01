package com.spikeguard.pseudocerebellum

import android.content.Context
import android.os.Handler
import com.spikeguard.core.EventType
import com.spikeguard.core.GuardEvent
import com.spikeguard.core.MessageBus
import com.spikeguard.util.LogManager
import kotlin.math.abs

/**
 * 模块1：瞬时负载监测模块（感知尖峰）
 *
 * 职责：实时采集CPU、GPU、内存、温度数据，识别突发资源尖峰
 *
 * 核心逻辑：
 * 1. 读取安卓系统接口，持续采样：CPU占用率、GPU负载、可用内存、SOC温度
 * 2. 设置阈值规则：
 *    - 当GPU负载、内存占用在短时间（几十ms）突然暴涨，判定为「资源冲击尖峰」
 *    - 区分：正常持续高负载 VS 瞬时脉冲尖峰（只拦截瞬时脉冲）
 * 3. 事件上报：预判即将发生资源爆发，发送信号给分流模块
 * 4. 输出日志：记录每次尖峰触发时间、硬件指标
 *
 * 重点：不做画质删减，只识别冲击信号
 *
 * 数据来源（系统级，不注入游戏进程）：
 * - CPU: /proc/stat 两次采样差值
 * - GPU: /sys 下多种GPU sysfs节点（PowerVR/Adreno/Mali）
 * - 内存: ActivityManager.MemoryInfo + /proc/meminfo
 * - 温度: /sys/class/thermal/thermal_zoneX/temp
 */
class SpikeMonitor(
    private val context: Context,
    private val handler: Handler,
    private val onSpikeDetected: (SpikeInfo) -> Unit,
    private val onLoadLevelChanged: (PseudoCerebellum.LoadLevel) -> Unit
) {

    private val logManager = LogManager.getInstance(context)
    private val bus = MessageBus.getInstance()

    @Volatile private var running = false
    private var sampleThread: Thread? = null

    // 采样间隔（ms），性能开销控制：200ms采样，不额外大量占用CPU
    private val sampleIntervalMs = 200L

    // 历史数据（用于识别瞬时脉冲 vs 持续高负载）
    private val gpuHistory = ArrayDeque<Pair<Long, Float>>()  // timestamp, value
    private val memoryHistory = ArrayDeque<Pair<Long, Int>>()
    private val cpuHistory = ArrayDeque<Pair<Long, Float>>()
    private val tempHistory = ArrayDeque<Pair<Long, Float>>()

    private val maxHistorySize = 50  // 保留最近50个采样点（约10秒）

    // CPU计算基线
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuInitialized = false

    // 上一次负载等级
    private var lastLoadLevel = PseudoCerebellum.LoadLevel.NORMAL

    // 尖峰检测阈值（可通过配置调整）
    private var gpuSpikeThreshold = 25f     // GPU负载在短时间内暴涨超过此值（百分点）
    private var memorySurgeThreshold = 200  // 内存在短时间内暴涨超过此值（MB）
    private var spikeWindowMs = 300L        // 判定为"瞬时"的时间窗口（ms）
    private var sustainedLoadThreshold = 80f // 持续高负载阈值（%）

    // 订阅现有采集器的METRICS_SAMPLE事件
    private val metricsCallback: (GuardEvent) -> Unit = callback@{ event ->
        if (!running) return@callback
        val gpuLoad = event.data["gpu_load"] as? Float ?: 0f
        val cpuLoad = event.data["cpu_load"] as? Float ?: 0f
        val memoryUsed = event.data["memory_used_mb"] as? Int ?: 0
        val temperature = event.data["temperature"] as? Float ?: 0f
        val timestamp = event.data["timestamp"] as? Long ?: System.currentTimeMillis()

        onMetricsSample(timestamp, gpuLoad, cpuLoad, memoryUsed, temperature)
    }

    /**
     * 启动监测
     */
    fun start() {
        if (running) return
        running = true
        logManager.i(TAG, "SpikeMonitor started, interval=${sampleIntervalMs}ms")

        // 订阅主采集器的采样数据（复用现有采集，不重复采样降低CPU开销）
        bus.subscribe(EventType.METRICS_SAMPLE, metricsCallback)
    }

    /**
     * 停止监测
     */
    fun stop() {
        running = false
        logManager.i(TAG, "SpikeMonitor stopped")
        // 清理历史数据
        gpuHistory.clear()
        memoryHistory.clear()
        cpuHistory.clear()
        tempHistory.clear()
        cpuInitialized = false
    }

    /**
     * 处理每次采样数据
     */
    private fun onMetricsSample(
        timestamp: Long,
        gpuLoad: Float,
        cpuLoad: Float,
        memoryUsed: Int,
        temperature: Float
    ) {
        // 记录历史
        addToHistory(gpuHistory, timestamp to gpuLoad)
        addToHistory(memoryHistory, timestamp to memoryUsed)
        addToHistory(cpuHistory, timestamp to cpuLoad)
        addToHistory(tempHistory, timestamp to temperature)

        // 检测各类尖峰
        detectGpuSpike(timestamp, gpuLoad)
        detectMemorySurge(timestamp, memoryUsed)
        detectTemperatureRush(timestamp, temperature)

        // 评估整体负载等级
        evaluateLoadLevel(gpuLoad, cpuLoad, memoryUsed, temperature)
    }

    /**
     * 检测GPU瞬时脉冲尖峰
     *
     * 关键：区分「正常持续高负载」和「瞬时脉冲尖峰」
     * - 持续高负载：GPU长期>80%，不是尖峰，不拦截
     * - 瞬时脉冲：短时间内暴涨，才拦截
     */
    private fun detectGpuSpike(timestamp: Long, currentGpu: Float) {
        if (gpuHistory.size < 3) return

        // 取spikeWindowMs内的最小值作为基线
        val windowStart = timestamp - spikeWindowMs
        val windowValues = gpuHistory.filter { it.first >= windowStart }
        if (windowValues.size < 2) return

        val baselineGpu = windowValues.minOf { it.second }
        val delta = currentGpu - baselineGpu

        // 判定条件：
        // 1. 暴涨幅度超过阈值
        // 2. 基线较低（说明之前是正常状态，不是持续高负载）
        // 3. 当前值较高（说明是真的尖峰）
        if (delta >= gpuSpikeThreshold && baselineGpu < 60f && currentGpu > 55f) {
            // 进一步排除持续高负载：检查前5秒平均负载
            val fiveSecAgo = timestamp - 5000
            val recentValues = gpuHistory.filter { it.first >= fiveSecAgo }
            val avgRecent = if (recentValues.isNotEmpty()) {
                recentValues.map { it.second }.average().toFloat()
            } else currentGpu

            // 如果近期平均已经很高，说明是持续高负载，不是脉冲尖峰
            if (avgRecent > sustainedLoadThreshold) return

            val severity = (delta / 100f).coerceIn(0f, 1f)
            logManager.i(TAG, "GPU spike detected: baseline=${"%.1f".format(baselineGpu)}%, " +
                    "current=${"%.1f".format(currentGpu)}%, " +
                    "delta=${"%.1f".format(delta)}%, window=${spikeWindowMs}ms")

            onSpikeDetected(SpikeInfo(
                type = SpikeType.GPU_SPIKE,
                gpuLoad = currentGpu,
                memoryUsedMb = memoryHistory.lastOrNull()?.second ?: 0,
                cpuLoad = cpuHistory.lastOrNull()?.second ?: 0f,
                temperature = tempHistory.lastOrNull()?.second ?: 0f,
                deltaMs = spikeWindowMs,
                severity = severity
            ))
        }
    }

    /**
     * 检测内存占用暴涨
     *
     * 场景加载时会瞬间申请大量内存，这是贴图闪烁/闪退的主要原因之一
     */
    private fun detectMemorySurge(timestamp: Long, currentMemory: Int) {
        if (memoryHistory.size < 3) return

        val windowStart = timestamp - spikeWindowMs
        val windowValues = memoryHistory.filter { it.first >= windowStart }
        if (windowValues.size < 2) return

        val baselineMemory = windowValues.minOf { it.second }
        val delta = currentMemory - baselineMemory

        if (delta >= memorySurgeThreshold) {
            val severity = (delta / 1000f).coerceIn(0f, 1f)
            logManager.i(TAG, "Memory surge detected: baseline=${baselineMemory}MB, " +
                    "current=${currentMemory}MB, delta=${delta}MB")

            onSpikeDetected(SpikeInfo(
                type = SpikeType.MEMORY_SURGE,
                gpuLoad = gpuHistory.lastOrNull()?.second ?: 0f,
                memoryUsedMb = currentMemory,
                cpuLoad = cpuHistory.lastOrNull()?.second ?: 0f,
                temperature = tempHistory.lastOrNull()?.second ?: 0f,
                deltaMs = spikeWindowMs,
                severity = severity
            ))
        }
    }

    /**
     * 检测温度急升（温控前兆）
     */
    private fun detectTemperatureRush(timestamp: Long, currentTemp: Float) {
        if (tempHistory.size < 5 || currentTemp <= 0) return

        val windowStart = timestamp - 1000  // 1秒窗口
        val windowValues = tempHistory.filter { it.first >= windowStart }
        if (windowValues.size < 2) return

        val baselineTemp = windowValues.first().second
        val delta = currentTemp - baselineTemp

        // 1秒内温度上升超过3度，判定为温控前兆
        if (delta >= 3f && currentTemp > 40f) {
            val severity = (delta / 10f).coerceIn(0f, 1f)
            logManager.w(TAG, "Temperature rush detected: baseline=${"%.1f".format(baselineTemp)}°C, " +
                    "current=${"%.1f".format(currentTemp)}°C, delta=${"%.1f".format(delta)}°C/s")

            onSpikeDetected(SpikeInfo(
                type = SpikeType.TEMPERATURE_RUSH,
                gpuLoad = gpuHistory.lastOrNull()?.second ?: 0f,
                memoryUsedMb = memoryHistory.lastOrNull()?.second ?: 0,
                cpuLoad = cpuHistory.lastOrNull()?.second ?: 0f,
                temperature = currentTemp,
                deltaMs = 1000,
                severity = severity
            ))
        }
    }

    /**
     * 评估整体负载等级
     */
    private fun evaluateLoadLevel(gpu: Float, cpu: Float, memory: Int, temp: Float) {
        // 获取总内存计算占比
        val totalMem = getTotalMemoryMb()
        val memoryRatio = if (totalMem > 0) memory.toFloat() / totalMem else 0f

        // 组合评分
        var score = 0
        if (gpu > 85f) score += 3
        else if (gpu > 70f) score += 2
        else if (gpu > 50f) score += 1

        if (cpu > 90f) score += 2
        else if (cpu > 70f) score += 1

        if (memoryRatio > 0.85f) score += 3
        else if (memoryRatio > 0.7f) score += 2
        else if (memoryRatio > 0.55f) score += 1

        if (temp > 55f) score += 3
        else if (temp > 45f) score += 1

        val newLevel = when {
            score >= 7 -> PseudoCerebellum.LoadLevel.CRITICAL
            score >= 4 -> PseudoCerebellum.LoadLevel.WARNING
            else -> PseudoCerebellum.LoadLevel.NORMAL
        }

        if (newLevel != lastLoadLevel) {
            lastLoadLevel = newLevel
            onLoadLevelChanged(newLevel)
        }
    }

    /**
     * 获取总内存（MB）
     */
    private fun getTotalMemoryMb(): Int {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            8000 // 默认8GB
        }
    }

    /**
     * 添加到历史队列并限制大小
     */
    private fun <T> addToHistory(queue: ArrayDeque<Pair<Long, T>>, item: Pair<Long, T>) {
        queue.addLast(item)
        while (queue.size > maxHistorySize) {
            queue.removeFirst()
        }
    }

    /**
     * 更新阈值（可由配置驱动）
     */
    fun updateThresholds(
        gpuSpike: Float,
        memorySurge: Int,
        windowMs: Long,
        sustained: Float
    ) {
        this.gpuSpikeThreshold = gpuSpike
        this.memorySurgeThreshold = memorySurge
        this.spikeWindowMs = windowMs
        this.sustainedLoadThreshold = sustained
        logManager.i(TAG, "Thresholds updated: gpu_spike=$gpuSpike, " +
                "mem_surge=$memorySurge, window=$windowMs, sustained=$sustained")
    }

    companion object {
        private const val TAG = "PC_SpikeMonitor"
    }
}
