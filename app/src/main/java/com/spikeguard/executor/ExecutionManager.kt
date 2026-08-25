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
        // 订阅"测试保护"指令（来自UI的手动触发）
        bus.subscribe(EventType.TEST_PROTECTION_REQUESTED) { event ->
            executorHandler?.post {
                val result = executeTestProtection()
                bus.publish(
                    EventType.TEST_PROTECTION_RESULT,
                    "any_success" to result.anySuccess,
                    "success_count" to result.successCount,
                    "attempted_count" to result.attemptedCount,
                    "total_ms" to result.totalMs,
                    "executor" to (currentExecutor?.name ?: "none")
                )
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
     * 核心改动（P1-2c）：统一调用 executor.executeFullProtectionFlow()
     * 由执行器内部完成：内存回收→GPU钳制→CPU钳制→优先级提升→等待1500ms→无条件resetAll
     * 不再在这里分开5步调用+渐变恢复，避免"恢复延迟"和"钳制泄漏"
     */
    private fun onProtectionTriggered(event: GuardEvent) {
        val executor = currentExecutor ?: run {
            android.util.Log.w(TAG, "onProtectionTriggered: no executor available")
            return
        }

        if (isPaused) {
            android.util.Log.w(TAG, "Protection skipped: paused (silent mode)")
            return
        }

        val sceneId = event.data["scene_id"] as? String ?: "unknown"
        val sceneName = event.data["scene_name"] as? String ?: "未知场景"
        val cpuThrottle = event.data["cpu_throttle"] as? Float ?: 0.7f
        val gpuThrottle = event.data["gpu_throttle"] as? Float ?: 0.55f
        val reclaimMemory = event.data["reclaim_memory"] as? Boolean ?: true
        val boostPriority = event.data["boost_priority"] as? Boolean ?: true
        val durationMs = event.data["duration_ms"] as? Long ?: 1500L
        val logOnly = event.data["log_only"] as? Boolean ?: false
        val unconditionalRestore = event.data["unconditional_restore"] as? Boolean ?: true

        android.util.Log.i(TAG,
            "Executing FULL FLOW protection: scene=$sceneName, " +
                    "reclaim_mem=$reclaimMemory, " +
                    "gpu=${(gpuThrottle * 100).toInt()}%, " +
                    "cpu=${(cpuThrottle * 100).toInt()}%, " +
                    "boost_priority=$boostPriority, " +
                    "duration=${durationMs}ms, " +
                    "log_only=$logOnly")

        val targetPackage = detectGenshinPackage() ?: "com.miHoYo.Yuanshen"

        // log_only 或 LogOnlyExecutor 时，仍然调用 executeFullProtectionFlow（LogOnly内部不做真操作），
        // 但也可以直接快速返回
        val result = if (logOnly) {
            // 纯日志快速路径：不Sleep
            val list = listOfNotNull(
                if (reclaimMemory) executor.reclaimMemory() else null,
                executor.setGpuThrottle(gpuThrottle),
                executor.setCpuThrottle(cpuThrottle),
                if (boostPriority) executor.boostProcessPriority(targetPackage) else null,
                executor.resetAll()
            )
            val ok = list.count { it.success }
            FullFlowResult(list.getOrNull(0), list.getOrNull(1), list.getOrNull(2),
                list.getOrNull(3), list.lastOrNull(), 0L, ok, list.size)
        } else {
            safeExecuteFlow {
                executor.executeFullProtectionFlow(
                    reclaimMemory = reclaimMemory,
                    gpuThrottle = gpuThrottle,
                    cpuThrottle = cpuThrottle,
                    boostPriority = boostPriority,
                    targetPackageName = targetPackage,
                    durationMs = durationMs
                )
            }
        }

        // 钳制状态记录
        currentCpuThrottle = 1f
        currentGpuThrottle = 1f
        targetCpuThrottle = 1f
        targetGpuThrottle = 1f

        android.util.Log.i(TAG,
            "Full flow result: success=${result.successCount}/${result.attemptedCount}, total=${result.totalMs}ms")

        bus.publish(
            EventType.ACTION_EXECUTED,
            "scene_id" to sceneId,
            "scene_name" to sceneName,
            "memory_result" to (result.reclaimMemory?.success ?: true),
            "gpu_result" to (result.gpuThrottle?.success ?: true),
            "cpu_result" to (result.cpuThrottle?.success ?: true),
            "priority_result" to (result.boostPriority?.success ?: true),
            "frame_result" to false,
            "reset_result" to (result.resetAfter?.success ?: false),
            "executor" to executor.name,
            "log_only" to (logOnly || executor is LogOnlyExecutor),
            "flow_success_count" to result.successCount,
            "flow_attempted_count" to result.attemptedCount,
            "flow_total_ms" to result.totalMs
        )
    }

    /**
     * 对外公开：手动触发一次"测试保护"完整流程
     * 供UI上的"测试保护"按钮调用，使用标准/激进强度参数验证执行模块真的在干活
     */
    fun executeTestProtection(): FullFlowResult {
        val executor = currentExecutor ?: return FullFlowResult(null, null, null, null, null, 0, 0, 0)
        if (isPaused) {
            android.util.Log.w(TAG, "Test protection skipped: paused")
            return FullFlowResult(null, null, null, null, null, 0, 0, 0)
        }

        val targetPackage = detectGenshinPackage() ?: "com.miHoYo.Yuanshen"
        android.util.Log.i(TAG, "TEST PROTECTION starting via ${executor.name}")

        return safeExecuteFlow {
            executor.executeFullProtectionFlow(
                reclaimMemory = true,
                gpuThrottle = 0.55f,       // 标准保护强度
                cpuThrottle = 0.7f,
                boostPriority = true,
                targetPackageName = targetPackage,
                durationMs = 1500L
            )
        }
    }

    /**
     * 安全执行Flow（捕获所有异常）
     */
    private fun safeExecuteFlow(block: () -> FullFlowResult): FullFlowResult {
        return try {
            block()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Full flow exception", e)
            val fail = ActionResult(ActionType.FULL_FLOW, false, "Exception: ${e.message}")
            FullFlowResult(fail, fail, fail, fail, fail, 0L, 0, 1)
        }
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
     * 保护解除处理（P1-2c 核心改动）
     *
     * 关键：DecisionEngine 发布的 PROTECTION_RELEASED 事件
     * 已经在 executeFullProtectionFlow 内部完成了 1500ms 保护 + 无条件 resetAll()
     * 所以这里**直接跳过**，避免双重恢复或渐变恢复把刚reset掉的频率又拉错。
     *
     * 仅在暂停模式强制恢复时才需要手动调用 resetAll()。
     */
    private fun onProtectionReleased(event: GuardEvent) {
        val executor = currentExecutor ?: return

        // 只有在暂停模式（强制中断）才手动兜底 reset
        if (isPaused) {
            android.util.Log.w(TAG, "Protection release in paused mode, manual reset")
            safeExecute { executor.resetAll() }
        } else {
            android.util.Log.i(TAG,
                "onProtectionReleased: executor already restored inside executeFullProtectionFlow, skipping")
        }

        currentCpuThrottle = 1f
        currentGpuThrottle = 1f
        targetCpuThrottle = 1f
        targetGpuThrottle = 1f
    }

    /**
     * 获取当前执行器详细状态（用于UI的Shizuku引导提示）
     */
    fun getCurrentExecutorStatus(): Pair<ShizukuDetailedStatus, String> {
        val exec = currentExecutor
        return if (exec == null) {
            ShizukuDetailedStatus.INITIALIZING to "⏳ 执行器尚未初始化"
        } else {
            exec.getDetailedStatus() to exec.getStatusHumanMessage()
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
