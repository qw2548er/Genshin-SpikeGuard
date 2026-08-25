package com.spikeguard.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spikeguard.R
import com.spikeguard.collector.GpuFrameCollector
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.EventType
import com.spikeguard.core.MessageBus
import com.spikeguard.core.UiStateBridge
import com.spikeguard.decision.DecisionEngine
import com.spikeguard.executor.ExecutionManager
import com.spikeguard.util.LogManager

/**
 * 守护前台服务
 *
 * 运行在独立进程 (:guard)，确保原神崩溃时服务不跟着死亡
 *
 * 职责：
 * 1. 启动并管理采集、决策、执行模块
 * 2. 维持前台服务状态，降低被系统杀死的概率
 * 3. 心跳检测，自我恢复
 * 4. 原神启动静默期管理
 */
class GuardService : Service() {

    private val bus = MessageBus.getInstance()
    private lateinit var configManager: ConfigManager
    private lateinit var collector: GpuFrameCollector
    private lateinit var decisionEngine: DecisionEngine
    private lateinit var executionManager: ExecutionManager
    private lateinit var uiStateBridge: UiStateBridge

    // 心跳使用主线程（轻量操作）
    private var heartbeatHandler = Handler(android.os.Looper.getMainLooper())
    private var heartbeatCount = 0L

