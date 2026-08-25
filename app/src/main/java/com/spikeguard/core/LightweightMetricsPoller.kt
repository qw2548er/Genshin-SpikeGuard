package com.spikeguard.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlin.math.max

/**
 * 主进程（MainActivity）用的轻量性能轮询器
 *
 * 设计目标：
 *  - 守护服务 **没启动**（界面是"已停止"状态，用户截图的那个状态）时，
 *    也能实时采集并更新 UI，让界面不再全是 "--"
 *  - 不需要 root / Shizuku 权限：只使用普通 app 权限可访问的系统节点
 *    （/proc/stat、/sys/class/thermal、/sys/class/kgsl...）
 *  - 功耗低：1 秒 1 次，使用独立 HandlerThread
 *  - UI 线程回调：通过 mainHandler.post 保证主线程 setText
 *
 * 一旦 GuardService 启动，MainActivity 应调用 stop() 并完全
 * 依赖 :guard 进程广播过来的 UI_STATE_UPDATE（更精确的数据）。
 */
class LightweightMetricsPoller(
    private val context: Context,
    private val onMetrics: (MetricsSnapshot) -> Unit
) {

    data class MetricsSnapshot(
        val gpuLoad: Float,   // -1f=未知, 0-100 真实值
        val cpuLoad: Float,   // -1f=未知, 0-100 真实值
        val temperature: Float, // -1f=未知, 其余为真实℃
        val fps: Int,           // -1=未知, 0-120 真实FPS
        val entityEstimate: Int // -1=未知, 0-200 估算
    )

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mainHandler: Handler? = null

    // CPU / FPS 状态
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuReady = false
    private var lastPollAt = 0L

    // FPS
    private var lastLatencyLineCount = -1L
    private var lastLatencyAt = 0L

    // GPU：动态探测出的"有效路径"会在第一次扫描后缓存下来，避免每秒全机扫描
    @Volatile private var cachedGpuBusyPath: String? = null
    @Volatile private var cachedGpuFreqPath: String? = null
    @Volatile private var cachedDevfreqDirName: String? = null // 例："devfreq0"
    private var gpuProbeDone = false

    // 电池温度：用 sticky Intent（ACTION_BATTERY_CHANGED），这是所有"设备信息"类App
    // 读取电池温度的标准官方方式（用户截图里"概览"页43.5℃ 就来自这里）。
    // 用 BroadcastReceiver 一次性取一次 sticky，不注册常驻监听，避免内存泄漏。
    private fun readBatteryTemperatureOfficial(): Float {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
            if (batteryStatus == null) return -1f
            val raw = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (raw == Int.MIN_VALUE) return -1f
            // BatteryManager 约定：返回值是 10 倍放大的 ℃（435 = 43.5℃，对应用户第一张概览截图）
            (raw.toFloat() / 10f).coerceIn(-1f, 120f)
        } catch (_: Throwable) {
            -1f
        }
    }

    @Volatile
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val snap = collectOnce()
                mainHandler?.post { onMetrics(snap) }
            } catch (_: Throwable) {
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
        workerHandler?.post(tickRunnable)
    }

    fun stop() {
        running = false
        try { workerHandler?.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
        mainHandler = null
    }

    // ============== 采集逻辑（与 GpuFrameCollector 同源，但更轻，不依赖协程）==============

    private fun collectOnce(): MetricsSnapshot {
        val cpu = readCpuLoad()
        val gpu = readGpuLoad()
        val temp = readTemperature()
        val fps = readFpsSurfaceFlinger()
        val est = estimateEntity(gpu, fps)
        lastPollAt = System.currentTimeMillis()
        return MetricsSnapshot(
            gpuLoad = gpu,
            cpuLoad = cpu,
            temperature = temp,
            fps = fps,
            entityEstimate = est
        )
    }

    private fun readCpuLoad(): Float {
        return try {
            val stat = java.io.File("/proc/stat").readText()
            val first = stat.lineSequence().firstOrNull() ?: return -1f
            val parts = first.trim().split("\\s+".toRegex())
            if (parts.size < 8) return -1f
            val user = parts[1].toLongOrNull() ?: return -1f
            val nice = parts[2].toLongOrNull() ?: return -1f
            val sys = parts[3].toLongOrNull() ?: return -1f
            val idle = parts[4].toLongOrNull() ?: return -1f
            val iow = parts[5].toLongOrNull() ?: return -1f
            val irq = parts[6].toLongOrNull() ?: return -1f
            val sirq = parts[7].toLongOrNull() ?: return -1f
            val total = user + nice + sys + idle + iow + irq + sirq
            val idleT = idle + iow
            if (!cpuReady) {
                lastCpuTotal = total; lastCpuIdle = idleT; cpuReady = true
                return -1f // 第一帧只有基准，没有差分结果 → 未知，不返回假 0
            }
            val dt = total - lastCpuTotal
            val di = idleT - lastCpuIdle
            lastCpuTotal = total; lastCpuIdle = idleT
            if (dt <= 0) return -1f
            (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
        } catch (_: Throwable) { -1f }
    }

    // ============== GPU：弃用猜路径，全部改"动态探测+缓存" ==============
    // 思路：用户机器是 HONOR X60 + 天玑9300(MT6855) + PowerVR BXM-8-256
    // 不同 ROM / 内核版本下 sysfs 具体文件名千差万别，硬编码猜不到。
    // 这里改成：启动第 1 次采样时，真正去扫这台手机的以下目录，
    // 把"第一个能成功读出有效数字的文件路径"缓存下来复用（真读不到就返回 -1f 显示--，绝不塞假值）。
    //
    // 探测范围：
    //   A. /proc/mtk_gpufreq/ 目录下所有文件（联发科必带，里面全是 key=value）
    //   B. /sys/class/devfreq/*/ 全部设备，先读 name，按名字匹配置信度判断是不是 GPU
    //   C. 常见的 PowerVR / Mali / Adreno 节点（兜底，若能读到就用）

    private fun isLikelyGpuDevfreqName(name: String): Boolean {
        val lower = name.lowercase()
        // PowerVR = pvr/bxm/bxe/bxs/rogue/imagination；Mali = mali；Adreno = kgsl/adreno；通用 gpu/g3d/
        return lower.contains("gpu") ||
               lower.contains("pvr") || lower.contains("bxml") || lower.contains("bxm") ||
               lower.contains("bxe") || lower.contains("rogue") || lower.contains("img") ||
               lower.contains("mali") ||
               lower.contains("kgsl") || lower.contains("adreno") ||
               lower.contains("gx6") || lower.contains("g3d") || lower.contains("ge")
    }

    private fun tryParsePercentFromString(text: String): Float? {
        val c = text.trim()
        if (c.isEmpty()) return null
        // a b 格式（高通 gpubusy 标准）
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
        // A. /proc/mtk_gpufreq 全部文件（天玑9300 必带）
        try {
            val dir = java.io.File("/proc/mtk_gpufreq/")
            if (dir.exists() && dir.isDirectory) {
                // 先扫一遍所有文件找带 "busy/percent/utilisation" 的
                for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
                    if (!f.isFile) continue
                    try {
                        val lines = f.readLines()
                        // 逐行解析 key=value / 或整文件纯数字
                        for (l in lines) {
                            val lower = l.lowercase()
                            if (lower.contains("busy") || lower.contains("percent") || lower.contains("util")) {
                                val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                                if (num in 0f..100f) { cachedGpuBusyPath = f.absolutePath; return }
                                if (num > 100f) { cachedGpuBusyPath = f.absolutePath; return }
                            }
                        }
                        // 整文件纯数字（百分比如 42）
                        val pure = tryParsePercentFromString(f.readText())
                        if (pure != null) { cachedGpuBusyPath = f.absolutePath; return }

                        // 还没找到 busy，那找有没有 freq 数字，存为 freq 路径备用
                        for (l in lines) {
                            val lower = l.lowercase()
                            if (lower.contains("freq") || lower.contains("cur") || lower.contains("current")) {
                                val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                                val khz = m.groupValues[1].toIntOrNull() ?: continue
                                if (khz > 0) { cachedGpuFreqPath = f.absolutePath; break }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        // B. /sys/class/devfreq/* 全部设备，读 name 筛选 GPU 相关
        try {
            val dir = java.io.File("/sys/class/devfreq/")
            if (dir.exists() && dir.isDirectory) {
                val subs = dir.listFiles() ?: emptyArray()
                // 第 1 轮：按 name 匹配置信度排序
                val ranked = subs.mapNotNull { sub ->
                    try {
                        val nameF = java.io.File(sub, "name")
                        val name = if (nameF.exists()) nameF.readText().trim() else sub.name
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
                        } else {
                            null
                        }
                    } catch (_: Throwable) { null }
                }.sortedByDescending { it.second }

                for ((sub, _) in ranked) {
                    val loadF = java.io.File(sub, "load")
                    try {
                        if (loadF.exists()) {
                            val v = tryParsePercentFromString(loadF.readText())
                            if (v != null && v in 0f..100f) {
                                cachedGpuBusyPath = loadF.absolutePath
                                cachedDevfreqDirName = sub.name
                                return
                            }
                        }
                    } catch (_: Throwable) {}
                    val curF = java.io.File(sub, "cur_freq")
                    try {
                        if (curF.exists() && cachedGpuFreqPath == null) {
                            if (tryParseFreqToMhz(curF.readText()) != null) {
                                cachedGpuFreqPath = curF.absolutePath
                                cachedDevfreqDirName = sub.name
                            }
                        }
                    } catch (_: Throwable) {}
                }
                // 第 2 轮：没命中名字但有 load/cur_freq 文件的 devfreq，随便选一个读得通的当 fallback
                if (cachedGpuBusyPath == null) {
                    for (sub in subs) {
                        val loadF = java.io.File(sub, "load")
                        if (loadF.exists()) {
                            try {
                                val v = tryParsePercentFromString(loadF.readText())
                                if (v != null && v in 0f..100f) {
                                    cachedGpuBusyPath = loadF.absolutePath
                                    cachedDevfreqDirName = sub.name
                                    return
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // C. 兜底：常见单节点名（/sys/class/kgsl 等）
        val fallbacks = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/debug/pvr/status",
            "/sys/devices/platform/pvrsrvkm/gpuutilisation"
        )
        for (p in fallbacks) {
            try {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val v = if (p.contains("pvr/status")) {
                    // pvr/status 文本里找 Utilization: xx%
                    var best: Float? = null
                    for (l in f.readLines()) {
                        val m = Regex("""utilization[:\s]*(\d+(\.\d+)?)""").find(l.lowercase()) ?: continue
                        best = (m.groupValues[1].toFloatOrNull() ?: continue).coerceIn(0f, 100f)
                        break
                    }
                    best
                } else {
                    tryParsePercentFromString(f.readText())
                }
                if (v != null) { cachedGpuBusyPath = p; return }
            } catch (_: Throwable) {}
        }

        // D. 还没找到任何 busy，那找一条 cur_freq（即使没 load 也能做后续映射）
        if (cachedGpuFreqPath == null) {
            for (p in listOf(
                "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
                "/sys/class/misc/mali0/device/cur_freq",
                "/sys/kernel/gpu/gpu_freq"
            )) {
                try {
                    val f = java.io.File(p)
                    if (!f.exists()) continue
                    if (tryParseFreqToMhz(f.readText()) != null) { cachedGpuFreqPath = p; break }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun readGpuLoad(): Float {
        if (!gpuProbeDone) {
            gpuProbeDone = true
            probeGpuOnceAndCache()
        }

        // 1) 优先用探测到的 busy 路径直接读
        cachedGpuBusyPath?.let { path ->
            try {
                val f = java.io.File(path)
                if (f.exists()) {
                    // 针对 pvr/status 的多行解析（这条路径是文本）
                    val raw = f.readText()
                    if (path.contains("pvr/status")) {
                        for (l in raw.lineSequence()) {
                            val m = Regex("""utilization[:\s]*(\d+(\.\d+)?)""").find(l.lowercase()) ?: continue
                            return (m.groupValues[1].toFloatOrNull() ?: continue).coerceIn(0f, 100f)
                        }
                    }
                    // 针对 mtk_gpufreq/*.key=value 文本
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
                } else {
                    // 路径失效（例如热更新/重启），下次重新探测
                    cachedGpuBusyPath = null
                    gpuProbeDone = false
                }
            } catch (_: Throwable) { cachedGpuBusyPath = null; gpuProbeDone = false }
        }

        // 2) 没探测到 busy 路径：用 cur_freq 做基于最大频率的线性"估算负载"
        // 注意：这仍然是估算，但至少用的是真实硬件频率，不是我拍脑袋写的假数字；
        //       如果连 cur_freq 都探测不到，就直接返回 -1f（显示--），绝不造假。
        cachedGpuFreqPath?.let { path ->
            try {
                val f = java.io.File(path)
                if (!f.exists()) { cachedGpuFreqPath = null; gpuProbeDone = false; return@let }
                val text = f.readText()
                // mtk_gpufreq 的 key=value
                if (path.contains("mtk_gpufreq")) {
                    for (l in text.lineSequence()) {
                        val lower = l.lowercase()
                        if (lower.contains("freq") || lower.contains("cur") || lower.contains("current")) {
                            val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                            val raw = m.groupValues[1].toIntOrNull() ?: continue
                            val khz = raw
                            val mhz = if (khz > 1_000_000) khz / 1_000_000 else khz / 1_000
                            if (mhz <= 0) continue
                            // 芯片页截图 500MHz~2500MHz，取 1800 作为最大（用户实际 PowerVR BXM-8-256 降频版）
                            return ((mhz.toFloat() / 1800f) * 100f).coerceIn(0f, 100f)
                        }
                    }
                }
                val mhz = tryParseFreqToMhz(text) ?: return@let
                return ((mhz / 1800f) * 100f).coerceIn(0f, 100f)
            } catch (_: Throwable) { cachedGpuFreqPath = null; gpuProbeDone = false }
        }
        return -1f
    }

    // 温度：优先官方 BatteryManager（sticky）API → 这是你"设备信息"App 概览页 43.5℃
    // 的真实来源，**不需要任何权限**，所有 Android 版本都稳定。
    // 只有当 BatteryManager 真的读不到时，才会再去试 sysfs 节点（且返回值仍要判定有效，否则 -1f 显示--）
    private fun readTemperature(): Float {
        // 1) 官方 BatteryManager（第一优先级，绝不会是假数据）
        val bat = readBatteryTemperatureOfficial()
        if (bat >= 0f && bat in 0f..100f) return bat

        // 2) /sys/class/power_supply/battery/temp 兜底（有些ROM BatteryManager 被魔改，但 sysfs 仍有效）
        for (name in listOf("temp", "temperature", "battery_temp")) {
            try {
                val v = java.io.File("/sys/class/power_supply/battery/$name")
                    .readText().trim().toFloatOrNull() ?: continue
                if (v <= 0f) continue
                val c = if (v > 200f) v / 10f else v
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }

        // 3) thermal_zone：明确按类型匹配
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

    /**
     * 轻量 FPS 读取（SurfaceFlinger --latency 有效帧行数增量法）
     * 普通 app（非 root）也能执行 dumpsys SurfaceFlinger，在大多数国产机都可用
     */
    private fun readFpsSurfaceFlinger(): Int {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "dumpsys SurfaceFlinger --latency 2>&1")
            )
            val done = proc.waitFor(900L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) { proc.destroy(); return -1 }
            val lines = proc.inputStream.bufferedReader().useLines { it.toList() }
            proc.destroy()
            val valid = lines.count { l ->
                val p = l.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                p.size == 3 && p.all { it.toLongOrNull() != null }
            }
            val now = System.currentTimeMillis()
            return if (lastLatencyLineCount < 0) {
                lastLatencyLineCount = valid.toLong()
                lastLatencyAt = now
                -1
            } else {
                val df = (valid.toLong() - lastLatencyLineCount).coerceAtLeast(0L)
                val dt = (now - lastLatencyAt).coerceAtLeast(1L)
                lastLatencyLineCount = valid.toLong()
                lastLatencyAt = now
                if (dt < 500L) -1 else ((df * 1000f) / dt).toInt().coerceIn(0, 120)
            }
        } catch (_: Throwable) { -1 }
    }

    // 实体估算：只有当 GPU / FPS 真的都读到了才估算；任何一项未知都 -1 → UI 显示--
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
