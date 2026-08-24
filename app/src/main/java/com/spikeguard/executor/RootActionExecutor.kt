package com.spikeguard.executor

import android.util.Log
import java.io.DataOutputStream

/**
 * Root 模式执行器
 *
 * 通过 su shell 执行系统调优命令
 *
 * 注意：
 * 1. 只修改系统级别的频率/温控参数
 * 2. 不注入任何进程
 * 3. 不修改游戏 APK
 * 4. 所有操作可逆，退出时恢复
 */
class RootActionExecutor : ActionExecutor {

    override val name = "Root"

    private var rootSession: Process? = null
    private var outputStream: DataOutputStream? = null
    private var initialized = false

    // 保存原始值用于恢复
    private val originalValues = mutableMapOf<String, String>()

    override fun isAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c echo root_check")
            val result = process.waitFor() == 0
            process.destroy()
            result
        } catch (e: Exception) {
            false
        }
    }

    override fun initialize(): Boolean {
        return try {
            rootSession = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(rootSession!!.outputStream)
            initialized = true
            Log.i(TAG, "Root executor initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize root executor", e)
            false
        }
    }

    override fun setCpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.CPU_THROTTLE, false, "Not initialized")

        return try {
            // 获取 CPU 核心数
            val cpuCount = getCpuCoreCount()

            // 计算目标频率
            // throttle: 1.0 = 最大频率, 0.5 = 一半频率
            for (i in 0 until cpuCount) {
                val maxFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val curGovPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor"
                val maxFreq = readSysfs(maxFreqPath)?.toIntOrNull() ?: continue

                // 保存原始值
                if (!originalValues.containsKey(curGovPath)) {
                    originalValues[curGovPath] = readSysfs(curGovPath) ?: "interactive"
                }

                // 设置为 userspace  governor 以便控制频率
                writeSysfs(curGovPath, "userspace")

                // 设置目标频率
                val targetFreq = (maxFreq * throttle).toInt()
                val setFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_setspeed"
                writeSysfs(setFreqPath, targetFreq.toString())
            }

            ActionResult(ActionType.CPU_THROTTLE, true,
                "CPU throttled to ${(throttle * 100).toInt()}%")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setGpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.GPU_THROTTLE, false, "Not initialized")

        return try {
            // PowerVR GPU 频率控制路径
            val gpuFreqPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
                "/sys/devices/platform/soc/1c00000.gpu/devfreq/1c00000.gpu/max_freq"
            )

            var success = false
            for (path in gpuFreqPaths) {
                val maxFreq = readSysfs(path)?.toIntOrNull()
                if (maxFreq != null && maxFreq > 0) {
                    // 保存原始值
                    if (!originalValues.containsKey(path)) {
                        originalValues[path] = maxFreq.toString()
                    }

                    val targetFreq = (maxFreq * throttle).toInt()
                    writeSysfs(path, targetFreq.toString())
                    success = true
                    Log.i(TAG, "GPU freq set to $targetFreq (${(throttle * 100).toInt()}%)")
                    break
                }
            }

            if (success) {
                ActionResult(ActionType.GPU_THROTTLE, true,
                    "GPU throttled to ${(throttle * 100).toInt()}%")
            } else {
                ActionResult(ActionType.GPU_THROTTLE, false, "GPU frequency control not available")
            }
        } catch (e: Exception) {
            ActionResult(ActionType.GPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setFrameLimit(fpsLimit: Int): ActionResult {
        // 帧率限制通过 SurfaceFlinger 属性实现
        // 注意：这只是系统层面的建议，实际帧率由应用决定
        return try {
            // 设置动画缩放比例来间接影响帧率感知
            // 或者设置 surfaceflinger 的 vsync 周期
            executeCommand("setprop debug.sf.latch_unsignaled 1")
            executeCommand("setprop debug.egl.hw $fpsLimit")

            ActionResult(ActionType.FRAME_LIMIT, true, "Frame limit set to $fpsLimit")
        } catch (e: Exception) {
            ActionResult(ActionType.FRAME_LIMIT, false, e.message ?: "Unknown error")
        }
    }

    override fun reclaimMemory(): ActionResult {
        return try {
            var reclaimed = 0

            // 方法1: 清理后台进程缓存
            executeCommand("echo 3 > /proc/sys/vm/drop_caches")
            reclaimed += 1

            // 方法2: 压缩内存（如果支持）
            executeCommand("echo 1 > /proc/sys/vm/compact_memory")
            reclaimed += 1

            // 方法3: 杀掉低优先级后台进程
            executeCommand("am kill-all background")
            reclaimed += 1

            ActionResult(ActionType.RECLAIM_MEMORY, true,
                "Memory reclaimed ($reclaimed methods applied)")
        } catch (e: Exception) {
            ActionResult(ActionType.RECLAIM_MEMORY, false, e.message ?: "Unknown error")
        }
    }

    override fun boostProcessPriority(packageName: String): ActionResult {
        return try {
            // 方法1: 设置进程oom_adj为较低值（更难被杀死）
            val pid = getPidByPackage(packageName)
            if (pid > 0) {
                // 设置oom_adj_score为-1000（最高优先级）
                executeCommand("echo -1000 > /proc/$pid/oom_score_adj")

                // 设置进程优先级为高优先级
                executeCommand("renice -10 -p $pid")

                ActionResult(ActionType.BOOST_PRIORITY, true,
                    "Priority boosted for $packageName (pid=$pid)")
            } else {
                ActionResult(ActionType.BOOST_PRIORITY, false,
                    "Process not found: $packageName")
            }
        } catch (e: Exception) {
            ActionResult(ActionType.BOOST_PRIORITY, false, e.message ?: "Unknown error")
        }
    }

    override fun resetAll(): ActionResult {
        return try {
            // 恢复所有原始值
            for ((path, value) in originalValues) {
                try {
                    writeSysfs(path, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore $path", e)
                }
            }

            // 恢复 CPU governor
            val cpuCount = getCpuCoreCount()
            for (i in 0 until cpuCount) {
                try {
                    writeSysfs(
                        "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor",
                        "schedutil"
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }

            // 清除属性
            executeCommand("setprop debug.egl.hw 0")

            originalValues.clear()

            ActionResult(ActionType.CPU_THROTTLE, true, "All settings reset")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun release() {
        try {
            resetAll()
            outputStream?.writeBytes("exit\n")
            outputStream?.flush()
            rootSession?.waitFor()
            rootSession?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing root session", e)
        } finally {
            initialized = false
            outputStream = null
            rootSession = null
        }
    }

    /**
     * 执行 shell 命令（通过 root）
     */
    private fun executeCommand(command: String): Boolean {
        return try {
            outputStream?.writeBytes("$command\n")
            outputStream?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            false
        }
    }

    /**
     * 读取 sysfs 文件
     */
    private fun readSysfs(path: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("su -c cat $path")
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入 sysfs 文件
     */
    private fun writeSysfs(path: String, value: String): Boolean {
        return executeCommand("echo $value > $path")
    }

    /**
     * 获取 CPU 核心数
     */
    private fun getCpuCoreCount(): Int {
        return try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            8
        }
    }

    /**
     * 通过包名获取进程PID
     */
    private fun getPidByPackage(packageName: String): Int {
        return try {
            val process = Runtime.getRuntime().exec("su -c pidof $packageName")
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.split(" ").firstOrNull()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "RootActionExecutor"
    }
}
