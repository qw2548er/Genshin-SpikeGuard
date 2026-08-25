package com.spikeguard.core

import android.content.Context
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
        val gpuLoad: Float,   // 0-100, 0f=未知
        val cpuLoad: Float,   // 0-100, 0f=未知
        val temperature: Float, // ℃, 0f=未知
        val fps: Int,           // -1=未知
        val entityEstimate: Int // 0-200 估算
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
            val first = stat.lineSequence().firstOrNull() ?: return 0f
            val parts = first.trim().split("\\s+".toRegex())
            if (parts.size < 8) return 0f
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val sys = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iow = parts[5].toLongOrNull() ?: 0L
            val irq = parts[6].toLongOrNull() ?: 0L
            val sirq = parts[7].toLongOrNull() ?: 0L
            val total = user + nice + sys + idle + iow + irq + sirq
            val idleT = idle + iow
            if (!cpuReady) {
                lastCpuTotal = total; lastCpuIdle = idleT; cpuReady = true; return 0f
            }
            val dt = total - lastCpuTotal
            val di = idleT - lastCpuIdle
            lastCpuTotal = total; lastCpuIdle = idleT
            if (dt <= 0) return 0f
            (((dt - di).toFloat() / dt.toFloat()) * 100f).coerceIn(0f, 100f)
        } catch (_: Throwable) { 0f }
    }

    // ============== GPU：适配天玑9300 PowerVR BXM（荣耀X60芯片页截图确认）==============
    // 优先级：mtk_gpufreq/proc > devfreq（安卓标准DF框架）> PowerVR debugfs > kgsl(高通兜底)
    private val gpuBusyPaths = listOf(
        // MTK 专用优先（天玑9300必带 /proc/mtk_gpufreq/）
        "/proc/mtk_gpufreq/gpu_busy_percent",
        "/proc/mtk_gpufreq/GPU_BUSY_PERCENT",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/kernel/debug/pvr/status",                // PowerVR debugfs 状态
        // devfreq 标准路径：/sys/class/devfreq/*/load（安卓DF，通用于非高通SoC）
        "/sys/class/devfreq/devfreq0/load",
        "/sys/class/devfreq/devfreq1/load",
        "/sys/class/devfreq/devfreq2/load",
        // PowerVR / Mali 兜底
        "/sys/devices/platform/pvrsrvkm/gpuutilisation",
        "/sys/class/misc/mali0/device/utilization",
        // 高通 Adreno（兜底兼容其他机器）
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/soc/1c00000.gpu/gpu_busy"
    )

    private fun readGpuLoad(): Float {
        // 1) /proc/mtk_gpufreq 专用解析：很多行 "key=value" 形式
        for (p in listOf("/proc/mtk_gpufreq/clk", "/proc/mtk_gpufreq/GPU_BUSY")) {
            try {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val lines = f.readLines()
                for (l in lines) {
                    val lower = l.trim().lowercase()
                    if (lower.contains("busy")) {
                        val num = Regex("""(\d+(\.\d+)?)""").find(l)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
                        if (num in 0f..100f) return num
                        if (num > 100f) return (num / 10f).coerceIn(0f, 100f)
                    }
                }
            } catch (_: Throwable) {}
        }
        // 2) 单文件逐行解析
        for (p in gpuBusyPaths) {
            try {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val c = f.readText().trim()
                if (c.isEmpty()) continue
                // pvr/status 里找类似 "Utilization: 42%" 的行
                if (p.contains("pvr")) {
                    for (l in c.lineSequence()) {
                        val lower = l.lowercase()
                        val m = Regex("""utilization[:\s]*(\d+(\.\d+)?)""").find(lower)
                        if (m != null) {
                            val v = m.groupValues[1].toFloatOrNull() ?: continue
                            return v.coerceIn(0f, 100f)
                        }
                    }
                }
                // gpubusy "a b" 格式
                val parts = c.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val a = parts[0].toFloatOrNull() ?: continue
                    val b = parts[1].toFloatOrNull() ?: continue
                    if (b > 0f) return ((a / b) * 100f).coerceIn(0f, 100f)
                } else {
                    val v = c.toFloatOrNull() ?: continue
                    if (v in 0f..100f) return v
                    if (v in 100f..1000f) return (v / 10f).coerceIn(0f, 100f)
                    if (v > 1000f) return (v / 1000f).coerceIn(0f, 100f) // 千分比
                }
            } catch (_: Throwable) {}
        }
        // 3) fallback：devfreq 目录全局扫一遍，找第一个带 load 的设备
        try {
            val dir = java.io.File("/sys/class/devfreq/")
            if (dir.exists() && dir.isDirectory) {
                for (sub in dir.listFiles() ?: emptyArray()) {
                    val loadF = java.io.File(sub, "load")
                    if (loadF.exists()) {
                        val v = loadF.readText().trim().toFloatOrNull() ?: continue
                        if (v > 0f) return if (v in 0f..100f) v else (v / 10f).coerceIn(0f, 100f)
                    }
                }
            }
        } catch (_: Throwable) {}
        // 4) 最后 fallback：通过频率线性估算（0-100 映射）
        return readGpuFreqBasedEstimate()
    }

    // GPU 频率路径：同样优先 MTK → devfreq 标准 → 高通兜底
    // 芯片截图显示 PowerVR BXM-8-256，频率上限截图写 500MHz~2500MHz 区间（CPU/GPU合并栏），取最大~1200MHz估
    private val gpuFreqPaths = listOf(
        "/proc/mtk_gpufreq/clk",                 // MTK 专用 key=value
        "/sys/class/devfreq/devfreq0/cur_freq",  // 安卓 devfreq 标准
        "/sys/class/devfreq/devfreq1/cur_freq",
        "/sys/class/devfreq/devfreq2/cur_freq",
        "/sys/class/misc/mali0/device/cur_freq",
        "/sys/kernel/gpu/gpu_freq",
        "/sys/kernel/debug/pvr/status",
        // 高通 Adreno（兜底）
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
        "/sys/devices/platform/soc/1c00000.gpu/devfreq/1c00000.gpu/cur_freq"
    )

    private fun readGpuFreqBasedEstimate(): Float {
        // 1) /proc/mtk_gpufreq/clk 里找 "CURRENT_GPU_FREQ=..."
        for (p in listOf("/proc/mtk_gpufreq/clk", "/proc/mtk_gpufreq/VPU_DVFS")) {
            try {
                val lines = java.io.File(p).readLines()
                for (l in lines) {
                    val lower = l.lowercase()
                    if (lower.contains("freq") || lower.contains("cur")) {
                        val m = Regex("""=\s*(\d+)""").find(l) ?: continue
                        val khz = m.groupValues[1].toIntOrNull() ?: continue
                        if (khz <= 0) continue
                        // mtk_gpufreq 通常单位 kHz -> mhz = khz/1000
                        val mhz = if (khz > 1000000) khz / 1000000 else khz / 1000
                        if (mhz <= 0) continue
                        return ((mhz.toFloat() / 1200f) * 100f).coerceIn(5f, 98f)
                    }
                }
            } catch (_: Throwable) {}
        }
        // 2) devfreq cur_freq（单位 Hz -> MHz）
        for (p in gpuFreqPaths) {
            try {
                val rawStr = java.io.File(p).readText().trim()
                if (rawStr.isEmpty()) continue
                val raw = rawStr.toIntOrNull() ?: continue
                if (raw <= 0) continue
                // devfreq cur_freq 通常 Hz，/sys/kgsl 则是 Hz 或 kHz 混合
                val mhz = when {
                    raw > 100_000_000 -> raw / 1_000_000       // Hz -> MHz
                    raw > 100_000     -> raw / 1_000           // kHz -> MHz
                    else              -> raw                    // 已经是 MHz
                }
                if (mhz <= 0) continue
                return ((mhz.toFloat() / 1200f) * 100f).coerceIn(5f, 98f)
            } catch (_: Throwable) {}
        }
        // 3) devfreq 目录全局扫一遍找 cur_freq
        try {
            val dir = java.io.File("/sys/class/devfreq/")
            if (dir.exists() && dir.isDirectory) {
                for (sub in dir.listFiles() ?: emptyArray()) {
                    val f = java.io.File(sub, "cur_freq")
                    if (!f.exists()) continue
                    val raw = f.readText().trim().toIntOrNull() ?: continue
                    val mhz = if (raw > 100_000_000) raw / 1_000_000 else raw / 1_000
                    if (mhz <= 0) continue
                    return ((mhz.toFloat() / 1200f) * 100f).coerceIn(5f, 98f)
                }
            }
        } catch (_: Throwable) {}
        return 0f
    }

    // 温度：你第一张图"概览"页明确显示"温度：43.5℃" → 电池temp一定能读到，
    // 因此把 /sys/class/power_supply/battery/temp 作为第一优先级（稳定、始终可读、普通app有权限）
    private fun readTemperature(): Float {
        // 1) 电池温度（优先，荣耀X60概览页43.5℃就是从这里来的）
        try {
            val batteryTempRaw = java.io.File("/sys/class/power_supply/battery/temp").readText().trim().toFloatOrNull()
            if (batteryTempRaw != null && batteryTempRaw > 0f) {
                // 常见返回格式是 435 表示 43.5℃（10倍放大）或直接 43.5
                val c = if (batteryTempRaw > 200f) batteryTempRaw / 10f else batteryTempRaw
                if (c in 0f..100f) return c
            }
        } catch (_: Throwable) {}
        // 2) 老路径 battery_temp/temperature（不同ROM命名差异）
        for (name in listOf("temperature", "battery_temp", "temp")) {
            try {
                val v = java.io.File("/sys/class/power_supply/battery/$name").readText().trim().toFloatOrNull() ?: continue
                if (v <= 0f) continue
                val c = if (v > 200f) v / 10f else v
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }
        // 3) thermal_zone：找 cpu / gpu / soc / mtkts* / mtkt* / tsens 等
        val wantTypes = listOf("cpu", "gpu", "soc", "tsens", "mtkts", "mtkt", "pm8998_tz", "ncp", "tzn", "tmep")
        for (i in 0 until 30) {
            val tp = "/sys/class/thermal/thermal_zone$i/temp"
            val ty = "/sys/class/thermal/thermal_zone$i/type"
            try {
                val type = java.io.File(ty).readText().trim().lowercase()
                if (wantTypes.any { type.contains(it) }) {
                    val raw = java.io.File(tp).readText().trim().toFloatOrNull() ?: continue
                    val c = if (raw > 1000f) raw / 1000f else raw
                    if (c in 0f..100f) return c
                }
            } catch (_: Throwable) {}
        }
        // 4) 最后兜底：前6个thermal_zone随便拿一个非0
        for (i in 0 until 10) {
            try {
                val raw = java.io.File("/sys/class/thermal/thermal_zone$i/temp")
                    .readText().trim().toFloatOrNull() ?: continue
                val c = if (raw > 1000f) raw / 1000f else raw
                if (c in 0f..100f) return c
            } catch (_: Throwable) {}
        }
        return 0f
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

    private fun estimateEntity(gpu: Float, fps: Int): Int {
        val loadF = (gpu / 100f).coerceIn(0f, 1f)
        val fpsF = if (fps < 0) 0.3f else (max(0f, (60f - fps) / 60f))
        val s = loadF * 0.7f + fpsF * 0.3f
        return (s * s * 200f).toInt().coerceIn(0, 180)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}
