package com.spikeguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.spikeguard.R
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.EventType
import com.spikeguard.core.MessageBus
import com.spikeguard.core.PermissionMode
import com.spikeguard.core.RunMode
import com.spikeguard.service.GuardService

/**
 * 主界面 Activity
 *
 * 功能：
 * 1. 显示实时性能数据
 * 2. 显示当前保护状态
 * 3. 切换运行模式（完整保护 / 仅日志）
 * 4. 切换权限模式（Root / Shizuku）
 * 5. 启动/停止守护服务
 * 6. 显示风险等级和保护统计
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private val bus = MessageBus.getInstance()

    // UI 组件
    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvGpuLoad: TextView
    private lateinit var tvCpuLoad: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvEntityEstimate: TextView
    private lateinit var tvRiskLevel: TextView
    private lateinit var tvProtections: TextView
    private lateinit var tvMode: TextView

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var switchMode: Switch
    private lateinit var switchPermission: Switch
    private lateinit var switchAutoStart: Switch

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化配置
        configManager = ConfigManager(this)
        configManager.loadConfig()

        // 初始化 UI
        initViews()

        // 加载设置
        loadSettings()

        // 订阅 UI 更新事件
        bus.subscribe(EventType.UI_STATE_UPDATE) { event ->
            runOnUiThread {
                updateUiState(event.data)
            }
        }

        bus.subscribe(EventType.RISK_LEVEL_CHANGED) { event ->
            runOnUiThread {
                val level = event.data["risk_level"] as? String ?: "LOW"
                tvRiskLevel.text = "风险等级: $level"
            }
        }

        bus.subscribe(EventType.PROTECTION_TRIGGERED) { event ->
            runOnUiThread {
                val sceneName = event.data["scene_name"] as? String ?: "未知"
                tvStatus.text = "保护中 - $sceneName"
                tvStatus.setTextColor(getColor(R.color.protecting))
            }
        }

        bus.subscribe(EventType.PROTECTION_RELEASED) { _ ->
            runOnUiThread {
                tvStatus.text = "监控中"
                tvStatus.setTextColor(getColor(R.color.monitoring))
            }
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvFps = findViewById(R.id.tvFps)
        tvGpuLoad = findViewById(R.id.tvGpuLoad)
        tvCpuLoad = findViewById(R.id.tvCpuLoad)
        tvTemperature = findViewById(R.id.tvTemperature)
        tvEntityEstimate = findViewById(R.id.tvEntityEstimate)
        tvRiskLevel = findViewById(R.id.tvRiskLevel)
        tvProtections = findViewById(R.id.tvProtections)
        tvMode = findViewById(R.id.tvMode)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        switchMode = findViewById(R.id.switchMode)
        switchPermission = findViewById(R.id.switchPermission)
        switchAutoStart = findViewById(R.id.switchAutoStart)

        btnStart.setOnClickListener {
            startGuardService()
        }

        btnStop.setOnClickListener {
            stopGuardService()
        }

        switchMode.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) RunMode.FULL_PROTECT else RunMode.LOG_ONLY
            configManager.setRunMode(mode)
            updateModeDisplay()

            // 如果服务正在运行，发送重启指令
            if (serviceRunning) {
                val intent = Intent(this, GuardService::class.java).apply {
                    action = GuardService.ACTION_RESTART
                }
                startService(intent)
            }
        }

        switchPermission.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) PermissionMode.ROOT else PermissionMode.SHIZUKU
            configManager.setPermissionMode(mode)
            updatePermissionDisplay()

            if (serviceRunning) {
                val intent = Intent(this, GuardService::class.java).apply {
                    action = GuardService.ACTION_RESTART
                }
                startService(intent)
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("spikeguard_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("auto_start", isChecked).apply()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("spikeguard_prefs", MODE_PRIVATE)
        switchAutoStart.isChecked = prefs.getBoolean("auto_start", false)

        // 加载运行模式
        val runMode = configManager.getRunMode()
        switchMode.isChecked = runMode == RunMode.FULL_PROTECT

        // 加载权限模式
        val permMode = configManager.getPermissionMode()
        switchPermission.isChecked = permMode == PermissionMode.ROOT

        updateModeDisplay()
        updatePermissionDisplay()
    }

    private fun updateModeDisplay() {
        val mode = configManager.getRunMode()
        tvMode.text = "运行模式: ${if (mode == RunMode.FULL_PROTECT) "完整保护" else "仅日志"}"
    }

    private fun updatePermissionDisplay() {
        val mode = configManager.getPermissionMode()
        switchPermission.text = when (mode) {
            PermissionMode.ROOT -> "Root 模式"
            PermissionMode.SHIZUKU -> "Shizuku 模式"
            PermissionMode.NONE -> "无权限"
        }
    }

    /**
     * 启动守护服务
     */
    private fun startGuardService() {
        try {
            val intent = Intent(this, GuardService::class.java).apply {
                action = GuardService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            tvStatus.text = "启动中..."
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
        }
    }

    /**
     * 停止守护服务
     */
    private fun stopGuardService() {
        try {
            val intent = Intent(this, GuardService::class.java).apply {
                action = GuardService.ACTION_STOP
            }
            startService(intent)
            serviceRunning = false
            tvStatus.text = "已停止"
            tvStatus.setTextColor(getColor(R.color.stopped))
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        } catch (e: Exception) {
            tvStatus.text = "停止失败: ${e.message}"
        }
    }

    /**
     * 更新 UI 状态
     */
    private fun updateUiState(data: Map<String, Any>) {
        val fps = data["fps"] as? Int ?: 0
        val gpuLoad = data["gpu_load"] as? Float ?: 0f
        val cpuLoad = data["cpu_load"] as? Float ?: 0f
        val temperature = data["temperature"] as? Float ?: 0f
        val entityEstimate = data["entity_estimate"] as? Int ?: 0
        val isProtecting = data["is_protecting"] as? Boolean ?: false
        val protectionsToday = data["protections_today"] as? Int ?: 0

        tvFps.text = "$fps FPS"
        tvGpuLoad.text = "GPU: ${"%.1f".format(gpuLoad)}%"
        tvCpuLoad.text = "CPU: ${"%.1f".format(cpuLoad)}%"
        tvTemperature.text = "温度: ${"%.1f".format(temperature)}°C"
        tvEntityEstimate.text = "估算实体: ~$entityEstimate"
        tvProtections.text = "今日保护: $protectionsToday 次"

        // 帧率颜色
        tvFps.setTextColor(
            when {
                fps >= 50 -> getColor(R.color.good)
                fps >= 30 -> getColor(R.color.warning)
                else -> getColor(R.color.danger)
            }
        )

        // GPU 负载颜色
        tvGpuLoad.setTextColor(
            when {
                gpuLoad < 60 -> getColor(R.color.good)
                gpuLoad < 85 -> getColor(R.color.warning)
                else -> getColor(R.color.danger)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // 启动消息总线（如果未启动）
        bus.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不停止总线，因为服务可能还在运行
    }
}
