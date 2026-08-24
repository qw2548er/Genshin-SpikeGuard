package com.spikeguard.executor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.EventType
import com.spikeguard.core.GuardEvent
import com.spikeguard.core.MessageBus
import com.spikeguard.core.PermissionMode
import com.spikeguard.core.RunMode

/**
 * 执行管理器
 *
 * 负责：
 * 1. 根据运行模式选择执行器
 * 2. 协调执行保护动作
 * 3. 处理执行结果
 * 4. 管理执行生命周期
 *
 * 所有保护动作在独立后台线程执行，不阻塞主线程
 * 支持暂停/恢复（静默期使用）
 */
class ExecutionManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    private val bus = MessageBus.getInstance()
    private var currentExecutor: ActionExecutor? = null
    private var isActive = false
    private var isPaused = false

    // 后台线程处理所有执行逻辑
    private var executorThread: HandlerThread? = null
    private var executorHandler: Handler? = null

    // 渐变恢复相关
    private var currentGpuThrottle = 1f
    private var currentCpuThrottle = 1f
    private var targetGpuThrottle = 1f
    private var targetCpuThrottle = 1f
    private var fadeOutStep = 0
    private var fadeOutSteps = 10
    private var fadeOutIntervalMs = 300L

    init {
        // 订阅保护事件
        bus.subscribe(EventType.PROTECTION_TRIGGERED) { event ->
            // 立即投递到后台线程，不阻塞当前线程
            executorHandler?.post {
                onProtectionTriggered(event)
            }
        }
        bus.subscribe(EventType.PROTECTION_RELEASED) { event ->
            executorHandler?.post {
                onProtectionReleased(event)
            }
        }
        bus.subscribe(EventType.MODE_CHANGED) { event ->
            executorHandler?.post {
                onModeChanged(event)
            }
        }
    }

    /**
     * 启动执行管理器
     */
    fun start() {
        if (isActive) return

        // 启动后台线程
        executorThread = HandlerThread("ExecManager")
        executorThread?.start()
        executorHandler = Handler(executorThread!!.looper)

        // 在后台线程初始化执行器
        executorHandler?.post {
            initializeExecutor()
            isActive = true
        }
    }

    /**
     * 停止执行管理器
     */
    fun stop() {
        if (!isActive) return

        // 在后台线程执行清理
        executorHandler?.post {
            try {
                currentExecutor?.resetAll()
                currentExecutor?.release()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error stopping executor", e)
            }
            currentExecutor = null
            isActive = false
            isPaused = false
        }

        // 稍后退出线程（给清理工作一点时间）
        try {
            executorThread?.quitSafely()
        } catch (e: Exception) {
            // 忽略
        }
        executorThread = null
        executorHandler = null
    }

    /**
     * 暂停所有执行（静默期）
     */
    fun pause() {
        isPaused = true
        android.util.Log.i(TAG, "Execution manager paused (silent mode)")

        executorHandler?.post {
            // 通知具体执行器暂停
            (currentExecutor as? ShizukuActionExecutor)?.pause()
            (currentExecutor as? RootActionExecutor)?.pause()
        }
    }

    /**
     * 恢复执行
     */
    fun resume() {
        isPaused = false
        android.util.Log.i(TAG, "Execution manager resumed")

        executorHandler?.post {
            // 通知具体执行器恢复
            (currentExecutor as? ShizukuActionExecutor)?.resume()
            (currentExecutor as? RootActionExecutor)?.resume()
        }
    }

    /**
     * 是否处于暂停状态
     */
    fun isPaused(): Boolean = isPaused

    /**
     * 初始化执行器（在后台线程调用）
     */
    private fun initializeExecutor() {
        val runMode = configManager.getRunMode()

        try {
            currentExecutor?.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error releasing old executor", e)
        }
        currentExecutor = null

        currentExecutor = when (runMode) {
            RunMode.FULL_PROTECT -> {
                // 根据权限模式选择执行器
                val permMode = configManager.getPermissionMode()
                when (permMode) {
                    PermissionMode.ROOT -> {
                        val executor = RootActionExecutor()
                        if (executor.isAvailable()) {
                            try {
                                if (executor.initialize()) {
                                    executor
                                } else {
                                    android.util.Log.w(TAG, "Root init failed, falling back to LogOnly")
                                    LogOnlyExecutor().also { it.initialize() }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Root init exception", e)
                                LogOnlyExecutor().also { it.initialize() }
                            }
                        } else {
                            android.util.Log.w(TAG, "Root not available, falling back to LogOnly")
                            LogOnlyExecutor().also { it.initialize() }
                        }
                    }
                    PermissionMode.SHIZUKU -> {
                        val executor = ShizukuActionExecutor(context)
                        if (executor.isAvailable()) {
                            try {
                                if (executor.initialize()) {
                                    executor
                                } else {
                                    android.util.Log.w(TAG, "Shizuku init failed, falling back to LogOnly")
                                    LogOnlyExecutor().also { it.initialize() }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Shizuku init exception", e)
                                LogOnlyExecutor().also { it.initialize() }
                            }
                        } else {
                            android.util.Log.w(TAG, "Shizuku not available, falling back to LogOnly")
                            LogOnlyExecutor().also { it.initialize() }
                        }
                    }
                    PermissionMode.NONE -> {
                        android.util.Log.w(TAG, "No permission mode set, using LogOnly")
                        LogOnlyExecutor().also { it.initialize() }
                    }
                }
            }
            RunMode.LOG_ONLY -> {
                // 纯日志模式：直接使用LogOnlyExecutor，完全不调用Shizuku/Root
                android.util.Log.i(TAG, "Log only mode - no Shizuku/Root calls will be made")
                LogOnlyExecutor().also { it.initialize() }
            }
        }

        android.util.Log.i(TAG, "Executor initialized: ${currentExecutor?.name}")

        // 发布模式变更
        bus.publish(
            EventType.MODE_CHANGED,
            "run_mode" to runMode.name,
            "executor" to (currentExecutor?.name ?: "none")
        )
    }

    /**
     * 保护触发处理（在后台线程调用）
     *
     * 完整保护流程：
     * 1. 回收空闲内存
     * 2. GPU钳制
     * 3. CPU钳制
     * 4. 提升进程优先级
     * 5. 帧率限制（可选）
     */
    private fun onProtectionTriggered(event: GuardEvent) {
        val executor = currentExecutor ?: return

        // 暂停状态下不执行保护
        if (isPaused) {
            android.util.Log.w(TAG, "Protection skipped: paused (silent mode)")
            return
        }

        val sceneId = event.data["scene_id"] as? String ?: "unknown"
        val sceneName = event.data["scene_name"] as? String ?: "未知场景"
        val cpuThrottle = event.data["cpu_throttle"] as? Float ?: 0.7f
        val gpuThrottle = event.data["gpu_throttle"] as? Float ?: 0.6f
        val frameLimit = event.data["frame_limit"] as? Int ?: 30
        val reclaimMemory = event.data["reclaim_memory"] as? Boolean ?: true
        val boostPriority = event.data["boost_priority"] as? Boolean ?: true
        val logOnly = event.data["log_only"] as? Boolean ?: false

        android.util.Log.i(TAG,
            "Executing protection: scene=$sceneName, " +
                    "reclaim_mem=$reclaimMemory, " +
                    "gpu=${(gpuThrottle * 100).toInt()}%, " +
                    "cpu=${(cpuThrottle * 100).toInt()}%, " +
                    "boost_priority=$boostPriority, " +
                    "log_only=$logOnly")

        // 如果是log_only模式，直接记录日志，不调用执行器
        if (logOnly || executor is LogOnlyExecutor) {
            android.util.Log.i(TAG, "[LOG ONLY] Protection would be triggered for $sceneName")
            bus.publish(
                EventType.ACTION_EXECUTED,
                "scene_id" to sceneId,
                "scene_name" to sceneName,
                "log_only" to true,
                "executor" to executor.name
            )
            return
        }

        // 步骤1: 回收内存
        val memoryResult = if (reclaimMemory) {
            safeExecute { executor.reclaimMemory() }
        } else {
            ActionResult(ActionType.RECLAIM_MEMORY, true, "Skipped")
        }

        // 步骤2: GPU钳制
        val gpuResult = safeExecute { executor.setGpuThrottle(gpuThrottle) }

        // 步骤3: CPU钳制
        val cpuResult = safeExecute { executor.setCpuThrottle(cpuThrottle) }

        // 步骤4: 提升进程优先级（针对原神）
        val priorityResult = if (boostPriority) {
            val genshinPackage = detectGenshinPackage()
            if (genshinPackage != null) {
                safeExecute { executor.boostProcessPriority(genshinPackage) }
            } else {
                ActionResult(ActionType.BOOST_PRIORITY, false, "Genshin process not found")
            }
        } else {
            ActionResult(ActionType.BOOST_PRIORITY, true, "Skipped")
        }

        // 步骤5: 帧率限制
        val frameResult = safeExecute { executor.setFrameLimit(frameLimit) }

        currentCpuThrottle = cpuThrottle
        currentGpuThrottle = gpuThrottle
        targetCpuThrottle = cpuThrottle
        targetGpuThrottle = gpuThrottle

        // 发布执行结果
        bus.publish(
            EventType.ACTION_EXECUTED,
            "scene_id" to sceneId,
            "scene_name" to sceneName,
            "memory_result" to memoryResult.success,
            "gpu_result" to gpuResult.success,
            "cpu_result" to cpuResult.success,
            "priority_result" to priorityResult.success,
            "frame_result" to frameResult.success,
            "executor" to executor.name,
            "log_only" to false
        )
    }

    /**
     * 安全执行：捕获所有异常，不向外抛出
     */
    private fun safeExecute(block: () -> ActionResult): ActionResult {
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Execution error", e)
            ActionResult(ActionType.CPU_THROTTLE, false, "Exception: ${e.message}")
        }
    }

    /**
     * 保护解除处理 - 带渐变恢复（在后台线程调用）
     */
    private fun onProtectionReleased(event: GuardEvent) {
        val executor = currentExecutor ?: return

        // 暂停状态下直接重置
        if (isPaused) {
            android.util.Log.w(TAG, "Protection release in paused mode, resetting immediately")
            safeExecute { executor.resetAll() }
            currentCpuThrottle = 1f
            currentGpuThrottle = 1f
            return
        }

        val fadeOutMs = event.data["fade_out_ms"] as? Long ?: 3000

        android.util.Log.i(TAG, "Releasing protection, fadeOut=${fadeOutMs}ms")

        targetCpuThrottle = 1f
        targetGpuThrottle = 1f

        if (fadeOutMs <= 0) {
            // 立即恢复
            safeExecute { executor.resetAll() }
            currentCpuThrottle = 1f
            currentGpuThrottle = 1f
        } else {
            // 渐变恢复
            startFadeOut(fadeOutMs)
        }
    }

    /**
     * 渐变恢复（在后台线程）
     */
    private fun startFadeOut(durationMs: Long) {
        fadeOutSteps = 10
        fadeOutIntervalMs = durationMs / fadeOutSteps
        val startCpu = currentCpuThrottle
        val startGpu = currentGpuThrottle
        fadeOutStep = 0

        val fadeRunnable = object : Runnable {
            override fun run() {
                fadeOutStep++
                val progress = fadeOutStep.toFloat() / fadeOutSteps

                currentCpuThrottle = startCpu + (targetCpuThrottle - startCpu) * progress
                currentGpuThrottle = startGpu + (targetGpuThrottle - startGpu) * progress

                // 应用中间值
                currentExecutor?.let { exec ->
                    safeExecute { exec.setCpuThrottle(currentCpuThrottle) }
                    safeExecute { exec.setGpuThrottle(currentGpuThrottle) }
                }

                if (fadeOutStep < fadeOutSteps) {
                    executorHandler?.postDelayed(this, fadeOutIntervalMs)
                } else {
                    // 完成，完全恢复
                    currentExecutor?.let { exec ->
                        safeExecute { exec.resetAll() }
                    }
                    currentCpuThrottle = 1f
                    currentGpuThrottle = 1f
                    android.util.Log.i(TAG, "Fade out complete")
                }
            }
        }

        executorHandler?.postDelayed(fadeRunnable, fadeOutIntervalMs)
    }

    /**
     * 模式变更处理（在后台线程）
     */
    private fun onModeChanged(event: GuardEvent) {
        if (isActive) {
            initializeExecutor()
        }
    }

    /**
     * 获取当前执行器名称
     */
    fun getExecutorName(): String = currentExecutor?.name ?: "none"

    /**
     * 是否活跃
     */
    fun isActive(): Boolean = isActive

    /**
     * 检测原神包名（不使用Root/Shizuku，纯ActivityManager方式）
     * 尝试多个可能的包名，返回第一个找到的
     */
    private fun detectGenshinPackage(): String? {
        val possiblePackages = listOf(
            "com.miHoYo.GenshinImpact",
            "com.miHoYo.Yuanshen",
            "com.mihoyo.genshinimpact"
        )

        for (pkg in possiblePackages) {
            try {
                // 使用 ActivityManager 获取运行中进程（不需要Root/Shizuku）
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningApps = am.runningAppProcesses
                for (processInfo in runningApps) {
                    if (processInfo.processName == pkg) {
                        return pkg
                    }
                }
            } catch (e: Exception) {
                // 忽略，继续尝试
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ExecutionManager"
    }
}
