package com.spikeguard.executor

import android.content.Context
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.EventType
import com.spikeguard.core.MessageBus
import com.spikeguard.core.RunMode

/**
 * 执行管理器
 *
 * 负责：
 * 1. 根据运行模式选择执行器
 * 2. 协调执行保护动作
 * 3. 处理执行结果
 * 4. 管理执行生命周期
 */
class ExecutionManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    private val bus = MessageBus.getInstance()
    private var currentExecutor: ActionExecutor? = null
    private var isActive = false

    // 渐变恢复相关
    private var fadeOutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentGpuThrottle = 1f
    private var currentCpuThrottle = 1f
    private var targetGpuThrottle = 1f
    private var targetCpuThrottle = 1f

    init {
        // 订阅保护事件
        bus.subscribe(EventType.PROTECTION_TRIGGERED) { event ->
            onProtectionTriggered(event)
        }
        bus.subscribe(EventType.PROTECTION_RELEASED) { event ->
            onProtectionReleased(event)
        }
        bus.subscribe(EventType.MODE_CHANGED) { event ->
            onModeChanged(event)
        }
    }

    /**
     * 启动执行管理器
     */
    fun start() {
        if (isActive) return

        // 初始化执行器
        initializeExecutor()
        isActive = true
    }

    /**
     * 停止执行管理器
     */
    fun stop() {
        if (!isActive) return

        // 重置所有设置
        currentExecutor?.resetAll()
        currentExecutor?.release()
        currentExecutor = null
        isActive = false
    }

    /**
     * 初始化执行器
     */
    private fun initializeExecutor() {
        val runMode = configManager.getRunMode()

        currentExecutor?.release()
        currentExecutor = null

        currentExecutor = when (runMode) {
            RunMode.FULL_PROTECT -> {
                // 根据权限模式选择执行器
                val permMode = configManager.getPermissionMode()
                when (permMode) {
                    com.spikeguard.core.PermissionMode.ROOT -> {
                        val executor = RootActionExecutor()
                        if (executor.isAvailable()) {
                            executor.initialize()
                            executor
                        } else {
                            android.util.Log.w(TAG, "Root not available, falling back to LogOnly")
                            LogOnlyExecutor().also { it.initialize() }
                        }
                    }
                    com.spikeguard.core.PermissionMode.SHIZUKU -> {
                        val executor = ShizukuActionExecutor(context)
                        if (executor.isAvailable()) {
                            executor.initialize()
                            executor
                        } else {
                            android.util.Log.w(TAG, "Shizuku not available, falling back to LogOnly")
                            LogOnlyExecutor().also { it.initialize() }
                        }
                    }
                    com.spikeguard.core.PermissionMode.NONE -> {
                        android.util.Log.w(TAG, "No permission mode set, using LogOnly")
                        LogOnlyExecutor().also { it.initialize() }
                    }
                }
            }
            RunMode.LOG_ONLY -> {
                LogOnlyExecutor().also { it.initialize() }
            }
        }

        android.util.Log.i(TAG, "Executor initialized: ${currentExecutor?.name}")

        bus.publish(
            EventType.MODE_CHANGED,
            "run_mode" to runMode.name,
            "executor" to (currentExecutor?.name ?: "none")
        )
    }

    /**
     * 保护触发处理
     *
     * 完整保护流程：
     * 1. 回收空闲内存
     * 2. GPU钳制
     * 3. CPU钳制
     * 4. 提升进程优先级
     * 5. 帧率限制（可选）
     */
    private fun onProtectionTriggered(event: com.spikeguard.core.GuardEvent) {
        val executor = currentExecutor ?: return

        val sceneId = event.data["scene_id"] as? String ?: "unknown"
        val sceneName = event.data["scene_name"] as? String ?: "未知场景"
        val cpuThrottle = event.data["cpu_throttle"] as? Float ?: 0.7f
        val gpuThrottle = event.data["gpu_throttle"] as? Float ?: 0.6f
        val frameLimit = event.data["frame_limit"] as? Int ?: 30
        val durationMs = event.data["duration_ms"] as? Long ?: 8000
        val reclaimMemory = event.data["reclaim_memory"] as? Boolean ?: true
        val boostPriority = event.data["boost_priority"] as? Boolean ?: true
        val logOnly = event.data["log_only"] as? Boolean ?: false

        android.util.Log.i(TAG,
            "Executing protection: scene=$sceneName, " +
                    "reclaim_mem=$reclaimMemory, " +
                    "gpu=${(gpuThrottle * 100).toInt()}%, " +
                    "cpu=${(cpuThrottle * 100).toInt()}%, " +
                    "boost_priority=$boostPriority, " +
                    "duration=${durationMs}ms, " +
                    "log_only=$logOnly")

        if (logOnly) {
            // 纯日志模式，不执行实际动作
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
            executor.reclaimMemory()
        } else {
            ActionResult(ActionType.RECLAIM_MEMORY, true, "Skipped")
        }

        // 步骤2: GPU钳制
        val gpuResult = executor.setGpuThrottle(gpuThrottle)

        // 步骤3: CPU钳制
        val cpuResult = executor.setCpuThrottle(cpuThrottle)

        // 步骤4: 提升进程优先级（针对原神）
        val priorityResult = if (boostPriority) {
            val genshinPackage = detectGenshinPackage()
            if (genshinPackage != null) {
                executor.boostProcessPriority(genshinPackage)
            } else {
                ActionResult(ActionType.BOOST_PRIORITY, false, "Genshin process not found")
            }
        } else {
            ActionResult(ActionType.BOOST_PRIORITY, true, "Skipped")
        }

        // 步骤5: 帧率限制（可选）
        val frameResult = executor.setFrameLimit(frameLimit)

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
     * 保护解除处理 - 带渐变恢复
     */
    private fun onProtectionReleased(event: com.spikeguard.core.GuardEvent) {
        val executor = currentExecutor ?: return

        val fadeOutMs = event.data["fade_out_ms"] as? Long ?: 3000
        val sceneId = event.data["scene_id"] as? String ?: "unknown"

        android.util.Log.i(TAG, "Releasing protection for scene=$sceneId, fadeOut=${fadeOutMs}ms")

        // 渐变恢复到正常状态
        targetCpuThrottle = 1f
        targetGpuThrottle = 1f

        if (fadeOutMs <= 0) {
            // 立即恢复
            executor.resetAll()
            currentCpuThrottle = 1f
            currentGpuThrottle = 1f
        } else {
            // 渐变恢复
            startFadeOut(fadeOutMs)
        }
    }

    /**
     * 渐变恢复
     */
    private fun startFadeOut(durationMs: Long) {
        val steps = 10
        val stepInterval = durationMs / steps
        val startCpu = currentCpuThrottle
        val startGpu = currentGpuThrottle
        var step = 0

        fadeOutHandler.removeCallbacksAndMessages(null)

        val fadeRunnable = object : Runnable {
            override fun run() {
                step++
                val progress = step.toFloat() / steps

                currentCpuThrottle = startCpu + (targetCpuThrottle - startCpu) * progress
                currentGpuThrottle = startGpu + (targetGpuThrottle - startGpu) * progress

                // 应用中间值
                currentExecutor?.setCpuThrottle(currentCpuThrottle)
                currentExecutor?.setGpuThrottle(currentGpuThrottle)

                if (step < steps) {
                    fadeOutHandler.postDelayed(this, stepInterval)
                } else {
                    // 完成，完全恢复
                    currentExecutor?.resetAll()
                    currentCpuThrottle = 1f
                    currentGpuThrottle = 1f
                    android.util.Log.i(TAG, "Fade out complete")
                }
            }
        }

        fadeOutHandler.postDelayed(fadeRunnable, stepInterval)
    }

    /**
     * 模式变更处理
     */
    private fun onModeChanged(event: com.spikeguard.core.GuardEvent) {
        // 重新初始化执行器
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
     * 检测原神包名
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
                val process = Runtime.getRuntime().exec("pidof $pkg")
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (output.isNotEmpty()) {
                    return pkg
                }
            } catch (e: Exception) {
                // 继续尝试下一个
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ExecutionManager"
    }
}
