package com.spikeguard.collector

import android.content.Context
import com.spikeguard.core.EventType
import com.spikeguard.util.LogManager
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

/**
 * GPU与帧率采集器
 *
 * 真实采集硬件数据：
 * - CPU: /proc/stat 两次采样差值计算
 * - GPU: 多种sysfs路径尝试（PowerVR/Adreno/Mali）
 * - 内存: ActivityManager.MemoryInfo + /proc/meminfo
 * - 温度: thermal_zone sysfs
 * - FPS: 通过SurfaceFlinger/帧率估算
 * - 实体数: GPU+FPS综合估算
 *
 * 所有采样数据实时发布到MessageBus并写入日志文件
 *
 * 注意：不进行任何进程注入或HOOK
 */
class GpuFrameCollector(private val context: Context) : BaseCollector() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logManager = LogManager.getInstance(context)

    // CPU计算相关 - 保存上次采样数据
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuLoadInitialized = false

    // FPS相关 - 真实SurfaceFlinger读取
    private var realFpsValue = -1  // -1 表示尚未获取到真实值
    private var lastFpsSampleTime = 0L
    private var lastFpsWindowStart = 0L
    private var lastFrameCountTotal = -1L

    // FPS命令尝试顺序（不同Android版本可用的不同）
    private val surfaceFlingerFpsCommands = listOf(
        "dumpsys SurfaceFlinger --latency 'SurfaceView - com.miHoYo.Yuanshen/com.miHoYo.Yuanshen/com.miHoYo.ys.YsNativeActivity'",
        "dumpsys SurfaceFlinger --latency 'SurfaceView - com.miHoYo.GenshinImpact/com.miHoYo.GenshinImpact/com.miHoYo.ys.YsNativeActivity'",
        "dumpsys SurfaceFlinger --latency 'SurfaceView - com.mihoyo.genshinimpact/com.mihoyo.genshinimpact/com.miHoYo.ys.YsNativeActivity'",
        // 任意 SurfaceView
        "dumpsys SurfaceFlinger --latency-clear 2>/dev/null; dumpsys SurfaceFlinger --latency 'DEFAULT_DISPLAY' 2>/dev/null | tail -100",
        // 简化通用方案：统计指定1s周期内提交的帧数（直接数行数）
        "dumpsys SurfaceFlinger --latency 2>/dev/null"
    )

    // 备用方案：dumpsys gfxinfo 读总帧数增量
    private val gfxInfoPackages = listOf(
        "com.miHoYo.Yuanshen",
        "com.miHoYo.GenshinImpact",
        "com.mihoyo.genshinimpact"
    )

    // 尖峰检测相关
    private val gpuLoadHistory = ArrayDeque<Float>()
    private val maxHistorySize = 30

    // PowerVR GPU 特殊路径（荣耀X60）
    private val powervrGpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/soc/1c00000.gpu/gpu_busy",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/graphics/fb0/gpu_utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/pvrsrvkm/gpuutilisation"
    )

    // GPU频率路径
    private val gpuFreqPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
        "/sys/devices/platform/soc/1c00000.gpu/devfreq/1c00000.gpu/cur_freq",
        "/sys/class/misc/mali0/device/cur_freq"
    )

    override fun start(intervalMs: Long) {
        super.start(intervalMs)
        logManager.i(TAG, "Collector starting with interval=${intervalMs}ms")
        startSampling()
    }

    override fun stop() {
        super.stop()
        logManager.i(TAG, "Collector stopping")
        scope.coroutineContext.cancelChildren()
        cpuLoadInitialized = false
        gpuLoadHistory.clear()
    }

    override fun pause() {
        super.pause()
        logManager.i(TAG, "Collector paused (silent mode)")
    }

    override fun resume() {
        super.resume()
        logManager.i(TAG, "Collector resumed")
        // 恢复时重置CPU基线，避免计算出异常值
        cpuLoadInitialized = false
    }

    private fun startSampling() {
        scope.launch {
            logManager.i(TAG, "Sampling loop started")
            var sampleCount = 0L
            while (running && isActive) {
                try {
                    if (!paused) {
                        val sample = collectSample()
                        publishMetrics(sample)
                        logSample(sample)
                        checkGpuSpike(sample)
                        sampleCount++

                        // 每100次采样输出一条汇总日志
                        if (sampleCount % 100 == 0L) {
                            logManager.i(TAG, "Sampling alive: count=$sampleCount, " +
                                    "gpu=${"%.1f".format(sample.gpuLoad)}%, " +
                                    "cpu=${"%.1f".format(sample.cpuLoad)}%, " +
                                    "fps=${sample.fps}")
                        }
                    }
                    delay(sampleIntervalMs)
                } catch (e: Exception) {
                    logManager.e(TAG, "Sampling error", e)
                    delay(500) // 出错后缩短间隔，快速恢复
                }
            }
            logManager.i(TAG, "Sampling loop stopped, total samples=$sampleCount")
        }
    }

    private fun collectSample(): MetricsSample {
        val timestamp = System.currentTimeMillis()

        // 采集 GPU 负载
        val gpuLoad = readGpuLoad()

        // 采集 GPU 频率
        val gpuFreq = readGpuFreq()

        // 采集 CPU 负载（两次采样差值法）
        val cpuLoad = readCpuLoad()

        // 采集内存使用
        val memoryUsed = readMemoryUsed()
        val memoryTotal = readMemoryTotal()

        // 采集温度
        val temperature = readTemperature()

        // 计算FPS
        val fps = calculateFps()

        // 通过帧率和GPU负载估算实体数量
        val entityEstimate = estimateEntityCount(gpuLoad, fps)

        return MetricsSample(
            timestamp = timestamp,
            gpuLoad = gpuLoad,
            gpuFreqMhz = gpuFreq,
            cpuLoad = cpuLoad,
            fps = fps,
            frameTimeMs = if (fps > 0) 1000f / fps else 33f,
            memoryUsedMb = memoryUsed,
            memoryTotalMb = memoryTotal,
            temperature = temperature,
            entityEstimate = entityEstimate
        )
    }

    /**
     * 记录采样数据到日志
     */
    private fun logSample(sample: MetricsSample) {
        val data = mapOf<String, Any>(
            "ts" to sample.timestamp,
            "gpu" to "%.1f".format(sample.gpuLoad),
            "gpu_freq" to sample.gpuFreqMhz,
            "cpu" to "%.1f".format(sample.cpuLoad),
            "fps" to sample.fps,
            "mem_mb" to sample.memoryUsedMb,
            "temp" to "%.1f".format(sample.temperature),
            "entity_est" to sample.entityEstimate
        )
        logManager.logMetrics("SAMPLE", data)
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
            val spikeThreshold = baseline * 1.4f // 超过基线40%视为尖峰

            if (current > spikeThreshold && current > 50f) {
                logManager.i(TAG, "GPU SPIKE: current=${"%.1f".format(current)}%, " +
                        "baseline=${"%.1f".format(baseline)}%, " +
                        "ratio=${"%.2f".format(current / baseline)}x")

                bus.publish(
                    EventType.GPU_SPIKE_DETECTED,
                    "gpu_load" to current,
                    "baseline" to baseline,
                    "spike_ratio" to (current / baseline),
                    "fps" to sample.fps,
                    "entity_estimate" to sample.entityEstimate
                )
            }
        }

        // 检测实体数量激增
        if (sample.entityEstimate > 40 && sample.fps < 35) {
            bus.publish(
                EventType.ENTITY_SURGE_DETECTED,
                "entity_estimate" to sample.entityEstimate,
                "fps" to sample.fps,
                "gpu_load" to sample.gpuLoad
            )
        }
    }

    // ========== GPU 负载采集 ==========

    /**
     * 读取 GPU 负载
     * 尝试多种 sysfs 路径，兼容不同设备（PowerVR/Adreno/Mali）
     */
    private fun readGpuLoad(): Float {
        // 优先尝试 PowerVR 路径（荣耀X60）
        for (path in powervrGpuPaths) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) continue

                val content = file.readText().trim()
                if (content.isEmpty()) continue

                val value = parseGpuBusy(content)
                if (value > 0f && value <= 100f) {
                    return value
                }
            } catch (e: Exception) {
                // 继续下一个
            }
        }

        // 所有路径都失败，通过GPU频率估算负载
        return estimateGpuLoadFromFreq()
    }

    /**
     * 解析GPU busy值
     */
    private fun parseGpuBusy(content: String): Float {
        return when {
            content.contains(" ") -> {
                // 格式如 "busy total" (kgsl格式)
                val parts = content.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busy = parts[0].toFloatOrNull() ?: 0f
                    val total = parts[1].toFloatOrNull() ?: 0f
                    if (total > 0) (busy / total) * 100f else 0f
                } else 0f
            }
            else -> {
                // 直接是百分比数值
                val value = content.toFloatOrNull() ?: 0f
                value
            }
        }
    }

    /**
     * 读取GPU当前频率（MHz）
     */
    private fun readGpuFreq(): Int {
        for (path in gpuFreqPaths) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) continue

                val content = file.readText().trim()
                val freqHz = content.toIntOrNull() ?: continue

                // 转换为MHz
                return if (freqHz > 1000000) freqHz / 1000000 else freqHz / 1000
            } catch (e: Exception) {
                continue
            }
        }
        return 0
    }

    /**
     * 通过GPU频率估算负载（当无法直接读取负载时）
     */
    private fun estimateGpuLoadFromFreq(): Float {
        val freq = readGpuFreq()
        if (freq <= 0) return 25f // 无数据时返回低值

        // 假设最大频率约为目标值的比例
        // 荣耀X60 PowerVR GPU 最大约 600-800MHz
        val maxFreqEstimate = 700f
        val load = (freq.toFloat() / maxFreqEstimate) * 100f
        return load.coerceIn(5f, 95f)
    }

    // ========== CPU 负载采集 ==========

    /**
     * 读取 CPU 负载
     * 通过 /proc/stat 两次采样的差值计算真实CPU使用率
     */
    private fun readCpuLoad(): Float {
        return try {
            val stat = java.io.File("/proc/stat").readText()
            val lines = stat.lines()
            if (lines.isEmpty()) return 0f

            val parts = lines[0].trim().split("\\s+".toRegex())
            if (parts.size < 8) return 0f

            // /proc/stat cpu行: user nice system idle iowait irq softirq steal guest guest_nice
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts[5].toLongOrNull() ?: 0L
            val irq = parts[6].toLongOrNull() ?: 0L
            val softirq = parts[7].toLongOrNull() ?: 0L

            val total = user + nice + system + idle + iowait + irq + softirq
            val idleTotal = idle + iowait

            if (!cpuLoadInitialized) {
                // 第一次采样，保存基线
                lastCpuTotal = total
                lastCpuIdle = idleTotal
                cpuLoadInitialized = true
                return 0f // 第一次无法计算，返回0
            }

            // 计算差值
            val totalDiff = total - lastCpuTotal
            val idleDiff = idleTotal - lastCpuIdle

            // 保存当前值
            lastCpuTotal = total
            lastCpuIdle = idleTotal

            if (totalDiff <= 0) return 0f

            val cpuUsage = ((totalDiff - idleDiff).toFloat() / totalDiff.toFloat()) * 100f
            cpuUsage.coerceIn(0f, 100f)
        } catch (e: Exception) {
            logManager.w(TAG, "Failed to read CPU load: ${e.message}")
            0f
        }
    }

    // ========== 内存采集 ==========

    /**
     * 读取已用内存（MB）
     */
    private fun readMemoryUsed(): Int {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            ((mi.totalMem - mi.availMem) / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 读取总内存（MB）
     */
    private fun readMemoryTotal(): Int {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    // ========== 温度采集 ==========

    /**
     * 读取温度
     */
    private fun readTemperature(): Float {
        // 尝试多个thermal zone，找到CPU/GPU温度
        for (i in 0 until 15) {
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            val typePath = "/sys/class/thermal/thermal_zone$i/type"
            try {
                val type = java.io.File(typePath).readText().trim().lowercase()
                // 优先选择CPU/GPU温度
                if (type.contains("cpu") || type.contains("gpu") ||
                    type.contains("soc") || type.contains("tsens_tz_sensor")) {
                    val content = java.io.File(tempPath).readText().trim()
                    val temp = content.toFloatOrNull() ?: continue
                    // 某些设备返回毫摄氏度
                    return if (temp > 1000) temp / 1000f else temp
                }
            } catch (e: Exception) {
                continue
            }
        }

        // 如果没找到CPU/GPU温度，返回第一个可用的
        for (i in 0 until 5) {
            try {
                val path = "/sys/class/thermal/thermal_zone$i/temp"
                val content = java.io.File(path).readText().trim()
                val temp = content.toFloatOrNull() ?: continue
                return if (temp > 1000) temp / 1000f else temp
            } catch (e: Exception) {
                continue
            }
        }
        return 0f
    }

    // ========== FPS 计算 ==========

    /**
     * 真实 FPS 计算（绝不造假）
     *
     * 策略（优先级从高到低）：
     *  1. dumpsys SurfaceFlinger --latency <layer>  — 直接数帧，最准（后台 Android S+ 都允许）
     *  2. dumpsys gfxinfo <pkg> framecounter  — 读"Total frames rendered"增量 ÷ 窗口秒
     *  3. SurfaceFlinger --latency 通用行数统计
     *  4. **作为最后的 fallback**：基于 GPU/CPU 负载的近似估算（完全平滑，不做随机波动）
     *
     *  采样窗口：至少 1s 才计算一次，避免抖动
     */
    private fun calculateFps(): Int {
        val now = System.currentTimeMillis()
        val windowMs = 1000L

        // 1秒内不重算，直接返回上一次真实值
        if (now - lastFpsSampleTime < windowMs) {
            return if (realFpsValue > 0) realFpsValue else fallbackFpsEstimate()
        }
        lastFpsSampleTime = now

        // ===== 方案 1&3：SurfaceFlinger --latency 行数统计 =====
        val sfFps = runCommandForIntFps("dumpsys SurfaceFlinger --latency 2>&1")
        if (sfFps > 0) {
            realFpsValue = sfFps
            return sfFps
        }

        // ===== 原神 Layer 精确版 =====
        for (cmd in surfaceFlingerFpsCommands) {
            val v = runCommandForIntFps(cmd)
            if (v > 0) {
                realFpsValue = v
                return v
            }
        }

        // ===== 方案 2：dumpsys gfxinfo Total frames rendered 增量 =====
        for (pkg in gfxInfoPackages) {
            val v = gfxInfoFpsByPackage(pkg, now)
            if (v > 0) {
                realFpsValue = v
                return v
            }
        }

        // ===== 最后才使用 fallback 估算（零随机，基于负载）=====
        val fallback = fallbackFpsEstimate()
        if (realFpsValue < 0) realFpsValue = fallback
        return fallback
    }

    /**
     * 纯负载估算（不造假、不做随机波动）
     * 只在所有真实命令失败时使用
     */
    private fun fallbackFpsEstimate(): Int {
        val recentGpu = gpuLoadHistory.takeLast(3).average().let { if (it.isNaN()) 30f else it.toFloat() }
        val cpuNow = readCpuLoadCached()
        val combined = recentGpu * 0.65f + cpuNow * 0.35f
        return when {
            combined < 25f -> 60
            combined < 40f -> 58
            combined < 50f -> 55
            combined < 60f -> 48
            combined < 70f -> 40
            combined < 80f -> 30
            combined < 90f -> 22
            else -> 14
        }
    }

    // 最近一次 CPU 缓存（避免 1 秒内双重采样）
    private var cachedCpuAt = 0L
    private var cachedCpu = 0f
    private fun readCpuLoadCached(): Float {
        val now = System.currentTimeMillis()
        if (now - cachedCpuAt < 300L) return cachedCpu
        cachedCpuAt = now
        cachedCpu = readCpuLoad()
        return cachedCpu
    }

    /**
     * 解析 dumpsys SurfaceFlinger --latency 输出：
     *  - 格式：每行是 v1, v2, v3（3个时间戳）代表一个真实提交的帧
     *  - 我们只数 3 个数字的有效行数
     *  - 通过前后两次调用之间的行数差 ÷ 时间差秒数 得到 FPS
     */
    private var lastLatencyLineCount = -1
    private var lastLatencySampleTime = 0L

    private fun runCommandForIntFps(cmd: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            proc.waitFor(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
            val lines = proc.inputStream.bufferedReader().useLines { it.toList() }
            // 只取 3 个数字列的有效帧行
            val valid = lines.count { l ->
                val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                p.size == 3 && p.all { it.toLongOrNull() != null }
            }
            proc.destroy()
            val now = System.currentTimeMillis()
            return if (lastLatencyLineCount < 0) {
                lastLatencyLineCount = valid
                lastLatencySampleTime = now
                -1
            } else {
                val lineDiff = (valid - lastLatencyLineCount).coerceAtLeast(0)
                val dt = (now - lastLatencySampleTime).coerceAtLeast(1)
                lastLatencyLineCount = valid
                lastLatencySampleTime = now
                if (dt >= 400L) ((lineDiff * 1000f) / dt).toInt().coerceIn(0, 120) else -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 通过 gfxinfo 的 "Total frames rendered:" 增量算FPS
     */
    private fun gfxInfoFpsByPackage(pkg: String, nowMs: Long): Int {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "dumpsys gfxinfo $pkg 2>/dev/null | grep -E 'Total frames rendered|Janky frames'")
            )
            proc.waitFor(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
            val out = proc.inputStream.bufferedReader().readText()
            proc.destroy()
            val total = Regex("Total frames rendered:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
                ?: return -1
            if (lastFrameCountTotal < 0 || lastFpsWindowStart == 0L) {
                lastFrameCountTotal = total
                lastFpsWindowStart = nowMs
                return -1
            }
            val df = total - lastFrameCountTotal
            val dt = (nowMs - lastFpsWindowStart).coerceAtLeast(1)
            lastFrameCountTotal = total
            lastFpsWindowStart = nowMs
            if (dt < 600L || df < 0) return -1
            ((df * 1000f) / dt).toInt().coerceIn(0, 120)
        } catch (_: Throwable) {
            -1
        }
    }

    // ========== 实体数量估算 ==========

    /**
     * 估算实体数量 - 不做随机波动，只用稳定的比例模型
     *
     * 模型逻辑：
     * - GPU负载越高，实体越多
     * - FPS越低，实体越多
     * - 两者组合得到估算值（0~150范围）
     */
    private fun estimateEntityCount(gpuLoad: Float, fps: Int): Int {
        // 高GPU + 低FPS = 大量实体
        val loadFactor = (gpuLoad / 100f).coerceIn(0f, 1f)
        val fpsFactor = max(0f, (60f - fps) / 60f)

        // 加权计算（GPU主导，因为 GPU 负载是真数据）
        val combinedScore = loadFactor * 0.7f + fpsFactor * 0.3f

        // 映射到实体数量（0~150范围），用曲线让战斗区间更敏感
        val estimate = (combinedScore * combinedScore * 200f).toInt()
        return estimate.coerceIn(0, 180)
    }

    companion object {
        private const val TAG = "GpuFrameCollector"
    }
}
