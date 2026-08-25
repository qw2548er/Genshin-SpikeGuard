package com.spikeguard.decision

import android.util.Log
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
 * 场景分类
 */
enum class SceneCategory {
    SKIP_PROTECTION,    // 跳过强保护（仅日志）
    GENTLE_MONITOR,     // 温和监控（仅日志）
    FULL_PROTECTION     // 全套尖峰防护
}

/**
 * 场景状态
 */
data class SceneState(
    val sceneId: String,
    val name: String,
    val category: SceneCategory,
    val active: Boolean,
    val spikeCount: Int,
    val lastTriggerTime: Long,
    val protectionCount: Int = 0
)

/**
 * 战斗结算检测状态
 */
data class SettlementDetectionState(
    var peakEntityCount: Int = 0,
    var peakTime: Long = 0,
    var gpuSpikeDetected: Boolean = false,
    var detecting: Boolean = false
)

/**
 * 决策引擎 v0.1.0
 *
 * 核心更新：
 * 1. 三级场景分类：跳过 / 监控 / 全套防护
 * 2. 战斗结算前兆检测：实体骤降+GPU尖峰+帧率反弹
 * 3. 1500ms固定保护窗口，无条件恢复
 * 4. 保护流程：内存回收 → GPU钳制 → 进程优先级提升 → 恢复
 * 5. 原神启动静默期支持
 */
