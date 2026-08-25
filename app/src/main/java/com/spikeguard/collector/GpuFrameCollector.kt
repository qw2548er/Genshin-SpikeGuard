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

    // GPU：动态探测缓存（与 LightweightMetricsPoller 同策略，真读不到就 -1f）
    @Volatile private var cachedGpuBusyPath: String? = null
    @Volatile private var cachedGpuFreqPath: String? = null
    private var gpuProbeDone = false

    private fun isLikelyGpuDevfreqName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("gpu") ||
               lower.contains("pvr") || lower.contains("bxml") || lower.contains("bxm") ||
               lower.contains("bxe") || lower.contains("rogue") || lower.contains("img") ||
               lower.contains("mali") ||
               lower.contains("kgsl") || lower.contains("adreno") ||
               lower.contains("g3d")
    }

    private fun tryParsePercentFromString(text: String): Float? {
        val c = text.trim()
        if (c.isEmpty()) return null
        val parts = c.split("\\s+".toRegex())
        if (parts.size >= 2) {
            val a = parts[0].toFloatOrNull() ?: return null
            val b = parts[1].toFloatOrNull() ?: return null
            if (b > 0f) return ((a / b) * 100f).coerceIn(0f, 100f)
        }
        val v = c.toFloatOrNull() ?: return null
        return when {
            v in 0f..100f -> v
            v in 100f..1000f -> (v / 10f).coerceIn(0f, 100f)
            v > 1000f -> (v / 1000f).coerceIn(0f, 100f)
            else -> null
        }
    }

    private fun tryParseFreqToMhz(text: String): Float? {
        val c = text.trim()
        if (c.isEmpty()) return null
        val raw = c.toIntOrNull() ?: c.toFloatOrNull()?.toInt() ?: return null
        if (raw <= 0) return null
        return when {
            raw > 100_000_000 -> raw / 1_000_000f
            raw > 100_000     -> raw / 1_000f
            else              -> raw.toFloat()
        }
    }

    private fun probeGpuOnceAndCache() {
        // A. /proc/mtk_gpufreq（天玑必带）
        try {
            val dir = java.io.File("/proc/mtk_gpufreq/")
            if (dir.exists() && dir.isDirectory) {
                for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
                    if (!f.isFile) continue
                    try {
                        val lines = f.readLines()
                        for (l in lines) {
                            val lower = l.lowercase()
                            if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                                val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num in 0f..100f || num > 100f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                        }
                        val pure = tryParsePercentFromString(f.readText())
                        if (pure != null) { cachedGpuBusyPath = f.absolutePath; return }
                        for (l in lines) {
                            val lower = l.lowercase()
                            if (lower.contains("freq") || lower.contains("cur")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toIntOrNull() ?: continue
                                if (khz > 0) { cachedGpuFreqPath = f.absolutePath; break }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // B. /sys/class/devfreq/* 按 name 匹配 GPU
        try {
            val dir = java.io.File("/sys/class/devfreq/")
            if (dir.exists() && dir.isDirectory) {
                val subs = dir.listFiles() ?: emptyArray()
                val ranked = subs.mapNotNull { sub ->
                    try {
                        val nameF = java.io.File(sub, "name")
                        val name = if (nameF.exists()) nameF.readText().trim() else sub.name
                        if (isLikelyGpuDevfreqName(name)) {
                            val score = when {
                                name.contains("pvr", true) || name.contains("bxm", true) -> 100
                                name.contains("gpu", true) && name.contains("freq", true) -> 90
                                name.contains("gpu", true) -> 80
                                else -> 50
                            }
                            sub to score
                        } else null
                    } catch (_: Throwable) { null }
                }.sortedByDescending { it.second }
                for ((sub, _) in ranked) {
                    val loadF = java.io.File(sub, "load")
                    try {
                        if (loadF.exists()) {
                            val v = tryParsePercentFromString(loadF.readText())
                            if (v != null && v in 0f..100f) { cachedGpuBusyPath = loadF.absolutePath; return }
                        }
                    } catch (_: Throwable) {}
                    val curF = java.io.File(sub, "cur_freq")
                    try {
                        if (curF.exists() && cachedGpuFreqPath == null) {
                            if (tryParseFreqToMhz(curF.readText()) != null) cachedGpuFreqPath = curF.absolutePath
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // C. 兜底静态路径（含原 powervrGpuPaths）
        for (p in powervrGpuPaths) {
            try {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val v = tryParsePercentFromString(f.readText())
                if (v != null) { cachedGpuBusyPath = p; return }
            } catch (_: Throwable) {}
        }
        if (cachedGpuFreqPath == null) {
            for (p in gpuFreqPaths) {
                try {
                    val f = java.io.File(p)
                    if (!f.exists()) continue
                    if (tryParseFreqToMhz(f.readText()) != null) { cachedGpuFreqPath = p; break }
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 读取 GPU 负载 —— 真读不到返回 -1f，绝不返回假底座数
     */
    private fun readGpuLoad(): Float {
        if (!gpuProbeDone) { gpuProbeDone = true; probeGpuOnceAndCache() }

        cachedGpuBusyPath?.let { path ->
            try {
                val f = java.io.File(path)
                if (!f.exists()) { cachedGpuBusyPath = null; gpuProbeDone = false; return@let }
                val raw = f.readText()
                if (path.contains("mtk_gpufreq")) {
                    for (l in raw.lineSequence()) {
                        val lower = l.lowercase()
                        if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                            val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                            if (num in 0f..100f) return num
                            if (num > 100f) return (num / 10f).coerceIn(0f, 100f)
                        }
                    }
                }
                val v = tryParsePercentFromString(raw)
                if (v != null) return v
            } catch (_: Throwable) { cachedGpuBusyPath = null; gpuProbeDone = false }
        }

        cachedGpuFreqPath?.let { path ->
            try {
                val f = java.io.File(path)
                if (!f.exists()) { cachedGpuFreqPath = null; gpuProbeDone = false; return@let }
                val mhz = tryParseFreqToMhz(f.readText()) ?: return@let
                // 注意：这里用的是真实读出来的频率，比例是估算但基数是真的
                // 但如果用户的 GPU 最大频率不是 1800，这个比例就不准；
                // 我们认为"不准的估算"也比"拍脑袋的假6%"好，但如果连freq都没有就直接-1f
                return ((mhz / 1800f) * 100f).coerceIn(0f, 100f)
            } catch (_: Throwable) { cachedGpuFreqPath = null; gpuProbeDone = false }
        }
        return -1f
    }

    /**
     * 读取GPU当前频率（MHz）— 读不到返回 -1
     */
    private fun readGpuFreq(): Int {
        // 优先用动态探测到的缓存路径
        cachedGpuFreqPath?.let { path ->
            try {
                val f = java.io.File(path)
                if (f.exists()) {
                    val mhz = tryParseFreqToMhz(f.readText())
                    if (mhz != null && mhz > 0f) return mhz.toInt()
                }
            } catch (_: Throwable) {}
        }
        for (path in gpuFreqPaths) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) continue
                val content = file.readText().trim()
                val freqHz = content.toIntOrNull() ?: continue
                return if (freqHz > 1000000) freqHz / 1000000 else freqHz / 1000
            } catch (_: Throwable) { continue }
        }
        return -1
    }

    // ========== CPU 负载采集 ==========

    /**
     * 读取 CPU 负载
     * 通过 /proc/stat 两次采样的差值计算真实CPU使用率
     * —— 首帧/异常/无差分 → 返回 -1f，绝不返回假 0f
     */
    private fun readCpuLoad(): Float {
        return try {
            val stat = java.io.File("/proc/stat").readText()
            val lines = stat.lines()
            if (lines.isEmpty()) return -1f

            val parts = lines[0].trim().split("\\s+".toRegex())
            if (parts.size < 8) return -1f

            val user = parts[1].toLongOrNull() ?: return -1f
            val nice = parts[2].toLongOrNull() ?: return -1f
            val system = parts[3].toLongOrNull() ?: return -1f
            val idle = parts[4].toLongOrNull() ?: return -1f
            val iowait = parts[5].toLongOrNull() ?: return -1f
            val irq = parts[6].toLongOrNull() ?: return -1f
            val softirq = parts[7].toLongOrNull() ?: return -1f

            val total = user + nice + system + idle + iowait + irq + softirq
            val idleTotal = idle + iowait

            if (!cpuLoadInitialized) {
                lastCpuTotal = total
                lastCpuIdle = idleTotal
                cpuLoadInitialized = true
                return -1f // 第一帧只有基准，无差分 → 未知
            }

            val totalDiff = total - lastCpuTotal
            val idleDiff = idleTotal - lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idleTotal

            if (totalDiff <= 0) return -1f
            ((totalDiff - idleDiff).toFloat() / totalDiff.toFloat() * 100f).coerceIn(0f, 100f)
        } catch (_: Throwable) { -1f }
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

    private fun readBatteryTemperatureOfficial(): Float {
        return try {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: android.content.Intent = context.registerReceiver(null, ifilter)
                ?: return -1f
            val raw = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (raw == Int.MIN_VALUE) return -1f
            (raw.toFloat() / 10f).coerceIn(-1f, 120f)
        } catch (_: Throwable) { -1f }
    }

    /**
     * 读取温度 —— 优先 BatteryManager 官方 API（与 LightweightMetricsPoller 一致）
     * 真读不到返回 -1f，绝不造假
     */
    private fun readTemperature(): Float {
        // 1) 官方 BatteryManager（第一优先级，这是所有"设备信息"App读温度的方式）
        val bat = readBatteryTemperatureOfficial()
        if (bat >= 0f && bat in 0f..100f) return bat

        // 2) /sys/class/power_supply/battery/temp 兜底（有些ROM魔改BatteryManager，但sysfs有效）
        for (name in listOf("temp", "temperature", "battery_temp")) {
            try {
                val v = java.io.File("/sys/class/power_supply/battery/$name")
                    .readText().trim().toFloatOrNull() ?: continue
                if (v <= 0f) continue
                val c = if (v > 200f) v / 10f else v
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }

        // 3) thermal_zone：按类型匹配 CPU/GPU/SOC
        val wantTypes = listOf("cpu", "gpu", "soc", "tsens", "mtkts", "mtkt", "pm8998_tz", "ncp", "tzn", "tmep")
        for (i in 0 until 30) {
            try {
                val type = java.io.File("/sys/class/thermal/thermal_zone$i/type")
                    .readText().trim().lowercase()
                if (!wantTypes.any { type.contains(it) }) continue
                val raw = java.io.File("/sys/class/thermal/thermal_zone$i/temp")
                    .readText().trim().toFloatOrNull() ?: continue
                val c = if (raw > 1000f) raw / 1000f else raw
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }
        return -1f
    }

    // ========== FPS 计算 ==========

    /**
     * 真实 FPS 计算（绝不造假）
     *
     * 策略（优先级从高到低）：
     *  1. dumpsys SurfaceFlinger --latency <layer>  — 直接数帧
     *  2. dumpsys gfxinfo <pkg> framecounter  — 读"Total frames rendered"增量 ÷ 窗口秒
     *  3. SurfaceFlinger --latency 通用行数统计
     *  所有真实方案失败 → 返回 -1（UI显示--），**绝不做负载估算造假**
     */
    private fun calculateFps(): Int {
        val now = System.currentTimeMillis()
        val windowMs = 1000L

        // 1秒内不重算，直接返回上一次真实值（没有就返回 -1，绝不猜测）
        if (now - lastFpsSampleTime < windowMs) {
            return if (realFpsValue >= 0) realFpsValue else -1
        }
        lastFpsSampleTime = now

        // ===== 方案 1&3：SurfaceFlinger --latency 行数统计 =====
        val sfFps = runCommandForIntFps("dumpsys SurfaceFlinger --latency 2>&1")
        if (sfFps > 0) { realFpsValue = sfFps; return sfFps }

        // ===== 原神 Layer 精确版 =====
        for (cmd in surfaceFlingerFpsCommands) {
            val v = runCommandForIntFps(cmd)
            if (v > 0) { realFpsValue = v; return v }
        }

        // ===== 方案 2：dumpsys gfxinfo Total frames rendered 增量 =====
        for (pkg in gfxInfoPackages) {
            val v = gfxInfoFpsByPackage(pkg, now)
            if (v > 0) { realFpsValue = v; return v }
        }

        // ===== 所有真实方案都失败 → 标记未知，返回 -1（UI显示--）=====
        realFpsValue = -1
        return -1
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
     * 估算实体数量
     *
     * 关键规则：任何一项基础数据未知（gpuLoad<0 或 fps<0）→ 直接返回 -1（UI显示--）
     * 绝不基于假底座数据（如gpu=0,fps=0）算出一个"~18"来糊弄用户。
     */
    private fun estimateEntityCount(gpuLoad: Float, fps: Int): Int {
        if (gpuLoad < 0f || fps < 0) return -1
        val loadFactor = (gpuLoad / 100f).coerceIn(0f, 1f)
        val fpsFactor = max(0f, (60f - fps) / 60f)
        val combinedScore = loadFactor * 0.7f + fpsFactor * 0.3f
        val estimate = (combinedScore * combinedScore * 200f).toInt()
        return estimate.coerceIn(0, 180)
    }

    companion object {
        private const val TAG = "GpuFrameCollector"
    }
}
