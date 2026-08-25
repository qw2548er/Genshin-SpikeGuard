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
        // Fix-6: 订阅「重试连接」请求（主界面按钮触发 → 重新执行执行器探测）
        bus.subscribe(EventType.EXECUTOR_RECONNECT_REQUESTED) { _ ->
            executorHandler?.post {
                android.util.Log.i(TAG, "EXECUTOR_RECONNECT_REQUESTED received → reinitializeExecutor")
                reinitializeExecutor()
            }
        }
        // 订阅"测试保护"指令（来自UI的手动触发）
        bus.subscribe(EventType.TEST_PROTECTION_REQUESTED) { _ ->
            executorHandler?.post {
                val (result, errorReason) = executeTestProtectionWithReason()
                android.util.Log.i(TAG, "TEST_PROTECTION_RESULT: ok=${result.successCount}/${result.attemptedCount}, " +
                        "reason=$errorReason, executor=${currentExecutor?.name}")
                bus.publish(
                    EventType.TEST_PROTECTION_RESULT,
                    "any_success" to result.anySuccess,
                    "success_count" to result.successCount,
                    "attempted_count" to result.attemptedCount,
                    "total_ms" to result.totalMs,
                    "executor" to (currentExecutor?.name ?: "none"),
                    // Fix-5: 携带具体失败原因，UI据此给不同提示
                    "error_reason" to errorReason,
                    "detailed_status" to (currentExecutor?.getDetailedStatus()?.name ?: "NULL")
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
     * Fix-6：外部（UI「重试连接」按钮）请求重新探测执行器
     */
    fun reinitializeExecutor() {
        initializeExecutor(forceProbe = true)
    }

    /**
     * 初始化执行器（在后台线程调用）
     *
     * Fix-4：**不再看 configManager.getPermissionMode()**
     * 固定优先级探测顺序：Shizuku Binder → Root Shell → LogOnly。
     * 用户哪怕把权限模式关了也没关系，自动选当前设备能用的最强方式。
     * 每个阶段 5 秒超时，超时失败尝试下一等级。
     * 探测完成后 publish ACTUAL_EXECUTOR_CHANGED 让 UI 实时刷新。
     */
    private fun initializeExecutor(forceProbe: Boolean = false) {
        val runMode = configManager.getRunMode()

        try {
            currentExecutor?.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error releasing old executor", e)
        }
        currentExecutor = null

        // 1) 纯日志模式：快速路径
        if (runMode == RunMode.LOG_ONLY) {
            val exec = LogOnlyExecutor().also { it.initialize() }
            currentExecutor = exec
            android.util.Log.i(TAG, "Mode=LOG_ONLY, using LogOnlyExecutor")
            publishModeChanged(runMode, exec)
            publishActualExecutorChanged(exec, "RUNMODE_LOGONLY")
            return
        }

        // 2) FULL_PROTECT：按 Shizuku → Root → LogOnly 顺序探测（阻塞但总超时5+5=10s）
        //    为避免阻塞整个执行器Handler 10秒，这里用 CountDownLatch 把异步回调转同步等待，
        //    但整个方法仍在 ExecutorManager 自己的后台 HandlerThread 上执行，所以不阻塞主线程。
        android.util.Log.i(TAG, "FULL_PROTECT: start auto-probe (Shizuku → Root → LogOnly)")
        val timeoutPerStage = 5000L
        val latch = java.util.concurrent.CountDownLatch(1)
        var fallbackReason = "FALLBACK_NONE"
        var chosen: ActionExecutor? = null

        // === Stage 1: Shizuku ===
        val shizuku = ShizukuActionExecutor(context)
        val s0 = shizuku.getDetailedStatus()
        android.util.Log.i(TAG, "Probe Shizuku: pre-probe status=$s0")
        shizuku.ensureInitializedAsync(timeoutPerStage) { result ->
            android.util.Log.i(TAG, "Probe Shizuku: async handshake result=$result")
            if (result == ShizukuDetailedStatus.BINDER_OK) {
                chosen = shizuku
                fallbackReason = "SHIZUKU_BINDER_OK"
                latch.countDown()
            } else {
                // Shizuku 握手失败 → 尝试 Root
                val shizukuFailReason = when (result) {
                    ShizukuDetailedStatus.NOT_INSTALLED -> "SHIZUKU_NOT_INSTALLED"
                    ShizukuDetailedStatus.SERVICE_NOT_RUNNING -> "SHIZUKU_SERVICE_NOT_RUNNING"
                    ShizukuDetailedStatus.PERMISSION_DENIED -> "SHIZUKU_PERMISSION_DENIED"
                    ShizukuDetailedStatus.INITIALIZING -> "SHIZUKU_HANDSHAKE_TIMEOUT"
                    else -> "SHIZUKU_FALLBACK_${result.name}"
                }
                val root = RootActionExecutor()
                val r0 = root.getDetailedStatus()
                android.util.Log.i(TAG, "Probe Root: pre-probe status=$r0")
                root.ensureInitializedAsync(timeoutPerStage) { rResult ->
                    android.util.Log.i(TAG, "Probe Root: async handshake result=$rResult")
                    if (rResult == ShizukuDetailedStatus.BINDER_OK) {
                        chosen = root
                        fallbackReason = "ROOT_OK (after $shizukuFailReason)"
                        latch.countDown()
                    } else {
                        // Shizuku 和 Root 都失败 → 降级 LogOnly
                        val logOnly = LogOnlyExecutor().also { it.initialize() }
                        chosen = logOnly
                        fallbackReason = "LOGONLY (shizukuReason=$shizukuFailReason, root=$rResult)"
                        latch.countDown()
                    }
                }
            }
        }

        // 总兜底：如果两级异步回调都没触发（极端情况），11s 后强制解阻塞用 LogOnly
        val totalDeadline = System.currentTimeMillis() + timeoutPerStage * 2 + 1000
        while (latch.count > 0 && System.currentTimeMillis() < totalDeadline) {
            if (!latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // 继续循环直到超时
            }
        }
        if (latch.count > 0) {
            android.util.Log.w(TAG, "Auto-probe total timeout → 强制降级 LogOnly")
            chosen = LogOnlyExecutor().also { it.initialize() }
            fallbackReason = "PROBE_TOTAL_TIMEOUT → LOGONLY"
        }

        val exec = chosen ?: LogOnlyExecutor().also { it.initialize() }
        currentExecutor = exec
        android.util.Log.i(TAG, "Auto-probe finished: chosen=${exec.name}, reason=$fallbackReason")
        publishModeChanged(runMode, exec)
        publishActualExecutorChanged(exec, fallbackReason)
    }

    private fun publishModeChanged(runMode: RunMode, exec: ActionExecutor) {
        bus.publish(
            EventType.MODE_CHANGED,
            "run_mode" to runMode.name,
            "executor" to exec.name
        )
    }

    /**
     * Fix-4：通知所有订阅者实际执行器的最终选择（UI更新显示、日志记录等）
     */
    private fun publishActualExecutorChanged(exec: ActionExecutor, fallbackReason: String) {
        val status = try { exec.getDetailedStatus() } catch (_: Throwable) { ShizukuDetailedStatus.UNKNOWN }
        val msg = try { exec.getStatusHumanMessage() } catch (_: Throwable) { "异常" }
        android.util.Log.i(TAG, "ACTUAL_EXECUTOR_CHANGED => ${exec.name}, status=$status, reason=$fallbackReason")
        bus.publish(
            EventType.ACTUAL_EXECUTOR_CHANGED,
            "executor_name" to exec.name,
            "detailed_status" to status.name,
            "human_message" to msg,
            "fallback_reason" to fallbackReason
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
     * Fix-5：手动触发一次"测试保护"完整流程
     * 返回 Pair<FullFlowResult, String>
     * 第二个元素 error_reason 是给 UI 用的失败原因枚举：
     *   OK                      → 流程执行完且至少1步成功
     *   NO_EXECUTOR             → ExecutionManager 尚未初始化
     *   PAUSED_SILENT           → 当前处于静默期
     *   LOGONLY_NO_PERM         → 用的是 LogOnly（纯日志，没权限）
     *   SHIZUKU_NOT_READY       → Shizuku 详细状态不是 BINDER_OK
     *   ROOT_NOT_READY          → Root 详细状态不是 BINDER_OK
     *   EXECUTION_ALL_FAILED    → 4/5 步全部失败（权限可能假授权）
     *   EXECUTION_EXCEPTION     → 抛异常
     */
    private fun executeTestProtectionWithReason(): Pair<FullFlowResult, String> {
        val executor = currentExecutor
        if (executor == null) {
            return FullFlowResult(null, null, null, null, null, 0, 0, 0) to "NO_EXECUTOR_SERVICE_NOT_STARTED"
        }
        if (isPaused) {
            android.util.Log.w(TAG, "Test protection skipped: paused (silent mode)")
            return FullFlowResult(null, null, null, null, null, 0, 0, 0) to "PAUSED_SILENT"
        }

        val status = try { executor.getDetailedStatus() } catch (_: Throwable) { ShizukuDetailedStatus.UNKNOWN }
        when {
            executor is LogOnlyExecutor -> {
                // 纯日志：快速返回并标注原因
                val r = safeExecuteFlow {
                    executor.executeFullProtectionFlow(true, 0.55f, 0.7f, true,
                        detectGenshinPackage() ?: "com.miHoYo.Yuanshen", 1500L)
                }
                return r to if (r.anySuccess) "OK_LOGONLY_EXPECTED" else "LOGONLY_NO_PERMISSION"
            }
            executor is ShizukuActionExecutor && status != ShizukuDetailedStatus.BINDER_OK -> {
                return FullFlowResult(null, null, null, null, null, 0, 0, 0) to
                        "SHIZUKU_${status.name}"
            }
            executor is RootActionExecutor && status != ShizukuDetailedStatus.BINDER_OK -> {
                return FullFlowResult(null, null, null, null, null, 0, 0, 0) to
                        "ROOT_${status.name}"
            }
        }

        val targetPackage = detectGenshinPackage() ?: "com.miHoYo.Yuanshen"
        android.util.Log.i(TAG, "TEST PROTECTION starting via ${executor.name}, status=$status")

        val startAt = System.currentTimeMillis()
        return try {
            val r = executor.executeFullProtectionFlow(
                reclaimMemory = true,
                gpuThrottle = 0.55f,
                cpuThrottle = 0.7f,
                boostPriority = true,
                targetPackageName = targetPackage,
                durationMs = 1500L
            )
            android.util.Log.i(TAG, "TEST PROTECTION flow done in ${System.currentTimeMillis() - startAt}ms: " +
                    "${r.successCount}/${r.attemptedCount}")
            when {
                r.anySuccess -> r to "OK"
                r.attemptedCount == 0 -> r to "EXECUTION_NO_ATTEMPTS"
                else -> r to "EXECUTION_ALL_STEPS_FAILED"
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "TEST PROTECTION exception: ${t.message}", t)
            val fail = ActionResult(ActionType.FULL_FLOW, false, "Exception: ${t.message}")
            FullFlowResult(fail, fail, fail, fail, fail, System.currentTimeMillis() - startAt, 0, 1) to
                    "EXECUTION_EXCEPTION_${t.javaClass.simpleName}"
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
