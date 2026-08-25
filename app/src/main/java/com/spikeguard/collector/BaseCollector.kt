package com.spikeguard.collector

import com.spikeguard.core.EventType
import com.spikeguard.core.GuardEvent
import com.spikeguard.core.MessageBus

/**
 * 性能指标数据
 */
data class MetricsSample(
    val timestamp: Long,
    val gpuLoad: Float,        // GPU 负载 0-100%
    val gpuFreqMhz: Int,       // GPU频率 MHz
    val cpuLoad: Float,        // CPU 负载 0-100%
    val fps: Int,              // 当前帧率
    val frameTimeMs: Float,    // 帧时间
    val memoryUsedMb: Int,     // 内存使用
    val memoryTotalMb: Int,    // 总内存
    val temperature: Float,    // 温度
    val entityEstimate: Int,   // 估算实体数量（通过帧率/GPU反推）
    val coreFreqMhz: IntArray = IntArray(8) { -1 }, // 逐核 MHz（CPU 八核详情）
    val coreLoadPct: IntArray = IntArray(8) { -1 }  // 逐核 %（CPU 八核详情）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetricsSample) return false
        return timestamp == other.timestamp && gpuLoad == other.gpuLoad &&
                gpuFreqMhz == other.gpuFreqMhz && cpuLoad == other.cpuLoad &&
                fps == other.fps && frameTimeMs == other.frameTimeMs &&
                memoryUsedMb == other.memoryUsedMb && memoryTotalMb == other.memoryTotalMb &&
                temperature == other.temperature && entityEstimate == other.entityEstimate &&
                coreFreqMhz.contentEquals(other.coreFreqMhz) &&
                coreLoadPct.contentEquals(other.coreLoadPct)
    }
    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + gpuLoad.hashCode()
        result = 31 * result + gpuFreqMhz
        result = 31 * result + cpuLoad.hashCode()
        result = 31 * result + fps
        result = 31 * result + frameTimeMs.hashCode()
        result = 31 * result + memoryUsedMb
        result = 31 * result + memoryTotalMb
        result = 31 * result + temperature.hashCode()
        result = 31 * result + entityEstimate
        result = 31 * result + coreFreqMhz.contentHashCode()
        result = 31 * result + coreLoadPct.contentHashCode()
        return result
    }
}

/**
 * 采集模块基类
 * 负责采集系统性能数据，通过消息总线发布
 *
 * 设计原则：
 * 1. 只采集，不决策
 * 2. 采样频率可配置
 * 3. 异常时自动降级，不影响其他模块
 */
abstract class BaseCollector {

    protected val bus = MessageBus.getInstance()
    protected var running = false
    protected var paused = false
    protected var sampleIntervalMs: Long = 500

    open fun start(intervalMs: Long) {
        sampleIntervalMs = intervalMs
        running = true
        paused = false
    }

    open fun stop() {
        running = false
        paused = false
    }

    /**
     * 暂停采集（静默期使用）
     */
    open fun pause() {
        paused = true
    }

    /**
     * 恢复采集
     */
    open fun resume() {
        paused = false
    }

    /**
     * 是否暂停
     */
    fun isPaused(): Boolean = paused

    protected fun publishMetrics(sample: MetricsSample) {
        val pairs = mutableListOf<Pair<String, Any>>(
            "gpu_load" to sample.gpuLoad,
            "gpu_freq_mhz" to sample.gpuFreqMhz,
            "cpu_load" to sample.cpuLoad,
            "fps" to sample.fps,
            "frame_time_ms" to sample.frameTimeMs,
            "memory_used_mb" to sample.memoryUsedMb,
            "memory_total_mb" to sample.memoryTotalMb,
            "temperature" to sample.temperature,
            "entity_estimate" to sample.entityEstimate,
            "timestamp" to sample.timestamp,
            "core_freq_mhz" to sample.coreFreqMhz,
            "core_load_pct" to sample.coreLoadPct
        )
        bus.publish(EventType.METRICS_SAMPLE, *pairs.toTypedArray())
    }
}
