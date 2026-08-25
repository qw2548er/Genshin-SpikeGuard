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

    // 主进程本地轻量轮询器（服务没启动时使用）
    private var localPoller: LightweightMetricsPoller? = null
    // 是否显示过任何真实数据（用来避免第一次采样前UI显示0）
    private var metricsSeen = false

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

        // 界面一打开，先初始化占位（后续 localPoller 或广播会覆盖）
        updateMetricsUi(fps = -1, gpuLoad = -1f, cpuLoad = -1f, temperature = -1f, entityEstimate = -1)

        // 检查服务状态，如果没启动则启动本地轮询采集（核心：即使"已停止"也有实时性能数据）
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
                                // 守护服务起来了 → 停掉本地 poller，避免双重采样
                                stopLocalPoller()
                            }
                            "stopped" -> {
                                serviceRunning = false
                                tvStatus.text = "已停止"
                                tvStatus.setTextColor(getColor(R.color.stopped))
                                btnStart.isEnabled = true
                                btnStop.isEnabled = false
                                // 服务停了 → 重新启动本地 poller 保证实时数据仍显示
                                startLocalPoller()
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
        // Android 13+ 必须指定 exported 标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiStateReceiver, filter)
        }
    }

    /**
     * 从Bundle更新UI状态（来自 :guard 进程的广播）
     */
    private fun updateUiStateFromBundle(extras: Bundle) {
        // 只要收到广播数据，一定认为这是"真实数据"（不管字段是0还是多少）
        metricsSeen = true

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

        // 静默模式状态
        if (silentMode) {
            tvStatus.text = "静默中 - 原神启动保护"
            tvStatus.setTextColor(getColor(R.color.warning))
        }
    }

    // ============== 本地轻量轮询 ==============

    private fun startLocalPoller() {
        if (localPoller != null) return
        val poller = LightweightMetricsPoller(this) { snap ->
            metricsSeen = true
            updateMetricsUi(snap.fps, snap.gpuLoad, snap.cpuLoad, snap.temperature, snap.entityEstimate)
        }
        localPoller = poller
        poller.start()
    }

    private fun stopLocalPoller() {
        try {
            localPoller?.stop()
        } catch (_: Throwable) {}
        localPoller = null
    }

    /**
     * 统一更新 6 个实时性能字段（无论数据源是本地 poller 还是服务广播）
     *
     * 重要策略变化（针对用户反馈：像"设备信息"App一样，绝不全是"--"）：
     *  - FPS：-1 表示"没读到数据" → 显示 "0 FPS"，不打"--"（界面清爽，用户知道是待渲染）
     *  - fps            < 0  → 未知 → "-- FPS"
     *  - gpuLoad/cpuLoad < 0 → 未知 → "--%"
     *  - temperature    < 0  → 未知 → "--℃"
     *  - entityEstimate < 0  → 未知 → "估算实体: --"
     *  - 值 == 0 是合法的真实读数（比如没玩游戏时 FPS=0），应原样显示，不算"未知"
     */
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

        // ---- FPS ---- 未知 = -1 显示 --；真 = 0~120 显示真实值
        if (fps < 0) {
            tvFps.text = "-- FPS"
            tvFps.setTextColor(unknown)
        } else {
            tvFps.text = "$fps FPS"
            tvFps.setTextColor(
                when {
                    fps == 0 -> unknown
                    fps >= 50 -> good
                    fps >= 30 -> warning
                    else -> danger
                }
            )
        }

        // ---- GPU ---- 未知 = 负/NaN → --；真0也显示0%
        if (gpuLoad < 0f || gpuLoad.isNaN()) {
            tvGpuLoad.text = "GPU: --%"
            tvGpuLoad.setTextColor(unknown)
        } else {
            tvGpuLoad.text = "GPU: ${"%.1f".format(gpuLoad)}%"
            tvGpuLoad.setTextColor(
                when {
                    gpuLoad < 60 -> good
                    gpuLoad < 85 -> warning
                    else -> danger
                }
            )
        }

        // ---- CPU ---- 同上
        if (cpuLoad < 0f || cpuLoad.isNaN()) {
            tvCpuLoad.text = "CPU: --%"
            tvCpuLoad.setTextColor(unknown)
        } else {
            tvCpuLoad.text = "CPU: ${"%.1f".format(cpuLoad)}%"
            tvCpuLoad.setTextColor(
                when {
                    cpuLoad < 60 -> good
                    cpuLoad < 85 -> warning
                    else -> danger
                }
            )
        }

        // ---- Temperature ---- 未知 = 负/NaN → --
        if (temperature < 0f || temperature.isNaN()) {
            tvTemperature.text = "温度: --°C"
            tvTemperature.setTextColor(unknown)
        } else {
            tvTemperature.text = "温度: ${"%.1f".format(temperature)}°C"
            tvTemperature.setTextColor(
                when {
                    temperature < 45 -> good
                    temperature < 60 -> warning
                    else -> danger
                }
            )
        }

        // ---- Entity ---- 未知 = 负 → --
        if (entityEstimate < 0) {
            tvEntityEstimate.text = "估算实体: --"
            tvEntityEstimate.setTextColor(unknown)
        } else {
            tvEntityEstimate.text = "估算实体: ~$entityEstimate"
            tvEntityEstimate.setTextColor(
                when {
                    entityEstimate < 30 -> good
                    entityEstimate < 80 -> warning
                    else -> danger
                }
            )
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
            // 既然准备让服务接管数据了，本地先停掉（避免双重耗电）
            stopLocalPoller()
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
            // 失败就把本地采样重新拉回来
            startLocalPoller()
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
            // 服务停了，UI 实时数据不应该停 —— 启动本地轮询
            startLocalPoller()
        } catch (e: Exception) {
            tvStatus.text = "停止失败: ${e.message}"
        }
    }

    /**
     * 更新 UI 状态（来自 GuardService 广播）
     *
     * 关键修复：缺失值一律用 -1/-1f（代表"未知"），绝不造假为 0；
     * 然后统一调用 updateMetricsUi，确保"未知→显示--"的逻辑在所有数据源下一致生效。
     */
    private fun updateUiState(data: Map<String, Any>) {
        val fps = data["fps"] as? Int ?: -1
        val gpuLoad = data["gpu_load"] as? Float ?: -1f
        val cpuLoad = data["cpu_load"] as? Float ?: -1f
        val temperature = data["temperature"] as? Float ?: -1f
        val entityEstimate = data["entity_estimate"] as? Int ?: -1
        val isProtecting = data["is_protecting"] as? Boolean ?: false
        val protectionsToday = data["protections_today"] as? Int ?: 0

        // 性能6字段：统一走 updateMetricsUi（未知→--，绝不显示假底座数）
        updateMetricsUi(fps, gpuLoad, cpuLoad, temperature, entityEstimate)

        // 统计字段
        tvProtections.text = "今日保护: $protectionsToday 次"
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
        // 检查服务是否在运行并更新UI状态；如果没启动则启动本地 poller
        checkServiceStatus()
        if (!serviceRunning) {
            startLocalPoller()
        } else {
            stopLocalPoller()
        }
    }

    override fun onPause() {
        // 离开页面时停掉本地轮询，省 CPU
        if (!serviceRunning) {
            stopLocalPoller()
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalPoller()
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
