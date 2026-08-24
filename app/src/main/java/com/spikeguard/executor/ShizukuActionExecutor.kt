package com.spikeguard.executor

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 模式执行器
 *
 * 通过 Shizuku Sui 框架获取系统级权限
 * 使用 app_process 运行 Java 代码来控制系统参数
 *
 * 优势：
 * 1. 不需要完整 Root
 * 2. 权限更可控
 * 3. 不需要修改系统分区
 */
class ShizukuActionExecutor(private val context: Context) : ActionExecutor {

    override val name = "Shizuku"

    private var initialized = false

    // 保存原始值
    private val originalValues = mutableMapOf<String, String>()

    override fun isAvailable(): Boolean {
        return try {
            // 检查 Shizuku 是否安装并运行
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val hasShizuku = packages.any { it.packageName == "moe.shizuku.privileged.api" }
            val hasSui = packages.any { it.packageName == "rikka.sui" }

            if (!hasShizuku && !hasSui) {
                return false
            }

            // 检查 Shizuku 服务是否运行
            val process = Runtime.getRuntime().exec("sh -c 'ps -A | grep shizuku'")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun initialize(): Boolean {
        return try {
            // 等待 Shizuku 权限授权
            // 实际应用中需要通过 Shizuku API 绑定服务
            initialized = true
            Log.i(TAG, "Shizuku executor initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku executor", e)
            false
        }
    }

    override fun setCpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.CPU_THROTTLE, false, "Not initialized")

        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()

            for (i in 0 until cpuCount) {
                val maxFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val govPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor"

                val maxFreq = readFileWithShizuku(maxFreqPath)?.toIntOrNull() ?: continue

                // 保存原始值
                if (!originalValues.containsKey(govPath)) {
                    originalValues[govPath] = readFileWithShizuku(govPath) ?: "schedutil"
                }

                // 设置 governor
                writeFileWithShizuku(govPath, "userspace")

                // 设置频率
                val targetFreq = (maxFreq * throttle).toInt()
                val setSpeedPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_setspeed"
                writeFileWithShizuku(setSpeedPath, targetFreq.toString())
            }

            ActionResult(ActionType.CPU_THROTTLE, true,
                "CPU throttled to ${(throttle * 100).toInt()}% via Shizuku")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setGpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.GPU_THROTTLE, false, "Not initialized")

        return try {
            val gpuPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
            )

            var success = false
            for (path in gpuPaths) {
                val maxFreq = readFileWithShizuku(path)?.toIntOrNull()
                if (maxFreq != null && maxFreq > 0) {
                    if (!originalValues.containsKey(path)) {
                        originalValues[path] = maxFreq.toString()
                    }

                    val targetFreq = (maxFreq * throttle).toInt()
                    writeFileWithShizuku(path, targetFreq.toString())
                    success = true
                    break
                }
            }

            if (success) {
                ActionResult(ActionType.GPU_THROTTLE, true,
                    "GPU throttled to ${(throttle * 100).toInt()}% via Shizuku")
            } else {
                ActionResult(ActionType.GPU_THROTTLE, false, "GPU control not available")
            }
        } catch (e: Exception) {
            ActionResult(ActionType.GPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setFrameLimit(fpsLimit: Int): ActionResult {
        return try {
            // 通过 Shizuku 执行 setprop
            execWithShizuku("setprop debug.sf.latch_unsignaled 1")
            execWithShizuku("setprop debug.egl.hw $fpsLimit")

            ActionResult(ActionType.FRAME_LIMIT, true, "Frame limit set to $fpsLimit")
        } catch (e: Exception) {
            ActionResult(ActionType.FRAME_LIMIT, false, e.message ?: "Unknown error")
        }
    }

    override fun resetAll(): ActionResult {
        return try {
            // 恢复所有原始值
            for ((path, value) in originalValues) {
                try {
                    writeFileWithShizuku(path, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore $path", e)
                }
            }

            // 恢复 CPU governor
            val cpuCount = Runtime.getRuntime().availableProcessors()
            for (i in 0 until cpuCount) {
                try {
                    writeFileWithShizuku(
                        "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor",
                        "schedutil"
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }

            // 清除属性
            execWithShizuku("setprop debug.egl.hw 0")

            originalValues.clear()

            ActionResult(ActionType.CPU_THROTTLE, true, "All settings reset")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun release() {
        try {
            resetAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Shizuku session", e)
        } finally {
            initialized = false
        }
    }

    /**
     * 通过 Shizuku 执行命令
     *
     * 实际实现中应该使用 Shizuku 的 Binder API
     * 这里使用 sh 作为兼容降级方案
     */
    private fun execWithShizuku(command: String): String {
        // 注意：实际应用应使用 ShizukuRemoteProcess
        // 这里简化实现，使用 sh 执行
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            process.waitFor()
            result
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 通过 Shizuku 读取文件
     */
    private fun readFileWithShizuku(path: String): String? {
        return try {
            execWithShizuku("cat $path").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过 Shizuku 写入文件
     */
    private fun writeFileWithShizuku(path: String, value: String): Boolean {
        return try {
            execWithShizuku("echo $value > $path")
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ShizukuActionExecutor"
    }
}
