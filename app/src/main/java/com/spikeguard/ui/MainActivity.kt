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

    // CPU 八核详情独立卡片（与上面实时性能卡片完全分开）
    private lateinit var tvCpuCore0: TextView
    private lateinit var tvCpuCore1: TextView
    private lateinit var tvCpuCore2: TextView
    private lateinit var tvCpuCore3: TextView
    private lateinit var tvCpuCore4: TextView
    private lateinit var tvCpuCore5: TextView
    private lateinit var tvCpuCore6: TextView
    private lateinit var tvCpuCore7: TextView

    // P1-4b: 新增控件
    private lateinit var btnTestProtection: Button
    private lateinit var tvScene: TextView
    private lateinit var btnLaunchShizuku: Button

    // Fix-4/Fix-6: 实际执行方式显示 + 重试连接按钮
    private lateinit var tvActualExecutor: TextView
    private lateinit var btnReconnectExecutor: Button

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

    // 记录最近一次识别到的激活场景名
    @Volatile
    private var lastKnownSceneName: String = "待识别"

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

        // CPU 八核详情独立卡片
        tvCpuCore0 = findViewById(R.id.tvCpuCore0)
        tvCpuCore1 = findViewById(R.id.tvCpuCore1)
        tvCpuCore2 = findViewById(R.id.tvCpuCore2)
        tvCpuCore3 = findViewById(R.id.tvCpuCore3)
        tvCpuCore4 = findViewById(R.id.tvCpuCore4)
        tvCpuCore5 = findViewById(R.id.tvCpuCore5)
        tvCpuCore6 = findViewById(R.id.tvCpuCore6)
        tvCpuCore7 = findViewById(R.id.tvCpuCore7)

        // P1-4b: 新增控件
        btnTestProtection = findViewById(R.id.btnTestProtection)
        tvScene = findViewById(R.id.tvScene)
        btnLaunchShizuku = findViewById(R.id.btnLaunchShizuku)

        // Fix-4/Fix-6: 实际执行方式显示 + 重试连接按钮
        tvActualExecutor = findViewById(R.id.tvActualExecutor)
        btnReconnectExecutor = findViewById(R.id.btnReconnectExecutor)

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

        // P1-4b: 测试保护按钮：手动触发一次完整保护流程
        btnTestProtection.setOnClickListener {
            onTestProtectionClicked()
        }

        // P1-4b: Shizuku引导按钮：打开Shizuku应用或跳转市场
        btnLaunchShizuku.setOnClickListener {
            onLaunchShizukuClicked()
        }

        // Fix-6: 重试连接按钮 → 重新探测执行器（Shizuku→Root→LogOnly），不用重启APP
        btnReconnectExecutor.setOnClickListener {
            onReconnectExecutorClicked()
        }

        // 初始化场景显示
        updateSceneDisplay()
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
                                if (sceneName.isNotEmpty()) lastKnownSceneName = sceneName
                                tvStatus.text = "保护中 - $sceneName"
                                tvStatus.setTextColor(getColor(R.color.protecting))
                                updateSceneDisplay()
                            }
                            "released" -> {
                                tvStatus.text = "监控中"
                                tvStatus.setTextColor(getColor(R.color.monitoring))
                                updateSceneDisplay()
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
                    // P1-4b: 场景变化事件（DecisionEngine发布的SCENE_CHANGED桥接）
                    UiStateBridge.ACTION_SCENE_EVENT -> {
                        val sceneId = intent.getStringExtra("scene_id") ?: ""
                        val sceneName = intent.getStringExtra("scene_name") ?: ""
                        val active = intent.getBooleanExtra("active", false)
                        if (active && sceneName.isNotEmpty()) {
                            lastKnownSceneName = sceneName
                        } else if (!active && sceneName.isNotEmpty() && lastKnownSceneName == sceneName) {
                            // 场景失活，如果是同一个才回到"待识别"，否则保留上次识别
                            lastKnownSceneName = sceneName
                        }
                        updateSceneDisplay()
                    }
                    // P1-4b: 测试保护执行结果
                    UiStateBridge.ACTION_TEST_PROTECTION_RESULT -> {
                        val anySuccess = intent.getBooleanExtra("any_success", false)
                        val ok = intent.getIntExtra("success_count", 0)
                        val total = intent.getIntExtra("attempted_count", 0)
                        val ms = intent.getLongExtra("total_ms", 0L)
                        val exec = intent.getStringExtra("executor") ?: "?"
                        val errReason = intent.getStringExtra("error_reason") ?: "UNKNOWN"
                        val detailStatus = intent.getStringExtra("detailed_status") ?: ""
                        btnTestProtection.isEnabled = true
                        btnTestProtection.text = "🧪 测试保护（完成：$ok/$total，${ms}ms · $exec）"
                        // Fix-5: 根据具体错误原因给不同提示+引导
                        when {
                            anySuccess -> {
                                Toast.makeText(this@MainActivity,
                                    "✅ 测试保护执行成功（${ms}ms，${ok}/${total}步完成）",
                                    Toast.LENGTH_SHORT).show()
                            }
                            errReason.startsWith("NO_EXECUTOR") -> {
                                Toast.makeText(this@MainActivity,
                                    "❌ 服务未启动：请先点「启动保护」让后台服务运行",
                                    Toast.LENGTH_LONG).show()
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            errReason == "PAUSED_SILENT" -> {
                                Toast.makeText(this@MainActivity,
                                    "⏸ 静默中：原神还没启动，等原神启动后自动退出静默",
                                    Toast.LENGTH_LONG).show()
                            }
                            errReason.startsWith("LOGONLY_") -> {
                                Toast.makeText(this@MainActivity,
                                    "📝 当前为纯日志模式（无Shizuku/Root权限）\n→ 点击「启动Shizuku/授权应用」获得真正保护能力",
                                    Toast.LENGTH_LONG).show()
                                btnLaunchShizuku.visibility = android.view.View.VISIBLE
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            errReason.startsWith("SHIZUKU_") -> {
                                val shizukuHint = when {
                                    errReason.contains("NOT_INSTALLED") -> "Shizuku未安装：请从 shizuku.rikka.app 下载安装"
                                    errReason.contains("SERVICE_NOT_RUNNING") -> "Shizuku服务未启动：打开Shizuku APP 并点击「启动」"
                                    errReason.contains("PERMISSION_DENIED") -> "Shizuku未授权：打开Shizuku → 已授权应用 → 打开 SpikeGuard 开关"
                                    errReason.contains("INITIALIZING") || errReason.contains("HANDSHAKE") -> "Shizuku握手超时：点击下方「重试连接」，或检查 Shizuku 是否真的在运行"
                                    else -> "Shizuku状态异常($errReason)：请确认Shizuku APP已运行并授权本应用"
                                }
                                Toast.makeText(this@MainActivity,
                                    "❌ Shizuku不可用：\n$shizukuHint",
                                    Toast.LENGTH_LONG).show()
                                btnLaunchShizuku.visibility = android.view.View.VISIBLE
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            errReason.startsWith("ROOT_") -> {
                                Toast.makeText(this@MainActivity,
                                    "❌ Root不可用：请确认Magisk/KernelSU已装并授予SpikeGuard Root权限\n（当前检测结果：$detailStatus）",
                                    Toast.LENGTH_LONG).show()
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            errReason.startsWith("EXECUTION_ALL_") -> {
                                Toast.makeText(this@MainActivity,
                                    "❌ 执行失败：所有步骤返回失败\n可能是授权假阳性 → 请重启Shizuku服务后点「重试连接」",
                                    Toast.LENGTH_LONG).show()
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            errReason.startsWith("EXECUTION_EXCEPTION") -> {
                                Toast.makeText(this@MainActivity,
                                    "❌ 执行异常：$errReason\n建议点「重试连接」或重启服务",
                                    Toast.LENGTH_LONG).show()
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                            else -> {
                                Toast.makeText(this@MainActivity,
                                    "❌ 测试保护未生效（$errReason），请检查Shizuku/Root授权",
                                    Toast.LENGTH_LONG).show()
                                btnReconnectExecutor.visibility = android.view.View.VISIBLE
                            }
                        }
                        // 10秒后恢复按钮默认文案
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!isDestroyed) {
                                btnTestProtection.text = "🧪 测试保护（手动触发完整1500ms流程）"
                            }
                        }, 10_000L)
                    }
                    // Fix-4: 实际执行方式变更（ExecutionManager自动探测结果）
                    UiStateBridge.ACTION_ACTUAL_EXECUTOR_CHANGED -> {
                        val executorName = intent.getStringExtra("executor_name") ?: "unknown"
                        val detailedStatus = intent.getStringExtra("detailed_status") ?: ""
                        val humanMessage = intent.getStringExtra("human_message") ?: ""
                        val fallbackReason = intent.getStringExtra("fallback_reason") ?: ""
                        updateActualExecutorDisplay(executorName, detailedStatus, humanMessage, fallbackReason)
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
            addAction(UiStateBridge.ACTION_SCENE_EVENT)
            addAction(UiStateBridge.ACTION_TEST_PROTECTION_RESULT)
            addAction(UiStateBridge.ACTION_ACTUAL_EXECUTOR_CHANGED)
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
        // 八核详情（来自 :guard 进程广播 GpuFrameCollector 的 METRICS_SAMPLE）
        val coreFreq = extras.getIntArray("core_freq_mhz") ?: IntArray(8) { -1 }
        val coreLoad = extras.getIntArray("core_load_pct") ?: IntArray(8) { -1 }

        updateMetricsUi(fps, gpuLoad, cpuLoad, temperature, entityEstimate)
        updatePerCoreCpuUi(coreFreq, coreLoad)

        tvProtections.text = "今日保护: $protectionsToday 次"
        tvRiskLevel.text = "风险等级: $riskLevel"

        // 每次收到采样数据都刷新场景显示（保护中状态的颜色可能变化）
        updateSceneDisplay()

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
            updatePerCoreCpuUi(snap.coreFreqMhz, snap.coreLoadPct)
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
     * P1-4b: 更新当前识别到的场景显示
     * 格式：当前场景: {大世界/副本/枪限挑战/千星奇域/待识别}
     * 保护中状态下用特殊高亮颜色
     */
    private fun updateSceneDisplay() {
        if (!::tvScene.isInitialized) return
        val status = tvStatus.text?.toString() ?: ""
        val isProtecting = status.startsWith("保护中")
        val prefix = if (isProtecting) "⚡ 保护中 · 当前场景: " else "当前场景: "
        val name = when {
            lastKnownSceneName.equals("generic", ignoreCase = true) -> "通用保护触发"
            lastKnownSceneName.equals("battle_settlement", ignoreCase = true) -> "战斗结算"
            lastKnownSceneName.contains("副本") || lastKnownSceneName.contains("domain", ignoreCase = true) -> "副本"
            lastKnownSceneName.contains("枪限") || lastKnownSceneName.contains("枪限挑战") -> "枪限挑战"
            lastKnownSceneName.contains("千星") || lastKnownSceneName.contains("奇域") -> "千星奇域"
            lastKnownSceneName.contains("世界") || lastKnownSceneName.contains("open", ignoreCase = true) -> "大世界"
            lastKnownSceneName.isEmpty() || lastKnownSceneName == "待识别" -> "待识别"
            else -> lastKnownSceneName
        }
        tvScene.text = prefix + name
        tvScene.setTextColor(
            when {
                isProtecting -> getColor(R.color.protecting)
                lastKnownSceneName == "待识别" -> getColor(R.color.text_secondary)
                else -> getColor(R.color.primary)
            }
        )
    }

    /**
     * P1-4b: 测试保护按钮点击
     * 发送 ACTION_TEST_PROTECTION 到 GuardService（服务进程通过MessageBus转TEST_PROTECTION_REQUESTED）
     * 如果服务没启动，就先启动再发
     */
    private fun onTestProtectionClicked() {
        btnTestProtection.isEnabled = false
        btnTestProtection.text = "⏳ 测试保护执行中（约1500ms+恢复）..."
        try {
            if (!serviceRunning) {
                // 服务没启动 → 先启动一次，稍等后再发测试指令
                val start = Intent(this, GuardService::class.java).apply {
                    action = GuardService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(start)
                } else {
                    startService(start)
                }
                // 服务起来需要一点时间，延迟发送测试指令
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val testIntent = Intent(this@MainActivity, GuardService::class.java).apply {
                        action = GuardService.ACTION_TEST_PROTECTION
                    }
                    startService(testIntent)
                }, 2500L)
            } else {
                val testIntent = Intent(this, GuardService::class.java).apply {
                    action = GuardService.ACTION_TEST_PROTECTION
                }
                startService(testIntent)
            }
            // 最长兜底：30000ms 超时强制恢复按钮（防止广播没收到）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !btnTestProtection.isEnabled) {
                    btnTestProtection.isEnabled = true
                    btnTestProtection.text = "🧪 测试保护（超时，请重试）"
                }
            }, 30_000L)
        } catch (e: Throwable) {
            btnTestProtection.isEnabled = true
            btnTestProtection.text = "🧪 测试保护失败: ${e.message}"
            Toast.makeText(this, "触发失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * P1-4b: 点击"启动Shizuku/授权应用"按钮
     * 优先打开Shizuku应用；失败跳转系统包信息；还不行弹Toast引导去应用市场
     */
    private fun onLaunchShizukuClicked() {
        val shizukuPackages = listOf(
            "moe.shizuku.privileged.api",
            "rikka.sui"
        )
        for (pkg in shizukuPackages) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                Toast.makeText(this, "请启动 Shizuku 服务并授权本应用", Toast.LENGTH_LONG).show()
                return
            }
        }
        // 没装 → 跳应用详情（设置→应用→允许安装未知来源之类）
        try {
            val uri = Uri.parse("package:moe.shizuku.privileged.api")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this,
                "请安装 Shizuku 应用（https://shizuku.rikka.app/）或在 Magisk 中安装 Sui 模块",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Fix-4: 更新实际执行方式显示
     * 根据自动探测结果：Shizuku Binder / Root Shell / LogOnly
     * 自动上色 + 降级时显示原因
     */
    private fun updateActualExecutorDisplay(
        executorName: String,
        detailedStatus: String,
        humanMessage: String,
        fallbackReason: String
    ) {
        val (displayName, color, isOk) = when {
            executorName.contains("Shizuku", ignoreCase = true) || detailedStatus == "BINDER_OK" -> {
                if (detailedStatus == "BINDER_OK") {
                    Triple("✅ Shizuku Binder (最高优先级)", R.color.good, true)
                } else {
                    Triple("⚠ Shizuku 异常: $humanMessage", R.color.warning, false)
                }
            }
            executorName.contains("Root", ignoreCase = true) || executorName.contains("Shell", ignoreCase = true) -> {
                Triple("🔑 Root Shell (已自动降级，Shizuku不可用)", R.color.warning, true)
            }
            executorName.contains("Log", ignoreCase = true) -> {
                Triple("📝 LogOnly (纯日志，无法执行保护)", R.color.danger, false)
            }
            else -> {
                Triple("执行方式: $executorName", R.color.text_secondary, false)
            }
        }

        // 降级时附加原因（小字显示）
        val reasonText = if (fallbackReason.isNotEmpty() && fallbackReason != "FALLBACK_NONE"
            && fallbackReason != "SHIZUKU_BINDER_OK" && fallbackReason != "ROOT_OK") {
            "\n降级原因: $fallbackReason"
        } else ""

        val hint = if (!isOk && !fallbackReason.contains("LOGONLY") && !fallbackReason.contains("RUNMODE_LOGONLY")) {
            "\n→ 可点击下方「重试连接」或「启动Shizuku」按钮修复"
        } else ""

        tvActualExecutor.text = "执行方式: $displayName$reasonText$hint"
        tvActualExecutor.setTextColor(getColor(color))

        // 探测完成 → 重试按钮状态恢复（无论成功失败，都允许用户再次点击）
        if (::btnReconnectExecutor.isInitialized) {
            btnReconnectExecutor.isEnabled = true
            btnReconnectExecutor.text = "🔄 重试连接（重新检测 Shizuku/Root）"
        }

        // 非最佳状态 → 显示重试和引导按钮
        if (!isOk) {
            btnReconnectExecutor.visibility = android.view.View.VISIBLE
            if (fallbackReason.contains("SHIZUKU", ignoreCase = true) || executorName.contains("Log", ignoreCase = true)) {
                btnLaunchShizuku.visibility = android.view.View.VISIBLE
            }
        } else {
            // Shizuku正常 → 重试按钮保留（用户可能想手动刷新），但引导按钮隐藏
            btnLaunchShizuku.visibility = android.view.View.GONE
        }
    }

    /**
     * Fix-6: 重试连接按钮 → 请求 GuardService 重新探测执行器
     * 如果服务还没启动，先启动服务再立刻发 RECONNECT 指令
     */
    private fun onReconnectExecutorClicked() {
        btnReconnectExecutor.isEnabled = false
        btnReconnectExecutor.text = "🔄 重新探测中（约5~11秒）..."
        tvActualExecutor.text = "执行方式: ⏳ 重新探测执行器..."
        tvActualExecutor.setTextColor(getColor(R.color.text_secondary))
        try {
            // 优先复用服务；没启动就先启动
            val testIntent = Intent(this@MainActivity, GuardService::class.java).apply {
                action = if (serviceRunning) GuardService.ACTION_RECONNECT_EXECUTOR else GuardService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(testIntent)
            } else {
                startService(testIntent)
            }
            // 如果刚启动服务，额外再发一条 RECONNECT（等服务初始化好后才处理）
            if (!serviceRunning) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val reconnect = Intent(this@MainActivity, GuardService::class.java).apply {
                            action = GuardService.ACTION_RECONNECT_EXECUTOR
                        }
                        startService(reconnect)
                    } catch (_: Throwable) {}
                }, 1500L)
                serviceRunning = true
                tvStatus.text = "启动中..."
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                ensureLocalPollerStarted()
            }
            // 最长13秒兜底：探测超时强制恢复按钮，防止卡死
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !btnReconnectExecutor.isEnabled) {
                    btnReconnectExecutor.isEnabled = true
                    btnReconnectExecutor.text = "🔄 重试连接（重新检测 Shizuku/Root）"
                }
            }, 13_000L)
            // 探测完成回调（收到 ACTUAL_EXECUTOR_CHANGED 时会恢复按钮）
        } catch (e: Throwable) {
            btnReconnectExecutor.isEnabled = true
            btnReconnectExecutor.text = "🔄 重试连接失败: ${e.message}"
            Toast.makeText(this, "重试连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 独立的 CPU 八核详情卡片 UI 渲染（与上面 updateMetricsUi 完全分开，不修改原卡片）
     *
     * P1-4b 增强：
     *  1) 每个核显示 {标签 · 频率 · 负载% · 状态（空闲/轻载/中载/重载）}
     *  2) 大核/小核用不同颜色区分：CPU4~CPU7（大核）用 primary 前缀色，CPU0~CPU3（小核）用 text_primary 深灰
     *  3) 数值真实跳动（来自 IntArray 广播），八核一起刷新
     *
     * 显示格式：L/B-XX {freq}MHz {load}% [状态]
     *   L=Little 小核（CPU0-3），B=Big 大核（CPU4-7）
     */
    private fun updatePerCoreCpuUi(coreFreqMhz: IntArray?, coreLoadPct: IntArray?) {
        val freq = coreFreqMhz ?: IntArray(8) { -1 }
        val load = coreLoadPct ?: IntArray(8) { -1 }
        val tvs = arrayOf(tvCpuCore0, tvCpuCore1, tvCpuCore2, tvCpuCore3,
                          tvCpuCore4, tvCpuCore5, tvCpuCore6, tvCpuCore7)
        val good = getColor(R.color.good)
        val warning = getColor(R.color.warning)
        val danger = getColor(R.color.danger)
        val unknown = getColor(R.color.text_secondary)
        val big = getColor(R.color.primary)
        val little = getColor(android.R.color.tab_indicator_text)  // 深灰

        for (i in 0 until 8) {
            val tv = tvs[i]
            val f = freq.getOrNull(i) ?: -1
            val l = load.getOrNull(i) ?: -1

            // 大小核区分：CPU0-3=Little(A510等)，CPU4-7=Big(A715/X2等，八核手机典型拓扑)
            val isBig = i >= 4
            val coreType = if (isBig) "B" else "L"

            val freqStr = if (f <= 0) "--" else String.format("%dMHz", f)
            val loadStr = if (l < 0) "--" else "$l%"
            val state = when {
                l < 0 -> "未知"
                l == 0 -> "空闲"
                l < 30 -> "轻载"
                l < 70 -> "中载"
                else -> "重载"
            }
            // 前缀颜色区分大小核；负载颜色覆盖前缀来反映当前压力
            tv.text = "$coreType-CPU$i: $freqStr  $loadStr  $state"
            val baseColor = if (isBig) big else little
            tv.setTextColor(
                when {
                    l < 0 -> unknown
                    l < 60 -> if (l < 1) baseColor else good
                    l < 85 -> warning
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
     * P1-4b 增强：检测权限状态，显示详细的Shizuku引导提示，错误时显示btnLaunchShizuku
     */
    private fun checkPermissionStatus() {
        val runMode = configManager.getRunMode()
        if (runMode == RunMode.LOG_ONLY) {
            tvPermissionStatus.text = "📝 纯日志模式（无权限也能记录，保护动作仅打日志）"
            tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
            btnLaunchShizuku.visibility = android.view.View.GONE
            return
        }

        val permMode = configManager.getPermissionMode()
        tvPermissionStatus.text = "⏳ 权限检测中..."
        tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
        btnLaunchShizuku.visibility = android.view.View.GONE

        // 异步检测详细状态
        Thread {
            // 1) 先尝试用本地构造的ShizukuActionExecutor.isAvailable/getDetailedStatus快速检测
            val detailMsg = try {
                if (permMode == PermissionMode.SHIZUKU) {
                    val exec = com.spikeguard.executor.ShizukuActionExecutor(this)
                    val st = exec.getDetailedStatus()
                    val msg = exec.getStatusHumanMessage()
                    // 根据状态决定颜色和按钮显示
                    runOnUiThread {
                        when (st) {
                            com.spikeguard.executor.ShizukuDetailedStatus.BINDER_OK -> {
                                tvPermissionStatus.text = msg
                                tvPermissionStatus.setTextColor(getColor(R.color.good))
                                btnLaunchShizuku.visibility = android.view.View.GONE
                            }
                            com.spikeguard.executor.ShizukuDetailedStatus.USING_FALLBACK_SHELL -> {
                                tvPermissionStatus.text = msg
                                tvPermissionStatus.setTextColor(getColor(R.color.warning))
                                btnLaunchShizuku.visibility = android.view.View.VISIBLE
                            }
                            com.spikeguard.executor.ShizukuDetailedStatus.INITIALIZING -> {
                                tvPermissionStatus.text = msg
                                tvPermissionStatus.setTextColor(getColor(R.color.text_secondary))
                                btnLaunchShizuku.visibility = android.view.View.GONE
                            }
                            else -> {
                                tvPermissionStatus.text = msg
                                tvPermissionStatus.setTextColor(getColor(R.color.danger))
                                btnLaunchShizuku.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                    return@Thread
                } else if (permMode == PermissionMode.ROOT) {
                    val exec = com.spikeguard.executor.RootActionExecutor()
                    val msg = exec.getStatusHumanMessage()
                    val st = exec.getDetailedStatus()
                    runOnUiThread {
                        if (st == com.spikeguard.executor.ShizukuDetailedStatus.BINDER_OK) {
                            tvPermissionStatus.text = msg
                            tvPermissionStatus.setTextColor(getColor(R.color.good))
                            btnLaunchShizuku.visibility = android.view.View.GONE
                        } else {
                            tvPermissionStatus.text = msg
                            tvPermissionStatus.setTextColor(getColor(R.color.danger))
                            btnLaunchShizuku.text = "🔧 安装 Root 管理器 (Magisk/KernelSU)"
                            btnLaunchShizuku.visibility = android.view.View.VISIBLE
                        }
                    }
                    return@Thread
                }
                null
            } catch (_: Throwable) { null }

            // 2) 兜底：走旧的permissionChecker（无详细信息时）
            permissionChecker.checkPermissionStatus(permMode) { status ->
                runOnUiThread {
                    if (status.available) {
                        tvPermissionStatus.text = "权限状态: ${status.message}"
                        tvPermissionStatus.setTextColor(getColor(R.color.good))
                        btnLaunchShizuku.visibility = android.view.View.GONE
                    } else {
                        tvPermissionStatus.text = "权限状态: ${status.message}" +
                                "\n请点击下方按钮启动Shizuku并授权应用"
                        tvPermissionStatus.setTextColor(getColor(R.color.danger))
                        btnLaunchShizuku.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }.start()
    }

    /**
     * 启动守护服务
     *
     * 修复关键竞态：不要一启动服务就立刻停本地poller！
     * 服务还在"启动中..."时，SERVICE_STARTED广播 + 首条METRICS_SAMPLE广播都还没到，
     * 提前停poller会让这段"真空期"UI完全失去数据源（用户截图里还停在启动中就是这个情况）。
     * 正确动作：只等 SERVICE_STARTED 到达后，再停掉本地 poller。
     * 再加超时保护：8秒内服务还没起来 → 自动重启本地poller，避免永久无数据。
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
            // ==== 关键修复：本地poller不停，继续采集。等SERVICE_STARTED真的来了再停。====
            // 避免"启动中..."期间丢失CPU/GPU/温度/FPS所有刷新
            ensureLocalPollerStarted()

            // 超时保护：8秒后若还没收到 SERVICE_STARTED，认为服务启动失败，UI继续依赖本地poller
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.postDelayed({
                if (tvStatus.text?.startsWith("启动中") == true) {
                    // Fix-6: 明确区分「采集监控正常 / 执行保护不可用」
                    tvStatus.text = "启动超时" +
                            "\n✅ 采集监控正常（FPS/CPU/GPU/温度继续刷新）" +
                            "\n❌ 执行保护不可用（Shizuku/Root未就绪）"
                    tvStatus.setTextColor(getColor(R.color.warning))
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    serviceRunning = false
                    startLocalPoller()
                    // 显示重试连接按钮，不用重启APP
                    btnReconnectExecutor.visibility = android.view.View.VISIBLE
                    // 同时显示Shizuku引导按钮
                    btnLaunchShizuku.visibility = android.view.View.VISIBLE
                    // 给用户一个Toast提示
                    Toast.makeText(this@MainActivity,
                        "启动超时：采集数据仍正常显示\n但保护功能需Shizuku/Root权限\n请点击「重试连接」或「启动Shizuku」",
                        Toast.LENGTH_LONG).show()
                }
            }, 8000L)
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
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
            // 立刻拉起本地poller，不等 stopped 广播，避免真空期
            startLocalPoller()
        } catch (e: Exception) {
            tvStatus.text = "停止失败: ${e.message}"
        }
    }

    private fun ensureLocalPollerStarted() {
        if (localPoller == null) startLocalPoller()
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
        val coreFreq = data["core_freq_mhz"] as? IntArray ?: IntArray(8) { -1 }
        val coreLoad = data["core_load_pct"] as? IntArray ?: IntArray(8) { -1 }

        // 性能6字段：统一走 updateMetricsUi（未知→--，绝不显示假底座数）
        updateMetricsUi(fps, gpuLoad, cpuLoad, temperature, entityEstimate)
        // 独立八核卡片
        updatePerCoreCpuUi(coreFreq, coreLoad)

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
