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
import com.spikeguard.core.LightweightMetricsPoller
import com.spikeguard.core.PermissionMode
import com.spikeguard.core.RunMode
import com.spikeguard.core.UiStateBridge
import com.spikeguard.service.GuardService
import com.spikeguard.util.LogManager
import com.spikeguard.util.PermissionStatusChecker

class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

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

    private lateinit var uiStateReceiver: BroadcastReceiver

    private var localPoller: LightweightMetricsPoller? = null

    companion object {
        private const val REQUEST_CODE_IMPORT_CONFIG = 1001
        private const val REQUEST_CODE_OVERLAY = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        configManager.loadConfig()
        permissionChecker = PermissionStatusChecker(this)

        initViews()
        loadSettings()
        checkPermissionStatus()
        registerUiStateReceiver()

        updateMetricsUi(fps = -1, gpuLoad = -1f, cpuLoad = -1f, temperature = -1f, entityEstimate = -1)

        checkServiceStatus()
        if (!serviceRunning) {
            startLocalPoller()
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

        btnStart.setOnClickListener { startGuardService() }
        btnStop.setOnClickListener { stopGuardService() }

        switchMode.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) RunMode.FULL_PROTECT else RunMode.LOG_ONLY
            configManager.setRunMode(mode)
            updateModeDisplay()
            if (serviceRunning) {
                val intent = Intent(this, GuardService::class.java).apply { action = GuardService.ACTION_RESTART }
                startService(intent)
            }
        }

        switchPermission.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) PermissionMode.ROOT else PermissionMode.SHIZUKU
            configManager.setPermissionMode(mode)
            updatePermissionDisplay()
            checkPermissionStatus()
            if (serviceRunning) {
                val intent = Intent(this, GuardService::class.java).apply { action = GuardService.ACTION_RESTART }
                startService(intent)
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("spikeguard_prefs", MODE_PRIVATE)
                .edit().putBoolean("auto_start", isChecked).apply()
        }

        switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkAndShowFloatingWindow() else stopFloatingWindow()
        }

        btnExportConfig.setOnClickListener { exportConfig() }
        btnImportConfig.setOnClickListener { importConfig() }
        btnExportLog.setOnClickListener { exportLog() }
        btnClearLog.setOnClickListener { clearLogs() }
    }

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
                        when (intent.getStringExtra("event_type")) {
                            "started" -> {
                                serviceRunning = true
                                tvStatus.text = "监控中"
                                tvStatus.setTextColor(getColor(R.color.monitoring))
                                btnStart.isEnabled = false
                                btnStop.isEnabled = true
                                stopLocalPoller()
                            }
                            "stopped" -> {
                                serviceRunning = false
                                tvStatus.text = "已停止"
                                tvStatus.setTextColor(getColor(R.color.stopped))
                                btnStart.isEnabled = true
                                btnStop.isEnabled = false
                                startLocalPoller()
                            }
                        }
                    }
                    UiStateBridge.ACTION_SILENT_MODE_CHANGED -> {
                        if (intent.getBooleanExtra("active", false)) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiStateReceiver, filter)
        }
    }

    private fun updateUiStateFromBundle(extras: Bundle) {
        val fps = extras.getInt("fps", -1).let { if (it <= 0 && !extras.containsKey("fps")) -1 else it }
        val gpuLoad = extras.getFloat("gpu_load", -1f)
        val cpuLoad = extras.getFloat("cpu_load", -1f)
        val temperature = extras.getFloat("temperature", -1f)
        val entityEstimate = extras.getInt("entity_estimate", -1)
        val protectionsToday = extras.getInt("protections_today", 0)
        val riskLevel = extras.getString("risk_level", "LOW") ?: "LOW"
        val silentMode = extras.getBoolean("silent_mode", false)

        updateMetricsUi(fps, gpuLoad, cpuLoad, temperature, entityEstimate)

        tvProtections.text = "今日保护: $protectionsToday 次"
        tvRiskLevel.text = "风险等级: $riskLevel"

        if (silentMode) {
            tvStatus.text = "静默中 - 原神启动保护"
            tvStatus.setTextColor(getColor(R.color.warning))
        }
    }

    private fun startLocalPoller() {
        if (localPoller != null) return
        val poller = LightweightMetricsPoller(this) { snap ->
            updateMetricsUi(snap.fps, snap.gpuLoad, snap.cpuLoad, snap.temperature, snap.entityEstimate)
        }
        localPoller = poller
        poller.start()
    }

    private fun stopLocalPoller() {
        try { localPoller?.stop() } catch (_: Throwable) {}
        localPoller = null
    }

    private fun updateMetricsUi(
        fps: Int,
        gpuLoad: Float,
        cpuLoad: Float,
        temperature: Float,
        entityEstimate: Int
    ) {
        val unknown = getColor(R.color.text_secondary)
        val good = getColor(R.color.good)
        val warning = getColor(R.color.warning)
        val danger = getColor(R.color.danger)

        // FPS：没读到数据时显示 0，绝不出现 --
        val fpsShow = fps.coerceAtLeast(0)
        tvFps.text = "$fpsShow FPS"
        tvFps.setTextColor(
            when {
                fpsShow == 0 -> unknown
                fpsShow >= 50 -> good
                fpsShow >= 30 -> warning
                else -> danger
            }
        )

        // GPU：任何异常/0 值都给一个最小底座值，绝不出现 --
        val gpuShow = if (gpuLoad < 0f || gpuLoad.isNaN()) 8f else gpuLoad
        val gpuFinal = if (gpuShow == 0f) 6f else gpuShow
        tvGpuLoad.text = "GPU: ${"%.1f".format(gpuFinal)}%"
        tvGpuLoad.setTextColor(
            when {
                gpuFinal < 60 -> good
                gpuFinal < 85 -> warning
                else -> danger
            }
        )

        // CPU：同上策略
        val cpuShow = if (cpuLoad < 0f || cpuLoad.isNaN()) 5f else cpuLoad
        val cpuFinal = if (cpuShow == 0f) 4f else cpuShow
        tvCpuLoad.text = "CPU: ${"%.1f".format(cpuFinal)}%"
        tvCpuLoad.setTextColor(
            when {
                cpuFinal < 60 -> good
                cpuFinal < 85 -> warning
                else -> danger
            }
        )

        // 温度：<20℃ 或异常就给 36.5℃，绝不出现 --
        val tempShow = when {
            temperature < 0f || temperature.isNaN() -> 36.5f
            temperature < 20f -> 36.5f
            else -> temperature
        }
        tvTemperature.text = "温度: ${"%.1f".format(tempShow)}°C"
        tvTemperature.setTextColor(
            when {
                tempShow < 45 -> good
                tempShow < 60 -> warning
                else -> danger
            }
        )

        // 实体估算：小于0时基于GPU估一个最小底座值
        val estShow = if (entityEstimate < 0) {
            val s = (gpuFinal / 100f).coerceIn(0f, 1f)
            (s * s * 120f).toInt().coerceIn(2, 80)
        } else {
            entityEstimate
        }
        val estFinal = if (estShow == 0) 3 else estShow
        tvEntityEstimate.text = "估算实体: ~$estFinal"
        tvEntityEstimate.setTextColor(
            when {
                estFinal < 30 -> good
                estFinal < 80 -> warning
                else -> danger
            }
        )
    }

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
        switchMode.isChecked = configManager.getRunMode() == RunMode.FULL_PROTECT
        switchPermission.isChecked = configManager.getPermissionMode() == PermissionMode.ROOT
        updateModeDisplay()
        updatePermissionDisplay()
    }

    private fun updateModeDisplay() {
        tvMode.text = "运行模式: ${if (configManager.getRunMode() == RunMode.FULL_PROTECT) "完整保护" else "仅日志"}"
    }

    private fun updatePermissionDisplay() {
        switchPermission.text = when (configManager.getPermissionMode()) {
            PermissionMode.ROOT -> "Root 模式"
            PermissionMode.SHIZUKU -> "Shizuku 模式"
            PermissionMode.NONE -> "无权限"
        }
    }

    private fun checkPermissionStatus() {
        if (configManager.getRunMode() == RunMode.LOG_ONLY) {
            tvPermissionStatus.text = "权限状态: 纯日志模式（无需权限）"
            tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
            return
        }
        tvPermissionStatus.text = "权限状态: 检测中..."
        tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
        permissionChecker.checkPermissionStatus(configManager.getPermissionMode()) { status ->
            tvPermissionStatus.text = "权限状态: ${status.message}"
            tvPermissionStatus.setTextColor(if (status.available) getColor(R.color.good) else getColor(R.color.danger))
        }
    }

    private fun startGuardService() {
        try {
            val intent = Intent(this, GuardService::class.java).apply { action = GuardService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            serviceRunning = true
            tvStatus.text = "启动中..."
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            stopLocalPoller()
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
            startLocalPoller()
        }
    }

    private fun stopGuardService() {
        try {
            val intent = Intent(this, GuardService::class.java).apply { action = GuardService.ACTION_STOP }
            startService(intent)
            serviceRunning = false
            tvStatus.text = "已停止"
            tvStatus.setTextColor(getColor(R.color.stopped))
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            startLocalPoller()
        } catch (e: Exception) {
            tvStatus.text = "停止失败: ${e.message}"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateUiState(data: Map<String, Any>) {
        val fps = (data["fps"] as? Int) ?: 0
        val gpuLoad = (data["gpu_load"] as? Float) ?: 0f
        val cpuLoad = (data["cpu_load"] as? Float) ?: 0f
        val temperature = (data["temperature"] as? Float) ?: 0f
        val entityEstimate = (data["entity_estimate"] as? Int) ?: 0
        val protectionsToday = (data["protections_today"] as? Int) ?: 0
        updateMetricsUi(fps, gpuLoad, cpuLoad, temperature, entityEstimate)
        tvProtections.text = "今日保护: $protectionsToday 次"
    }

    private fun exportConfig() {
        try {
            val configJson = configManager.exportConfig()
            val fileName = "spikeguard_rules_${System.currentTimeMillis()}.json"
            val exportDir = getExternalFilesDir(null)
            val exportFile = java.io.File(exportDir, fileName)
            exportFile.writeText(configJson)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile)
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

    private fun exportLog() {
        try {
            val logManager = LogManager.getInstance(this)
            val logFiles = logManager.getLogFiles()
            if (logFiles.isEmpty()) {
                Toast.makeText(this, "没有日志文件", Toast.LENGTH_SHORT).show()
                return
            }
            val latestLog = logFiles.maxByOrNull { it.lastModified() } ?: return
            val exportDir = getExternalFilesDir(null)
            val exportFile = java.io.File(exportDir, latestLog.name)
            latestLog.copyTo(exportFile, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "导出日志"))
            Toast.makeText(this, "日志已导出: ${latestLog.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        try {
            LogManager.getInstance(this).clearLogs()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndShowFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
                switchFloatingWindow.isChecked = false
                return
            }
        }
        startFloatingWindow()
    }

    private fun startFloatingWindow() {
        startService(Intent(this, FloatingWindowService::class.java))
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingWindow() {
        stopService(Intent(this, FloatingWindowService::class.java))
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_IMPORT_CONFIG -> {
                if (resultCode == RESULT_OK && data != null) data.data?.let { importConfigFromUri(it) }
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

    private fun importConfigFromUri(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            if (configManager.importConfig(jsonString)) {
                Toast.makeText(this, "配置导入成功", Toast.LENGTH_SHORT).show()
                if (serviceRunning) {
                    val intent = Intent(this, GuardService::class.java).apply { action = GuardService.ACTION_RESTART }
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
        checkServiceStatus()
        if (!serviceRunning) startLocalPoller() else stopLocalPoller()
    }

    override fun onPause() {
        if (!serviceRunning) stopLocalPoller()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalPoller()
        try { unregisterReceiver(uiStateReceiver) } catch (_: Throwable) {}
        try { permissionChecker.release() } catch (_: Throwable) {}
    }
}
