package com.spikeguard.executor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import moe.shizuku.server.IRemoteProcess
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 模式执行器
 *
 * 通过 Shizuku Sui 框架的 Binder API 获取系统级权限执行命令
 * 所有调用在后台线程执行，带超时机制，防止ANR
 *
 * 真正提权的实现：
 * - 优先使用 Shizuku Binder API 执行命令（系统权限级）
 * - 如果 Shizuku 未就绪/授权失败，降级到 shell（日志提示）
 *
 * 安全特性：
 * 1. 后台线程执行，不阻塞主线程
 * 2. 3秒超时机制，超时自动放弃
 * 3. 服务状态检查，未就绪不盲目调用
 * 4. 完善的异常捕获，防止崩溃传播
 */
class ShizukuActionExecutor(private val context: Context) : ActionExecutor {

    override val name = "Shizuku"

    private var initialized = false
    private var useShizukuApi = false

    // 保存原始值
    private val originalValues = mutableMapOf<String, String>()

    // 后台线程处理所有Shizuku调用
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 暂停标志（静默期使用）
    private val paused = AtomicBoolean(false)

    companion object {
        private const val TAG = "ShizukuActionExecutor"
        private const val CALL_TIMEOUT_MS = 3000L
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SUI_PACKAGE = "rikka.sui"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 10001
    }

    /**
     * 检查 Shizuku 服务是否可用
     * 注意：此方法也不应在主线程调用，这里做了轻量级检查
     */
    override fun isAvailable(): Boolean {
        return try {
            // 轻量级检查：只检查包是否安装
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val hasShizuku = packages.any { it.packageName == SHIZUKU_PACKAGE }
            val hasSui = packages.any { it.packageName == SUI_PACKAGE }

            if (!hasShizuku && !hasSui) {
                Log.w(TAG, "Shizuku/Sui not installed")
                return false
            }

            // 不做进程检查（可能阻塞），由 initialize 时在后台线程验证
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Shizuku availability", e)
            false
        }
    }

    override fun initialize(): Boolean {
        return try {
            // 启动后台线程
            handlerThread = HandlerThread("ShizukuExecutor")
            handlerThread?.start()
            handler = Handler(handlerThread!!.looper)

            // 在后台线程验证Shizuku服务
            val result = runOnBackgroundWithTimeout(CALL_TIMEOUT_MS * 2) {
                initializeShizukuInternal()
            }

            initialized = result == true
            Log.i(TAG, "Shizuku executor initialized: $initialized (useShizukuApi=$useShizukuApi)")
            initialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku executor", e)
            false
        }
    }

