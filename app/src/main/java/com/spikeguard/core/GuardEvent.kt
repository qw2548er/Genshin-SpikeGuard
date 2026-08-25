package com.spikeguard.core

/**
 * 事件类型定义
 * 解势化架构中各模块通过事件通信，不直接依赖
 */
enum class EventType {
    // 采集模块事件
    METRICS_SAMPLE,           // 性能采样数据
    GPU_SPIKE_DETECTED,       // GPU尖峰检测
    ENTITY_SURGE_DETECTED,    // 实体数量激增检测

    // 决策模块事件
    SCENE_CHANGED,            // 场景变化
    PROTECTION_TRIGGERED,     // 触发保护
    PROTECTION_RELEASED,      // 解除保护
    RISK_LEVEL_CHANGED,       // 风险等级变化

    // 执行模块事件
    ACTION_EXECUTED,          // 动作已执行
    ACTION_FAILED,            // 动作执行失败
    MODE_CHANGED,             // 运行模式变化
    TEST_PROTECTION_REQUESTED, // UI请求一次"测试保护"
    TEST_PROTECTION_RESULT,    // "测试保护"执行结果

    // 服务状态事件
    SERVICE_STARTED,          // 服务启动
    SERVICE_STOPPED,          // 服务停止
    HEARTBEAT,                // 心跳
    SILENT_MODE_CHANGED,      // 静默模式变化

    // UI事件
    UI_STATE_UPDATE,          // UI状态更新
    CONFIG_CHANGED,           // 配置变更
}

/**
 * 事件基类
 */
data class GuardEvent(
    val type: EventType,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(type: EventType, vararg pairs: Pair<String, Any>): GuardEvent {
            return GuardEvent(type, mapOf(*pairs))
        }
    }
}
