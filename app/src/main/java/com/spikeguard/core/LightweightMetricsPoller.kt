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

    private val gpuBusyPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/soc/1c00000.gpu/gpu_busy",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/pvrsrvkm/gpuutilisation"
    )

    private fun readGpuLoad(): Float {
        for (p in gpuBusyPaths) {
            try {
                val f = java.io.File(p)
                if (!f.exists()) continue
                val c = f.readText().trim()
                if (c.isEmpty()) continue
                val parts = c.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val a = parts[0].toFloatOrNull() ?: continue
                    val b = parts[1].toFloatOrNull() ?: continue
                    if (b > 0f) return ((a / b) * 100f).coerceIn(0f, 100f)
                } else {
                    val v = c.toFloatOrNull() ?: continue
                    if (v in 0f..100f) return v
                    if (v > 100f) return (v / 10f).coerceIn(0f, 100f)
                }
            } catch (_: Throwable) {}
        }
        // fallback：通过频率线性估算（0-100 映射）
        return readGpuFreqBasedEstimate()
    }

    private val gpuFreqPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/cur_gpuclk",
        "/sys/devices/platform/soc/1c00000.gpu/devfreq/1c00000.gpu/cur_freq",
        "/sys/class/misc/mali0/device/cur_freq"
    )

    private fun readGpuFreqBasedEstimate(): Float {
        for (p in gpuFreqPaths) {
            try {
                val raw = java.io.File(p).readText().trim().toIntOrNull() ?: continue
                val mhz = if (raw > 1000000) raw / 1000000 else raw / 1000
                if (mhz <= 0) continue
                // 荣耀X60（PowerVR）最大按 800MHz 估计
                return ((mhz.toFloat() / 800f) * 100f).coerceIn(5f, 98f)
            } catch (_: Throwable) {}
        }
        return 0f
    }

    private fun readTemperature(): Float {
        for (i in 0 until 20) {
            val tp = "/sys/class/thermal/thermal_zone$i/temp"
            val ty = "/sys/class/thermal/thermal_zone$i/type"
            try {
                val type = java.io.File(ty).readText().trim().lowercase()
                if (type.contains("cpu") || type.contains("gpu") ||
                    type.contains("soc") || type.contains("tsens") || type.contains("pm8998_tz")) {
                    val raw = java.io.File(tp).readText().trim().toFloatOrNull() ?: continue
                    return if (raw > 1000f) raw / 1000f else raw
                }
            } catch (_: Throwable) {}
        }
        for (i in 0 until 6) {
            try {
                val raw = java.io.File("/sys/class/thermal/thermal_zone$i/temp")
                    .readText().trim().toFloatOrNull() ?: continue
                return if (raw > 1000f) raw / 1000f else raw
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