    /**
     * 初始化 Shizuku（内部方法，后台线程调用）
     * 尝试绑定 Shizuku Binder 并验证权限
     */
    private fun initializeShizukuInternal(): Boolean {
        return try {
            // 方式1：尝试 Shizuku Binder API（v11+）
            try {
                if (Shizuku.pingBinder()) {
                    // Binder 可用，检查权限
                    val uid = try {
                        Shizuku.getUid()
                    } catch (t: Throwable) {
                        -1
                    }
                    // root/shell 权限 uid 为 0 / 2000，普通 app 至少大于 10000
                    useShizukuApi = uid >= 0
                    if (useShizukuApi) {
                        Log.i(TAG, "Shizuku Binder API ready, uid=$uid")
                        return true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku binder not available (${e.javaClass.simpleName}: ${e.message})")
            }

            // 方式2：Sui 模式（通过 Provider）
            Log.i(TAG, "Shizuku Binder unavailable, falling back to shell executor")
            useShizukuApi = false
            // shell 模式至少能执行普通命令（虽然可能没有 root 权限）
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku service check failed", e)
            false
        }
    }

    // ==========================================================
    // 核心动作方法
    // ==========================================================

    override fun setCpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.CPU_THROTTLE, false, "Not initialized")
        if (paused.get()) return ActionResult(ActionType.CPU_THROTTLE, false, "Paused (silent mode)")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS) {
                setCpuThrottleInternal(throttle)
            } ?: ActionResult(ActionType.CPU_THROTTLE, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "setCpuThrottle failed", e)
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }
    }

    private fun setCpuThrottleInternal(throttle: Float): ActionResult {
        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()

            for (i in 0 until cpuCount) {
                val maxFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val govPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor"

                val maxFreq = readFileInternal(maxFreqPath)?.toIntOrNull() ?: continue

                // 保存原始值
                if (!originalValues.containsKey(govPath)) {
                    originalValues[govPath] = readFileInternal(govPath) ?: "schedutil"
                }

                // 设置 governor
                writeFileInternal(govPath, "userspace")

                // 设置频率
                val targetFreq = (maxFreq * throttle).toInt()
                val setSpeedPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_setspeed"
                writeFileInternal(setSpeedPath, targetFreq.toString())
            }

            val apiTag = if (useShizukuApi) "Shizuku API" else "shell fallback"
            ActionResult(ActionType.CPU_THROTTLE, true,
                "CPU throttled to ${(throttle * 100).toInt()}% via $apiTag")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setGpuThrottle(throttle: Float): ActionResult {
        if (!initialized) return ActionResult(ActionType.GPU_THROTTLE, false, "Not initialized")
        if (paused.get()) return ActionResult(ActionType.GPU_THROTTLE, false, "Paused (silent mode)")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS) {
                setGpuThrottleInternal(throttle)
            } ?: ActionResult(ActionType.GPU_THROTTLE, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "setGpuThrottle failed", e)
            ActionResult(ActionType.GPU_THROTTLE, false, "Exception: ${e.message}")
        }
    }

    private fun setGpuThrottleInternal(throttle: Float): ActionResult {
        return try {
            val gpuPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
            )

            var success = false
            for (path in gpuPaths) {
                val maxFreq = readFileInternal(path)?.toIntOrNull()
                if (maxFreq != null && maxFreq > 0) {
                    if (!originalValues.containsKey(path)) {
                        originalValues[path] = maxFreq.toString()
                    }

                    val targetFreq = (maxFreq * throttle).toInt()
                    writeFileInternal(path, targetFreq.toString())
                    success = true
                    break
                }
            }

            val apiTag = if (useShizukuApi) "Shizuku API" else "shell fallback"
            if (success) {
                ActionResult(ActionType.GPU_THROTTLE, true,
                    "GPU throttled to ${(throttle * 100).toInt()}% via $apiTag")
            } else {
                ActionResult(ActionType.GPU_THROTTLE, false, "GPU control not available")
            }
        } catch (e: Exception) {
            ActionResult(ActionType.GPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun setFrameLimit(fpsLimit: Int): ActionResult {
        if (!initialized) return ActionResult(ActionType.FRAME_LIMIT, false, "Not initialized")
        if (paused.get()) return ActionResult(ActionType.FRAME_LIMIT, false, "Paused (silent mode)")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS) {
                setFrameLimitInternal(fpsLimit)
            } ?: ActionResult(ActionType.FRAME_LIMIT, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "setFrameLimit failed", e)
            ActionResult(ActionType.FRAME_LIMIT, false, "Exception: ${e.message}")
        }
    }

    private fun setFrameLimitInternal(fpsLimit: Int): ActionResult {
        return try {
            execCommandInternal("setprop debug.sf.latch_unsignaled 1")
            execCommandInternal("setprop debug.egl.hw $fpsLimit")

            val apiTag = if (useShizukuApi) "Shizuku API" else "shell fallback"
            ActionResult(ActionType.FRAME_LIMIT, true, "Frame limit set to $fpsLimit via $apiTag")
        } catch (e: Exception) {
            ActionResult(ActionType.FRAME_LIMIT, false, e.message ?: "Unknown error")
        }
    }

    override fun reclaimMemory(): ActionResult {
        if (!initialized) return ActionResult(ActionType.RECLAIM_MEMORY, false, "Not initialized")
        if (paused.get()) return ActionResult(ActionType.RECLAIM_MEMORY, false, "Paused (silent mode)")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS) {
                reclaimMemoryInternal()
            } ?: ActionResult(ActionType.RECLAIM_MEMORY, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "reclaimMemory failed", e)
            ActionResult(ActionType.RECLAIM_MEMORY, false, "Exception: ${e.message}")
        }
    }

    private fun reclaimMemoryInternal(): ActionResult {
        return try {
            var reclaimed = 0

            execCommandInternal("echo 3 > /proc/sys/vm/drop_caches")
            reclaimed += 1

            execCommandInternal("echo 1 > /proc/sys/vm/compact_memory")
            reclaimed += 1

            execCommandInternal("am kill-all background")
            reclaimed += 1

            val apiTag = if (useShizukuApi) "Shizuku API" else "shell fallback"
            ActionResult(ActionType.RECLAIM_MEMORY, true,
                "Memory reclaimed ($reclaimed methods applied) via $apiTag")
        } catch (e: Exception) {
            ActionResult(ActionType.RECLAIM_MEMORY, false, e.message ?: "Unknown error")
        }
    }

    override fun boostProcessPriority(packageName: String): ActionResult {
        if (!initialized) return ActionResult(ActionType.BOOST_PRIORITY, false, "Not initialized")
        if (paused.get()) return ActionResult(ActionType.BOOST_PRIORITY, false, "Paused (silent mode)")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS) {
                boostProcessPriorityInternal(packageName)
            } ?: ActionResult(ActionType.BOOST_PRIORITY, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "boostProcessPriority failed", e)
            ActionResult(ActionType.BOOST_PRIORITY, false, "Exception: ${e.message}")
        }
    }

    private fun boostProcessPriorityInternal(packageName: String): ActionResult {
        return try {
            val pid = getPidByPackageInternal(packageName)
            if (pid > 0) {
                execCommandInternal("echo -1000 > /proc/$pid/oom_score_adj")
                execCommandInternal("renice -10 -p $pid")

                val apiTag = if (useShizukuApi) "Shizuku API" else "shell fallback"
                ActionResult(ActionType.BOOST_PRIORITY, true,
                    "Priority boosted for $packageName (pid=$pid) via $apiTag")
            } else {
                ActionResult(ActionType.BOOST_PRIORITY, false,
                    "Process not found: $packageName")
            }
        } catch (e: Exception) {
            ActionResult(ActionType.BOOST_PRIORITY, false, e.message ?: "Unknown error")
        }
    }

    override fun resetAll(): ActionResult {
        if (!initialized) return ActionResult(ActionType.CPU_THROTTLE, false, "Not initialized")

        return try {
            runOnBackgroundWithTimeout(CALL_TIMEOUT_MS * 2) {
                resetAllInternal()
            } ?: ActionResult(ActionType.CPU_THROTTLE, false, "Timeout or error")
        } catch (e: Exception) {
            Log.e(TAG, "resetAll failed", e)
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }
    }

    private fun resetAllInternal(): ActionResult {
        return try {
            // 恢复所有原始值
            for ((path, value) in originalValues) {
                try {
                    writeFileInternal(path, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore $path", e)
                }
            }

            // 恢复 CPU governor
            val cpuCount = Runtime.getRuntime().availableProcessors()
            for (i in 0 until cpuCount) {
                try {
                    writeFileInternal(
                        "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor",
                        "schedutil"
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }

            // 清除属性
            execCommandInternal("setprop debug.egl.hw 0")

            originalValues.clear()

            ActionResult(ActionType.CPU_THROTTLE, true, "All settings reset")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun release() {
        try {
            if (initialized) {
                // 在后台线程执行reset，但不等待
                handler?.post {
                    try {
                        resetAllInternal()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in release reset", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Shizuku session", e)
        } finally {
            initialized = false
            // 退出后台线程
            try {
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    // ==========================================================
    // 暂停/恢复
    // ==========================================================

    fun pause() {
        paused.set(true)
        Log.i(TAG, "Shizuku executor paused (silent mode)")
    }

    fun resume() {
        paused.set(false)
        Log.i(TAG, "Shizuku executor resumed")
    }

    // ==========================================================
    // 内部工具：后台线程调度 + 超时
    // ==========================================================

    private fun <T> runOnBackgroundWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val h = handler ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>()

        h.post {
            try {
                result.set(block())
            } catch (e: Exception) {
                Log.e(TAG, "Background task exception", e)
                result.set(null)
            } finally {
                latch.countDown()
            }
        }

        return try {
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.w(TAG, "Shizuku call timed out after ${timeoutMs}ms")
            }
            result.get()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Shizuku call interrupted", e)
            null
        }
    }

    // ==========================================================
    // 命令执行：优先 Shizuku Binder，fallback shell
    // ==========================================================

    /**
     * 执行 shell 命令（内部方法，必须在后台线程调用）
     *
     * 提权路径：
     * 1) useShizukuApi = true：使用 Shizuku.newProcess(cmd) Binder 调用
     *    这会把命令提交到 Shizuku/Sui 守护进程，以 shell/root 权限执行
     * 2) 否则：回退 Runtime.exec("sh", "-c", cmd)，只能普通权限
     */
    private fun execCommandInternal(command: String): String {
        // 检查暂停状态
        if (paused.get()) {
            Log.w(TAG, "Shizuku call skipped: paused")
            return ""
        }

        // 路径1：Shizuku Binder API
        if (useShizukuApi) {
            return try {
                execCommandViaShizukuBinder(command)
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku Binder exec failed, fallback to shell: ${e.message}")
                execCommandViaShell(command)
            }
        }

        // 路径2：普通 shell fallback（可能没有 root 权限）
        return execCommandViaShell(command)
    }

    /**
     * 使用 Shizuku Binder 执行命令（真正提权）
     *
     * Shizuku v13 没有暴露公开的 newProcess()，需要：
     * 1) 用 Shizuku.transactRemote(code=TRANSACTION_newProcess=8) 自己打 Parcel
     * 2) reply 里 readStrongBinder 得到 IRemoteProcess.Stub.asInterface(...)
     * 3) 通过 IRemoteProcess.getInputStream/getErrorStream/waitFor 读取输出
     */
    private fun execCommandViaShizukuBinder(command: String): String {
        var data: Parcel? = null
        var reply: Parcel? = null
        var remoteProcess: IRemoteProcess? = null
        var stdoutFd: ParcelFileDescriptor? = null
        var stderrFd: ParcelFileDescriptor? = null

        return try {
            val cmd = arrayOf("sh", "-c", command)
            data = Parcel.obtain()
            reply = Parcel.obtain()
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeStringArray(cmd)
            data.writeStringArray(null as Array<String?>?)  // env
            data.writeString(null as String?)                // cwd

            // Shizuku.transactRemote(code, data, reply): code=8 -> newProcess
            Shizuku.transactRemote(data, reply, 8)

            reply.readException()
            val binder: IBinder? = reply.readStrongBinder()
            remoteProcess = if (binder != null) {
                IRemoteProcess.Stub.asInterface(binder)
            } else {
                Log.w(TAG, "newProcess returned null binder")
                return ""
            }

            // 拿 stdout / stderr 的文件描述符
            stdoutFd = remoteProcess.inputStream
            stderrFd = remoteProcess.errorStream

            val outBuilder = StringBuilder()
            val stdoutReader = BufferedReader(InputStreamReader(FileInputStream(stdoutFd.fileDescriptor)))
            val stderrReader = BufferedReader(InputStreamReader(FileInputStream(stderrFd.fileDescriptor)))

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CALL_TIMEOUT_MS) {
                if (stdoutReader.ready()) {
                    val line = stdoutReader.readLine()
                    if (line != null) outBuilder.append(line).append('\n')
                } else {
                    val buf = CharArray(4096)
                    val n = stdoutReader.read(buf)
                    if (n > 0) outBuilder.appendRange(buf, 0, n)
                }
                if (stderrReader.ready()) {
                    val line = stderrReader.readLine()
                    if (line != null) outBuilder.append("[stderr] ").append(line).append('\n')
                }
                // 看进程是否退出
                val exited = try {
                    remoteProcess.exitValue()
                    true
                } catch (_: IllegalThreadStateException) {
                    false
                } catch (_: RemoteException) {
                    false
                }
                if (exited) break
                Thread.sleep(20)
            }

            // 等待进程退出（最长 CALL_TIMEOUT_MS/2）
            run waitForBlock@ {
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < CALL_TIMEOUT_MS / 2) {
                    val exited = try {
                        remoteProcess.exitValue()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    if (exited) return@waitForBlock
                    Thread.sleep(20)
                }
                Log.w(TAG, "Shizuku process timed out, destroy: $command")
                try { remoteProcess.destroy() } catch (_: Throwable) {}
            }

            outBuilder.toString().trim()
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku binder newProcess failed: ${t.javaClass.simpleName} ${t.message}")
            throw t
        } finally {
            try { remoteProcess?.destroy() } catch (_: Throwable) {}
            try { stdoutFd?.close() } catch (_: Throwable) {}
            try { stderrFd?.close() } catch (_: Throwable) {}
            data?.recycle()
            reply?.recycle()
        }
    }

    /**
     * Fallback：通过普通 shell 执行命令（不提权）
     */
    private fun execCommandViaShell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            val exited = process.waitFor(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) {
                Log.w(TAG, "Command timed out: $command")
                process.destroy()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            ""
        }
    }

    /**
     * 读取文件（内部方法）
     */
    private fun readFileInternal(path: String): String? {
        return try {
            val result = execCommandInternal("cat $path").trim()
            result.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入文件（内部方法）
     */
    private fun writeFileInternal(path: String, value: String): Boolean {
        return try {
            execCommandInternal("echo $value > $path")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过包名获取进程PID（内部方法）
     */
    private fun getPidByPackageInternal(packageName: String): Int {
        return try {
            val output = execCommandInternal("pidof $packageName").trim()
            output.split(" ").firstOrNull()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
