package com.spikeguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spikeguard.R
import com.spikeguard.collector.GpuFrameCollector
import com.spikeguard.core.ConfigManager
import com.spikeguard.core.EventType
import com.spikeguard.core.MessageBus
import com.spikeguard.decision.DecisionEngine
import com.spikeguard.executor.ExecutionManager

/**
 * 守护前台服务
 *
 * 运行在独立进程 (:guard)，确保原神崩溃时服务不跟着死亡
 *
 * 职责：
 * 1. 启动并管理采集、决策、执行模块
 * 2. 维持前台服务状态，降低被系统杀死的概率
 * 3. 心跳检测，自我恢复
 */
class GuardService : Service() {

    private val bus = MessageBus.getInstance()
    private lateinit var configManager: ConfigManager
    private lateinit var collector: GpuFrameCollector
    private lateinit var decisionEngine: DecisionEngine
    private lateinit var executionManager: ExecutionManager

    private var heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatCount = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            heartbeatCount++
            bus.publish(
                EventType.HEARTBEAT,
                "count" to heartbeatCount,
                "uptime_ms" to (System.currentTimeMillis() - startTime)
            )
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private var startTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardService onCreate - process: ${android.os.Process.myPid()}")

        startTime = System.currentTimeMillis()

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
        decisionEngine = DecisionEngine(scenes, riskConfig)

        // 初始化执行管理器
        executionManager = ExecutionManager(this, configManager)

        // 启动所有模块
        startModules()

        // 启动心跳
        heartbeatHandler.post(heartbeatRunnable)

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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "spikeguard_guard"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 3000L

        const val ACTION_START = "com.spikeguard.action.START"
        const val ACTION_STOP = "com.spikeguard.action.STOP"
        const val ACTION_RESTART = "com.spikeguard.action.RESTART"
    }
}