class DecisionEngine(
    private val scenesConfig: Map<String, JSONObject>,
    private val riskConfig: JSONObject,
    private val settlementConfig: JSONObject,
    private val sceneCategories: JSONObject
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

    // 尖峰窗口
    private val spikeWindow = ArrayDeque<Long>()
    private var consecutiveSpikes = 0

    // 战斗结算检测
    private val settlementState = SettlementDetectionState()
    private val entityHistory = ArrayDeque<Pair<Long, Int>>()
    private val fpsHistory = ArrayDeque<Pair<Long, Int>>()
    // Fix-3：基础保底场景识别所需的 GPU/CPU 历史（最近 10 秒，用于判定"大世界稳定"/"战斗中波动"）
    private val gpuLoadHistory = ArrayDeque<Pair<Long, Float>>()
    private val cpuLoadHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastReportedSceneId: String? = null
    private var lastReportedSceneName: String? = null

    // 静默期 - 原神启动时停止一切活动
    private var silentMode = false
    private var silentUntil = 0L
    private val genshinPackages = listOf(
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.Yuanshen",
        "com.mihoyo.genshinimpact"
    )

    // 统计
    private var totalGpuSpikes = 0
    private var totalProtections = 0

    init {
        // 初始化场景状态
        scenesConfig.keys.forEach { sceneId ->
            val config = scenesConfig[sceneId]
            val categoryStr = config?.optString("category", "full_protection") ?: "full_protection"
            val category = when (categoryStr) {
                "skip_protection" -> SceneCategory.SKIP_PROTECTION
                "gentle_monitor" -> SceneCategory.GENTLE_MONITOR
                else -> SceneCategory.FULL_PROTECTION
            }
            sceneStates[sceneId] = SceneState(
                sceneId = sceneId,
                name = config?.optString("name", sceneId) ?: sceneId,
                category = category,
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
        bus.subscribe(EventType.CONFIG_CHANGED) {
            Log.i(TAG, "Config changed")
        }
    }

    /**
     * 进入静默模式（原神启动时）
     */
    fun enterSilentMode(durationMs: Long) {
        silentMode = true
        silentUntil = System.currentTimeMillis() + durationMs
        Log.i(TAG, "Entering silent mode for ${durationMs}ms")

        // 如果正在保护，立即解除
        if (isProtecting) {
            forceReleaseProtection("silent_mode")
        }
    }

    /**
     * 退出静默模式
     */
    fun exitSilentMode() {
        silentMode = false
        silentUntil = 0L
        Log.i(TAG, "Exiting silent mode")
    }

    /**
     * 检查是否在静默期
     */
    private fun isSilent(): Boolean {
        if (silentMode) {
            if (System.currentTimeMillis() > silentUntil) {
                exitSilentMode()
                return false
            }
            return true
        }
        return false
    }

    /**
     * GPU尖峰事件处理
     */
    private fun onGpuSpike(event: GuardEvent) {
        if (isSilent()) return

        val gpuLoad = event.data["gpu_load"] as? Float ?: 0f
        val fps = event.data["fps"] as? Int ?: 60
        val entityEstimate = event.data["entity_estimate"] as? Int ?: 0
        val now = System.currentTimeMillis()

        totalGpuSpikes++

        // 记录尖峰时间
        spikeWindow.addLast(now)
        while (spikeWindow.isNotEmpty() && now - spikeWindow.first() > 5000) {
            spikeWindow.removeFirst()
        }

        consecutiveSpikes++

        // 标记战斗结算检测中的GPU尖峰
        if (settlementState.detecting) {
            settlementState.gpuSpikeDetected = true
        }

        // 评估保护
        evaluateProtection(gpuLoad, fps, entityEstimate)
    }

    /**
     * 实体数量激增事件处理
     */
    private fun onEntitySurge(event: GuardEvent) {
        if (isSilent()) return

        val entityEstimate = event.data["entity_estimate"] as? Int ?: 0
        val fps = event.data["fps"] as? Int ?: 60
        val gpuLoad = event.data["gpu_load"] as? Float ?: 0f

        if (entityEstimate > settlementState.peakEntityCount) {
            settlementState.peakEntityCount = entityEstimate
            settlementState.peakTime = System.currentTimeMillis()
        }

        // 实体激增是战斗开始的信号，启动结算检测
        if (entityEstimate > 30 && !settlementState.detecting) {
            settlementState.detecting = true
            settlementState.gpuSpikeDetected = false
            Log.d(TAG, "Battle started detected, entity_estimate=$entityEstimate")
        }

        if (entityEstimate > 80 && fps < 25) {
            evaluateProtection(gpuLoad, fps, entityEstimate)
        }
    }

    /**
     * 性能采样处理
     *
     * Fix-2：每次采样时独立计算风险等级（不依赖是否触发保护）
     *   GPU > 80 或 CPU > 90 或 实体 > 50 → 1项=MEDIUM, 2项=HIGH, 3项=CRITICAL
     * Fix-3：基础保底场景识别（不依赖 scenesConfig 阈值，也不依赖 evaluateProtection 是否命中）
     *   近10秒稳定低负载 + 帧率稳定 → 大世界
     *   高负载 或 连续尖峰 或 帧率大幅波动 → 战斗中
     */
    private fun onMetricsSample(event: GuardEvent) {
        if (isSilent()) return

        val fps = event.data["fps"] as? Int ?: 60
        val gpuLoad = (event.data["gpu_load"] as? Float) ?: 0f
        val cpuLoad = (event.data["cpu_load"] as? Float) ?: 0f
        val entityEstimate = event.data["entity_estimate"] as? Int ?: 0
        val now = System.currentTimeMillis()

        // ===== Fix-2：风险等级独立计算 =====
        val hits = listOf(gpuLoad > 80f, cpuLoad > 90f, entityEstimate > 50).count { it }
        val newRisk = when (hits) {
            3 -> RiskLevel.CRITICAL
            2 -> RiskLevel.HIGH
            1 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        if (newRisk != currentRiskLevel) {
            Log.d(TAG, "[Risk] re-evaluate: gpu=$gpuLoad, cpu=$cpuLoad, entity=$entityEstimate " +
                    "=> hits=$hits => ${currentRiskLevel.name} -> ${newRisk.name}")
            updateRiskLevel(newRisk)
        }

        // ===== Fix-3：基础保底场景识别 + 上报（即便没触发保护也上报）=====
        // 追加历史
        gpuLoadHistory.addLast(now to gpuLoad)
        cpuLoadHistory.addLast(now to cpuLoad)
        entityHistory.addLast(now to entityEstimate)
        fpsHistory.addLast(now to fps)
        while (gpuLoadHistory.isNotEmpty() && now - gpuLoadHistory.first().first > 10_000L) gpuLoadHistory.removeFirst()
        while (cpuLoadHistory.isNotEmpty() && now - cpuLoadHistory.first().first > 10_000L) cpuLoadHistory.removeFirst()
        while (entityHistory.isNotEmpty() && now - entityHistory.first().first > 10_000L) entityHistory.removeFirst()
        while (fpsHistory.isNotEmpty() && now - fpsHistory.first().first > 10_000L) fpsHistory.removeFirst()

        // 基础判断
        val avgGpu = if (gpuLoadHistory.size > 0) gpuLoadHistory.map { it.second }.average().toFloat() else 0f
        val avgCpu = if (cpuLoadHistory.size > 0) cpuLoadHistory.map { it.second }.average().toFloat() else 0f
        val fpsStdDev = computeStdDev(fpsHistory.map { it.second.toDouble() })
        val highLoad = avgGpu > 70f || avgCpu > 80f || consecutiveSpikes >= 2 || entityEstimate > 40
        val fpsUnstable = fpsStdDev > 6.0
        val (sceneId, sceneName) = when {
            highLoad || fpsUnstable -> "battle_combat" to "战斗中"
            avgGpu < 40f && avgCpu < 50f && fps >= 30 && fpsStdDev < 5.0 -> "open_world" to "大世界"
            else -> "idle_unknown" to "待识别"
        }
        // 有变化才下发，避免广播风暴
        if (sceneId != lastReportedSceneId || sceneName != lastReportedSceneName) {
            lastReportedSceneId = sceneId
            lastReportedSceneName = sceneName
            Log.d(TAG, "[Scene] basic detect: highLoad=$highLoad, fpsUnstable=$fpsUnstable, " +
                    "avgGpu=$avgGpu, avgCpu=$avgCpu, fpsStdDev=$fpsStdDev => $sceneName")
            bus.publish(
                EventType.SCENE_CHANGED,
                "scene_id" to sceneId,
                "scene_name" to sceneName,
                "active" to true
            )
        }

        // 帧率恢复时重置连续尖峰计数
        if (fps >= 45) {
            if (consecutiveSpikes > 0) {
                consecutiveSpikes = (consecutiveSpikes * 0.7f).toInt()
            }
        }

        // 战斗结算检测
        checkBattleSettlement(entityEstimate, fps, now)

        // 更新UI状态
        bus.publish(
            EventType.UI_STATE_UPDATE,
            "fps" to fps,
            "gpu_load" to (event.data["gpu_load"] ?: 0f),
            "cpu_load" to (event.data["cpu_load"] ?: 0f),
            "temperature" to (event.data["temperature"] ?: 0f),
            "entity_estimate" to entityEstimate,
            "risk_level" to currentRiskLevel.name,
            "is_protecting" to isProtecting,
            "protections_today" to totalProtectionsToday,
            "gpu_spikes" to totalGpuSpikes,
            "total_protections" to totalProtections,
            "silent_mode" to silentMode,
            "silent_remaining_ms" to maxOf(0, silentUntil - now)
        )
    }

    /**
     * 战斗结算前兆检测
     *
     * 特征：
     * 1. 实体数量达到峰值后骤降
     * 2. 峰值前有GPU尖峰
     * 3. 实体下降后帧率反弹
     */
    private fun checkBattleSettlement(currentEntity: Int, currentFps: Int, now: Long) {
        if (!settlementState.detecting) return

        val minEntitiesBeforeDrop = settlementConfig.optInt("min_entities_before_drop", 30)
        val entityDropRatio = settlementConfig.optDouble("entity_drop_ratio", 0.6).toFloat()
        val entityDropWindowMs = settlementConfig.optLong("entity_drop_window_ms", 500)

        // 检查实体是否骤降
        if (settlementState.peakEntityCount >= minEntitiesBeforeDrop) {
            val timeSincePeak = now - settlementState.peakTime
            if (timeSincePeak in 100..entityDropWindowMs) {
                val dropRatio = 1f - (currentEntity.toFloat() / settlementState.peakEntityCount)
                if (dropRatio >= entityDropRatio && settlementState.gpuSpikeDetected) {
                    // 检测到战斗结算前兆！
                    Log.i(TAG,
                        "Battle settlement detected! peak=${settlementState.peakEntityCount}, " +
                                "current=$currentEntity, drop=${"%.0f".format(dropRatio * 100)}%, " +
                                "time=${timeSincePeak}ms")

                    // 触发保护
                    triggerSettlementProtection(currentEntity, currentFps)

                    // 重置检测状态
                    settlementState.detecting = false
                    settlementState.peakEntityCount = 0
                    settlementState.gpuSpikeDetected = false
                }
            }

            // 超时重置
            if (timeSincePeak > entityDropWindowMs * 2) {
                settlementState.detecting = false
                settlementState.peakEntityCount = 0
                settlementState.gpuSpikeDetected = false
            }
        }
    }

    /**
     * 评估是否需要触发保护
     */
    private fun evaluateProtection(gpuLoad: Float, fps: Int, entityEstimate: Int) {
        if (isProtecting || isSilent()) return
        val now = System.currentTimeMillis()

        // 检查最小间隔
        val minInterval = riskConfig.optLong("min_interval_between_protections_ms", 1500)
        if (now - lastProtectionEndTime < minInterval) {
            return
        }

        // 检查每小时保护次数限制
        val maxPerHour = riskConfig.optInt("max_protections_per_hour", 30)
        val oneHourAgo = now - 3600_000
        while (protectionTimestamps.isNotEmpty() && protectionTimestamps.first() < oneHourAgo) {
            protectionTimestamps.removeFirst()
            protectionsLastHour--
        }

        if (protectionsLastHour >= maxPerHour) {
            Log.w(TAG, "Hourly protection limit reached: $protectionsLastHour/$maxPerHour")
            updateRiskLevel(RiskLevel.HIGH)
            return
        }

        // 识别场景
        val detectedScene = detectScene(gpuLoad, fps, entityEstimate)

        if (detectedScene != null) {
            val sceneConfig = scenesConfig[detectedScene]
            val category = getSceneCategory(detectedScene)

            when (category) {
                SceneCategory.FULL_PROTECTION -> {
                    // 全套保护
                    if (sceneConfig != null && checkSceneRiskBudget(detectedScene, sceneConfig)) {
                        triggerProtection(detectedScene, sceneConfig)
                    }
                }
                SceneCategory.GENTLE_MONITOR, SceneCategory.SKIP_PROTECTION -> {
                    // 仅日志，不执行保护
                    logOnlyProtection(detectedScene, gpuLoad, fps, entityEstimate)
                }
            }
        } else if (consecutiveSpikes >= 5 && fps < 20) {
            // 未识别场景但压力极高
            triggerGenericProtection(gpuLoad, fps)
        }
    }

    /**
     * 触发战斗结算保护
     */
    private fun triggerSettlementProtection(entityEstimate: Int, fps: Int) {
        if (isProtecting || isSilent()) return

        // 找一个最合适的full_protection场景
        val fullProtectionScenes = sceneStates.filterValues {
            it.category == SceneCategory.FULL_PROTECTION
        }

        if (fullProtectionScenes.isEmpty()) {
            triggerGenericProtection(90f, fps)
            return
        }

        // 使用匹配度最高的场景配置
        val (sceneId, state) = fullProtectionScenes.entries.first()
        val config = scenesConfig[sceneId]
        if (config != null) {
            triggerProtection(sceneId, config)
        } else {
            triggerGenericProtection(90f, fps)
        }
    }

    /**
     * 仅日志保护（不执行实际动作）
     */
    private fun logOnlyProtection(sceneId: String, gpuLoad: Float, fps: Int, entityEstimate: Int) {
        val sceneName = sceneStates[sceneId]?.name ?: sceneId
        Log.i(TAG,
            "[LOG ONLY] Would protect scene=$sceneName, " +
                    "gpu=${"%.1f".format(gpuLoad)}%, fps=$fps, entity=$entityEstimate")

        // 发布事件供UI显示，但不触发执行
        bus.publish(
            EventType.PROTECTION_TRIGGERED,
            "scene_id" to sceneId,
            "scene_name" to sceneName,
            "log_only" to true,
            "gpu_load" to gpuLoad,
            "fps" to fps
        )
    }

    /**
     * 场景识别
     */
    private fun detectScene(gpuLoad: Float, fps: Int, entityEstimate: Int): String? {
        var bestMatch: String? = null
        var bestScore = 0

        for ((sceneId, config) in scenesConfig) {
            if (!config.optBoolean("enabled", true)) continue

            val detection = config.optJSONObject("detection") ?: continue
            val entityThreshold = detection.optInt("entity_rate_threshold", 20)
            val requiredSpikes = detection.optInt("consecutive_spikes", 2)

            var score = 0
            if (entityEstimate >= entityThreshold) score += 40
            if (consecutiveSpikes >= requiredSpikes) score += 40
            if (fps < 25) score += 20
            if (gpuLoad > 80) score += 20

            if (score > bestScore && score >= 50) {
                bestScore = score
                bestMatch = sceneId
            }
        }

        return bestMatch
    }

    /**
     * 获取场景分类
     */
    private fun getSceneCategory(sceneId: String): SceneCategory {
        return sceneStates[sceneId]?.category ?: SceneCategory.FULL_PROTECTION
    }

    /**
     * 检查场景风险预算
     */
    private fun checkSceneRiskBudget(sceneId: String, config: JSONObject): Boolean {
        val state = sceneStates[sceneId] ?: return false
        val riskMitigation = config.optJSONObject("risk_mitigation") ?: return true

        val maxDaily = riskMitigation.optInt("max_daily_triggers", 100)
        val minInterval = riskMitigation.optLong("min_interval_ms", 2000)

        if (state.protectionCount >= maxDaily) {
            return false
        }

        if (System.currentTimeMillis() - state.lastTriggerTime < minInterval) {
            return false
        }

        return true
    }

    /**
     * 触发场景保护 - 1500ms固定窗口
     */
    private fun triggerProtection(sceneId: String, config: JSONObject) {
        if (isProtecting || isSilent()) return

        val protection = config.optJSONObject("protection") ?: return

        val durationMs = protection.optLong("duration_ms", 1500)
        val reclaimMemory = protection.optBoolean("reclaim_memory", true)
        val gpuThrottle = protection.optDouble("gpu_throttle", 0.5).toFloat()
        val cpuThrottle = protection.optDouble("cpu_throttle", 0.6).toFloat()
        val boostPriority = protection.optBoolean("boost_priority", true)
        val unconditionalRestore = protection.optBoolean("unconditional_restore", true)

        Log.i(TAG,
            "Triggering protection: scene=$sceneId, " +
                    "reclaim_mem=$reclaimMemory, " +
                    "gpu=${"%.0f".format(gpuThrottle * 100)}%, " +
                    "boost_priority=$boostPriority, " +
                    "duration=${durationMs}ms")

        isProtecting = true
        totalProtections++
        protectionTimestamps.addLast(System.currentTimeMillis())
        protectionsLastHour++
        totalProtectionsToday++

        // 更新场景状态
        val state = sceneStates[sceneId]
        if (state != null) {
            sceneStates[sceneId] = state.copy(
                active = true,
                lastTriggerTime = System.currentTimeMillis(),
                protectionCount = state.protectionCount + 1
            )
        }

        updateRiskLevel(RiskLevel.HIGH)

        // 发布保护触发事件
        bus.publish(
            EventType.PROTECTION_TRIGGERED,
            "scene_id" to sceneId,
            "scene_name" to config.optString("name", sceneId),
            "reclaim_memory" to reclaimMemory,
            "gpu_throttle" to gpuThrottle,
            "cpu_throttle" to cpuThrottle,
            "boost_priority" to boostPriority,
            "duration_ms" to durationMs,
            "unconditional_restore" to unconditionalRestore,
            "log_only" to false
        )

        bus.publish(
            EventType.SCENE_CHANGED,
            "scene_id" to sceneId,
            "scene_name" to config.optString("name", sceneId),
            "active" to true
        )

        // 1500ms后无条件恢复
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            releaseProtection(sceneId)
        }, durationMs)
    }

    /**
     * 触发通用保护
     */
    private fun triggerGenericProtection(gpuLoad: Float, fps: Int) {
        if (isProtecting || isSilent()) return

        Log.i(TAG, "Triggering generic protection")

        isProtecting = true
        totalProtections++
        protectionTimestamps.addLast(System.currentTimeMillis())
        protectionsLastHour++
        totalProtectionsToday++

        updateRiskLevel(RiskLevel.MEDIUM)

        bus.publish(
            EventType.PROTECTION_TRIGGERED,
            "scene_id" to "generic",
            "scene_name" to "通用保护",
            "reclaim_memory" to true,
            "gpu_throttle" to 0.6f,
            "cpu_throttle" to 0.7f,
            "boost_priority" to true,
            "duration_ms" to 1500,
            "unconditional_restore" to true,
            "log_only" to false
        )

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            releaseProtection("generic")
        }, 1500)
    }

    /**
     * 强制解除保护
     */
    private fun forceReleaseProtection(reason: String) {
        if (!isProtecting) return

        Log.i(TAG, "Force releasing protection: reason=$reason")
        isProtecting = false
        lastProtectionEndTime = System.currentTimeMillis()
        updateRiskLevel(RiskLevel.LOW)

        bus.publish(
            EventType.PROTECTION_RELEASED,
            "scene_id" to "forced",
            "reason" to reason
        )
    }

    /**
     * 解除保护 - 1500ms后无条件恢复
     */
    private fun releaseProtection(sceneId: String) {
        isProtecting = false
        lastProtectionEndTime = System.currentTimeMillis()

        Log.i(TAG, "Releasing protection for scene=$sceneId, unconditional restore")

        // 更新场景状态
        val state = sceneStates[sceneId]
        if (state != null) {
            sceneStates[sceneId] = state.copy(active = false)
        }

        updateRiskLevel(RiskLevel.LOW)

        bus.publish(
            EventType.PROTECTION_RELEASED,
            "scene_id" to sceneId,
            "unconditional_restore" to true
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
                "protections_today" to totalProtectionsToday
            )
        }
    }

    fun getCurrentRiskLevel(): RiskLevel = currentRiskLevel
    fun isProtecting(): Boolean = isProtecting
    fun getProtectionsToday(): Int = totalProtectionsToday
    fun getTotalGpuSpikes(): Int = totalGpuSpikes
    fun getTotalProtections(): Int = totalProtections
    fun isSilentMode(): Boolean = isSilent()

    /**
     * Fix-3：计算标准差，用来判断 FPS 稳定度
     */
    private fun computeStdDev(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val mean = values.average()
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return kotlin.math.sqrt(sumSq / values.size)
    }

    companion object {
        private const val TAG = "DecisionEngine"
    }
}
