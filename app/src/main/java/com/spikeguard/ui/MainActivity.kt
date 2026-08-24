package com.spikeguard.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.spikeguard.R
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.PermissionMode
import com.spikeguard.core.RunMode
import com.spikeguard.core.UiStateBridge
import com.spikeguard.service.GuardService
import com.spikeguard.util.LogManager
import com.spikeguard.util.PermissionStatusChecker

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
    private lateinit var tvPermissionStatus: TextView

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var switchMode: Switch
    private lateinit var switchPermission: Switch
    private lateinit var switchAutoStart: Switch
    private lateinit var switchFloatingWindow: Switch
    private lateinit var btnExportConfig: Button
    private lateinit var btnImportConfig: Button
    private lateinit var btnExportLog: Button
    private lateinit var btnClearLog: Button

    private var serviceRunning = false
    private lateinit var permissionChecker: PermissionStatusChecker

    // 跨进程广播接收器
    private lateinit var uiStateReceiver: BroadcastReceiver

    companion object {
        private const val REQUEST_CODE_IMPORT_CONFIG = 1001
        private const val REQUEST_CODE_OVERLAY = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化配置
        configManager = ConfigManager(this)
        configManager.loadConfig()

        // 初始化权限检测器
        permissionChecker = PermissionStatusChecker(this)

        // 初始化 UI
        initViews()

        // 加载设置
        loadSettings()

        // 检测权限状态（异步，不阻塞主线程）
        checkPermissionStatus()

        // 注册跨进程广播接收器（接收来自:guard服务进程的UI状态更新）
        registerUiStateReceiver()
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
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        switchMode = findViewById(R.id.switchMode)
        switchPermission = findViewById(R.id.switchPermission)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchFloatingWindow = findViewById(R.id.switchFloatingWindow)
        btnExportConfig = findViewById(R.id.btnExportConfig)
        btnImportConfig = findViewById(R.id.btnImportConfig)
        btnExportLog = findViewById(R.id.btnExportLog)
        btnClearLog = findViewById(R.id.btnClearLog)

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
            checkPermissionStatus()

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

        switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndShowFloatingWindow()
            } else {
                stopFloatingWindow()
            }
        }

        btnExportConfig.setOnClickListener {
            exportConfig()
        }

        btnImportConfig.setOnClickListener {
            importConfig()
        }

        btnExportLog.setOnClickListener {
            exportLog()
        }

        btnClearLog.setOnClickListener {
            clearLogs()
        }
    }

    /**
     * 注册跨进程UI状态广播接收器
     * 接收来自:guard服务进程的实时数据更新
     */
    private fun registerUiStateReceiver() {
        uiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    UiStateBridge.ACTION_UI_STATE_UPDATE -> {
                        val extras = intent.extras ?: return
                        updateUiStateFromBundle(extras)
                    }
                    UiStateBridge.ACTION_PROTECTION_EVENT -> {
                        val eventType = intent.getStringExtra("event_type")
                        val sceneName = intent.getStringExtra("scene_name") ?: "未知"
                        when (eventType) {
                            "triggered" -> {
                                tvStatus.text = "保护中 - $sceneName"
                                tvStatus.setTextColor(getColor(R.color.protecting))
                            }
                            "released" -> {
                                tvStatus.text = "监控中"
                                tvStatus.setTextColor(getColor(R.color.monitoring))
                            }
                        }
                    }
                    UiStateBridge.ACTION_SERVICE_EVENT -> {
                        val eventType = intent.getStringExtra("event_type")
                        when (eventType) {
                            "started" -> {
                                serviceRunning = true
                                tvStatus.text = "监控中"
                                tvStatus.setTextColor(getColor(R.color.monitoring))
                                btnStart.isEnabled = false
                                btnStop.isEnabled = true
                            }
                            "stopped" -> {
                                serviceRunning = false
                                tvStatus.text = "已停止"
                                tvStatus.setTextColor(getColor(R.color.stopped))
                                btnStart.isEnabled = true
                                btnStop.isEnabled = false
                            }
                        }
                    }
                    UiStateBridge.ACTION_SILENT_MODE_CHANGED -> {
                        val active = intent.getBooleanExtra("active", false)
                        if (active) {
                            tvStatus.text = "静默中 - 原神启动保护"
                            tvStatus.setTextColor(getColor(R.color.warning))
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UiStateBridge.ACTION_UI_STATE_UPDATE)
            addAction(UiStateBridge.ACTION_PROTECTION_EVENT)
            addAction(UiStateBridge.ACTION_SERVICE_EVENT)
            addAction(UiStateBridge.ACTION_SILENT_MODE_CHANGED)
            addAction(UiStateBridge.ACTION_MODE_CHANGED)
        }
        registerReceiver(uiStateReceiver, filter)
    }

    /**
     * 从Bundle更新UI状态
     */
    private fun updateUiStateFromBundle(extras: Bundle) {
        val fps = extras.getInt("fps", 0)
        val gpuLoad = extras.getFloat("gpu_load", 0f)
        val cpuLoad = extras.getFloat("cpu_load", 0f)
        val temperature = extras.getFloat("temperature", 0f)
        val entityEstimate = extras.getInt("entity_estimate", 0)
        val protectionsToday = extras.getInt("protections_today", 0)
        val riskLevel = extras.getString("risk_level", "LOW")
        val silentMode = extras.getBoolean("silent_mode", false)

        tvFps.text = "$fps FPS"
        tvGpuLoad.text = "GPU: ${"%.1f".format(gpuLoad)}%"
        tvCpuLoad.text = "CPU: ${"%.1f".format(cpuLoad)}%"
        tvTemperature.text = "温度: ${"%.1f".format(temperature)}°C"
        tvEntityEstimate.text = "估算实体: ~$entityEstimate"
        tvProtections.text = "今日保护: $protectionsToday 次"
        tvRiskLevel.text = "风险等级: $riskLevel"

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

        // CPU 负载颜色
        tvCpuLoad.setTextColor(
            when {
                cpuLoad < 60 -> getColor(R.color.good)
                cpuLoad < 85 -> getColor(R.color.warning)
                else -> getColor(R.color.danger)
            }
        )

        // 静默模式状态
        if (silentMode) {
            tvStatus.text = "静默中 - 原神启动保护"
            tvStatus.setTextColor(getColor(R.color.warning))
        }
    }

    /**
     * 检查服务是否在运行
     */
    private fun checkServiceStatus() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        var running = false
        for (service in am.getRunningServices(100)) {
            if (service.service.className == GuardService::class.java.name) {
                running = true
                break
            }
        }
        serviceRunning = running
        if (running) {
            tvStatus.text = "监控中"
            tvStatus.setTextColor(getColor(R.color.monitoring))
            btnStart.isEnabled = false
            btnStop.isEnabled = true
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
     * 检测权限状态（异步，不阻塞主线程）
     */
    private fun checkPermissionStatus() {
        val runMode = configManager.getRunMode()
        if (runMode == RunMode.LOG_ONLY) {
            tvPermissionStatus.text = "权限状态: 纯日志模式（无需权限）"
            tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
            return
        }

        val permMode = configManager.getPermissionMode()
        tvPermissionStatus.text = "权限状态: 检测中..."
        tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))

        permissionChecker.checkPermissionStatus(permMode) { status ->
            if (status.available) {
                tvPermissionStatus.text = "权限状态: ${status.message}"
                tvPermissionStatus.setTextColor(getColor(R.color.good))
            } else {
                tvPermissionStatus.text = "权限状态: ${status.message}"
                tvPermissionStatus.setTextColor(getColor(R.color.danger))
            }
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

    /**
     * 导出配置
     */
    private fun exportConfig() {
        try {
            val configJson = configManager.exportConfig()
            val fileName = "spikeguard_rules_${System.currentTimeMillis()}.json"

            // 保存到外部存储
            val exportDir = getExternalFilesDir(null)
            val exportFile = java.io.File(exportDir, fileName)
            exportFile.writeText(configJson)

            // 分享文件
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                exportFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "导出配置"))

            Toast.makeText(this, "配置已导出: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 导入配置
     */
    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_CONFIG)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 导出日志
     */
    private fun exportLog() {
        try {
            val logManager = LogManager.getInstance(this)
            val logFiles = logManager.getLogFiles()

            if (logFiles.isEmpty()) {
                Toast.makeText(this, "没有日志文件", Toast.LENGTH_SHORT).show()
                return
            }

            // 导出最新的日志文件
            val latestLog = logFiles.maxByOrNull { it.lastModified() }
            if (latestLog != null) {
                val exportDir = getExternalFilesDir(null)
                val exportFile = java.io.File(exportDir, latestLog.name)
                latestLog.copyTo(exportFile, overwrite = true)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    exportFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "导出日志"))

                Toast.makeText(this, "日志已导出: ${latestLog.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清空日志
     */
    private fun clearLogs() {
        try {
            val logManager = LogManager.getInstance(this)
            logManager.clearLogs()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查并显示悬浮窗
     */
    private fun checkAndShowFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // 请求悬浮窗权限
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
                switchFloatingWindow.isChecked = false
                return
            }
        }
        startFloatingWindow()
    }

    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        startService(intent)
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止悬浮窗服务
     */
    private fun stopFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        stopService(intent)
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_IMPORT_CONFIG -> {
                if (resultCode == RESULT_OK && data != null) {
                    data.data?.let { uri ->
                        importConfigFromUri(uri)
                    }
                }
            }
            REQUEST_CODE_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        switchFloatingWindow.isChecked = true
                        startFloatingWindow()
                    } else {
                        Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * 从Uri导入配置
     */
    private fun importConfigFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            inputStream?.close()

            if (configManager.importConfig(jsonString)) {
                Toast.makeText(this, "配置导入成功", Toast.LENGTH_SHORT).show()
                // 如果服务正在运行，重启以应用新配置
                if (serviceRunning) {
                    val intent = Intent(this, GuardService::class.java).apply {
                        action = GuardService.ACTION_RESTART
                    }
                    startService(intent)
                }
            } else {
                Toast.makeText(this, "配置格式无效", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查服务是否在运行并更新UI状态
        checkServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        try {
            unregisterReceiver(uiStateReceiver)
        } catch (e: Exception) {
            // 忽略
        }
        // 释放权限检测器资源
        try {
            permissionChecker.release()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
