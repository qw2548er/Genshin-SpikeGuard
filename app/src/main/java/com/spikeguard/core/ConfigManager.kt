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
 * 配置管理器
 * 所有场景、阈值全部外置，后续游戏版本更新只改配置
 */
class ConfigManager(private val context: Context) {

    private var config: JSONObject = JSONObject()

    // 默认配置
    private val defaultConfig = """
        {
          "version": "1.0.0",
          "general": {
            "run_mode": "log_only",
            "permission_mode": "none",
            "sample_interval_ms": 500,
            "heartbeat_interval_ms": 3000,
            "protection_cooldown_ms": 5000
          },
          "scenes": {
            "gun_limit_challenge": {
              "name": "枪限挑战",
              "description": "大量敌人同时生成与销毁的高压力场景",
              "enabled": true,
              "detection": {
                "entity_rate_threshold": 20,
                "spike_window_ms": 3000,
                "consecutive_spikes": 3
              },
              "protection": {
                "cpu_throttle": 0.7,
                "gpu_throttle": 0.6,
                "frame_limit": 30,
                "duration_ms": 8000,
                "fade_out_ms": 3000
              },
              "risk_mitigation": {
                "max_daily_triggers": 50,
                "cooldown_after_max": 3600000,
                "gradual_escalation": true
              }
            },
            "thousand_star_domain": {
              "name": "千星奇域",
              "description": "大量特效和实体的高渲染压力场景",
              "enabled": true,
              "detection": {
                "entity_rate_threshold": 15,
                "spike_window_ms": 2000,
                "consecutive_spikes": 2
              },
              "protection": {
                "cpu_throttle": 0.75,
                "gpu_throttle": 0.65,
                "frame_limit": 45,
                "duration_ms": 6000,
                "fade_out_ms": 2000
              },
              "risk_mitigation": {
                "max_daily_triggers": 80,
                "cooldown_after_max": 1800000,
                "gradual_escalation": true
              }
            },
            "new_nation_dungeon": {
              "name": "新国家副本",
              "description": "新区域副本中大规模战斗场景",
              "enabled": true,
              "detection": {
                "entity_rate_threshold": 25,
                "spike_window_ms": 4000,
                "consecutive_spikes": 4
              },
              "protection": {
                "cpu_throttle": 0.65,
                "gpu_throttle": 0.55,
                "frame_limit": 30,
                "duration_ms": 10000,
                "fade_out_ms": 4000
              },
              "risk_mitigation": {
                "max_daily_triggers": 30,
                "cooldown_after_max": 7200000,
                "gradual_escalation": true
              }
            }
          },
          "gpu_monitor": {
            "spike_threshold_percent": 85,
            "baseline_window_ms": 10000,
            "spike_cooldown_ms": 2000
          },
          "frame_monitor": {
            "drop_threshold_percent": 40,
            "min_fps": 15,
            "drop_window_ms": 1000
          },
          "risk_mitigation": {
            "account_safety_level": "normal",
            "max_protections_per_hour": 20,
            "min_interval_between_protections_ms": 2000,
            "warning_threshold_count": 10,
            "auto_slow_down_after_warnings": true
          },
          "logging": {
            "level": "INFO",
            "max_log_size_mb": 50,
            "log_to_file": true,
            "include_metrics": true
          }
        }
    """.trimIndent()

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
                // 首次运行，使用默认配置
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
            500L
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
                if (scene.getBoolean("enabled")) {
                    result[key] = scene
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get enabled scenes", e)
        }
        return result
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
     * 获取原始配置 JSON
     */
    fun getRawConfig(): JSONObject = config

    private fun getConfigFile(): File {
        return File(context.filesDir, "config/rules.json")
    }

    companion object {
        private const val TAG = "ConfigManager"
    }
}
