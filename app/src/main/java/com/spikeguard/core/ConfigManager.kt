package com.spikeguard.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 运行模式
 */
enum class RunMode {
    FULL_PROTECT,   // 完整保护模式
    LOG_ONLY        // 仅日志模式（不执行实际保护动作）
}

/**
 * 权限模式
 */
enum class PermissionMode {
    ROOT,           // Root 模式
    SHIZUKU,        // Shizuku 模式
    NONE            // 无权限
}

/**
 * 配置管理器 v0.1.0
 *
 * 所有场景、阈值全部外置，后续游戏版本更新只改配置
 */
class ConfigManager(private val context: Context) {

    private var config: JSONObject = JSONObject()

    // 默认配置 - 从 assets 读取
    private val defaultConfig by lazy {
        try {
            context.assets.open("config/rules.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * 加载配置
     */
    fun loadConfig() {
        try {
            val configFile = getConfigFile()
            if (configFile.exists()) {
                val content = configFile.readText()
                config = JSONObject(content)
            } else {
                // 首次运行，从 assets 复制默认配置
                config = JSONObject(defaultConfig)
                saveConfig()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load config", e)
            config = JSONObject(defaultConfig)
        }
    }

    /**
     * 保存配置
     */
    fun saveConfig() {
        try {
            val configFile = getConfigFile()
            configFile.parentFile?.mkdirs()
            configFile.writeText(config.toString(2))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save config", e)
        }
    }

    /**
     * 从 assets 恢复默认配置
     */
    fun resetToDefault() {
        config = JSONObject(defaultConfig)
        saveConfig()
    }

    /**
     * 导入配置
     */
    fun importConfig(jsonString: String): Boolean {
        return try {
            val newConfig = JSONObject(jsonString)
            // 简单校验
            if (newConfig.has("version") && newConfig.has("scenes")) {
                config = newConfig
                saveConfig()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to import config", e)
            false
        }
    }

    /**
     * 导出配置
     */
    fun exportConfig(): String {
        return config.toString(2)
    }

    /**
     * 获取运行模式
     */
    fun getRunMode(): RunMode {
        return try {
            val mode = config.getJSONObject("general").getString("run_mode")
            RunMode.valueOf(mode.uppercase())
        } catch (e: Exception) {
            RunMode.LOG_ONLY
        }
    }

    /**
     * 设置运行模式
     */
    fun setRunMode(mode: RunMode) {
        try {
            config.getJSONObject("general").put("run_mode", mode.name.lowercase())
            saveConfig()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set run mode", e)
        }
    }

    /**
     * 获取权限模式
     */
    fun getPermissionMode(): PermissionMode {
        return try {
            val mode = config.getJSONObject("general").getString("permission_mode")
            PermissionMode.valueOf(mode.uppercase())
        } catch (e: Exception) {
            PermissionMode.NONE
        }
    }

    /**
     * 设置权限模式
     */
    fun setPermissionMode(mode: PermissionMode) {
        try {
            config.getJSONObject("general").put("permission_mode", mode.name.lowercase())
            saveConfig()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set permission mode", e)
        }
    }

    /**
     * 获取采样间隔
     */
    fun getSampleIntervalMs(): Long {
        return try {
            config.getJSONObject("general").getLong("sample_interval_ms")
        } catch (e: Exception) {
            200L
        }
    }

    /**
     * 获取保护窗口时长
     */
    fun getProtectionWindowMs(): Long {
        return try {
            config.getJSONObject("general").getLong("protection_window_ms")
        } catch (e: Exception) {
            1500L
        }
    }

    /**
     * 获取场景配置
     */
    fun getSceneConfig(sceneId: String): JSONObject? {
        return try {
            config.getJSONObject("scenes").getJSONObject(sceneId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取所有启用的场景
     */
    fun getEnabledScenes(): Map<String, JSONObject> {
        val result = mutableMapOf<String, JSONObject>()
        try {
            val scenes = config.getJSONObject("scenes")
            val keys = scenes.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val scene = scenes.getJSONObject(key)
                if (scene.optBoolean("enabled", true)) {
                    result[key] = scene
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get enabled scenes", e)
        }
        return result
    }

    /**
     * 获取场景分类配置
     */
    fun getSceneCategories(): JSONObject {
        return try {
            config.getJSONObject("scene_categories")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取战斗结算检测配置
     */
    fun getSettlementConfig(): JSONObject {
        return try {
            config.getJSONObject("battle_settlement_detection")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取 GPU 监控配置
     */
    fun getGpuMonitorConfig(): JSONObject {
        return try {
            config.getJSONObject("gpu_monitor")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取帧率监控配置
     */
    fun getFrameMonitorConfig(): JSONObject {
        return try {
            config.getJSONObject("frame_monitor")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取风险缓释配置
     */
    fun getRiskMitigationConfig(): JSONObject {
        return try {
            config.getJSONObject("risk_mitigation")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取原神启动静默时长
     */
    fun getGenshinSilenceMs(): Long {
        return try {
            config.getJSONObject("risk_mitigation").getLong("genshin_start_silence_ms")
        } catch (e: Exception) {
            10000L
        }
    }

    // ==================== 伪小脑配置 ====================

    /**
     * 伪小脑是否启用
     */
    fun isPseudoCerebellumEnabled(): Boolean {
        return try {
            config.getJSONObject("pseudo_cerebellum").getBoolean("enabled")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置伪小脑开关
     */
    fun setPseudoCerebellumEnabled(enabled: Boolean) {
        try {
            val pc = config.optJSONObject("pseudo_cerebellum") ?: org.json.JSONObject()
            pc.put("enabled", enabled)
            config.put("pseudo_cerebellum", pc)
            saveConfig()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set pseudo cerebellum enabled", e)
        }
    }

    /**
     * 获取伪小脑尖峰监测配置
     */
    fun getPseudoCerebellumSpikeConfig(): org.json.JSONObject {
        return try {
            config.getJSONObject("pseudo_cerebellum").getJSONObject("spike_monitor")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /**
     * 获取伪小脑任务调度配置
     */
    fun getPseudoCerebellumSchedulerConfig(): org.json.JSONObject {
        return try {
            config.getJSONObject("pseudo_cerebellum").getJSONObject("task_scheduler")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /**
     * 获取伪小脑资源缓冲配置
     */
    fun getPseudoCerebellumBufferConfig(): org.json.JSONObject {
        return try {
            config.getJSONObject("pseudo_cerebellum").getJSONObject("resource_buffer")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /**
     * 获取伪小脑兜底保护配置
     */
    fun getPseudoCerebellumFallbackConfig(): org.json.JSONObject {
        return try {
            config.getJSONObject("pseudo_cerebellum").getJSONObject("fallback_guard")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    // ==================== 帧调速V3配置 ====================

    /**
     * 帧调速是否启用
     */
    fun isFramePacingEnabled(): Boolean {
        return try {
            config.getJSONObject("frame_pacing").getBoolean("enabled")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置帧调速开关
     */
    fun setFramePacingEnabled(enabled: Boolean) {
        try {
            val fp = config.optJSONObject("frame_pacing") ?: org.json.JSONObject()
            fp.put("enabled", enabled)
            config.put("frame_pacing", fp)
            saveConfig()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set frame pacing enabled", e)
        }
    }

    /**
     * 低延迟模式是否启用
     */
    fun isFramePacingLowLatency(): Boolean {
        return try {
            config.getJSONObject("frame_pacing").getJSONObject("latency").getBoolean("low_latency_mode")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置低延迟模式
     */
    fun setFramePacingLowLatency(enabled: Boolean) {
        try {
            val fp = config.getJSONObject("frame_pacing")
            val latency = fp.getJSONObject("latency")
            latency.put("low_latency_mode", enabled)
            fp.put("latency", latency)
            config.put("frame_pacing", fp)
            saveConfig()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set low latency mode", e)
        }
    }

    /**
     * 获取帧调速默认延迟上限
     */
    fun getFramePacingDefaultLatency(): Float {
        return try {
            config.getJSONObject("frame_pacing").getJSONObject("latency")
                .getDouble("default_max_ms").toFloat()
        } catch (e: Exception) {
            1.5f
        }
    }

    /**
     * 获取帧调速低延迟上限
     */
    fun getFramePacingLowLatency(): Float {
        return try {
            config.getJSONObject("frame_pacing").getJSONObject("latency")
                .getDouble("low_latency_max_ms").toFloat()
        } catch (e: Exception) {
            0.5f
        }
    }

    /**
     * 获取帧调速看门狗配置
     */
    fun getFramePacingWatchdogConfig(): org.json.JSONObject {
        return try {
            config.getJSONObject("frame_pacing").getJSONObject("watchdog")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /**
     * 获取日志配置
     */
    fun getLoggingConfig(): JSONObject {
        return try {
            config.getJSONObject("logging")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * 获取原始配置 JSON
     */
    fun getRawConfig(): JSONObject = config

    /**
     * 获取配置文件路径
     */
    fun getConfigFilePath(): String = getConfigFile().absolutePath

    private fun getConfigFile(): File {
        return File(context.filesDir, "config/rules.json")
    }

    companion object {
        private const val TAG = "ConfigManager"
    }
}
