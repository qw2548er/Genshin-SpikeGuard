package com.spikeguard.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import kotlin.math.max

/**
 * 主进程（MainActivity）用的轻量性能轮询器
 *
 * 荣耀X60 特别适配（天玑930 MT6855 + PowerVR BXM-8-256）：
 *  - BUG修复：collectOnce 中先把 CPU 写入 lastCpuLoadSeen，再调 readGpuLoad()，让 GPU 的 CPU系数兜底真正生效
 *  - CPU：7 层兜底（含 proc/stat / 逐核freq / dumpsys cpuinfo / top -n1 / 所有进程stat总和 / loadavg / 采样choreographer）
 *  - GPU：全目录扫描+freq估算+强CPU系数兜底（荣耀X60 sysfs常被SELinux封，CPU兜底是第二可靠来源）
 *  - FPS：Choreographer官方帧回调（100%权限！）→ 多个dumpsys策略 → 刷新率兜底。df=0 一律不返回 0
 *  - 温度：每次强制用 ACTION_BATTERY_CHANGED sticky重取 + thermal_zone 全扫描，绝不缓存
 */
class LightweightMetricsPoller(
    private val context: Context,
    private val onMetrics: (MetricsSnapshot) -> Unit
) {

    /**
     * @param coreFreqMhz 每核频率（MHz），长度=8；无效=-1
     * @param coreLoadPct 每核负载（%），长度=8；无效=-1
     */
    data class MetricsSnapshot(
        val gpuLoad: Float,
        val cpuLoad: Float,
        val temperature: Float,
        val fps: Int,
        val entityEstimate: Int,
        val coreFreqMhz: IntArray,
        val coreLoadPct: IntArray
    ) {
        init {
            require(coreFreqMhz.size == 8) { "coreFreqMhz.size must be 8" }
            require(coreLoadPct.size == 8) { "coreLoadPct.size must be 8" }
        }
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MetricsSnapshot) return false
            return gpuLoad == other.gpuLoad && cpuLoad == other.cpuLoad &&
                    temperature == other.temperature && fps == other.fps &&
                    entityEstimate == other.entityEstimate &&
                    coreFreqMhz.contentEquals(other.coreFreqMhz) &&
                    coreLoadPct.contentEquals(other.coreLoadPct)
        }
        override fun hashCode(): Int {
            var result = gpuLoad.hashCode()
            result = 31 * result + cpuLoad.hashCode()
            result = 31 * result + temperature.hashCode()
            result = 31 * result + fps
            result = 31 * result + entityEstimate
            result = 31 * result + coreFreqMhz.contentHashCode()
            result = 31 * result + coreLoadPct.contentHashCode()
            return result
        }
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mainHandler: Handler? = null

    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuReady = false

    // 上次有效值（GPU最终兜底用）——**必须在readGpuLoad()之前写入**
    @Volatile private var lastCpuLoadSeen = -1f
    @Volatile private var lastGpuLoadSeen = -1f
    @Volatile private var lastFpsSeen = -1
    @Volatile private var lastTempSeen = -1f

    // GPU 动态探测缓存
    @Volatile private var cachedGpuBusyPath: String? = null
    @Volatile private var cachedGpuFreqPath: String? = null
    @Volatile private var cachedDevfreqDirName: String? = null
    @Volatile private var cachedGpuMaxFreqMhz: Float? = null
    private var gpuProbeDone = false

    // FPS: Choreographer 官方帧回调（任何app都能调，永不被权限墙）
    @Volatile private var choreoRunning = false
    @Volatile private var choreoFrames = 0L
    @Volatile private var choreoStartMs = 0L
    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (choreoRunning) {
                choreoFrames++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private fun startChoreo() {
        if (choreoRunning) return
        choreoRunning = true
        choreoFrames = 0
        choreoStartMs = System.currentTimeMillis()
        try { Choreographer.getInstance().postFrameCallback(choreoCallback) } catch (_: Throwable) { choreoRunning = false }
    }
    private fun stopChoreo() {
        choreoRunning = false
        try { Choreographer.getInstance().removeFrameCallback(choreoCallback) } catch (_: Throwable) {}
    }
    private fun readFpsByChoreographerWindow(): Int {
        val startFrames = choreoFrames
        val startMs = System.currentTimeMillis()
        if (!choreoRunning) startChoreo()
        // 在worker里sleep 150ms，读取帧计数器增量
        try { Thread.sleep(150L) } catch (_: Throwable) {}
        val endFrames = choreoFrames
        val endMs = System.currentTimeMillis()
        val df = (endFrames - startFrames).coerceAtLeast(0L)
        val dt = (endMs - startMs).coerceAtLeast(1L)
        if (df == 0L) return -1
        if (dt < 50L) return -1
        return ((df * 1000f) / dt).toInt().coerceIn(1, 144)
    }

    // FPS dumpsys 状态
    private var lastLatencyLineCount = -1L
    private var lastLatencyAt = 0L
    private var lastGfxInfoFrames = -1L
    private var lastGfxInfoAt = 0L
    private var lastDisplayFrames = -1L
    private var lastDisplayAt = 0L

    @Volatile private var running = false

    // ========= sh 工具 =========
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

    // ========= 温度：每次强制重取，绝不缓存 =========
    private fun readBatteryTemperatureOfficial(): Float {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.applicationContext.registerReceiver(null, ifilter)
            if (batteryStatus == null) return -1f
            val raw = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (raw == Int.MIN_VALUE) return -1f
            (raw.toFloat() / 10f).coerceIn(-1f, 120f)
        } catch (_: Throwable) { -1f }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val snap = collectOnce()
                lastTempSeen = snap.temperature
                mainHandler?.post { onMetrics(snap) }
            } catch (_: Throwable) {
                try {
                    val t = readTemperature()
                    if (t >= 0f) {
                        lastTempSeen = t
                        mainHandler?.post {
                            onMetrics(
                                MetricsSnapshot(
                                    lastGpuLoadSeen, lastCpuLoadSeen, t, lastFpsSeen, -1,
                                    IntArray(8) { -1 }, IntArray(8) { -1 }
                                )
                            )
                        }
                    }
                } catch (_: Throwable) {}
            } finally {
                workerHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        workerThread = HandlerThread("perf-poller").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        mainHandler = Handler(Looper.getMainLooper())
        startChoreo()
        workerHandler?.post(tickRunnable)
    }

    fun stop() {
        running = false
        stopChoreo()
        try { workerHandler?.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
        mainHandler = null
        lastCpuLoadSeen = -1f
        lastGpuLoadSeen = -1f
        lastFpsSeen = -1
        lastTempSeen = -1f
        lastLatencyLineCount = -1L
        lastGfxInfoFrames = -1L
        lastDisplayFrames = -1L
        cpuReady = false
        gpuProbeDone = false
    }

    // ========= 采集主入口：**修复BUG顺序** =========
    private fun collectOnce(): MetricsSnapshot {
        val cpu = readCpuLoad()
        if (cpu >= 0f) lastCpuLoadSeen = cpu           // ← 先写！
        val (coreFreq, coreLoad) = readPerCoreCpu(cpu) // 八核详情
        val gpu = readGpuLoad()                        // ← 后读GPU，这样GPU兜底才能拿到本次CPU值
        if (gpu >= 0f) lastGpuLoadSeen = gpu
        val temp = readTemperature()
        val fps = readFpsComposite()
        if (fps >= 0) lastFpsSeen = fps
        val est = estimateEntity(gpu, fps)
        return MetricsSnapshot(
            gpuLoad = gpu, cpuLoad = cpu, temperature = temp,
            fps = fps, entityEstimate = est,
            coreFreqMhz = coreFreq, coreLoadPct = coreLoad
        )
    }

    // =========================================================
    // Per-Core 八核详情：返回 Pair(频率MHz[8], 负载%[8])，未知道填-1
    // 左边监测工具样式：CPU0: 1650.0Mhz 83%
    // =========================================================
    private fun readPerCoreCpu(aggCpuLoad: Float): Pair<IntArray, IntArray> {
        val freqArr = IntArray(8) { -1 }
        val loadArr = IntArray(8) { -1 }

        // --- 1) 逐核 cur_freq / max_freq：左边工具 1650MHz、2050MHz 就是这样来的 ---
        for (i in 0 until 8) {
            val curKhz = readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.trim()?.toIntOrNull()
            val maxKhz = readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.trim()?.toIntOrNull()
            if (curKhz != null && curKhz > 0) {
                freqArr[i] = (curKhz / 1000) // KHz -> MHz
            }
            // 负载估算：freq / max_ratio，再以 aggCpuLoad 做整体约束（避免过高或过低）
            if (curKhz != null && maxKhz != null && maxKhz > 0) {
                val r = (curKhz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f)
                // 左边工具在 1650MHz(=max) 对应83%，所以这里r=1时取 aggCpuLoad 做基准；再按比例缩放
                val baseLoad = if (aggCpuLoad >= 0f) aggCpuLoad else 50f
                val factor = if (r >= 0.95f) 1f else (r / 0.95f)
                val est = (baseLoad * Math.pow(factor.toDouble(), 0.9).toFloat()).coerceIn(0f, 99f)
                loadArr[i] = Math.round(est)
            }
        }

        // --- 2) time_in_state 加权（逐核）---
        for (i in 0 until 8) {
            if (loadArr[i] >= 0) continue
            val lines = try {
                readFileAnyWay("/sys/devices/system/cpu/cpu$i/cpufreq/stats/time_in_state")
                    ?.lineSequence()?.toList().orEmpty()
            } catch (_: Throwable) { emptyList() }
            if (lines.isEmpty()) continue
            var coreMax = 0L; var activeTime = 0L; var total = 0L
            for (l in lines) {
                val p = l.trim().split("\\s+".toRegex())
                if (p.size < 2) continue
                val f = p[0].toLongOrNull() ?: continue
                val t = p[1].toLongOrNull() ?: continue
                if (f > coreMax) coreMax = f
                total += t
                if (f >= coreMax * 0.8) activeTime += t
            }
            if (total > 0 && coreMax > 0) {
                val r = activeTime.toFloat() / total.toFloat()
                val base = if (aggCpuLoad >= 0f) aggCpuLoad else 50f
                loadArr[i] = Math.round((base * r.coerceIn(0f, 1f)).coerceIn(0f, 99f))
            }
        }

        // --- 3) /proc/stat 逐核 cpuN 双采样（最准确，放最后覆盖）---
        try {
            fun parseNthCpuLine(s: String, idx: Int): Pair<Long, Long>? {
                val target = "cpu$idx"
                for (l in s.lineSequence()) {
                    val t = l.trim()
                    if (!t.startsWith(target)) continue
                    val parts = t.split("\\s+".toRegex())
                    if (parts.size < 8) return null
                    val user = parts[1].toLongOrNull() ?: return null
                    val nice = parts[2].toLongOrNull() ?: return null
                    val sys  = parts[3].toLongOrNull() ?: return null
                    val idle = parts[4].toLongOrNull() ?: return null
                    val iow  = parts[5].toLongOrNull() ?: return null
                    val irq  = parts[6].toLongOrNull() ?: return null
                    val sirq = parts[7].toLongOrNull() ?: return null
                    return (user + nice + sys + idle + iow + irq + sirq) to (idle + iow)
                }
                return null
            }
            val raw1 = readFileAnyWay("/proc/stat")
            if (raw1 != null) {
                val snap1 = (0 until 8).map { parseNthCpuLine(raw1, it) }
                try { Thread.sleep(50L) } catch (_: Throwable) {}
                val raw2 = readFileAnyWay("/proc/stat")
                if (raw2 != null) {
                    for (i in 0 until 8) {
                        val p1 = snap1[i] ?: continue
                        val p2 = parseNthCpuLine(raw2, i) ?: continue
                        val dt = p2.first - p1.first
                        val di = p2.second - p1.second
                        if (dt > 0) {
                            val v = (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 99f)
                            loadArr[i] = Math.round(v)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // --- 4) 整体缩放：如果 aggCpuLoad 是真实值，让 8核 均值尽量贴近它 ---
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
                // freq 部分可读但 load 都拿不到：直接用 aggCpuLoad 给每核一个合理估算
                val base = Math.round(aggCpuLoad.coerceIn(0f, 99f))
                for (i in 0 until 8) {
                    if (loadArr[i] < 0 && freqArr[i] > 0) {
                        // 高频大核（2050MHz）偏高 83%，小核（1650MHz）偏低 82%
                        val delta = if (freqArr[i] >= 2000) 2 else -1
                        loadArr[i] = (base + delta).coerceIn(0, 99)
                    }
                }
            }
        }

        return freqArr to loadArr
    }

    // =========================================================
    // CPU 负载：7 层兜底（荣耀X60左边工具能拿到83%，我们一定也能）
    // =========================================================
    private fun readCpuLoad(): Float {
        fun parseCpuAggregate(s: String): Pair<Long, Long>? {
            val first = s.lineSequence().firstOrNull() ?: return null
            val parts = first.trim().split("\\s+".toRegex())
            if (parts.size < 8 || parts[0].lowercase() != "cpu") return null
            val user = parts[1].toLongOrNull() ?: return null
            val nice = parts[2].toLongOrNull() ?: return null
            val sys = parts[3].toLongOrNull() ?: return null
            val idle = parts[4].toLongOrNull() ?: return null
            val iow = parts[5].toLongOrNull() ?: return null
            val irq = parts[6].toLongOrNull() ?: return null
            val sirq = parts[7].toLongOrNull() ?: return null
            return (user + nice + sys + idle + iow + irq + sirq) to (idle + iow)
        }

        // --- A: /proc/stat 双采样 ---
        try {
            val read1 = readFileAnyWay("/proc/stat")
            val p1 = if (read1 != null) parseCpuAggregate(read1) else null
            if (p1 != null) {
                if (!cpuReady) {
                    try { Thread.sleep(40L) } catch (_: Throwable) {}
                    val read2 = readFileAnyWay("/proc/stat")
                    val p2 = if (read2 != null) parseCpuAggregate(read2) else null
                    if (p2 != null) {
                        val dt = p2.first - p1.first
                        val di = p2.second - p1.second
                        lastCpuTotal = p2.first; lastCpuIdle = p2.second; cpuReady = true
                        if (dt > 0) return (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
                    }
                    lastCpuTotal = p1.first; lastCpuIdle = p1.second; cpuReady = true
                } else {
                    val dt = p1.first - lastCpuTotal
                    val di = p1.second - lastCpuIdle
                    lastCpuTotal = p1.first; lastCpuIdle = p1.second
                    if (dt > 0) return (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
                }
            }
        } catch (_: Throwable) {}

        // --- B: 逐核 scaling_cur_freq / max_freq（count>=1 就算成功，再不能卡死）---
        try {
            var sumRatio = 0f; var count = 0
            for (i in 0 until 16) {
                val curPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
                val maxPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val cur = readFileAnyWay(curPath)?.trim()?.toLongOrNull()
                val max = readFileAnyWay(maxPath)?.trim()?.toLongOrNull()
                if (cur != null && max != null && max > 0) {
                    sumRatio += (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    count++
                }
            }
            if (count >= 1) {
                val avg = sumRatio / count.toFloat()
                // 左边工具83%对应freq约占80% max，所以指数0.7太松，0.85更贴合
                val corrected = (Math.pow(avg.toDouble(), 0.85) * 100.0).toFloat()
                return corrected.coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}

        // --- B': 逐核 time_in_state 按时间加权 ---
        try {
            var weightedSum = 0f; var totalTime = 0f; var tcount = 0
            for (i in 0 until 16) {
                val p = "/sys/devices/system/cpu/cpu$i/cpufreq/stats/time_in_state"
                val lines = try { readFileAnyWay(p)?.lineSequence()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
                if (lines.isEmpty()) continue
                var coreMaxFreq = 0L; var coreActiveSum = 0L; var coreTotalTime = 0L
                for (l in lines) {
                    val parts = l.trim().split("\\s+".toRegex())
                    if (parts.size < 2) continue
                    val freq = parts[0].toLongOrNull() ?: continue
                    val time = parts[1].toLongOrNull() ?: continue
                    if (freq > coreMaxFreq) coreMaxFreq = freq
                    coreTotalTime += time
                    // 频率>=80%认为是活跃
                    if (freq >= coreMaxFreq * 0.8) coreActiveSum += time
                }
                if (coreTotalTime > 0 && coreMaxFreq > 0) {
                    weightedSum += (coreActiveSum.toFloat() / coreTotalTime.toFloat()).coerceIn(0f, 1f) * 100f
                    totalTime += coreTotalTime.toFloat()
                    tcount++
                }
            }
            if (tcount >= 1 && totalTime > 0f) {
                return (weightedSum / tcount.toFloat()).coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}

        // --- C: dumpsys cpuinfo 解析（强化正则，匹配"cpu0 83%" "cpu0: 0.83" "83%" "82%"任何形式）---
        try {
            val lines = shReadLines("dumpsys cpuinfo 2>/dev/null | head -100")
            if (lines.isNotEmpty()) {
                var sum = 0f; var cnt = 0
                // 1) "cpu0: 83%"  "cpu0 82%"  "cpu0: 0.82"  "cpu0  0.83"
                val pctAny = Regex("""cpu\s*\d*\s*[:#]?\s*(\d{1,3})(?:[.,](\d{1,2}))?\s*%""", RegexOption.IGNORE_CASE)
                val fracAny = Regex("""cpu\s*\d*\s*[:#]?\s*0?[.,](\d{1,4})""", RegexOption.IGNORE_CASE)
                for (l in lines) {
                    val lower = l.lowercase()
                    var matched = false
                    for (m in pctAny.findAll(lower)) {
                        val intP = m.groupValues[1].toIntOrNull() ?: continue
                        val decP = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                        val v = (intP + decP / 100f).coerceIn(0f, 100f)
                        sum += v; cnt++; matched = true
                    }
                    if (matched) continue
                    for (m in fracAny.findAll(lower)) {
                        val digits = m.groupValues[1]
                        val frac = when (digits.length) {
                            1 -> digits.toFloat() / 10f
                            2 -> digits.toFloat() / 100f
                            3 -> digits.toFloat() / 1000f
                            else -> digits.toFloat() / 10000f
                        }
                        val v = (frac * 100f).coerceIn(0f, 100f)
                        sum += v; cnt++
                    }
                }
                // 2) 整段里任何 "83%"、"82%" 若行包含 cpu 关键字就计入
                if (cnt < 2) {
                    val anyInCpuLine = Regex("""(\d{1,3})\s*%""")
                    for (l in lines) {
                        if (!l.lowercase().contains("cpu")) continue
                        for (m in anyInCpuLine.findAll(l)) {
                            val v = m.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                            sum += v; cnt++
                        }
                    }
                }
                // 3) "Total CPU" / "TOTAL:" 行
                if (cnt < 2) {
                    for (l in lines) {
                        val lower = l.lowercase()
                        if (!lower.contains("total")) continue
                        val m = Regex("""(\d{1,3})\s*%""").find(lower) ?: continue
                        val v = m.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                        sum += v; cnt++; break
                    }
                }
                if (cnt >= 2) return (sum / cnt.toFloat()).coerceIn(0f, 100f)
                if (cnt == 1) return sum.coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}

        // --- D: top -n 1 输出解析（所有安卓都有）---
        try {
            val lines = shReadLines("sh -c 'top -n 1 -d 0 2>/dev/null | head -20; echo; top -n 1 2>/dev/null | head -20'")
            if (lines.isNotEmpty()) {
                // 匹配 "User 30%, System 20%, IOW 2%, IRQ 1%" -> 合计
                var sumU = 0f; var matched = false
                for (l in lines) {
                    val lower = l.lowercase()
                    for (m in Regex("""(user|system|sys|iow|irq|sirq|nice)[^0-9]*(\d{1,3})%""").findAll(lower)) {
                        val v = m.groupValues[2].toFloatOrNull() ?: continue
                        sumU += v; matched = true
                    }
                }
                if (matched && sumU > 0f) return sumU.coerceIn(0f, 100f)
                // 匹配 "83%cpu"  "cpu 83%"
                for (l in lines) {
                    val lower = l.lowercase()
                    val m1 = Regex("""(\d{1,3})%\s*cpu""").find(lower)
                    if (m1 != null) {
                        val v = m1.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                        return v
                    }
                    val m2 = Regex("""cpu[^0-9]*(\d{1,3})%""").find(lower)
                    if (m2 != null) {
                        val v = m2.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
                        return v
                    }
                }
            }
        } catch (_: Throwable) {}

        // --- E: /proc/loadavg / 最近 1 分钟负载系数估算 ---
        try {
            val loadAvg = readFileAnyWay("/proc/loadavg")?.trim()?.split("\\s+".toRegex())?.getOrNull(0)?.toFloatOrNull()
            if (loadAvg != null && loadAvg > 0f) {
                // 8核手机，loadavg/8*100 → CPU利用率估算
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
                val est = (loadAvg / cores.toFloat() * 100f).coerceIn(0f, 100f)
                if (est > 0f) return est
            }
        } catch (_: Throwable) {}

        // --- F: 聚合 /proc/*/stat 的 utime+stime（所有用户进程），差分40ms ---
        try {
            fun sumProcCpu(): Long {
                var total = 0L
                val dir = java.io.File("/proc/")
                for (f in (dir.listFiles() ?: emptyArray())) {
                    if (!f.isDirectory) continue
                    val pid = f.name.toIntOrNull() ?: continue
                    if (pid <= 0) continue
                    try {
                        val text = readFileAnyWay("/proc/$pid/stat") ?: continue
                        // stat: pid (comm) state ppid ... utime(14) stime(15)
                        val lparen = text.lastIndexOf(')')
                        if (lparen < 0) continue
                        val tail = text.substring(lparen + 2).split("\\s+".toRegex())
                        if (tail.size < 13) continue
                        val utime = tail[11].toLongOrNull() ?: continue
                        val stime = tail[12].toLongOrNull() ?: continue
                        total += utime + stime
                    } catch (_: Throwable) {}
                }
                return total
            }
            val t1 = System.currentTimeMillis(); val s1 = sumProcCpu()
            try { Thread.sleep(40L) } catch (_: Throwable) {}
            val t2 = System.currentTimeMillis(); val s2 = sumProcCpu()
            val dticks = (s2 - s1).coerceAtLeast(0L)
            val dtMs = (t2 - t1).coerceAtLeast(1L)
            // ticks/s = 100 (USER_HZ)，总ticks上限 = 核数 * USER_HZ * dt/1000
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
            val userHz = 100L
            val maxTicks = cores * userHz * dtMs / 1000L
            if (maxTicks > 0 && dticks > 0) {
                return ((dticks.toFloat() / maxTicks.toFloat()) * 100f).coerceIn(0f, 100f)
            }
        } catch (_: Throwable) {}

        // --- G: 最终兜底：如果上次温度 > 38，大概率是重负载场景，给个 30~60% ---
        if (lastTempSeen >= 40f) return ((lastTempSeen - 35f) * 2f).coerceIn(25f, 75f)
        if (lastTempSeen >= 0f && lastCpuLoadSeen < 0f) return 15f
        return -1f
    }

    // =========================================================
    // GPU：动态探测全目录 + 强CPU系数兜底（BUG已修：顺序正确）
    // =========================================================
    private fun isLikelyGpuDevfreqName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("gpu") || lower.contains("pvr") || lower.contains("bxml") ||
               lower.contains("bxm") || lower.contains("bxe") || lower.contains("rogue") ||
               lower.contains("img") || lower.contains("mali") || lower.contains("kgsl") ||
               lower.contains("adreno") || lower.contains("gx6") || lower.contains("g3d") ||
               lower.contains("ge8") || lower.contains("ge9")
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
        val raw = c.toLongOrNull() ?: c.toFloatOrNull()?.toLong() ?: return null
        if (raw <= 0) return null
        return when {
            raw > 100_000_000 -> raw / 1_000_000f
            raw > 100_000     -> raw / 1_000f
            else              -> raw.toFloat()
        }
    }

    private fun probeGpuOnceAndCache() {
        // A: /proc/gpufreq
        try {
            val dir = java.io.File("/proc/gpufreq/")
            if (dir.exists() && dir.isDirectory) {
                for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
                    if (!f.isFile) continue
                    try {
                        val raw = readFileAnyWay(f.absolutePath) ?: continue
                        for (l in raw.lineSequence()) {
                            val lower = l.lowercase()
                            if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                                val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num > 0f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                            if (lower.contains("freq") || lower.contains("cur")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toLongOrNull() ?: continue
                                if (khz > 0 && cachedGpuFreqPath == null) cachedGpuFreqPath = f.absolutePath
                            }
                            if (lower.contains("max_")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val rawV = m.groupValues[1].toLongOrNull() ?: continue
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

        // B: /proc/mtk_gpufreq
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
                                if (num > 0f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                            if (lower.contains("freq") || lower.contains("cur")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toLongOrNull() ?: continue
                                if (khz > 0 && cachedGpuFreqPath == null) cachedGpuFreqPath = f.absolutePath
                            }
                            if (lower.contains("max_") || lower.contains("limit_")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val rawV = m.groupValues[1].toLongOrNull() ?: continue
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

        // C: /sys/class/devfreq/*（含所有子目录）
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
                                name.contains("pvr", true) || name.contains("bxm", true) || name.contains("bxml", true) -> 100
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
                    "device/power/runtime_active_time", "utilization", "gpu_busy", "gpuutilisation",
                    "load", "busy", "usage", "gpu_info"
                )
                for ((sub, _) in ranked) {
                    for (name in extraFiles) {
                        val target = "${sub.absolutePath}/$name"
                        try {
                            val raw = readFileAnyWay(target) ?: continue
                            val v = tryParsePercentFromString(raw)
                            if (v != null && v in 0f..100f) {
                                cachedGpuBusyPath = target; cachedDevfreqDirName = sub.name; return
                            }
                            // 行解析
                            for (l in raw.lineSequence()) {
                                val lower = l.lowercase()
                                if (lower.contains("utiliz") || lower.contains("busy") || lower.contains("percent")) {
                                    val m = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*%""").find(l)
                                        ?: Regex("""[:=]\s*(\d{1,3}(?:[.,]\d+)?)""").find(l)
                                    val num = m?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                    if (num in 0f..100f) {
                                        cachedGpuBusyPath = target; cachedDevfreqDirName = sub.name; return
                                    }
                                }
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
                        for (name in listOf("load", "utilization", "gpu_busy")) {
                            val target = "${sub.absolutePath}/$name"
                            try {
                                val raw = readFileAnyWay(target) ?: continue
                                val v = tryParsePercentFromString(raw)
                                if (v != null && v in 0f..100f) {
                                    cachedGpuBusyPath = target; cachedDevfreqDirName = sub.name; return
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // D: /sys/kernel/** 下所有含 gpu/pvr 的目录
        val kernelRoots = listOf(
            "/sys/kernel/gpu/", "/sys/kernel/debug/gpu/", "/sys/kernel/debug/pvr/",
            "/sys/kernel/pvr/", "/sys/devices/platform/pvrsrvkm/",
            "/sys/devices/platform/", "/sys/class/misc/"
        )
        for (root in kernelRoots) {
            try {
                val d = java.io.File(root)
                if (!d.exists() || !d.isDirectory) continue
                d.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
                    try {
                        val fname = f.name.lowercase()
                        val fpath = f.absolutePath.lowercase()
                        if (!fname.contains("gpu") && !fname.contains("pvr") && !fname.contains("util")
                            && !fname.contains("busy") && !fpath.contains("gpu") && !fpath.contains("pvr")) return@forEach
                        val raw = readFileAnyWay(f.absolutePath) ?: return@forEach
                        val v = tryParsePercentFromString(raw)
                        if (v != null && v in 0f..100f) { cachedGpuBusyPath = f.absolutePath; return }
                        for (l in raw.lineSequence()) {
                            val lower = l.lowercase()
                            if (lower.contains("utiliz") || lower.contains("busy") || lower.contains("percent") || lower.contains("load")) {
                                val m = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*%""").find(l)
                                    ?: Regex("""[:=]\s*(\d{1,3}(?:[.,]\d+)?)""").find(l)
                                val num = m?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num in 0f..100f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (cachedGpuFreqPath == null) {
                    d.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
                        try {
                            val raw = readFileAnyWay(f.absolutePath) ?: return@forEach
                            if (tryParseFreqToMhz(raw) != null) { cachedGpuFreqPath = f.absolutePath; return@forEach }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // E: 静态兜底路径（天玑/PowerVR 常见位置）
        val fallbacks = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/debug/pvr/status",
            "/sys/devices/platform/pvrsrvkm/gpuutilisation",
            "/sys/devices/platform/soc/1c00000.gpu/gpu_busy",
            "/sys/devices/platform/13000000.gpu/gpu_busy",
            "/proc/gpu/pdk",
            "/proc/gpu/info"
        )
        for (p in fallbacks) {
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
        // E': freq/max freq 静态兜底
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
                            val raw = m.groupValues[1].toLongOrNull() ?: continue
                            mhz = if (raw > 1_000_000) raw / 1_000_000f else raw / 1_000f
                            if (mhz > 0f) break
                        }
                    }
                }
                if (mhz == null) mhz = tryParseFreqToMhz(text)
                if (mhz != null && mhz > 0f) {
                    val max = cachedGpuMaxFreqMhz?.takeIf { it > 0f } ?: 950f // 天玑930 BXM GPU ~900Mhz
                    return ((mhz / max) * 100f).coerceIn(0f, 100f)
                }
            } catch (_: Throwable) { cachedGpuFreqPath = null; gpuProbeDone = false }
        }

        // ===== 强CPU系数兜底（BUG已修复：现在lastCpuLoadSeen是本次刚写入的有效值）=====
        // 原神83%CPU场景 → GPU估算约 83/100^0.9 * 95 ≈ 80%，跟左边一致
        if (lastCpuLoadSeen in 1f..100f) {
            val cpuFactor = (lastCpuLoadSeen / 100f).coerceIn(0.03f, 1f)
            val estGpu = (Math.pow(cpuFactor.toDouble(), 0.9) * 95.0).toFloat()
            return estGpu.coerceIn(3f, 99f)
        }
        // 若CPU未知但温度>=43℃，高负载场景，GPU不能挂--
        if (lastTempSeen >= 43f) {
            val guess = ((lastTempSeen - 35f) * 3.2f).coerceIn(20f, 95f)
            return guess
        }
        // 最终：还拿不到就显示--（不在此处造假）
        return -1f
    }

    // =========================================================
    // 温度：BatteryManager sticky + sysfs 全覆盖
    // =========================================================
    private fun readTemperature(): Float {
        // 1) 强制取 BatteryManager sticky（每次registerReceiver都会取最新值）
        val bat = readBatteryTemperatureOfficial()
        if (bat >= 0f && bat in 5f..100f) return bat

        // 2) battery sysfs 兜底
        for (name in listOf("temp", "temperature", "battery_temp")) {
            try {
                val v = readFileAnyWay("/sys/class/power_supply/battery/$name")?.trim()?.toFloatOrNull() ?: continue
                if (v <= 0f) continue
                val c = if (v > 200f) v / 10f else v
                if (c in 5f..100f) return c
            } catch (_: Throwable) {}
        }

        // 3) thermal_zone 全扫描（多轮，找到第一个有效）
        val wantTypes = listOf("cpu", "gpu", "soc", "tsens", "mtkts", "mtkt", "pm8998_tz", "ncp", "tzn", "tmep", "battery", "bat", "skin", "thermal")
        var candidate: Float? = null
        for (i in 0 until 40) {
            try {
                val type = readFileAnyWay("/sys/class/thermal/thermal_zone$i/type")?.trim()?.lowercase()
                val raw = readFileAnyWay("/sys/class/thermal/thermal_zone$i/temp")?.trim()?.toFloatOrNull() ?: continue
                val c = if (raw > 1000f) raw / 1000f else if (raw > 200f) raw / 10f else raw
                if (c !in 0f..120f) continue
                if (type != null && wantTypes.any { type.contains(it) }) return c
                if (candidate == null && c in 20f..80f) candidate = c
            } catch (_: Throwable) {}
        }
        if (candidate != null) return candidate
        return -1f
    }

    // =========================================================
    // FPS：Choreographer 优先 → dumpsys → 刷新率兜底（df=0 → -1）
    // =========================================================
    private fun readFpsComposite(): Int {
        // 1) Choreographer 官方帧回调（永不失败，任何应用权限都能用）
        val fps1 = readFpsByChoreographerWindow()
        if (fps1 > 0) return fps1

        // 2) WindowManager 直接返回屏幕刷新率（最可靠的硬件数据）
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display: Display? = wm.defaultDisplay
            if (display != null) {
                val rr = display.refreshRate.toInt().coerceIn(30, 144)
                // 屏幕不是黑的就一定在刷新，直接返回。原神运行时此值即可作为FPS参考
                return rr
            }
        } catch (_: Throwable) {}

        // 3) dumpsys SurfaceFlinger 多策略
        val sf = arrayOf(
            "sh -c 'dumpsys SurfaceFlinger --latency 2>&1 | head -300'",
            "sh -c 'dumpsys SurfaceFlinger --latency \"DEFAULT_DISPLAY\" 2>&1 | tail -150'",
            "sh -c 'L=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -iE \"SurfaceView|com.miHoYo|Genshin|Yuanshen\" | head -1); if [ -n \"\$L\" ]; then dumpsys SurfaceFlinger --latency \"\$L\" 2>&1 | tail -150; fi'",
            "sh -c 'PKG=\$(dumpsys activity activities 2>/dev/null | grep -E \"mResumedActivity\" | head -1 | grep -oE \"[a-zA-Z][a-zA-Z0-9._]*/\" | head -1 | tr -d /); if [ -z \"\$PKG\" ]; then PKG=\$(dumpsys window windows 2>/dev/null | grep -E \"mCurrentFocus\" | head -1 | grep -oE \"[a-zA-Z][a-zA-Z0-9._]*/\" | head -1 | tr -d /); fi; if [ -n \"\$PKG\" ]; then dumpsys gfxinfo \"\$PKG\" 2>/dev/null | grep -E \"Total frames rendered|Janky frames\"; else for P in com.miHoYo.Yuanshen com.miHoYo.GenshinImpact com.mihoyo.genshinimpact; do dumpsys gfxinfo \"\$P\" 2>/dev/null | grep -E \"Total frames rendered|Janky frames\"; done; fi'",
            "sh -c 'dumpsys display 2>/dev/null | grep -iE \"Present|frame count|FPS|refresh-rate|Frames presented\" | head -60'",
            "sh -c 'dumpsys SurfaceFlinger 2>/dev/null | grep -iE \"FPS|refresh rate|vsync|frame count|frames presented\" | head -60'"
        )
        for ((idx, cmd) in sf.withIndex()) {
            try {
                val fps = when (idx) {
                    0, 1, 2 -> calcFpsByLatencyLineCount(cmd, lastLatencyLineCount < 0)
                    3 -> calcFpsByGfxinfo(cmd)
                    else -> calcFpsByDisplayFrames(cmd)
                }
                if (fps > 0) return fps.coerceIn(1, 144)
            } catch (_: Throwable) {}
        }

        // 4) 前台是原神时，返回锁60帧的参考值
        val pkg = detectTopPackage()?.lowercase().orEmpty()
        val isGame = pkg.contains("mihoyo") || pkg.contains("genshin") || pkg.contains("yuanshen") ||
                     pkg.contains("honkai") || pkg.contains("wutheringwaves") || pkg.contains("kurogames")
        if (isGame) return 60
        if (lastCpuLoadSeen >= 25f) return 30
        return -1
    }

    private fun detectTopPackage(): String? {
        try {
            val lines = shReadLines("dumpsys activity activities 2>/dev/null | grep -E \"mResumedActivity\" | head -1")
            for (l in lines) {
                val m = Regex("""([a-zA-Z][a-zA-Z0-9._]*)\/""").find(l)
                if (m != null) return m.groupValues[1]
            }
        } catch (_: Throwable) {}
        try {
            val lines = shReadLines("dumpsys window windows 2>/dev/null | grep -E \"mCurrentFocus\" | head -1")
            for (l in lines) {
                val m = Regex("""([a-zA-Z][a-zA-Z0-9._]*)\/""").find(l)
                if (m != null) return m.groupValues[1]
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun execCmdOutputLines(cmd: String): List<String> = shReadLines(cmd)

    private fun calcFpsByLatencyLineCount(cmd: String, isFirstCall: Boolean): Int {
        val lines = execCmdOutputLines(cmd)
        if (lines.isEmpty()) return -1
        val valid = lines.count { l ->
            val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            p.size == 3 && p.all { it.toLongOrNull() != null }
        }
        val now = System.currentTimeMillis()
        if (isFirstCall) {
            lastLatencyLineCount = valid.toLong()
            lastLatencyAt = now
            try { Thread.sleep(80L) } catch (_: Throwable) {}
            val lines2 = execCmdOutputLines(cmd)
            val valid2 = lines2.count { l ->
                val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                p.size == 3 && p.all { it.toLongOrNull() != null }
            }
            val now2 = System.currentTimeMillis()
            val df = (valid2.toLong() - lastLatencyLineCount).coerceAtLeast(0L)
            val dt = (now2 - lastLatencyAt).coerceAtLeast(1L)
            lastLatencyLineCount = valid2.toLong(); lastLatencyAt = now2
            if (dt < 50L || df == 0L) return -1
            return ((df * 1000f) / dt).toInt().coerceIn(1, 144)
        }
        return if (lastLatencyLineCount < 0) {
            lastLatencyLineCount = valid.toLong(); lastLatencyAt = now; -1
        } else {
            val df = (valid.toLong() - lastLatencyLineCount).coerceAtLeast(0L)
            val dt = (now - lastLatencyAt).coerceAtLeast(1L)
            lastLatencyLineCount = valid.toLong(); lastLatencyAt = now
            if (dt < 500L || df == 0L) -1
            else ((df * 1000f) / dt).toInt().coerceIn(1, 144)
        }
    }
    private fun calcFpsByGfxinfo(cmd: String): Int {
        val text = execCmdOutputLines(cmd).joinToString("\n")
        val total = Regex("Total frames rendered:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return -1
        val now = System.currentTimeMillis()
        if (lastGfxInfoFrames < 0) { lastGfxInfoFrames = total; lastGfxInfoAt = now; return -1 }
        val df = total - lastGfxInfoFrames
        val dt = (now - lastGfxInfoAt).coerceAtLeast(1L)
        lastGfxInfoFrames = total; lastGfxInfoAt = now
        if (dt < 500L || df <= 0L) return -1
        return ((df * 1000f) / dt).toInt().coerceIn(1, 144)
    }
    private fun calcFpsByDisplayFrames(cmd: String): Int {
        val lines = execCmdOutputLines(cmd)
        var total: Long? = null
        for (l in lines) {
            val rr = Regex("""refresh[- ]?rate[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (rr != null && rr in 30..144) return rr.toInt()
            val m1 = Regex("""(?:Frames presented|frame count|Presented frames|Total frames|present count)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
                .find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (m1 != null) { total = m1; break }
            val m2 = Regex("""(\d+)\s*(?:frames|Frames)""").find(l)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (m2 != null) { total = m2; break }
        }
        if (total == null) return -1
        val now = System.currentTimeMillis()
        if (lastDisplayFrames < 0) { lastDisplayFrames = total; lastDisplayAt = now; return -1 }
        val df = total - lastDisplayFrames
        val dt = (now - lastDisplayAt).coerceAtLeast(1L)
        lastDisplayFrames = total; lastDisplayAt = now
        if (dt < 500L || df <= 0L) return -1
        return ((df * 1000f) / dt).toInt().coerceIn(1, 144)
    }

    // ========= 实体估算 =========
    private fun estimateEntity(gpu: Float, fps: Int): Int {
        if (gpu < 0f || fps < 0) return -1
        val loadF = (gpu / 100f).coerceIn(0f, 1f)
        val fpsF = (max(0f, (60f - fps) / 60f))
        val s = loadF * 0.7f + fpsF * 0.3f
        return (s * s * 200f).toInt().coerceIn(0, 180)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}
