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

    override fun getDetailedStatus(): ShizukuDetailedStatus = ShizukuDetailedStatus.BINDER_OK

    override fun getStatusHumanMessage(): String = "📝 纯日志模式（不执行任何实际系统修改，仅记录保护事件）"

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

    override fun reclaimMemory(): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would reclaim memory (drop caches, compact, kill background)")
        return ActionResult(
            ActionType.RECLAIM_MEMORY,
            true,
            "Log only: Memory reclaimed"
        )
    }

    override fun boostProcessPriority(packageName: String): ActionResult {
        Log.i(TAG, "[LOG ONLY] Would boost priority for $packageName")
        return ActionResult(
            ActionType.BOOST_PRIORITY,
            true,
            "Log only: Priority boosted for $packageName"
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

    override fun executeFullProtectionFlow(
        reclaimMemory: Boolean,
        gpuThrottle: Float,
        cpuThrottle: Float,
        boostPriority: Boolean,
        targetPackageName: String,
        durationMs: Long
    ): FullFlowResult {
        val t0 = System.currentTimeMillis()
        val r1 = if (reclaimMemory) reclaimMemory() else null
        val r2 = setGpuThrottle(gpuThrottle)
        val r3 = setCpuThrottle(cpuThrottle)
        val r4 = if (boostPriority) boostProcessPriority(targetPackageName) else null
        try { Thread.sleep(durationMs.coerceAtLeast(50L)) } catch (_: Throwable) {}
        val r5 = resetAll()
        val total = System.currentTimeMillis() - t0
        val list = listOfNotNull(r1, r2, r3, r4, r5)
        val ok = list.count { it.success }
        Log.i(TAG, "[LOG ONLY] Full flow done: success=$ok/${list.size}, total=${total}ms")
        return FullFlowResult(r1, r2, r3, r4, r5, total, ok, list.size)
    }

    override fun release() {
        Log.i(TAG, "LogOnly executor released")
        initialized = false
    }

    companion object {
        private const val TAG = "LogOnlyExecutor"
    }
}
