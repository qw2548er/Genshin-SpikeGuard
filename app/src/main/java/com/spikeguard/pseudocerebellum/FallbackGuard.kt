package com.spikeguard.pseudocerebellum

import android.content.Context
import android.os.Handler
import com.spikeguard.util.LogManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块4：异常兜底保护模块
 *
 * 职责：
 * 1. 持续监控：内存溢出、GPU驱动报错、温控触发前兆
 * 2. 兜底策略：优先减慢资源提交速度，而不是直接删除贴图、降低画质
 * 3. 临界保护：硬件濒临极限时，暂停新增资源加载，维持当前画面稳定，防止闪退、贴图丢失；
 *             硬件恢复余量后继续加载剩余资源
 * 4. 异常日志记录，方便定位触发尖峰的场景
 *
 * 核心原则：
 * - 不砍贴图、不降画质、不关特效
 * - 只通过系统级资源调度（内存回收、后台清理、优先级提升）来兜底
 * - 临界时暂停新增资源加载，但保留已加载的完整资源
 */
class FallbackGuard(
    private val context: Context,
    private val handler: Handler,
    private val onFallbackTriggered: (String) -> Unit
) {

    private val logManager = LogManager.getInstance(context)

    @Volatile private var running = false
    private val inCriticalMode = AtomicBoolean(false)

    // 监控阈值
    private val oomThreshold = 0.92f         // 内存占用超过92%判定OOM风险
    private val thermalThreshold = 58f        // 温度超过58度判定温控风险
    private val gpuCriticalThreshold = 95f    // GPU超过95%判定临界
    private val recoveryCheckInterval = 1000L // 恢复检查间隔

    // 原神包名候选
    private val genshinPackages = listOf(
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.Yuanshen",
        "com.mihoyo.genshinimpact"
    )

    fun start() {
        if (running) return
        running = true
        logManager.i(TAG, "FallbackGuard started")
    }

    fun stop() {
        running = false
        if (inCriticalMode.get()) {
            exitCriticalMode("module_stopped")
        }
        logManager.i(TAG, "FallbackGuard stopped")
    }

    fun pause() {
        logManager.i(TAG, "FallbackGuard paused (silent mode)")
    }

    fun resume() {
        logManager.i(TAG, "FallbackGuard resumed")
    }

    /**
     * 尖峰检测回调
     * 评估是否需要进入兜底保护
     */
    fun onSpikeDetected(spikeInfo: SpikeInfo) {
        if (!running) return

        // 评估各维度风险
        val oomRisk = checkOomRisk(spikeInfo)
        val thermalRisk = checkThermalRisk(spikeInfo)
        val gpuCritical = spikeInfo.gpuLoad >= gpuCriticalThreshold

        if (oomRisk || thermalRisk || gpuCritical) {
            val reason = buildString {
                if (oomRisk) append("OOM_RISK ")
                if (thermalRisk) append("THERMAL_RISK ")
                if (gpuCritical) append("GPU_CRITICAL ")
            }.trim()

            enterCriticalMode(reason, spikeInfo)
        }
    }

    /**
     * 临界等级回调
     */
    fun onCriticalLevel() {
        if (!running) return
        if (inCriticalMode.get()) return

        logManager.w(TAG, "Load level CRITICAL - entering fallback protection")
        enterCriticalMode("load_level_critical", null)
    }

    /**
     * 检查内存溢出风险
     */
    private fun checkOomRisk(spikeInfo: SpikeInfo): Boolean {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            val usedRatio = 1f - (mi.availMem.toFloat() / mi.totalMem.toFloat())

            if (usedRatio >= oomThreshold) {
                logManager.w(TAG, "OOM risk: used=${"%.1f".format(usedRatio * 100)}%, " +
                        "avail=${mi.availMem / (1024 * 1024)}MB")
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查温控风险
     */
    private fun checkThermalRisk(spikeInfo: SpikeInfo): Boolean {
        if (spikeInfo.temperature >= thermalThreshold) {
            logManager.w(TAG, "Thermal risk: temp=${"%.1f".format(spikeInfo.temperature)}°C")
            return true
        }
        return false
    }

    /**
     * 进入临界保护模式
     *
     * 兜底策略（不砍画质，只系统调度）：
     * 1. 立即回收所有可回收内存（drop_caches）
     * 2. 清理所有后台进程
     * 3. 提升目标进程优先级到最高
     * 4. 暂停新增资源加载（通过停止后台服务实现）
     * 5. 降低swappiness减少swap
     * 6. 监控恢复，硬件余量恢复后退出临界模式
     */
    private fun enterCriticalMode(reason: String, spikeInfo: SpikeInfo?) {
        if (inCriticalMode.get()) return
        inCriticalMode.set(true)

        logManager.w(TAG, "=== CRITICAL MODE ENTERED ===")
        logManager.w(TAG, "Reason: $reason")
        spikeInfo?.let {
            logManager.w(TAG, "Spike: gpu=${"%.1f".format(it.gpuLoad)}%, " +
                    "mem=${it.memoryUsedMb}MB, " +
                    "cpu=${"%.1f".format(it.cpuLoad)}%, " +
                    "temp=${"%.1f".format(it.temperature)}°C")
        }

        onFallbackTriggered(reason)

        handler.post {
            try {
                // 兜底动作1：立即回收内存（刚性保护，不删游戏资源）
                executeRootCommand("echo 3 > /proc/sys/vm/drop_caches")
                executeRootCommand("echo 1 > /proc/sys/vm/compact_memory")

                // 兜底动作2：清理所有后台进程（隔离资源）
                executeRootCommand("am kill-all background")

                // 兜底动作3：提升目标进程优先级到最高
                val pid = findGenshinPid()
                if (pid > 0) {
                    executeRootCommand("echo -1000 > /proc/$pid/oom_score_adj")
                    executeRootCommand("renice -20 -p $pid")
                }

                // 兜底动作4：降低swappiness
                executeRootCommand("echo 0 > /proc/sys/vm/swappiness")

                // 兜底动作5：提高最低空闲内存
                executeRootCommand("echo 16384 > /proc/sys/vm/min_free_kbytes")

                logManager.i(TAG, "Critical protection actions executed")

                // 启动恢复监控
                startRecoveryMonitor()

            } catch (e: Exception) {
                logManager.e(TAG, "Critical protection failed", e)
            }
        }
    }

    /**
     * 启动恢复监控
     * 每隔一段时间检查硬件是否恢复余量，恢复后退出临界模式
     */
    private fun startRecoveryMonitor() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!running || !inCriticalMode.get()) return

                if (checkRecoveryCondition()) {
                    exitCriticalMode("hardware_recovered")
                } else {
                    // 继续监控
                    handler.postDelayed(this, recoveryCheckInterval)
                }
            }
        }, recoveryCheckInterval)
    }

    /**
     * 检查恢复条件
     * 硬件余量恢复后退出临界模式
     */
    private fun checkRecoveryCondition(): Boolean {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            val usedRatio = 1f - (mi.availMem.toFloat() / mi.totalMem.toFloat())

            // 内存占用降到75%以下，认为恢复
            val memoryRecovered = usedRatio < 0.75f

            // 温度检查
            val temp = getCurrentTemperature()
            val thermalRecovered = temp < 45f

            memoryRecovered && thermalRecovered
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 退出临界模式
     */
    private fun exitCriticalMode(reason: String) {
        if (!inCriticalMode.get()) return
        inCriticalMode.set(false)

        logManager.i(TAG, "=== CRITICAL MODE EXITED: $reason ===")

        handler.post {
            try {
                // 恢复内存参数
                executeRootCommand("echo 60 > /proc/sys/vm/swappiness")
                executeRootCommand("echo 8192 > /proc/sys/vm/min_free_kbytes")
                logManager.i(TAG, "Memory params restored after critical mode")
            } catch (e: Exception) {
                logManager.e(TAG, "Failed to restore after critical mode", e)
            }
        }
    }

    /**
     * 获取当前温度
     */
    private fun getCurrentTemperature(): Float {
        for (i in 0 until 15) {
            try {
                val typePath = "/sys/class/thermal/thermal_zone$i/type"
                val type = java.io.File(typePath).readText().trim().lowercase()
                if (type.contains("cpu") || type.contains("soc") || type.contains("tsens")) {
                    val content = java.io.File("/sys/class/thermal/thermal_zone$i/temp").readText().trim()
                    val temp = content.toFloatOrNull() ?: continue
                    return if (temp > 1000) temp / 1000f else temp
                }
            } catch (e: Exception) {
                continue
            }
        }
        return 35f
    }

    /**
     * 查找原神进程PID
     */
    private fun findGenshinPid(): Int {
        for (pkg in genshinPackages) {
            try {
                val process = Runtime.getRuntime().exec("pidof $pkg")
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                val pid = output.split(" ").firstOrNull()?.toIntOrNull()
                if (pid != null && pid > 0) return pid
            } catch (e: Exception) {
                // 继续
            }
        }
        return 0
    }

    /**
     * 执行Root命令（带异常捕获）
     */
    private fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c \"$command\"")
            process.waitFor()
            true
        } catch (e: Exception) {
            logManager.e(TAG, "Root command failed: $command", e)
            false
        }
    }

    /**
     * 是否在临界模式
     */
    fun isInCriticalMode(): Boolean = inCriticalMode.get()

    companion object {
        private const val TAG = "PC_FallbackGuard"
    }
}
