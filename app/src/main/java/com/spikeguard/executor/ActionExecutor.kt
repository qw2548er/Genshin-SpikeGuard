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
 * 执行器接口
 * 不同权限模式有不同实现
 */
interface ActionExecutor {

    /**
     * 执行器名称
     */
    val name: String

    /**
     * 是否可用
     */
    fun isAvailable(): Boolean

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
     * 恢复默认设置
     */
    fun resetAll(): ActionResult

    /**
     * 释放资源
     */
    fun release()
}
