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
     */
    private fun onProtectionTriggered(event: com.spikeguard.core.GuardEvent) {
        val executor = currentExecutor ?: return

        val sceneId = event.data["scene_id"] as? String ?: "unknown"
        val sceneName = event.data["scene_name"] as? String ?: "未知场景"
        val cpuThrottle = event.data["cpu_throttle"] as? Float ?: 0.7f
        val gpuThrottle = event.data["gpu_throttle"] as? Float ?: 0.6f
        val frameLimit = event.data["frame_limit"] as? Int ?: 30
        val durationMs = event.data["duration_ms"] as? Long ?: 8000

        android.util.Log.i(TAG,
            "Executing protection: scene=$sceneName, " +
                    "cpu=${(cpuThrottle * 100).toInt()}%, " +
                    "gpu=${(gpuThrottle * 100).toInt()}%, " +
                    "fps=$frameLimit, " +
                    "duration=${durationMs}ms")

        // 执行保护动作
        val cpuResult = executor.setCpuThrottle(cpuThrottle)
        val gpuResult = executor.setGpuThrottle(gpuThrottle)
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
            "cpu_result" to cpuResult.success,
            "gpu_result" to gpuResult.success,
            "frame_result" to frameResult.success,
            "executor" to executor.name
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

    companion object {
        private const val TAG = "ExecutionManager"
    }
}
