package com.spikeguard.executor

import android.util.Log

/**
 * 仅日志模式执行器
 *
 * 在 LOG_ONLY 模式下使用，不执行任何实际的系统修改
 * 只记录保护动作，用于调试和评估效果
 */
class LogOnlyExecutor : ActionExecutor {

    override val name = "LogOnly"

    private var initialized = false

    override fun isAvailable(): Boolean = true

    override fun initialize(): Boolean {
        initialized = true
        Log.i(TAG, "LogOnly executor initialized - no actual changes will be made")
        return true
    }

    override fun setCpuThrottle(throttle: Float): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would set CPU throttle to ${(throttle * 100).toInt()}%")
        return ActionResult(
            ActionType.CPU_THROTTLE,
            true,
            "Log only: CPU throttle ${(throttle * 100).toInt()}%"
        )
    }

    override fun setGpuThrottle(throttle: Float): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would set GPU throttle to ${(throttle * 100).toInt()}%")
        return ActionResult(
            ActionType.GPU_THROTTLE,
            true,
            "Log only: GPU throttle ${(throttle * 100).toInt()}%"
        )
    }

    override fun setFrameLimit(fpsLimit: Int): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would set frame limit to $fpsLimit FPS")
        return ActionResult(
            ActionType.FRAME_LIMIT,
            true,
            "Log only: Frame limit $fpsLimit FPS"
        )
    }

    override fun resetAll(): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would reset all settings")
        return ActionResult(
            ActionType.CPU_THROTTLE,
            true,
            "Log only: All reset"
        )
    }

    override fun release() {
        Log.i(TAG, "LogOnly executor released")
        initialized = false
    }

    companion object {
        private const val TAG = "LogOnlyExecutor"
    }
}
