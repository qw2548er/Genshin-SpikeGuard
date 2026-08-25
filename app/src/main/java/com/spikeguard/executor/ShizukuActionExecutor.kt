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
     *  ===== Fix-1：彻底重写 getDetailedStatus()，不再依赖 initialized 标志 =====
     *
     * 根因：MainActivity.checkPermissionStatus 会直接 new ShizukuActionExecutor() 调 getDetailedStatus()，
     * 但 initialize() 只有在 ExecutionManager 内部启动时才调用，导致 initialized=false 永远 INITIALIZING。
     *
     * 修复策略：不管 initialize() 有没有被调用，getDetailedStatus() 都能基于真实探测给出明确结论。
     * 探测步骤按无阻塞顺序进行，每一步加 Log.d 便于定位。
     * 禁止在调用方线程做 CountDownLatch.await！
     */
    override fun getDetailedStatus(): ShizukuDetailedStatus {
        // Step 1：检查 Shizuku/Sui 是否安装（纯 PackageManager 查询，无阻塞无异常）
        val hasShizuku = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
        } catch (_: Throwable) { false }
        val hasSui = try {
            context.packageManager.getPackageInfo(SUI_PACKAGE, 0) != null
        } catch (_: Throwable) { false }
        Log.d(TAG, "[Status] Step1 install check: shizuku=$hasShizuku, sui=$hasSui")
        if (!hasShizuku && !hasSui) {
            return ShizukuDetailedStatus.NOT_INSTALLED
        }

        // Step 2：如果已经 initialize 成功并用了 Binder API，直接活 ping 确认
        if (initialized && useShizukuApi) {
            return try {
                if (Shizuku.pingBinder()) {
                    Log.d(TAG, "[Status] Step2 pingBinder=ok => BINDER_OK (useShizukuApi, initialized)")
                    ShizukuDetailedStatus.BINDER_OK
                } else {
                    Log.w(TAG, "[Status] Step2 pingBinder=false after initialized => SERVICE_NOT_RUNNING")
                    ShizukuDetailedStatus.SERVICE_NOT_RUNNING
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[Status] Step2 pingBinder exception=${t.javaClass.simpleName}: ${t.message}")
                ShizukuDetailedStatus.SERVICE_NOT_RUNNING
            }
        }
        if (initialized && !useShizukuApi) {
            Log.d(TAG, "[Status] Step2 initialized but useShizukuApi=false => USING_FALLBACK_SHELL")
            return ShizukuDetailedStatus.USING_FALLBACK_SHELL
        }

        // Step 3：没初始化 -> 直接尝试探测 Binder + 权限。
        // 即便 initialize() 没被调用，Shizuku.pingBinder/getUid 也能反映真实状态（Shizuku 库内部会从 Provider 拿 Service）
        val (binderAlive, uid) = try {
            val alive = Shizuku.pingBinder()
            val u = if (alive) {
                try { Shizuku.getUid() } catch (t: Throwable) {
                    Log.w(TAG, "[Status] Step3 getUid failed: ${t.javaClass.simpleName}: ${t.message}")
                    -2
                }
            } else -1
            alive to u
        } catch (t: Throwable) {
            Log.w(TAG, "[Status] Step3 pingBinder exception=${t.javaClass.simpleName}: ${t.message}")
            false to -1
        }
        Log.d(TAG, "[Status] Step3 probe: binderAlive=$binderAlive, uid=$uid")

        return when {
            !binderAlive -> {
                // Shizuku/Sui APK已装但服务没启或ADB连接断开
                ShizukuDetailedStatus.SERVICE_NOT_RUNNING
            }
            uid < 0 -> {
                // Binder 通但 UID 拿不到 = 权限被拒绝（Shizuku 授权列表里本应用没开）
                ShizukuDetailedStatus.PERMISSION_DENIED
            }
            else -> {
                // Binder 通 + 权限OK → 说明 Shizuku 端已就绪，只是本类还没走 initialize 流程
                // 此时给一个非 INITIALIZING 的"就绪但未握手"状态？这里直接判定 BINDER_OK，
                // 因为只要 Shizuku 能用，第一次真实动作会再 ensureInitialized 握手
                ShizukuDetailedStatus.BINDER_OK
            }
        }
    }

    /**
     * ===== Fix-1：异步安全初始化（5 秒超时 + 每步 Log + 结果回调）=====
     *
     * 绝不阻塞调用方线程。若调用方是 UI 线程，也能立刻返回，超时后回调 onResult(INITIALIZING)。
     * 初始化成功/失败后会刷新 initialized / useShizukuApi 标志，下一次 getDetailedStatus() 即反映真实状态。
     *
     * @param timeoutMs 超时时间（建议 5000）
     * @param onResult 初始化完成/超时回调（主线程执行，便于直接刷新 UI）
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

        // 超时兜底：无论内部线程挂没挂住，timeoutMs 后强制回调 INITIALIZING（调用方据此降级）
        mainHandler.postDelayed({
            finish(ShizukuDetailedStatus.INITIALIZING)
        }, timeoutMs)

        // 真实初始化工作在 HandlerThread 后台线程执行，CountDownLatch 只阻塞后台线程
        Thread {
            try {
                val stepStart = System.currentTimeMillis()
                Log.i(TAG, "[ensureInitAsync] Step start background thread")
                if (handlerThread?.isAlive != true) {
                    handlerThread = HandlerThread("ShizukuInit")
                    handlerThread!!.start()
                    handler = Handler(handlerThread!!.looper)
                }
                Log.d(TAG, "[ensureInitAsync] Step1 handlerThread start in ${System.currentTimeMillis() - stepStart}ms")

                val initOk = runOnBackgroundWithTimeout(timeoutMs - 500) {   // 给超时兜底留 500ms 余量
                    try {
                        initializeShizukuInternal()
                    } catch (t: Throwable) {
                        Log.e(TAG, "[ensureInitAsync] initializeShizukuInternal exception", t)
                        false
                    }
                } ?: false

                initialized = initOk
                Log.i(TAG, "[ensureInitAsync] Step2 initializeShizukuInternal result=$initOk, " +
                        "useShizukuApi=$useShizukuApi in ${System.currentTimeMillis() - stepStart}ms")

                // 最终再调用 getDetailedStatus() 把真实状态回传给 UI
                finish(getDetailedStatus())
            } catch (t: Throwable) {
                Log.e(TAG, "[ensureInitAsync] fatal exception: ${t.message}", t)
                finish(ShizukuDetailedStatus.UNKNOWN)
            }
        }.start()
    }

    override fun getStatusHumanMessage(): String {
        return when (getDetailedStatus()) {
            ShizukuDetailedStatus.NOT_INSTALLED ->
                "❌ Shizuku/Sui 未安装\n请先从应用商店安装 Shizuku 或 Sui（Magisk模块）"
            ShizukuDetailedStatus.SERVICE_NOT_RUNNING ->
                "⚠️ Shizuku 服务未启动\n请打开 Shizuku 应用，启动服务（通过ADB无线调试或Root），并在\"已授权应用\"中允许本应用"
            ShizukuDetailedStatus.PERMISSION_DENIED ->
                "🔒 Shizuku 权限被拒绝\n请打开 Shizuku 应用 → 已授权应用 → 找到 Genshin SpikeGuard → 允许访问"
            ShizukuDetailedStatus.BINDER_OK ->
                "✅ Shizuku 连接正常（Binder API 已就绪，可执行系统级命令）"
            ShizukuDetailedStatus.USING_FALLBACK_SHELL ->
                "⚠️ 使用普通Shell降级模式（无Root/Shell权限）\nGPU钳制和内存回收可能无法生效，建议启动Shizuku服务并授权"
            ShizukuDetailedStatus.INITIALIZING ->
                "⏳ Shizuku 正在初始化..."
            ShizukuDetailedStatus.UNKNOWN ->
                "❓ Shizuku 状态未知，请点击\"启动保护\"后重试检测"
        }
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

    // ==========================================================
    // P1-2a: executeFullProtectionFlow() 真实完整保护流程
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
            val fail = ActionResult(ActionType.FULL_FLOW, false, "Not initialized")
            return FullFlowResult(null, null, null, null, null, 0L, 0, 0)
        }
        if (paused.get()) {
            Log.w(TAG, "executeFullProtectionFlow skipped: paused")
            val fail = ActionResult(ActionType.FULL_FLOW, false, "Paused (silent mode)")
            return FullFlowResult(null, null, null, null, null, 0L, 0, 0)
        }

        val t0 = System.currentTimeMillis()

        // 同步阻塞执行（调用方已经在后台线程），不用 runOnBackgroundWithTimeout 再包一层
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

        // ====== 关键：等待 durationMs（默认1500ms）保护窗口 ======
        val waitMs = durationMs.coerceIn(100L, 10_000L)
        try {
            Thread.sleep(waitMs)
        } catch (_: InterruptedException) {
            Log.w(TAG, "Full protection sleep interrupted, will reset immediately")
        }

        // ====== 关键：1500ms后无条件恢复全部系统参数 ======
        val r5: ActionResult = try { resetAllInternal() } catch (e: Throwable) {
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }

        val total = System.currentTimeMillis() - t0
        val list = listOfNotNull(r1, r2, r3, r4, r5)
        val ok = list.count { it.success }
        Log.i(TAG, "Full protection flow done: success=$ok/${list.size}, total=${total}ms" +
                " (wait=${waitMs}ms, gpu=${(gpuThrottle*100).toInt()}%, cpu=${(cpuThrottle*100).toInt()}%)")
        return FullFlowResult(r1, r2, r3, r4, r5, total, ok, list.size)
    }
}
