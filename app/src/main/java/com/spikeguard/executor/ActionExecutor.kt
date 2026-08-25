package com.spikeguard.executor

/**
 * 执行动作类型
 */
enum class ActionType {
    CPU_THROTTLE,       // CPU 降频
    GPU_THROTTLE,       // GPU 降频
    FRAME_LIMIT,        // 帧率限制
    THERMAL_LIMIT,      // 温控调节
    CLEAR_CACHE,        // 清理缓存
    KILL_BACKGROUND,    // 杀后台进程
    RECLAIM_MEMORY,     // 回收空闲内存
    BOOST_PRIORITY,     // 提升进程优先级
    FULL_FLOW,          // 完整保护流程（4步+1500ms恢复）
}

/**
 * Shizuku 详细连接状态（给UI用，避免"未启动"这种模糊提示）
 */
enum class ShizukuDetailedStatus {
    NOT_INSTALLED,      // Shizuku/Sui 根本没装
    SERVICE_NOT_RUNNING,// 装了但 Shizuku 服务未启动（Shizuku图标没点亮）
    PERMISSION_DENIED,  // 服务在，但没授权本App
    BINDER_OK,          // Shizuku Binder 正常 + 授权通过 + shell/root权限
    USING_FALLBACK_SHELL, // Binder不可用，但普通shell fallback可用（降级提示）
    INITIALIZING,       // 后台初始化中
    UNKNOWN
}

/**
 * 执行结果
 */
data class ActionResult(
    val action: ActionType,
    val success: Boolean,
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 完整保护流程结果：4步各自的结果 + 1500ms后reset结果
 */
data class FullFlowResult(
    val reclaimMemory: ActionResult?,
    val gpuThrottle: ActionResult?,
    val cpuThrottle: ActionResult?,
    val boostPriority: ActionResult?,
    val resetAfter: ActionResult?,
    val totalMs: Long,
    val successCount: Int,
    val attemptedCount: Int
) {
    val anySuccess: Boolean get() = successCount > 0
}

/**
 * 执行器接口
 * 不同权限模式有不同实现
 */
interface ActionExecutor {

    /**
     * 执行器名称
     */
    val name: String

    /**
     * 是否可用（快速轻量检查）
     */
    fun isAvailable(): Boolean

    /**
     * 详细状态（给UI显示提示和引导按钮用）
     */
    fun getDetailedStatus(): ShizukuDetailedStatus = ShizukuDetailedStatus.UNKNOWN

    /**
     * 详细的用户可读错误说明（含引导建议）
     */
    fun getStatusHumanMessage(): String = ""

    /**
     * 初始化
     */
    fun initialize(): Boolean

    /**
     * 执行 CPU 降频
     * @param throttle 0-1，1表示最高性能，0.5表示降频50%
     */
    fun setCpuThrottle(throttle: Float): ActionResult

    /**
     * 执行 GPU 降频
     */
    fun setGpuThrottle(throttle: Float): ActionResult

    /**
     * 设置帧率限制
     */
    fun setFrameLimit(fpsLimit: Int): ActionResult

    /**
     * 回收空闲内存
     * 清理后台进程缓存，释放内存给前台应用
     */
    fun reclaimMemory(): ActionResult

    /**
     * 提升目标进程优先级
     * @param packageName 目标包名
     */
    fun boostProcessPriority(packageName: String): ActionResult

    /**
     * 恢复默认设置
     */
    fun resetAll(): ActionResult

    /**
     * === 需求P1-2：真实完整保护流程 ===
     *  1) 触发系统内存回收（drop_caches / compact_memory / am kill-all）
     *  2) 短时钳制 GPU 峰值频率（钳制1500ms内的最高频率）
     *  3) 提升原神进程优先级（oom_score_adj + renice -10）
     *  4) 等待 [durationMs]，默认1500ms，到点后**无条件**调用 resetAll() 恢复
     *
     * 同步阻塞返回（由调用方在后台线程调用）
     */
    fun executeFullProtectionFlow(
        reclaimMemory: Boolean = true,
        gpuThrottle: Float = 0.55f,
        cpuThrottle: Float = 0.7f,
        boostPriority: Boolean = true,
        targetPackageName: String = "com.miHoYo.Yuanshen",
        durationMs: Long = 1500L
    ): FullFlowResult

    /**
     * 释放资源
     */
    fun release()
}