    // 原神进程监控 - 使用独立后台线程，避免阻塞主线程导致ANR
    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null
    @Volatile private var genshinWasRunning = false
    @Volatile private var silentModeActive = false
    private var silentModeEndTime = 0L
    private val genshinPackages = listOf(
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.Yuanshen",
        "com.mihoyo.genshinimpact"
    )

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            heartbeatCount++
            bus.publish(
                EventType.HEARTBEAT,
                "count" to heartbeatCount,
                "uptime_ms" to (System.currentTimeMillis() - startTime),
                "silent_mode" to silentModeActive
            )
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * 原神进程监控Runnable（在后台线程运行）
     * 检测原神启动时进入静默模式，暂停所有Shizuku/Root调用
     */
    private val genshinMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                checkGenshinAndSilence()
            } catch (e: Exception) {
                Log.e(TAG, "Genshin monitor error", e)
            } finally {
                // 无论成功失败，都调度下一次检查
                monitorHandler?.postDelayed(this, GENSHIN_MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * 检查原神状态并管理静默期
     */
    private fun checkGenshinAndSilence() {
        val isRunning = isGenshinRunningSafe()

        if (isRunning && !genshinWasRunning) {
            // 原神刚启动，进入静默模式
            val silenceMs = configManager.getGenshinSilenceMs()
            Log.i(TAG, "Genshin startup detected! Entering silent mode for ${silenceMs}ms")
            enterSilentMode(silenceMs)
        }

        genshinWasRunning = isRunning

        // 检查静默期是否结束
        if (silentModeActive && System.currentTimeMillis() >= silentModeEndTime) {
            exitSilentMode()
        }
    }

    /**
     * 进入静默模式
     */
    private fun enterSilentMode(durationMs: Long) {
        silentModeActive = true
        silentModeEndTime = System.currentTimeMillis() + durationMs

        // 暂停采集器（停止一切数据采集）
        collector.pause()

        // 暂停执行器（停止所有Shizuku/Root调用）
        executionManager.pause()

        // 通知决策引擎进入静默
        decisionEngine.enterSilentMode(durationMs)

        // 更新通知
        updateNotification("静默中 - 原神启动保护")

        // 发布静默模式事件
        bus.publish(
            EventType.SILENT_MODE_CHANGED,
            "active" to true,
            "duration_ms" to durationMs,
            "reason" to "genshin_startup"
        )

        Log.i(TAG, "Silent mode activated for ${durationMs}ms - all collection & control paused")
    }

    /**
     * 退出静默模式
     */
    private fun exitSilentMode() {
        silentModeActive = false

        // 恢复采集器
        collector.resume()

        // 恢复执行器
        executionManager.resume()

        // 更新通知
        updateNotification("运行中 - 保护已启用")

        // 发布静默模式事件
        bus.publish(
            EventType.SILENT_MODE_CHANGED,
            "active" to false,
            "reason" to "timeout"
        )

        Log.i(TAG, "Silent mode deactivated - collection & control resumed")
    }

    private var startTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardService onCreate - process: ${android.os.Process.myPid()}")

        startTime = System.currentTimeMillis()

        // 初始化日志系统（必须最先初始化）
        val logManager = LogManager.getInstance(this)
        logManager.initialize()
        logManager.setLogLevel(LogManager.LogLevel.DEBUG)
        logManager.i(TAG, "=== SpikeGuard Service starting ===")
        logManager.i(TAG, "Process PID: ${android.os.Process.myPid()}")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("启动中..."))

        // 初始化消息总线
        bus.start()

        // 初始化配置
        configManager = ConfigManager(this)
        configManager.loadConfig()

        // 初始化采集器
        collector = GpuFrameCollector(this)

        // 初始化决策引擎
        val scenes = configManager.getEnabledScenes()
        val riskConfig = configManager.getRiskMitigationConfig()
        val settlementConfig = configManager.getSettlementConfig()
        val sceneCategories = configManager.getSceneCategories()
        decisionEngine = DecisionEngine(scenes, riskConfig, settlementConfig, sceneCategories)

        // 初始化执行管理器
        executionManager = ExecutionManager(this, configManager)

        // 启动所有模块
        startModules()

        // 启动心跳
        heartbeatHandler.post(heartbeatRunnable)

        // 启动原神进程监控（在独立后台线程）
        startGenshinMonitor()

        // 发布服务启动事件
        bus.publish(
            EventType.SERVICE_STARTED,
            "pid" to android.os.Process.myPid(),
            "start_time" to startTime,
            "run_mode" to configManager.getRunMode().name,
            "permission_mode" to configManager.getPermissionMode().name
        )

        Log.i(TAG, "GuardService started successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartModules()
            }
            // P1-3: 主界面"测试保护"按钮 → 启动服务发 ACTION_TEST_PROTECTION
            // 在 GuardService 所在进程通过 MessageBus 广播 TEST_PROTECTION_REQUESTED
            ACTION_TEST_PROTECTION -> {
                Log.i(TAG, "Received TEST_PROTECTION action, publishing via MessageBus")
                bus.publish(EventType.TEST_PROTECTION_REQUESTED,
                    "manual" to true,
                    "requester" to "main_activity")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "GuardService onDestroy")

        // 停止心跳
        heartbeatHandler.removeCallbacksAndMessages(null)

        // 停止原神进程监控
        stopGenshinMonitor()

        // 停止所有模块
        stopModules()

        // 停止消息总线
        bus.stop()

        // 发布服务停止事件
        bus.publish(EventType.SERVICE_STOPPED, "reason" to "onDestroy")

        super.onDestroy()
    }

    /**
     * 启动所有模块
     */
    private fun startModules() {
        val sampleInterval = configManager.getSampleIntervalMs()

        // 启动执行管理器
        executionManager.start()

        // 启动采集器
        collector.start(sampleInterval)

        // 启动跨进程UI状态桥接
        uiStateBridge = UiStateBridge(this)
        uiStateBridge.start()

        updateNotification("运行中 - 保护已启用")
    }

    /**
     * 停止所有模块
     */
    private fun stopModules() {
        collector.stop()
        executionManager.stop()
    }

    /**
     * 重启模块
     */
    private fun restartModules() {
        stopModules()
        configManager.loadConfig()
        startModules()
    }

    /**
     * 启动原神监控后台线程
     */
    private fun startGenshinMonitor() {
        monitorThread = HandlerThread("GenshinMonitor")
        monitorThread?.start()
        monitorHandler = Handler(monitorThread!!.looper)
        monitorHandler?.post(genshinMonitorRunnable)
    }

    /**
     * 停止原神监控
     */
    private fun stopGenshinMonitor() {
        try {
            monitorHandler?.removeCallbacksAndMessages(null)
            monitorThread?.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping genshin monitor", e)
        }
        monitorThread = null
        monitorHandler = null
    }

    /**
     * 安全检测原神是否在运行（使用ActivityManager，不需要Root/Shizuku）
     * 完全不会触发ANR
     */
    private fun isGenshinRunningSafe(): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val runningApps = am.runningAppProcesses ?: return false
            for (processInfo in runningApps) {
                if (genshinPackages.contains(processInfo.processName)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check genshin running state", e)
            false
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpikeGuard 守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPU尖峰保护后台服务"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(text: String): Notification {
        val runMode = configManager.getRunMode().name
        val permMode = configManager.getPermissionMode().name

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Genshin SpikeGuard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.spikeguard.ui.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "停止",
                android.app.PendingIntent.getService(
                    this, 1,
                    Intent(this, GuardService::class.java).apply {
                        action = ACTION_STOP
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$text\n模式: $runMode | 权限: $permMode")
            )
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(text: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "spikeguard_guard"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val GENSHIN_MONITOR_INTERVAL_MS = 2000L

        const val ACTION_START = "com.spikeguard.action.START"
        const val ACTION_STOP = "com.spikeguard.action.STOP"
        const val ACTION_RESTART = "com.spikeguard.action.RESTART"
        // P1-3: 手动触发一次完整保护流程（供测试按钮使用）
        const val ACTION_TEST_PROTECTION = "com.spikeguard.action.TEST_PROTECTION"
    }
}
