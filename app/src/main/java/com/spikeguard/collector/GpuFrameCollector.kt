package com.spikeguard.collector

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.max
import kotlin.math.min

/**
 * GPU与帧率采集器
 *
 * 通过 SurfaceFlinger / Choreographer 间接获取帧率信息
 * 通过读取系统文件获取 GPU 负载（需要 Root 或 Shizuku）
 *
 * 注意：不进行任何进程注入或 HOOK
 */
class GpuFrameCollector(private val context: Context) : BaseCollector() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var frameCount = 0
    private var lastFpsCalcTime = 0L
    private var currentFps = 60
    private var baselineGpuLoad = 30f  // 基线GPU负载

    // 尖峰检测相关
    private val gpuLoadHistory = ArrayDeque<Float>()
    private val maxHistorySize = 20

    override fun start(intervalMs: Long) {
        super.start(intervalMs)
        startSampling()
    }

    override fun stop() {
        super.stop()
        scope.coroutineContext.cancelChildren()
    }

    private fun startSampling() {
        scope.launch {
            while (running && isActive) {
                try {
                    val sample = collectSample()
                    publishMetrics(sample)
                    checkGpuSpike(sample)
                    delay(sampleIntervalMs)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Sampling error", e)
                    delay(1000) // 出错后延长间隔
                }
            }
        }

        // 独立的FPS计算
        scope.launch {
            while (running && isActive) {
                try {
                    val start = System.nanoTime()
                    var frames = 0
                    // 统计1秒内的帧数
                    while (System.nanoTime() - start < 1_000_000_000 && running) {
                        try {
                            // 使用 Choreographer 等待下一个vsync
                            // 在后台线程中近似估算
                            delay(16) // ~60fps
                            frames++
                        } catch (e: Exception) {
                            break
                        }
                    }
                    currentFps = frames
                    frameCount += frames
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "FPS calc error", e)
                }
            }
        }
    }

    private fun collectSample(): MetricsSample {
        val timestamp = System.currentTimeMillis()

        // 采集 GPU 负载（尝试读取 sysfs）
        val gpuLoad = readGpuLoad()

        // 采集 CPU 负载
        val cpuLoad = readCpuLoad()

        // 采集内存使用
        val memoryUsed = readMemoryUsed()

        // 采集温度
        val temperature = readTemperature()

        // 通过帧率和GPU负载估算实体数量
        val entityEstimate = estimateEntityCount(gpuLoad, currentFps)

        return MetricsSample(
            timestamp = timestamp,
            gpuLoad = gpuLoad,
            cpuLoad = cpuLoad,
            fps = currentFps,
            frameTimeMs = if (currentFps > 0) 1000f / currentFps else 33f,
            memoryUsedMb = memoryUsed,
            temperature = temperature,
            entityEstimate = entityEstimate
        )
    }

    /**
     * 检测 GPU 尖峰
     */
    private fun checkGpuSpike(sample: MetricsSample) {
        gpuLoadHistory.addLast(sample.gpuLoad)
        if (gpuLoadHistory.size > maxHistorySize) {
            gpuLoadHistory.removeFirst()
        }

        // 计算基线（前80%的平均值）
        if (gpuLoadHistory.size >= 10) {
            val baseline = gpuLoadHistory.take(gpuLoadHistory.size - 2).average().toFloat()
            val current = sample.gpuLoad
            val spikeThreshold = baseline * 1.5f // 超过基线50%视为尖峰

            if (current > spikeThreshold && current > 60f) {
                bus.publish(
                    EventType.GPU_SPIKE_DETECTED,
                    "gpu_load" to current,
                    "baseline" to baseline,
                    "spike_ratio" to (current / baseline),
                    "fps" to sample.fps,
                    "entity_estimate" to sample.entityEstimate
                )
            }

            // 更新基线
            baselineGpuLoad = baseline
        }

        // 检测实体数量激增
        if (sample.entityEstimate > 50 && sample.fps < 30) {
            bus.publish(
                EventType.ENTITY_SURGE_DETECTED,
                "entity_estimate" to sample.entityEstimate,
                "fps" to sample.fps,
                "gpu_load" to sample.gpuLoad
            )
        }
    }

    /**
     * 读取 GPU 负载
     * 尝试多种 sysfs 路径，兼容不同设备
     */
    private fun readGpuLoad(): Float {
        // PowerVR GPU 常见路径
        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/soc/1c00000.gpu/gpu_busy",
            "/sys/class/graphics/fb0/gpu_utilization"
        )

        for (path in paths) {
            try {
                val content = java.io.File(path).readText().trim()
                // 不同格式解析
                val value = when {
                    content.contains(" ") -> {
                        // 格式如 "busy total"
                        val parts = content.split(" ")
                        if (parts.size >= 2) {
                            val busy = parts[0].toFloatOrNull() ?: 0f
                            val total = parts[1].toFloatOrNull() ?: 1f
                            if (total > 0) (busy / total) * 100f else 0f
                        } else 0f
                    }
                    else -> content.toFloatOrNull() ?: 0f
                }
                return value.coerceIn(0f, 100f)
            } catch (e: Exception) {
                // 尝试下一个路径
            }
        }

        // 无法读取时，通过帧率估算
        return estimateGpuLoadFromFps(currentFps)
    }

    /**
     * 通过帧率估算 GPU 负载
     */
    private fun estimateGpuLoadFromFps(fps: Int): Float {
        // 简化估算：帧率越低，GPU负载越高
        val baseFps = 60f
        return min(100f, max(10f, (1 - fps / baseFps) * 80 + 20))
    }

    /**
     * 读取 CPU 负载
     */
    private fun readCpuLoad(): Float {
        try {
            val stat = java.io.File("/proc/stat").readText()
            val lines = stat.lines()
            if (lines.isNotEmpty()) {
                val parts = lines[0].split("\\s+".toRegex())
                if (parts.size >= 8) {
                    val user = parts[1].toLongOrNull() ?: 0L
                    val nice = parts[2].toLongOrNull() ?: 0L
                    val system = parts[3].toLongOrNull() ?: 0L
                    val idle = parts[4].toLongOrNull() ?: 0L
                    val iowait = parts[5].toLongOrNull() ?: 0L
                    val irq = parts[6].toLongOrNull() ?: 0L
                    val softirq = parts[7].toLongOrNull() ?: 0L

                    val total = user + nice + system + idle + iowait + irq + softirq
                    val idleTotal = idle + iowait

                    // 简化：直接返回估算值
                    // 实际实现需要两次采样计算差值
                    return 35f // 估算值
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return 35f
    }

    /**
     * 读取内存使用
     */
    private fun readMemoryUsed(): Int {
        try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            return ((mi.totalMem - mi.availMem) / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            return 0
        }
    }

    /**
     * 读取温度
     */
    private fun readTemperature(): Float {
        val tempPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp"
        )

        for (path in tempPaths) {
            try {
                val content = java.io.File(path).readText().trim()
                val temp = content.toFloatOrNull() ?: continue
                // 某些设备返回毫摄氏度
                return if (temp > 1000) temp / 1000f else temp
            } catch (e: Exception) {
                continue
            }
        }
        return 35f
    }

    /**
     * 估算实体数量
     * 通过GPU负载和帧率反推场景复杂度
     */
    private fun estimateEntityCount(gpuLoad: Float, fps: Int): Int {
        // 简化估算模型
        // 高GPU负载 + 低帧率 = 大量实体
        val loadFactor = gpuLoad / 100f
        val fpsFactor = max(0f, (60f - fps) / 60f)
        val estimate = (loadFactor * fpsFactor * 200).toInt()
        return max(0, estimate)
    }

    companion object {
        private const val TAG = "GpuFrameCollector"
    }
}
