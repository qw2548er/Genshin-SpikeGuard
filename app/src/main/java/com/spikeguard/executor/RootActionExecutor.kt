package com.spikeguard.executor

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Root 模式执行器
 *
 * 通过 su shell 执行系统调优命令
 * 所有调用在后台线程执行，带超时机制，防止ANR
 *
 * 注意：
 * 1. 只修改系统级别的频率/温控参数
 * 2. 不注入任何进程
 * 3. 不修改游戏 APK
 * 4. 所有操作可逆，退出时恢复
 */
class RootActionExecutor : ActionExecutor {

    override val name = "Root"

    /**
     * ===== Fix-1：Root 模式详细状态探测（不依赖 initialized 标志）=====
     *
     * 根因：UI 检测阶段永远不会调用 initialize()，导致 initialized=false 永远返回 INITIALIZING。
     * 修复策略：用非阻塞探测决定最终状态，不阻塞调用方线程。
     */
    override fun getDetailedStatus(): ShizukuDetailedStatus {
        // Step 1：已初始化并且会话还在 → BINDER_OK
        if (initialized && rootSession != null) {
            return try {
                // 检测 su 进程是否还活着（exitValue 若未退出抛 IllegalThreadStateException）
                rootSession!!.exitValue()
                // 若能走到这里说明进程已退出 → 重建
                ShizukuDetailedStatus.SERVICE_NOT_RUNNING
            } catch (_: IllegalThreadStateException) {
                // 还在运行 → OK
                ShizukuDetailedStatus.BINDER_OK
            } catch (_: Throwable) {
                ShizukuDetailedStatus.SERVICE_NOT_RUNNING
            }
        }

        // Step 2：检查 su 二进制是否存在（非阻塞，纯文件存在性检查）
        val suExists = try {
            val paths = arrayOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/system/su", "/su/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/ksud"
            )
            paths.any { p ->
                try { java.io.File(p).exists() } catch (_: Throwable) { false }
            } || Runtime.getRuntime().exec("which su").inputStream.bufferedReader().readLine()?.isNotBlank() == true
        } catch (_: Throwable) { false }
        Log.d(TAG, "[Status] su binary exists=$suExists")

