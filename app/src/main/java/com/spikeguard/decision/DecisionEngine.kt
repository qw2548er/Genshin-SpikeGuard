package com.spikeguard.decision

import com.spikeguard.core.EventType
import com.spikeguard.core.GuardEvent
import com.spikeguard.core.MessageBus
import org.json.JSONObject

/**
 * 风险等级
 */
enum class RiskLevel {
    LOW,        // 低风险
    MEDIUM,     // 中风险
    HIGH,       // 高风险
    CRITICAL    // 临界风险
}

/**
 * 场景状态
 */
data class SceneState(
    val sceneId: String,
    val name: String,
    val active: Boolean,
    val spikeCount: Int,
    val lastTriggerTime: Long,
    val protectionCount: Int = 0
)

/**
 * 决策模块
 *
 * 负责：
 * 1. 场景识别 - 根据性能数据判断当前处于哪个高风险场景
 * 2. 保护决策 - 决定是否触发保护、保护强度
 * 3. 风险缓释 - 控制保护频率，降低账号风险
 *
 * 设计原则：
 * 1. 只决策，不执行
 * 2. 所有阈值来自配置
 * 3. 渐进式升级保护强度
 */
class DecisionEngine(
    private val scenesConfig: Map<String, JSONObject>,
    private val riskConfig: JSONObject
) {

    private val bus = MessageBus.getInstance()

    // 场景状态追踪
    private val sceneStates = mutableMapOf<String, SceneState>()

    // 全局保护统计
    private var totalProtectionsToday = 0
    private var protectionsLastHour = 0
    private val protectionTimestamps = ArrayDeque<Long>()

    // 当前状态
    private var currentRiskLevel = RiskLevel.LOW
    private var isProtecting = false
    private var lastProtectionEndTime = 0L
    private var warningCount = 0

    // 尖峰窗口
    private val spikeWindow = ArrayDeque<Long>()
    private var consecutiveSpikes = 0

    init {
        // 初始化场景状态
        scenesConfig.keys.forEach { sceneId ->
            sceneStates[sceneId] = SceneState(
                sceneId = sceneId,
                name = scenesConfig[sceneId]?.optString("name", sceneId) ?: sceneId,
                active = false,
                spikeCount = 0,
                lastTriggerTime = 0
            )
        }

        // 订阅采集事件
        bus.subscribe(EventType.GPU_SPIKE_DETECTED) { event ->
            onGpuSpike(event)
        }
        bus.subscribe(EventType.ENTITY_SURGE_DETECTED) { event ->
            onEntitySurge(event)
        }
        bus.subscribe(EventType.METRICS_SAMPLE) { event ->
            onMetricsSample(event)
        }
        bus.subscribe(EventType.CONFIG_CHANGED) { event ->
            onConfigChanged(event)
        }
    }

    /**
     * GPU尖峰事件处理
     */
    private fun onGpuSpike(event: GuardEvent) {
        val gpuLoad = event.data["gpu_load"] as? Float ?: 0f
        val fps = event.data["fps"] as? Int ?: 60
        val entityEstimate = event.data["entity_estimate"] as? Int ?: 0
        val now = System.currentTimeMillis()

        // 记录尖峰时间
        spikeWindow.addLast(now)
        // 清理过期尖峰（5秒窗口）
        while (spikeWindow.isNotEmpty() && now - spikeWindow.first() > 5000) {
            spikeWindow.removeFirst()
        }

        consecutiveSpikes++

        android.util.Log.d(TAG, "GPU spike detected: load=$gpuLoad%, fps=$fps, consecutive=$consecutiveSpikes")

        // 判断是否触发保护
        evaluateProtection(gpuLoad, fps, entityEstimate)
    }

    /**
     * 实体数量激增事件处理
     */
    private fun onEntitySurge(event: GuardEvent) {
        val entityEstimate = event.data["entity_estimate"] as? Int ?: 0
        val fps = event.data["fps"] as? Int ?: 60
        val gpuLoad = event.data["gpu_load"] as? Float ?: 0f

        android.util.Log.d(TAG, "Entity surge detected: estimate=$entityEstimate, fps=$fps")

        // 实体激增也是强信号
        if (entityEstimate > 80 && fps < 25) {
            evaluateProtection(gpuLoad, fps, entityEstimate)
        }
    }

    /**
     * 性能采样处理
     */
    private fun onMetricsSample(event: GuardEvent) {
        val fps = event.data["fps"] as? Int ?: 60

        // 如果帧率恢复，重置连续尖峰计数
        if (fps >= 45) {
            if (consecutiveSpikes > 0) {
                consecutiveSpikes = (consecutiveSpikes * 0.8f).toInt()
            }
        }

        // 更新UI状态
        bus.publish(
            EventType.UI_STATE_UPDATE,
            "fps" to fps,
            "gpu_load" to (event.data["gpu_load"] ?: 0f),
            "cpu_load" to (event.data["cpu_load"] ?: 0f),
            "temperature" to (event.data["temperature"] ?: 0f),
            "entity_estimate" to (event.data["entity_estimate"] ?: 0),
            "risk_level" to currentRiskLevel.name,
            "is_protecting" to isProtecting,
            "protections_today" to totalProtectionsToday
        )
    }

    /**
     * 评估是否需要触发保护
     */
    private fun evaluateProtection(gpuLoad: Float, fps: Int, entityEstimate: Int) {
        val now = System.currentTimeMillis()

        // 检查冷却期
        val minInterval = riskConfig.optLong("min_interval_between_protections_ms", 2000)
        if (now - lastProtectionEndTime < minInterval && isProtecting) {
            return
        }

        // 检查每小时保护次数限制
        val maxPerHour = riskConfig.optInt("max_protections_per_hour", 20)
        val oneHourAgo = now - 3600_000
        while (protectionTimestamps.isNotEmpty() && protectionTimestamps.first() < oneHourAgo) {
            protectionTimestamps.removeFirst()
            protectionsLastHour--
        }

        if (protectionsLastHour >= maxPerHour) {
            android.util.Log.w(TAG, "Hourly protection limit reached: $protectionsLastHour/$maxPerHour")
            updateRiskLevel(RiskLevel.HIGH)
            return
        }

        // 识别场景
        val detectedScene = detectScene(gpuLoad, fps, entityEstimate)

        if (detectedScene != null) {
            val sceneConfig = scenesConfig[detectedScene]
            if (sceneConfig != null) {
                // 场景级风险缓释检查
                if (!checkSceneRiskBudget(detectedScene, sceneConfig)) {
                    android.util.Log.w(TAG, "Scene risk budget exhausted: $detectedScene")
                    return
                }

                // 触发保护
                triggerProtection(detectedScene, sceneConfig, gpuLoad, fps)
            }
        } else if (consecutiveSpikes >= 5 && fps < 20) {
            // 未识别场景但压力极高，使用通用保护
            triggerGenericProtection(gpuLoad, fps)
        }
    }

    /**
     * 场景识别
     * 根据性能特征匹配预设场景
     */
    private fun detectScene(gpuLoad: Float, fps: Int, entityEstimate: Int): String? {
        var bestMatch: String? = null
        var bestScore = 0

        for ((sceneId, config) in scenesConfig) {
            val detection = config.optJSONObject("detection") ?: continue
            val entityThreshold = detection.optInt("entity_rate_threshold", 20)
            val spikeWindowMs = detection.optInt("spike_window_ms", 3000)
            val requiredSpikes = detection.optInt("consecutive_spikes", 3)

            // 评分
            var score = 0
            if (entityEstimate >= entityThreshold) score += 40
            if (consecutiveSpikes >= requiredSpikes) score += 40
            if (fps < 25) score += 20
            if (gpuLoad > 80) score += 20

            if (score > bestScore && score >= 60) {
                bestScore = score
                bestMatch = sceneId
            }
        }

        return bestMatch
    }

    /**
     * 检查场景风险预算
     */
    private fun checkSceneRiskBudget(sceneId: String, config: JSONObject): Boolean {
        val state = sceneStates[sceneId] ?: return false
        val riskMitigation = config.optJSONObject("risk_mitigation") ?: return true

        val maxDaily = riskMitigation.optInt("max_daily_triggers", 50)
        val cooldownAfterMax = riskMitigation.optLong("cooldown_after_max", 3600_000)

        if (state.protectionCount >= maxDaily) {
            // 检查是否过了冷却期
            if (System.currentTimeMillis() - state.lastTriggerTime < cooldownAfterMax) {
                return false
            }
            // 冷却期过了，重置计数
            sceneStates[sceneId] = state.copy(protectionCount = 0)
        }

        return true
    }

    /**
     * 触发场景保护
     */
    private fun triggerProtection(sceneId: String, config: JSONObject, gpuLoad: Float, fps: Int) {
        if (isProtecting) return

        val protection = config.optJSONObject("protection") ?: return
        val riskMitigation = config.optJSONObject("risk_mitigation")

        val gradual = riskMitigation?.optBoolean("gradual_escalation", true) ?: true

        // 渐进式升级
        val sceneState = sceneStates[sceneId]
        val escalationFactor = if (gradual && sceneState != null) {
            min(1f, 0.5f + sceneState.protectionCount * 0.1f)
        } else {
            1f
        }

        val cpuThrottle = protection.optDouble("cpu_throttle", 0.7).toFloat() * escalationFactor
        val gpuThrottle = protection.optDouble("gpu_throttle", 0.6).toFloat() * escalationFactor
        val frameLimit = protection.optInt("frame_limit", 30)
        val durationMs = protection.optLong("duration_ms", 8000)
        val fadeOutMs = protection.optLong("fade_out_ms", 3000)

        android.util.Log.i(TAG,
            "Triggering protection for scene=$sceneId, " +
                    "gpu_throttle=${"%.0f".format(gpuThrottle * 100)}%, " +
                    "fps_limit=$frameLimit, " +
                    "duration=${durationMs}ms")

        isProtecting = true
        protectionTimestamps.addLast(System.currentTimeMillis())
        protectionsLastHour++
        totalProtectionsToday++

        // 更新场景状态
        sceneStates[sceneId] = sceneStates[sceneId]?.copy(
            active = true,
            lastTriggerTime = System.currentTimeMillis(),
            protectionCount = sceneStates[sceneId]!!.protectionCount + 1
        ) ?: SceneState(
            sceneId = sceneId,
            name = config.optString("name", sceneId),
            active = true,
            spikeCount = 1,
            lastTriggerTime = System.currentTimeMillis(),
            protectionCount = 1
        )

        // 更新风险等级
        updateRiskLevel(RiskLevel.HIGH)

        // 发布保护触发事件
        bus.publish(
            EventType.PROTECTION_TRIGGERED,
            "scene_id" to sceneId,
            "scene_name" to config.optString("name", sceneId),
            "cpu_throttle" to cpuThrottle,
            "gpu_throttle" to gpuThrottle,
            "frame_limit" to frameLimit,
            "duration_ms" to durationMs,
            "fade_out_ms" to fadeOutMs,
            "escalation_factor" to escalationFactor
        )

        // 发布场景变化事件
        bus.publish(
            EventType.SCENE_CHANGED,
            "scene_id" to sceneId,
            "scene_name" to config.optString("name", sceneId),
            "active" to true
        )

        // 定时解除保护
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            releaseProtection(sceneId, fadeOutMs)
        }, durationMs)
    }

    /**
     * 触发通用保护
     */
    private fun triggerGenericProtection(gpuLoad: Float, fps: Int) {
        if (isProtecting) return

        android.util.Log.i(TAG, "Triggering generic protection")

        isProtecting = true
        protectionTimestamps.addLast(System.currentTimeMillis())
        protectionsLastHour++
        totalProtectionsToday++

        updateRiskLevel(RiskLevel.MEDIUM)

        bus.publish(
            EventType.PROTECTION_TRIGGERED,
            "scene_id" to "generic",
            "scene_name" to "通用保护",
            "cpu_throttle" to 0.8f,
            "gpu_throttle" to 0.7f,
            "frame_limit" to 45,
            "duration_ms" to 5000,
            "fade_out_ms" to 2000,
            "escalation_factor" to 1f
        )

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            releaseProtection("generic", 2000)
        }, 5000)
    }

    /**
     * 解除保护
     */
    private fun releaseProtection(sceneId: String, fadeOutMs: Long) {
        isProtecting = false
        lastProtectionEndTime = System.currentTimeMillis()

        android.util.Log.i(TAG, "Releasing protection for scene=$sceneId")

        // 更新场景状态
        sceneStates[sceneId] = sceneStates[sceneId]?.copy(active = false)

        updateRiskLevel(RiskLevel.LOW)

        bus.publish(
            EventType.PROTECTION_RELEASED,
            "scene_id" to sceneId,
            "fade_out_ms" to fadeOutMs
        )

        bus.publish(
            EventType.SCENE_CHANGED,
            "scene_id" to sceneId,
            "active" to false
        )
    }

    /**
     * 更新风险等级
     */
    private fun updateRiskLevel(level: RiskLevel) {
        if (currentRiskLevel != level) {
            currentRiskLevel = level
            bus.publish(
                EventType.RISK_LEVEL_CHANGED,
                "risk_level" to level.name,
                "protections_hour" to protectionsLastHour,
                "protections_today" to totalProtectionsToday,
                "warning_count" to warningCount
            )
        }
    }

    /**
     * 配置变更处理
     */
    private fun onConfigChanged(event: GuardEvent) {
        // 重新加载配置
        android.util.Log.i(TAG, "Config changed, reloading decision engine")
    }

    /**
     * 获取当前风险等级
     */
    fun getCurrentRiskLevel(): RiskLevel = currentRiskLevel

    /**
     * 获取是否正在保护
     */
    fun isProtecting(): Boolean = isProtecting

    /**
     * 获取今日保护次数
     */
    fun getProtectionsToday(): Int = totalProtectionsToday

    companion object {
        private const val TAG = "DecisionEngine"
    }
}
