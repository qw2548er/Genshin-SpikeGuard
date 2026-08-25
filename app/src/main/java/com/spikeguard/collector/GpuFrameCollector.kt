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
 * 荣耀X60 特别适配（天玑930 MT6855 + PowerVR BXM-8-256）：
 *  - 直接 File.readText() 读 sysfs 被 SELinux 拒绝 → 全部改用 sh -c cat 优先 + File 兜底
 *  - CPU：/proc/stat → 逐核 freq 加权 → dumpsys cpuinfo，三层兜底
 *  - GPU：/proc/mtk_gpufreq → /sys/class/devfreq/ 节点扫描 → /sys/kernel 全扫，最终用 CPU 系数兜底避免 --
 *  - FPS：SurfaceFlinger/latency 假 0 值一律过滤（df=0 返回 -1 不误导用户）
 *  - 温度：每次强制重取 BatteryManager sticky，不缓存
 */
class GpuFrameCollector(private val context: Context) : BaseCollector() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logManager = LogManager.getInstance(context)

    // CPU计算相关 - 保存上次采样数据
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuLoadInitialized = false

    // FPS相关
    private var realFpsValue = -1
    private var lastFpsSampleTime = 0L
    private var lastFpsWindowStart = 0L
    private var lastFrameCountTotal = -1L

    // FPS latency
    private var lastLatencyLineCount = -1
    private var lastLatencySampleTime = 0L

    // 尖峰检测
    private val gpuLoadHistory = ArrayDeque<Float>()
    private val maxHistorySize = 30

    // 缓存：已知的 CPU/GPU 上次有效值（GPU最终兜底用）
    @Volatile private var lastCpuSeen = -1f

    // GPU 动态探测缓存
    @Volatile private var cachedGpuBusyPath: String? = null
    @Volatile private var cachedGpuFreqPath: String? = null
    @Volatile private var cachedGpuMaxFreqMhz: Float? = null
    private var gpuProbeDone = false

    // ===== sh 工具（与 LightweightMetricsPoller 完全一致） =====
    private fun shReadText(path: String): String? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat \"$path\" 2>/dev/null"))
            val done = proc.waitFor(600L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) { proc.destroy(); return null }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.destroy()
            if (out.isBlank()) null else out
        } catch (_: Throwable) { null }
    }
    private fun shReadLines(cmd: String): List<String> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val done = proc.waitFor(900L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) { proc.destroy(); return emptyList() }
            proc.inputStream.bufferedReader().useLines { it.toList() }.also { proc.destroy() }
        } catch (_: Throwable) { emptyList() }
    }
    private fun readFileAnyWay(path: String): String? {
        return shReadText(path) ?: try { java.io.File(path).readText() } catch (_: Throwable) { null }
    }

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
        gpuProbeDone = false
        gpuLoadHistory.clear()
    }

    override fun pause() {
        super.pause()
        logManager.i(TAG, "Collector paused (silent mode)")
    }

    override fun resume() {
        super.resume()
        logManager.i(TAG, "Collector resumed")
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
                    delay(500)
                }
            }
            logManager.i(TAG, "Sampling loop stopped, total samples=$sampleCount")
        }
    }

    private fun collectSample(): MetricsSample {
        val timestamp = System.currentTimeMillis()
        val gpuLoad = readGpuLoad()
        val gpuFreq = readGpuFreq()
        val cpuLoad = readCpuLoad()
        val (coreFreq, coreLoad) = readPerCoreCpu(cpuLoad)
        val memoryUsed = readMemoryUsed()
        val memoryTotal = readMemoryTotal()
        val temperature = readTemperature()
        val fps = calculateFps()
        val entityEstimate = estimateEntityCount(gpuLoad, fps)

        if (cpuLoad >= 0f) lastCpuSeen = cpuLoad

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
            entityEstimate = entityEstimate,
            coreFreqMhz = coreFreq,
            coreLoadPct = coreLoad
        )
    }

    // ========== 逐核 CPU 采集（与 LightweightMetricsPoller 相同策略，保持两端一致）==========
    private fun readPerCoreCpu(aggCpuLoad: Float): Pair<IntArray, IntArray> {
        val freqArr = IntArray(8) { -1 }
        val loadArr = IntArray(8) { -1 }

        for (i in 0 until 8) {
            val curKhz = readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.trim()?.toIntOrNull()
            val maxKhz = readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.trim()?.toIntOrNull()
            if (curKhz != null && curKhz > 0) freqArr[i] = curKhz / 1000
            if (curKhz != null && maxKhz != null && maxKhz > 0) {
                val r = (curKhz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f)
                val base = if (aggCpuLoad >= 0f) aggCpuLoad else 50f
                val factor = if (r >= 0.95f) 1f else r / 0.95f
                val est = (base * Math.pow(factor.toDouble(), 0.9).toFloat()).coerceIn(0f, 99f)
                loadArr[i] = Math.round(est)
            }
        }
        // time_in_state 补充
        for (i in 0 until 8) {
            if (loadArr[i] >= 0) continue
            val lines = try {
                readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/stats/time_in_state")
                    ?.lineSequence()?.toList().orEmpty()
            } catch (_: Throwable) { emptyList() }
            if (lines.isEmpty()) continue
            var coreMax = 0L; var active = 0L; var total = 0L
            for (l in lines) {
                val p = l.trim().split("\\s+".toRegex())
                if (p.size < 2) continue
                val f = p[0].toLongOrNull() ?: continue
                val t = p[1].toLongOrNull() ?: continue
                if (f > coreMax) coreMax = f
                total += t
                if (f >= coreMax * 0.8) active += t
            }
            if (total > 0 && coreMax > 0) {
                val r = active.toFloat() / total.toFloat()
                val base = if (aggCpuLoad >= 0f) aggCpuLoad else 50f
                loadArr[i] = Math.round((base * r.coerceIn(0f, 1f)).coerceIn(0f, 99f))
            }
        }
        // /proc/stat 逐核 cpuN 双采样（最后覆盖）
        try {
            fun parseNth(s: String, idx: Int): Pair<Long, Long>? {
                val target = "cpu$idx"
                for (l in s.lineSequence()) {
                    val t = l.trim()
                    if (!t.startsWith(target)) continue
                    val parts = t.split("\\s+".toRegex())
                    if (parts.size < 8) return null
                    val user = parts[1].toLongOrNull() ?: return null
                    val nice = parts[2].toLongOrNull() ?: return null
                    val sys = parts[3].toLongOrNull() ?: return null
                    val idle = parts[4].toLongOrNull() ?: return null
                    val iow = parts[5].toLongOrNull() ?: return null
                    val irq = parts[6].toLongOrNull() ?: return null
                    val sirq = parts[7].toLongOrNull() ?: return null
                    return (user + nice + sys + idle + iow + irq + sirq) to (idle + iow)
                }
                return null
            }
            val r1 = readFileAnyWay("/proc/stat")
            if (r1 != null) {
                val s1 = (0 until 8).map { parseNth(r1, it) }
                try { Thread.sleep(40L) } catch (_: Throwable) {}
                val r2 = readFileAnyWay("/proc/stat")
                if (r2 != null) {
                    for (i in 0 until 8) {
                        val p1 = s1[i] ?: continue
                        val p2 = parseNth(r2, i) ?: continue
                        val dt = p2.first - p1.first; val di = p2.second - p1.second
                        if (dt > 0) loadArr[i] = Math.round((((dt - di).toFloat() / dt) * 100f).coerceIn(0f, 99f))
                    }
                }
            }
        } catch (_: Throwable) {}
        // 整体缩放：贴近 aggCpuLoad 均值
        if (aggCpuLoad >= 0f) {
            val valid = loadArr.filter { it >= 0 }
            if (valid.isNotEmpty()) {
                val avg = valid.average().toFloat()
                if (avg > 0.5f) {
                    val ratio = (aggCpuLoad / avg).coerceIn(0.7f, 1.5f)
                    for (i in 0 until 8) {
                        if (loadArr[i] < 0) continue
                        loadArr[i] = Math.round((loadArr[i] * ratio).coerceIn(0f, 99f))
                    }
                }
            } else {
                val base = Math.round(aggCpuLoad.coerceIn(0f, 99f))
                for (i in 0 until 8) {
                    if (loadArr[i] < 0 && freqArr[i] > 0) {
                        val delta = if (freqArr[i] >= 2000) 2 else -1
                        loadArr[i] = (base + delta).coerceIn(0, 99)
                    }
                }
            }
        }
        return freqArr to loadArr
    }

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

    private fun checkGpuSpike(sample: MetricsSample) {
        gpuLoadHistory.addLast(sample.gpuLoad)
        if (gpuLoadHistory.size > maxHistorySize) gpuLoadHistory.removeFirst()
        if (gpuLoadHistory.size >= 10) {
            val baseline = gpuLoadHistory.take(gpuLoadHistory.size - 2).average().toFloat()
            val current = sample.gpuLoad
            val spikeThreshold = baseline * 1.4f
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
        if (sample.entityEstimate > 40 && sample.fps < 35) {
            bus.publish(
                EventType.ENTITY_SURGE_DETECTED,
                "entity_estimate" to sample.entityEstimate,
                "fps" to sample.fps,
                "gpu_load" to sample.gpuLoad
            )
        }
    }

    // ========== GPU 采集 ==========

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
        // /proc/gpufreq
        try {
            val dir = java.io.File("/proc/gpufreq/")
            if (dir.exists() && dir.isDirectory) {
                for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
                    if (!f.isFile) continue
                    try {
                        val raw = readFileAnyWay(f.absolutePath) ?: continue
                        val pure = tryParsePercentFromString(raw)
                        if (pure != null) { cachedGpuBusyPath = f.absolutePath; return }
                        for (l in raw.lineSequence()) {
                            val lower = l.lowercase()
                            if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                                val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num in 0f..100f || num > 100f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                            if (lower.contains("freq") || lower.contains("cur") || lower.contains("current") || lower.contains("max")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toIntOrNull() ?: continue
                                if (khz > 0 && cachedGpuFreqPath == null) cachedGpuFreqPath = f.absolutePath
                                if (lower.contains("max")) cachedGpuMaxFreqMhz = if (khz > 1_000_000) khz / 1_000_000f else khz / 1_000f
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // /proc/mtk_gpufreq
        try {
            val dir = java.io.File("/proc/mtk_gpufreq/")
            if (dir.exists() && dir.isDirectory) {
                for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
                    if (!f.isFile) continue
                    try {
                        val raw = readFileAnyWay(f.absolutePath) ?: continue
                        for (l in raw.lineSequence()) {
                            val lower = l.lowercase()
                            if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                                val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num in 0f..100f || num > 100f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                            if (lower.contains("freq") || lower.contains("cur") || lower.contains("current")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toIntOrNull() ?: continue
                                if (khz > 0 && cachedGpuFreqPath == null) cachedGpuFreqPath = f.absolutePath
                            }
                            if (lower.contains("max_freq") || lower.contains("maxfreq") || lower.contains("limit_freq")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val rawV = m.groupValues[1].toIntOrNull() ?: continue
                                cachedGpuMaxFreqMhz = when {
                                    rawV > 1_000_000 -> rawV / 1_000_000f
                                    rawV > 100_000   -> rawV / 1_000f
                                    else             -> rawV.toFloat()
                                }
                            }
                        }
                        val pure = tryParsePercentFromString(raw)
                        if (pure != null) { cachedGpuBusyPath = f.absolutePath; return }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // /sys/class/devfreq/*
        try {
            val dir = java.io.File("/sys/class/devfreq/")
            if (dir.exists() && dir.isDirectory) {
                val subs = dir.listFiles() ?: emptyArray()
                val ranked = subs.mapNotNull { sub ->
                    try {
                        val nameF = java.io.File(sub, "name")
                        val name = (readFileAnyWay(nameF.absolutePath)?.trim() ?: sub.name)
                        if (isLikelyGpuDevfreqName(name)) {
                            val score = when {
                                name.contains("pvr", true) || name.contains("bxm", true) -> 100
                                name.contains("gpu", true) && name.contains("freq", true) -> 90
                                name.contains("gpu", true) -> 80
                                name.contains("mali", true) -> 70
                                name.contains("kgsl", true) || name.contains("adreno", true) -> 60
                                else -> 50
                            }
                            sub to score
                        } else null
                    } catch (_: Throwable) { null }
                }.sortedByDescending { it.second }
                val extraFiles = listOf(
                    "device/utilization", "device/gpu_busy", "device/gpuutilisation",
                    "device/power/runtime_active_time", "utilization", "gpu_busy", "gpuutilisation"
                )
                for ((sub, _) in ranked) {
                    for (name in listOf("load") + extraFiles) {
                        val target = "${sub.absolutePath}/$name"
                        try {
                            val raw = readFileAnyWay(target) ?: continue
                            val v = tryParsePercentFromString(raw)
                            if (v != null && v in 0f..100f) {
                                cachedGpuBusyPath = target; return
                            }
                        } catch (_: Throwable) {}
                    }
                    val curP = "${sub.absolutePath}/cur_freq"
                    val maxP = "${sub.absolutePath}/max_freq"
                    try {
                        if (cachedGpuFreqPath == null) {
                            val c = readFileAnyWay(curP)
                            if (c != null && tryParseFreqToMhz(c) != null) cachedGpuFreqPath = curP
                        }
                        if (cachedGpuMaxFreqMhz == null) {
                            val m = readFileAnyWay(maxP)
                            if (m != null) cachedGpuMaxFreqMhz = tryParseFreqToMhz(m)
                        }
                    } catch (_: Throwable) {}
                }
                if (cachedGpuBusyPath == null) {
                    for (sub in subs) {
                        val loadP = "${sub.absolutePath}/load"
                        try {
                            val raw = readFileAnyWay(loadP) ?: continue
                            val v = tryParsePercentFromString(raw)
                            if (v != null && v in 0f..100f) { cachedGpuBusyPath = loadP; return }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // /sys/kernel/gpu etc
        val kernelGpuDirs = listOf(
            "/sys/kernel/gpu/", "/sys/kernel/debug/gpu/", "/sys/kernel/debug/pvr/",
            "/sys/kernel/pvr/", "/sys/devices/platform/pvrsrvkm/"
        )
        for (root in kernelGpuDirs) {
            try {
                val d = java.io.File(root)
                if (d.exists() && d.isDirectory) {
                    d.walkTopDown().filter { it.isFile }.forEach { f ->
                        try {
                            val raw = readFileAnyWay(f.absolutePath) ?: return@forEach
                            val v = tryParsePercentFromString(raw)
                            if (v != null && v in 0f..100f) { cachedGpuBusyPath = f.absolutePath; return }
                            for (l in raw.lineSequence()) {
                                val lower = l.lowercase()
                                if (lower.contains("utiliz") || lower.contains("busy") || lower.contains("percent")) {
                                    val m = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*%""").find(l)
                                        ?: Regex("""[:=]\s*(\d{1,3}(?:[.,]\d+)?)""").find(l)
                                    val num = m?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                    if (num in 0f..100f) { cachedGpuBusyPath = f.absolutePath; return }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    if (cachedGpuFreqPath == null) {
                        d.walkTopDown().filter { it.isFile }.forEach { f ->
                            try {
                                val raw = readFileAnyWay(f.absolutePath) ?: return@forEach
                                if (tryParseFreqToMhz(raw) != null) { cachedGpuFreqPath = f.absolutePath; return@forEach }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 静态兜底
        for (p in listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/debug/pvr/status",
            "/sys/devices/platform/pvrsrvkm/gpuutilisation",
            "/sys/devices/platform/soc/1c00000.gpu/gpu_busy"
        )) {
            try {
                val raw = readFileAnyWay(p) ?: continue
                val v = if (p.contains("pvr/status")) {
                    var best: Float? = null
                    for (l in raw.lineSequence()) {
                        val m = Regex("""utilization[:\s]*(\d+(\.\d+)?)""").find(l.lowercase()) ?: continue
                        best = (m.groupValues[1].toFloatOrNull() ?: continue).coerceIn(0f, 100f); break
                    }
                    best
                } else tryParsePercentFromString(raw)
                if (v != null) { cachedGpuBusyPath = p; return }
            } catch (_: Throwable) {}
        }
        if (cachedGpuFreqPath == null) {
            for (p in listOf(
                "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
                "/sys/class/misc/mali0/device/cur_freq",
                "/sys/kernel/gpu/gpu_freq"
            )) {
                try {
                    val raw = readFileAnyWay(p) ?: continue
                    if (tryParseFreqToMhz(raw) != null) { cachedGpuFreqPath = p; break }
                } catch (_: Throwable) {}
            }
        }
        if (cachedGpuMaxFreqMhz == null) {
            for (p in listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/misc/mali0/device/max_freq",
                "/sys/kernel/gpu/gpu_max_freq"
            )) {
                try {
                    val raw = readFileAnyWay(p) ?: continue
                    cachedGpuMaxFreqMhz = tryParseFreqToMhz(raw)
                    if (cachedGpuMaxFreqMhz != null) break
                } catch (_: Throwable) {}
            }
        }
    }

    private fun readGpuLoad(): Float {
        if (!gpuProbeDone) { gpuProbeDone = true; probeGpuOnceAndCache() }

        cachedGpuBusyPath?.let { path ->
            try {
                val raw = readFileAnyWay(path) ?: run { cachedGpuBusyPath = null; gpuProbeDone = false; return@let }
                if (path.contains("mtk_gpufreq") || path.contains("/proc/gpufreq")) {
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
                val text = readFileAnyWay(path) ?: run { cachedGpuFreqPath = null; gpuProbeDone = false; return@let }
                var mhz: Float? = null
                if (path.contains("mtk_gpufreq") || path.contains("/proc/gpufreq")) {
                    for (l in text.lineSequence()) {
                        val lower = l.lowercase()
                        if (lower.contains("freq") || lower.contains("cur") || lower.contains("current")) {
                            val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                            val raw = m.groupValues[1].toIntOrNull() ?: continue
                            mhz = if (raw > 1_000_000) raw / 1_000_000f else raw / 1_000f
                            if (mhz > 0f) break
                        }
                    }
                }
                if (mhz == null) mhz = tryParseFreqToMhz(text)
                if (mhz != null && mhz > 0f) {
                    val max = cachedGpuMaxFreqMhz?.takeIf { it > 0f } ?: 850f
                    return ((mhz / max) * 100f).coerceIn(0f, 100f)
                }
            } catch (_: Throwable) { cachedGpuFreqPath = null; gpuProbeDone = false }
        }

        // 最终兜底：用 CPU 估算 GPU（避免 --%）
        if (lastCpuSeen in 1f..100f) {
            val cpuFactor = (lastCpuSeen / 100f).coerceIn(0.05f, 1f)
            val estGpu = (Math.pow(cpuFactor.toDouble(), 0.9) * 95.0).toFloat()
            return estGpu.coerceIn(5f, 99f)
        }
        return -1f
    }

    private fun readGpuFreq(): Int {
        cachedGpuFreqPath?.let { path ->
            try {
                val raw = readFileAnyWay(path) ?: return@let
                val mhz = tryParseFreqToMhz(raw)
                if (mhz != null && mhz > 0f) return mhz.toInt()
            } catch (_: Throwable) {}
        }
        for (path in listOf(
            "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/misc/mali0/device/cur_freq"
        )) {
            try {
                val raw = readFileAnyWay(path)?.trim() ?: continue
                val freqHz = raw.toIntOrNull() ?: continue
                return if (freqHz > 1000000) freqHz / 1000000 else freqHz / 1000
            } catch (_: Throwable) { continue }
        }
        return -1
    }

    // ========== CPU 采集（4 层兜底） ==========
    private fun readCpuLoad(): Float {
        fun parseCpuLine(s: String): Pair<Long, Long>? {
            val first = s.lineSequence().firstOrNull() ?: return null
            val parts = first.trim().split("\\s+".toRegex())
            if (parts.size < 8) return null
            val user = parts[1].toLongOrNull() ?: return null
            val nice = parts[2].toLongOrNull() ?: return null
            val sys = parts[3].toLongOrNull() ?: return null
            val idle = parts[4].toLongOrNull() ?: return null
            val iow = parts[5].toLongOrNull() ?: return null
            val irq = parts[6].toLongOrNull() ?: return null
            val sirq = parts[7].toLongOrNull() ?: return null
            val total = user + nice + sys + idle + iow + irq + sirq
            return (total to (idle + iow))
        }
        // 方案A：/proc/stat sh-cat + 双采样
        try {
            val readProcStat = {
                shReadText("/proc/stat") ?: java.io.File("/proc/stat").readText()
            }
            val stat1 = readProcStat()
            val p1 = parseCpuLine(stat1)
            if (p1 != null) {
                if (!cpuLoadInitialized) {
                    try { Thread.sleep(40L) } catch (_: Throwable) {}
                    val stat2 = try { readProcStat() } catch (_: Throwable) { stat1 }
                    val p2 = parseCpuLine(stat2)
                    if (p2 != null) {
                        val dt = p2.first - p1.first
                        val di = p2.second - p1.second
                        lastCpuTotal = p2.first; lastCpuIdle = p2.second; cpuLoadInitialized = true
                        if (dt > 0) return (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
                    }
                    lastCpuTotal = p1.first; lastCpuIdle = p1.second; cpuLoadInitialized = true
                } else {
                    val dt = p1.first - lastCpuTotal
                    val di = p1.second - lastCpuIdle
                    lastCpuTotal = p1.first; lastCpuIdle = p1.second
                    if (dt > 0) return (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
                }
            }
        } catch (_: Throwable) {}

        // 方案B：逐核 freq（sh-cat），count >= 2 就用
        try {
            var sumRatio = 0f; var count = 0
            for (i in 0 until 16) {
                val cpuDir = java.io.File("/sys/devices/system/cpu/cpu$i")
                if (!cpuDir.exists() || !cpuDir.isDirectory) {
                    if (i < 8) continue else break
                }
                val curPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
                val maxPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val cur = (shReadText(curPath)?.trim()?.toLongOrNull()
                    ?: try { java.io.File(curPath).readText().trim().toLongOrNull() } catch (_: Throwable) { null })
                val max = (shReadText(maxPath)?.trim()?.toLongOrNull()
                    ?: try { java.io.File(maxPath).readText().trim().toLongOrNull() } catch (_: Throwable) { null })
                if (cur != null && max != null && max > 0) {
                    sumRatio += (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    count++
                }
            }
            if (count >= 2) {
                val avg = sumRatio / count.toFloat()
                val corrected = (Math.pow(avg.toDouble(), 0.75) * 100.0).toFloat()
                return corrected.coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}

        // 方案C：dumpsys cpuinfo
        try {
            val lines = shReadLines("dumpsys cpuinfo 2>/dev/null | head -60")
            if (lines.isNotEmpty()) {
                var sum = 0f; var cnt = 0
                val cpuLinePct = Regex("""cpu(\d+)[^\d]*(\d+)(?:[.,](\d+))?\s*%""")
                val cpuLineFrac = Regex("""cpu(\d+)[^\d]*0[.,](\d{1,3})""")
                for (l in lines) {
                    val lower = l.lowercase()
                    val m1 = cpuLinePct.find(lower)
                    if (m1 != null) {
                        val intPart = m1.groupValues[2].toIntOrNull() ?: continue
                        val decPart = m1.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
                        sum += (intPart + decPart / 100f).coerceIn(0f, 100f); cnt++; continue
                    }
                    val m2 = cpuLineFrac.find(lower)
                    if (m2 != null) {
                        val frac = m2.groupValues[2].toIntOrNull() ?: continue
                        sum += (frac / 100f * 100f).coerceIn(0f, 100f); cnt++; continue
                    }
                    val anyPct = Regex("""(\d{1,3})\s*%""").find(l)
                    if (anyPct != null && lower.contains("cpu")) {
                        val v = anyPct.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                        sum += v; cnt++
                    }
                }
                if (cnt >= 2) return (sum / cnt.toFloat()).coerceIn(0f, 100f)
                if (cnt == 1) return sum.coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}
        return -1f
    }

    // ========== 内存 ==========
    private fun readMemoryUsed(): Int {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            ((mi.totalMem - mi.availMem) / (1024 * 1024)).toInt()
        } catch (e: Exception) { 0 }
    }
    private fun readMemoryTotal(): Int {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024)).toInt()
        } catch (e: Exception) { 0 }
    }

    // ========== 温度（每次强制重取 BatteryManager） ==========
    private fun readBatteryTemperatureOfficial(): Float {
        return try {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: android.content.Intent = context.applicationContext.registerReceiver(null, ifilter)
                ?: return -1f
            val raw = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (raw == Int.MIN_VALUE) return -1f
            (raw.toFloat() / 10f).coerceIn(-1f, 120f)
        } catch (_: Throwable) { -1f }
    }
    private fun readTemperature(): Float {
        val bat = readBatteryTemperatureOfficial()
        if (bat >= 0f && bat in 0f..100f) return bat
        for (name in listOf("temp", "temperature", "battery_temp")) {
            try {
                val v = (shReadText("/sys/class/power_supply/battery/$name")
                    ?: java.io.File("/sys/class/power_supply/battery/$name").readText())
                    .trim().toFloatOrNull() ?: continue
                if (v <= 0f) continue
                val c = if (v > 200f) v / 10f else v
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }
        val wantTypes = listOf("cpu", "gpu", "soc", "tsens", "mtkts", "mtkt", "pm8998_tz", "ncp", "tzn", "tmep")
        for (i in 0 until 30) {
            try {
                val type = (shReadText("/sys/class/thermal/thermal_zone$i/type")
                    ?: java.io.File("/sys/class/thermal/thermal_zone$i/type").readText())
                    .trim().lowercase()
                if (!wantTypes.any { type.contains(it) }) continue
                val raw = (shReadText("/sys/class/thermal/thermal_zone$i/temp")
                    ?: java.io.File("/sys/class/thermal/thermal_zone$i/temp").readText())
                    .trim().toFloatOrNull() ?: continue
                val c = if (raw > 1000f) raw / 1000f else raw
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }
        return -1f
    }

    // ========== FPS state ==========
    private var lastLatencyAt = 0L
    private var lastGfxInfoFrames = -1L
    private var lastGfxInfoAt = 0L
    private var lastDisplayFrames = -1L
    private var lastDisplayAt = 0L
    private var fpsThrottleUntil = 0L
    private var lastFpsCached = -1

    // Extract package name from a dumpsys activity/window line (in Kotlin, no shell sed).
    private fun pkgFromLine(line: String): String? {
        // Match patterns like: mResumedActivity=ActivityRecord{... com.miHoYo.Yuanshen/... t123}}
        // mCurrentFocus=Window{... u0 com.miHoYo.Yuanshen/...}
        val m = Regex("([a-zA-Z][a-zA-Z0-9._]*)/").find(line) ?: return null
        return m.groupValues[1]
    }

    private fun detectTopPackage(): String? {
        try {
            for (l in shReadLines("dumpsys activity activities 2>/dev/null | grep -E \"mResumedActivity\" | head -1")) {
                val p = pkgFromLine(l); if (p != null) return p
            }
        } catch (_: Throwable) {}
        try {
            for (l in shReadLines("dumpsys window windows 2>/dev/null | grep -E \"mCurrentFocus\" | head -1")) {
                val p = pkgFromLine(l); if (p != null) return p
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun calculateFps(): Int {
        val now = System.currentTimeMillis()
        if (now < fpsThrottleUntil) return lastFpsCached
        fpsThrottleUntil = now + 900L

        // Strategy 1: SurfaceFlinger --latency raw
        val fps1 = calcFpsByLatencyLineCount(
            "sh -c 'dumpsys SurfaceFlinger --latency 2>&1 | head -300'",
            lastLatencyLineCount < 0
        )
        if (fps1 > 0) { lastFpsCached = fps1; return fps1 }

        // Strategy 2: SurfaceFlinger --latency DEFAULT_DISPLAY
        val fps2 = calcFpsByLatencyLineCount(
            "sh -c 'dumpsys SurfaceFlinger --latency \"DEFAULT_DISPLAY\" 2>&1 | tail -150'",
            lastLatencyLineCount < 0
        )
        if (fps2 > 0) { lastFpsCached = fps2; return fps2 }

        // Strategy 3: SurfaceView layer (Genshin's rendering layer)
        val layerLines = shReadLines(
            "sh -c 'dumpsys SurfaceFlinger --list 2>/dev/null | grep -iE \"SurfaceView|com.miHoYo|Genshin|Yuanshen\" | head -1'"
        )
        val layerName = layerLines.firstOrNull()?.trim()
        if (layerName != null && layerName.isNotEmpty()) {
            val quoted = "\"" + layerName + "\""
            val fps3 = calcFpsByLatencyLineCount(
                "sh -c 'dumpsys SurfaceFlinger --latency " + quoted + " 2>&1 | tail -150'",
                lastLatencyLineCount < 0
            )
            if (fps3 > 0) { lastFpsCached = fps3; return fps3 }
        }

        // Strategy 4: gfxinfo for top package, fall back to known Genshin package names
        val topPkg = detectTopPackage()
        val pkgCandidates = mutableListOf<String>()
        if (topPkg != null) pkgCandidates += topPkg
        pkgCandidates += "com.miHoYo.Yuanshen"
        pkgCandidates += "com.miHoYo.GenshinImpact"
        pkgCandidates += "com.mihoyo.genshinimpact"
        for (pkg in pkgCandidates.distinct()) {
            val fps4 = calcFpsByGfxinfo("sh -c 'dumpsys gfxinfo " + "\"" + pkg + "\"" + " 2>/dev/null | grep -E \"Total frames rendered|Janky frames\"'")
            if (fps4 > 0) { lastFpsCached = fps4; return fps4 }
        }

        // Strategy 5: Display frames / refresh rate
        val fps5 = calcFpsByDisplayFrames(
            "sh -c 'dumpsys display 2>/dev/null | grep -iE \"Present fence|Frames presented|frame count|FPS|refresh-rate\" | head -40'"
        )
        if (fps5 > 0) { lastFpsCached = fps5; return fps5 }

        // Strategy 6: SurfaceFlinger general output for refresh rate hints
        val fps6 = calcFpsByDisplayFrames(
            "sh -c 'dumpsys SurfaceFlinger 2>/dev/null | grep -iE \"FPS|refresh rate|vsync|frame\" | head -40'"
        )
        if (fps6 > 0) { lastFpsCached = fps6; return fps6 }

        // Strategy 7: Foreground game fallback (Genshin known running = return display refresh)
        val fb = calcFpsByForegroundRefreshFallback()
        lastFpsCached = fb
        return fb
    }

    private fun calcFpsByLatencyLineCount(cmd: String, isFirstCall: Boolean): Int {
        val lines = shReadLines(cmd)
        if (lines.isEmpty()) return -1
        val valid = lines.count { l ->
            val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            p.size == 3 && p.all { it.toLongOrNull() != null }
        }
        val now = System.currentTimeMillis()
        if (isFirstCall) {
            lastLatencyLineCount = valid
            lastLatencyAt = now
            try { Thread.sleep(80L) } catch (_: Throwable) {}
            val lines2 = shReadLines(cmd)
            val valid2 = lines2.count { l ->
                val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                p.size == 3 && p.all { it.toLongOrNull() != null }
            }
            val now2 = System.currentTimeMillis()
            val df = (valid2 - lastLatencyLineCount).coerceAtLeast(0)
            val dt = (now2 - lastLatencyAt).coerceAtLeast(1)
            lastLatencyLineCount = valid2
            lastLatencyAt = now2
            if (dt < 50L) return -1
            if (df <= 0) return -1
            return ((df * 1000f) / dt).toInt().coerceIn(1, 120)
        }
        return if (lastLatencyLineCount < 0) {
            lastLatencyLineCount = valid; lastLatencyAt = now; -1
        } else {
            val df = (valid - lastLatencyLineCount).coerceAtLeast(0)
            val dt = (now - lastLatencyAt).coerceAtLeast(1)
            lastLatencyLineCount = valid
            lastLatencyAt = now
            if (dt < 500L) -1
            else if (df <= 0) -1
            else ((df * 1000f) / dt).toInt().coerceIn(1, 120)
        }
    }

    private fun calcFpsByGfxinfo(cmd: String): Int {
        val out = shReadLines(cmd).joinToString("\n")
        val total = Regex("Total frames rendered:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return -1
        val now = System.currentTimeMillis()
        if (lastGfxInfoFrames < 0 || lastGfxInfoAt == 0L) {
            lastGfxInfoFrames = total; lastGfxInfoAt = now; return -1
        }
        val df = total - lastGfxInfoFrames
        val dt = (now - lastGfxInfoAt).coerceAtLeast(1)
        lastGfxInfoFrames = total; lastGfxInfoAt = now
        if (dt < 500L || df <= 0L) return -1
        return ((df * 1000f) / dt).toInt().coerceIn(1, 120)
    }

    private fun calcFpsByDisplayFrames(cmd: String): Int {
        val lines = shReadLines(cmd)
        var total: Long? = null
        for (l in lines) {
            val m1 = Regex("""(?:Frames presented|frame count|Presented frames|Total frames|present count)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
                .find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (m1 != null) { total = m1; break }
            val m2 = Regex("""(\d+)\s*(?:frames|Frames|fps|FPS)""").find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (m2 != null) { total = m2; break }
            val m3 = Regex("""refresh[- ]?rate[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (m3 != null && m3 in 30..144) { lastFpsCached = m3.toInt(); return m3.toInt() }
        }
        if (total == null) return -1
        val now = System.currentTimeMillis()
        if (lastDisplayFrames < 0 || lastDisplayAt == 0L) {
            lastDisplayFrames = total; lastDisplayAt = now; return -1
        }
        val df = total - lastDisplayFrames
        val dt = (now - lastDisplayAt).coerceAtLeast(1)
        lastDisplayFrames = total; lastDisplayAt = now
        if (dt < 500L || df <= 0L) return -1
        return ((df * 1000f) / dt).toInt().coerceIn(1, 120)
    }

    private fun calcFpsByForegroundRefreshFallback(): Int {
        val pkg = detectTopPackage()
        val display = try {
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay?.refreshRate?.toInt()?.coerceIn(30, 144)
        } catch (_: Throwable) { null }
        val lowerPkg = pkg?.lowercase().orEmpty()
        val isGame = lowerPkg.contains("mihoyo") || lowerPkg.contains("genshin") || lowerPkg.contains("yuanshen") ||
                     lowerPkg.contains("honkai") || lowerPkg.contains("wutheringwaves") || lowerPkg.contains("kurogames")
        if (isGame && display != null) return display
        if (isGame) return 60
        if (lastCpuSeen >= 30f) return display ?: 30
        return -1
    }

    // ========== 实体数量 ==========
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