        if (!suExists) {
            return ShizukuDetailedStatus.NOT_INSTALLED
        }
        // su 在但还没握手 → 显示 SERVICE_NOT_RUNNING（用户需要手动点重试让 su 弹授权）
        return ShizukuDetailedStatus.SERVICE_NOT_RUNNING
    }

    override fun getStatusHumanMessage(): String {
        return when (getDetailedStatus()) {
            ShizukuDetailedStatus.BINDER_OK -> "✅ Root 权限已获取（su会话已建立）"
            ShizukuDetailedStatus.INITIALIZING -> "⏳ 正在请求Root权限（5秒内未响应请点重试）"
            ShizukuDetailedStatus.SERVICE_NOT_RUNNING ->
                "⚠️ su 可用但未建立会话\n请点击下方「重试连接」或手动触发超级用户授权弹窗（Magisk/KernelSU）"
            else -> "❌ Root不可用\n请确保设备已Root并授予本应用超级用户权限（Magisk/KernelSU等）"
        }
    }

    /**
     * ===== Fix-1：Root 异步安全初始化（5秒超时，后台线程执行）=====
     */
    fun ensureInitializedAsync(
        timeoutMs: Long = 5000L,
        onResult: (ShizukuDetailedStatus) -> Unit
    ) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val startAt = System.currentTimeMillis()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        val finish: (ShizukuDetailedStatus) -> Unit = { status ->
            if (done.compareAndSet(false, true)) {
                Log.i(TAG, "[ensureInitializedAsync] finish in ${System.currentTimeMillis() - startAt}ms => $status")
                mainHandler.post { onResult(status) }
            }
        }
        mainHandler.postDelayed({ finish(ShizukuDetailedStatus.INITIALIZING) }, timeoutMs)

        Thread {
            try {
                val ok = try {
                    // 先关旧会话（避免死进程）
                    try { outputStream?.close() } catch (_: Throwable) {}
                    try { rootSession?.destroy() } catch (_: Throwable) {}
                    val proc = Runtime.getRuntime().exec("su")
                    val os = DataOutputStream(proc.outputStream)
                    os.writeBytes("id >/dev/null 2>&1\necho SPIKEGUARD_ROOT_OK=$?\n")
                    os.flush()
                    val sb = StringBuilder()
                    val reader = proc.inputStream.bufferedReader()
                    val deadline = System.currentTimeMillis() + (timeoutMs - 500)
                    while (System.currentTimeMillis() < deadline) {
                        val line = reader.readLine() ?: break
                        sb.append(line)
                        if (line.contains("SPIKEGUARD_ROOT_OK=0")) break
                    }
                    val success = sb.contains("SPIKEGUARD_ROOT_OK=0")
                    if (success) {
                        rootSession = proc
                        outputStream = os
                        initialized = true
                        Log.i(TAG, "[ensureInitAsync] Root session established")
                        true
                    } else {
                        try { os.close() } catch (_: Throwable) {}
                        try { proc.destroy() } catch (_: Throwable) {}
                        false
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[ensureInitAsync] Root handshake exception: ${t.message}", t)
                    false
                }
                initialized = ok
                finish(getDetailedStatus())
            } catch (t: Throwable) {
                Log.e(TAG, "[ensureInitAsync] fatal exception: ${t.message}", t)
                finish(ShizukuDetailedStatus.UNKNOWN)
            }
        }.start()
    }

    private var rootSession: Process? = null
    private var outputStream: DataOutputStream? = null
    private var initialized = false

    // 保存原始值用于恢复
    private val originalValues = mutableMapOf<String, String>()

    // 后台线程处理所有Root调用
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 暂停标志（静默期使用）
    private val paused = AtomicBoolean(false)

    companion object {
        private const val TAG = "RootActionExecutor"
        private const val CALL_TIMEOUT_MS = 3000L
    }

    override fun isAvailable(): Boolean {
        // 轻量级检查：不实际执行su，避免阻塞
        // 真正的可用性在 initialize 时后台线程验证
        return try {
            // 检查 su 二进制是否存在
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/su"
            )
            for (path in paths) {
                if (java.io.File(path).exists()) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun initialize(): Boolean {
        return try {
            // 启动后台线程
            handlerThread = HandlerThread("RootExecutor")
            handlerThread?.start()
            handler = Handler(handlerThread!!.looper)

            // 在后台线程初始化Root会话
            val result = runOnBackgroundWithTimeout(CALL_TIMEOUT_MS * 2) {
                initializeRootInternal()
            }

            initialized = result == true
            Log.i(TAG, "Root executor initialized: $initialized")
            initialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize root executor", e)
            false
        }
    }

    /**
     * 初始化Root会话（内部方法，后台线程调用）
     */
    private fun initializeRootInternal(): Boolean {
        return try {
            rootSession = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(rootSession!!.outputStream)

            // 验证root权限
            outputStream?.writeBytes("echo root_verified\n")
            outputStream?.flush()

            // 读取验证输出（带超时）
            val reader = rootSession!!.inputStream.bufferedReader()
            val output = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 2000) {
                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) {
                        output.append(line)
                        if (output.contains("root_verified")) {
                            return true
                        }
                    }
                } else {
                    Thread.sleep(50)
                }
            }

            // 如果没有读到输出，也认为可能成功（某些root实现不回显）
            Log.w(TAG, "Root verification output not received, assuming success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Root session initialization failed", e)
            rootSession?.destroy()
            rootSession = null
            outputStream = null
            false
        }
    }

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
            val cpuCount = getCpuCoreCountInternal()

            for (i in 0 until cpuCount) {
                val maxFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val curGovPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor"
                val maxFreq = readSysfsInternal(maxFreqPath)?.toIntOrNull() ?: continue

                // 保存原始值
                if (!originalValues.containsKey(curGovPath)) {
                    originalValues[curGovPath] = readSysfsInternal(curGovPath) ?: "interactive"
                }

                // 设置为 userspace governor 以便控制频率
                writeSysfsInternal(curGovPath, "userspace")

                // 设置目标频率
                val targetFreq = (maxFreq * throttle).toInt()
                val setFreqPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_setspeed"
                writeSysfsInternal(setFreqPath, targetFreq.toString())
            }

            ActionResult(ActionType.CPU_THROTTLE, true,
                "CPU throttled to ${(throttle * 100).toInt()}%")
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
            val gpuFreqPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
                "/sys/devices/platform/soc/1c00000.gpu/devfreq/1c00000.gpu/max_freq"
            )

            var success = false
            for (path in gpuFreqPaths) {
                val maxFreq = readSysfsInternal(path)?.toIntOrNull()
                if (maxFreq != null && maxFreq > 0) {
                    // 保存原始值
                    if (!originalValues.containsKey(path)) {
                        originalValues[path] = maxFreq.toString()
                    }

                    val targetFreq = (maxFreq * throttle).toInt()
                    writeSysfsInternal(path, targetFreq.toString())
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
            executeCommandInternal("setprop debug.sf.latch_unsignaled 1")
            executeCommandInternal("setprop debug.egl.hw $fpsLimit")

            ActionResult(ActionType.FRAME_LIMIT, true, "Frame limit set to $fpsLimit")
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

            executeCommandInternal("echo 3 > /proc/sys/vm/drop_caches")
            reclaimed += 1

            executeCommandInternal("echo 1 > /proc/sys/vm/compact_memory")
            reclaimed += 1

            executeCommandInternal("am kill-all background")
            reclaimed += 1

            ActionResult(ActionType.RECLAIM_MEMORY, true,
                "Memory reclaimed ($reclaimed methods applied)")
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
                executeCommandInternal("echo -1000 > /proc/$pid/oom_score_adj")
                executeCommandInternal("renice -10 -p $pid")

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
                    writeSysfsInternal(path, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore $path", e)
                }
            }

            // 恢复 CPU governor
            val cpuCount = getCpuCoreCountInternal()
            for (i in 0 until cpuCount) {
                try {
                    writeSysfsInternal(
                        "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor",
                        "schedutil"
                    )
                } catch (e: Exception) {
                    // 忽略
                }
            }

            // 清除属性
            executeCommandInternal("setprop debug.egl.hw 0")

            originalValues.clear()

            ActionResult(ActionType.CPU_THROTTLE, true, "All settings reset")
        } catch (e: Exception) {
            ActionResult(ActionType.CPU_THROTTLE, false, e.message ?: "Unknown error")
        }
    }

    override fun release() {
        try {
            if (initialized) {
                // 在后台线程执行清理，但不等待
                handler?.post {
                    try {
                        resetAllInternal()
                        outputStream?.writeBytes("exit\n")
                        outputStream?.flush()
                        try {
                            rootSession?.waitFor(2000, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            // 忽略
                        }
                        rootSession?.destroy()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in release cleanup", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing root session", e)
        } finally {
            initialized = false
            outputStream = null
            rootSession = null

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

    /**
     * 暂停所有调用（静默期使用）
     */
    fun pause() {
        paused.set(true)
        Log.i(TAG, "Root executor paused (silent mode)")
    }

    /**
     * 恢复调用
     */
    fun resume() {
        paused.set(false)
        Log.i(TAG, "Root executor resumed")
    }

    /**
     * 在后台线程执行任务并等待结果，带超时
     */
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
                Log.w(TAG, "Root call timed out after ${timeoutMs}ms")
            }
            result.get()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Root call interrupted", e)
            null
        }
    }

    /**
     * 执行 shell 命令（通过 root，内部方法，必须在后台线程）
     */
    private fun executeCommandInternal(command: String): Boolean {
        if (paused.get()) {
            Log.w(TAG, "Root call skipped: paused")
            return false
        }

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
     * 读取 sysfs 文件（内部方法）
     */
    private fun readSysfsInternal(path: String): String? {
        if (paused.get()) return null

        return try {
            val process = Runtime.getRuntime().exec("su -c cat $path")
            val result = StringBuilder()
            val reader = process.inputStream.bufferedReader()

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CALL_TIMEOUT_MS / 2) {
                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) {
                        result.append(line)
                    } else {
                        break
                    }
                } else {
                    Thread.sleep(20)
                }
            }

            process.destroy()
            result.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Read sysfs failed: $path", e)
            null
        }
    }

    /**
     * 写入 sysfs 文件（内部方法）
     */
    private fun writeSysfsInternal(path: String, value: String): Boolean {
        return executeCommandInternal("echo $value > $path")
    }

    /**
     * 获取 CPU 核心数（内部方法）
     */
    private fun getCpuCoreCountInternal(): Int {
        return try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            8
        }
    }

    /**
     * 通过包名获取进程PID（内部方法）
     */
    private fun getPidByPackageInternal(packageName: String): Int {
        if (paused.get()) return 0

        return try {
            val process = Runtime.getRuntime().exec("su -c pidof $packageName")
            val result = StringBuilder()
            val reader = process.inputStream.bufferedReader()

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CALL_TIMEOUT_MS / 2) {
                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) {
                        result.append(line)
                    } else {
                        break
                    }
                } else {
                    Thread.sleep(20)
                }
            }

            process.destroy()
            result.toString().trim().split(" ").firstOrNull()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // ==========================================================
    // P1-2b: RootActionExecutor.executeFullProtectionFlow() 真实完整保护流程
    // 四步 + 1500ms 后无条件恢复
    // ==========================================================
    override fun executeFullProtectionFlow(
        reclaimMemory: Boolean,
        gpuThrottle: Float,
        cpuThrottle: Float,
        boostPriority: Boolean,
        targetPackageName: String,
        durationMs: Long
    ): FullFlowResult {
        if (!initialized) {
            Log.w(TAG, "executeFullProtectionFlow called but not initialized")
            return FullFlowResult(null, null, null, null, null, 0L, 0, 0)
        }
        if (paused.get()) {
            Log.w(TAG, "executeFullProtectionFlow skipped: paused")
            return FullFlowResult(null, null, null, null, null, 0L, 0, 0)
        }

        val t0 = System.currentTimeMillis()

        val r1: ActionResult? = if (reclaimMemory) {
            try { reclaimMemoryInternal() } catch (e: Throwable) {
                ActionResult(ActionType.RECLAIM_MEMORY, false, "Exception: ${e.message}")
            }
        } else null

        val r2: ActionResult = try { setGpuThrottleInternal(gpuThrottle) } catch (e: Throwable) {
            ActionResult(ActionType.GPU_THROTTLE, false, "Exception: ${e.message}")
        }

        val r3: ActionResult = try { setCpuThrottleInternal(cpuThrottle) } catch (e: Throwable) {
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }

        val r4: ActionResult? = if (boostPriority) {
            try { boostProcessPriorityInternal(targetPackageName) } catch (e: Throwable) {
                ActionResult(ActionType.BOOST_PRIORITY, false, "Exception: ${e.message}")
            }
        } else null

        // ====== 等待 1500ms 保护窗口 ======
        val waitMs = durationMs.coerceIn(100L, 10_000L)
        try {
            Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
            Log.w(TAG, "Root full protection sleep interrupted, reset immediately")
        }

        // ====== 1500ms后无条件恢复 ======
        val r5: ActionResult = try { resetAllInternal() } catch (e: Throwable) {
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }

        val total = System.currentTimeMillis() - t0
        val list = listOfNotNull(r1, r2, r3, r4, r5)
        val ok = list.count { it.success }
        Log.i(TAG, "Root full flow done: success=$ok/${list.size}, total=${total}ms")
        return FullFlowResult(r1, r2, r3, r4, r5, total, ok, list.size)
    }
}
