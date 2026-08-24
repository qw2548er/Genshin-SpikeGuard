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
    val cpuLoad: Float,        // CPU 负载 0-100%
    val fps: Int,              // 当前帧率
    val frameTimeMs: Float,    // 帧时间
    val memoryUsedMb: Int,     // 内存使用
    val temperature: Float,    // 温度
    val entityEstimate: Int    // 估算实体数量（通过帧率/GPU反推）
)

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
    protected var sampleIntervalMs: Long = 500

    open fun start(intervalMs: Long) {
        sampleIntervalMs = intervalMs
        running = true
    }

    open fun stop() {
        running = false
    }

    protected fun publishMetrics(sample: MetricsSample) {
        bus.publish(
            EventType.METRICS_SAMPLE,
            "gpu_load" to sample.gpuLoad,
            "cpu_load" to sample.cpuLoad,
            "fps" to sample.fps,
            "frame_time_ms" to sample.frameTimeMs,
            "memory_mb" to sample.memoryUsedMb,
            "temperature" to sample.temperature,
            "entity_estimate" to sample.entityEstimate,
            "timestamp" to sample.timestamp
        )
    }
}
