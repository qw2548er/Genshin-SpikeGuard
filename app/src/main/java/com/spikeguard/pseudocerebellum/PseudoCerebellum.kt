package com.spikeguard.pseudocerebellum

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.spikeguard.core.EventType
import com.spikeguard.core.MessageBus
import com.spikeguard.util.LogManager
import kotlinx.coroutines.*

/**
 * 伪小脑（一加一减·刚柔并济）系统 — 主入口
 *
 * 核心思想：不删除贴图/模型资源，监测算力尖峰 → 任务排队分流 → 资源缓冲隔离 → 异常兜底保护，
 * 抹平CPU/GPU瞬时资源冲击，解决贴图闪烁、闪退、长时间负载卡顿。
 *
 * 架构（4大核心模块，完整闭环）：
 * ┌─────────────────────────────────────────────────────────┐
 * │                    APK应用层                              │
 * │  模块1：瞬时负载监测模块（感知尖峰）  ── SpikeMonitor    │
 * │         ↓ 数据                                           │
 * │  模块2：任务排队&时序分流模块（核心调度） ── TaskScheduler│
 * │         ↓ 指令                                           │
 * │  模块3：资源隔离缓冲模块          ── ResourceBuffer     │
 * │         ↓ 兜底                                           │
 * │  模块4：异常兜底保护模块          ── FallbackGuard      │
 * └─────────────────────────────────────────────────────────┘
 *
 * 重要约束（必须遵守）：
 * ✅ 必须保留：所有贴图、模型、特效资源完整，禁止自动降低画质、删减资源
 * ✅ 核心逻辑：疏导任务时序，抹平瞬时算力尖峰，不是削减资源总量
 * ✅ 软件层实现：全部代码写在APK内部，不需要修改手机内核、驱动
 * ❌ 禁止：直接砍贴图、降低分辨率、关闭特效这类传统暴力优化
 * ❌ 限制：当前APK内生效，不会对手机其他APP/游戏生效（应用层SDK局限）
 *
 * 本项目为系统级工具（不注入游戏进程），伪小脑从系统层面实现：
 * - 通过 /proc 与 sysfs 真实读取硬件数据
 * - 通过 Root/Shizuku 执行系统级资源调度（内存回收、进程优先级、后台清理）
 * - 不修改游戏任何资源，不注入游戏进程，不做画质删减
 */
class PseudoCerebellum private constructor(private val context: Context) {

    private val logManager = LogManager.getInstance(context)
    private val bus = MessageBus.getInstance()

    // 4大核心模块
    private var spikeMonitor: SpikeMonitor? = null
    private var taskScheduler: TaskScheduler? = null
    private var resourceBuffer: ResourceBuffer? = null
    private var fallbackGuard: FallbackGuard? = null

    // 后台线程
    private var workThread: HandlerThread? = null
    private var workHandler: Handler? = null

    // 模块总开关
    @Volatile private var enabled = false
    @Volatile private var running = false

    // 状态统计
    @Volatile private var spikeDetectedCount = 0
    @Volatile private var shuntTriggeredCount = 0
    @Volatile private var fallbackTriggeredCount = 0
    @Volatile private var currentLoadLevel = LoadLevel.NORMAL

    /**
     * 负载等级
     */
    enum class LoadLevel {
        NORMAL,      // 正常
        WARNING,     // 预警（尖峰前兆）
        CRITICAL,    // 临界（已触发保护）
        RECOVERING   // 恢复中
    }

    init {
        logManager.i(TAG, "PseudoCerebellum instance created")
    }

    // ==================== 对外API ====================

    /**
     * 启动伪小脑监测
     * 开启后台低功耗监测，只有预判资源尖峰才介入调度
     */
    fun startMonitor() {
        if (running) {
            logManager.w(TAG, "PseudoCerebellum already running")
            return
        }
        if (!enabled) {
            logManager.w(TAG, "PseudoCerebellum disabled by master switch")
            return
        }

        logManager.i(TAG, "Starting PseudoCerebellum monitor...")

        // 启动工作线程
        workThread = HandlerThread("PseudoCerebellum").apply { start() }
        workHandler = Handler(workThread!!.looper)

        // 初始化4大模块
        workHandler?.post {
            spikeMonitor = SpikeMonitor(context, workHandler!!, ::onSpikeDetected, ::onLoadLevelChanged)
            taskScheduler = TaskScheduler(context, workHandler!!)
            resourceBuffer = ResourceBuffer(context, workHandler!!)
            fallbackGuard = FallbackGuard(context, workHandler!!, ::onFallbackTriggered)

            spikeMonitor?.start()
            taskScheduler?.start()
            resourceBuffer?.start()
            fallbackGuard?.start()

            running = true
            logManager.i(TAG, "PseudoCerebellum monitor started successfully")
        }
    }

    /**
     * 通知伪小脑：即将进入场景，准备大批量加载资源
     * 游戏主动调用（本项目通过进程检测自动触发）
     *
     * 此时伪小脑会：
     * 1. 提前回收内存，为场景加载腾出空间
     * 2. 提升目标进程优先级
     * 3. 清理后台进程减少资源争抢
     */
    fun onSceneLoadPrepare() {
        if (!running || !enabled) return
        logManager.i(TAG, "Scene load prepare triggered - preparing resource buffer")

        workHandler?.post {
            // 模块3：提前隔离缓冲，为新场景资源腾出内存
            resourceBuffer?.prepareForSceneLoad()
            // 模块2：提升优先级，清理后台
            taskScheduler?.onSceneLoadPrepare()
        }
    }

    /**
     * 提交资源任务，交给伪小脑排队分流
     *
     * 注意：本项目作为系统工具，不直接拦截游戏的GPU资源提交。
     * 此接口预留用于游戏引擎集成场景。
     * 在当前系统工具架构中，分流通过系统级资源调度实现。
     *
     * @param resourceTag 资源标识（用于日志追踪）
     */
    fun enqueueGpuResourceTask(resourceTag: String) {
        if (!running || !enabled) return
        workHandler?.post {
            taskScheduler?.enqueueTask(resourceTag)
        }
    }

    /**
     * 停止伪小脑
     */
    fun stopMonitor() {
        if (!running) return
        logManager.i(TAG, "Stopping PseudoCerebellum monitor...")

        workHandler?.post {
            spikeMonitor?.stop()
            taskScheduler?.stop()
            resourceBuffer?.stop()
            fallbackGuard?.stop()

            spikeMonitor = null
            taskScheduler = null
            resourceBuffer = null
            fallbackGuard = null
            running = false

            logManager.i(TAG, "PseudoCerebellum monitor stopped. " +
                    "Stats: spikes=$spikeDetectedCount, shunts=$shuntTriggeredCount, " +
                    "fallbacks=$fallbackTriggeredCount")
        }

        // 退出工作线程
        try {
            workThread?.quitSafely()
        } catch (e: Exception) {
            // 忽略
        }
        workThread = null
        workHandler = null
    }

    /**
     * 设置模块总开关
     * @param enabled true启用 / false禁用
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        logManager.i(TAG, "PseudoCerebellum master switch: $enabled")
        if (!enabled && running) {
            stopMonitor()
        }
    }

    /**
     * 是否已启用
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 是否运行中
     */
    fun isRunning(): Boolean = running

    /**
     * 获取当前负载等级
     */
    fun getLoadLevel(): LoadLevel = currentLoadLevel

    /**
     * 获取统计数据
     */
    fun getStats(): Map<String, Any> = mapOf(
        "spike_detected" to spikeDetectedCount,
        "shunt_triggered" to shuntTriggeredCount,
        "fallback_triggered" to fallbackTriggeredCount,
        "load_level" to currentLoadLevel.name,
        "queue_size" to (taskScheduler?.getQueueSize() ?: 0),
        "buffer_occupancy" to (resourceBuffer?.getBufferOccupancy() ?: 0f)
    )

    // ==================== 内部回调 ====================

    /**
     * 模块1 → 模块2：尖峰检测回调
     */
    private fun onSpikeDetected(spikeInfo: SpikeInfo) {
        spikeDetectedCount++
        logManager.i(TAG, "Spike detected: type=${spikeInfo.type}, " +
                "gpu=${"%.1f".format(spikeInfo.gpuLoad)}%, " +
                "mem=${spikeInfo.memoryUsedMb}MB, " +
                "delta_ms=${spikeInfo.deltaMs}")

        // 发布UI状态
        bus.publish(
            EventType.UI_STATE_UPDATE,
            "pc_spike" to spikeDetectedCount,
            "pc_load_level" to currentLoadLevel.name
        )

        // 模块2：触发分流
        workHandler?.post {
            taskScheduler?.onSpikeDetected(spikeInfo)
            shuntTriggeredCount++
        }

        // 模块4：检查是否需要兜底
        workHandler?.post {
            fallbackGuard?.onSpikeDetected(spikeInfo)
        }
    }

    /**
     * 负载等级变化回调
     */
    private fun onLoadLevelChanged(level: LoadLevel) {
        currentLoadLevel = level
        logManager.i(TAG, "Load level changed: $level")

        when (level) {
            LoadLevel.WARNING -> {
                // 预警：模块3提前缓冲隔离
                workHandler?.post {
                    resourceBuffer?.onWarningLevel()
                }
            }
            LoadLevel.CRITICAL -> {
                // 临界：模块4兜底保护
                workHandler?.post {
                    fallbackGuard?.onCriticalLevel()
                }
            }
            LoadLevel.RECOVERING, LoadLevel.NORMAL -> {
                // 恢复：模块3释放缓冲
                workHandler?.post {
                    resourceBuffer?.onNormalLevel()
                }
            }
        }
    }

    /**
     * 兜底触发回调
     */
    private fun onFallbackTriggered(reason: String) {
        fallbackTriggeredCount++
        logManager.w(TAG, "Fallback triggered: $reason")
    }

    companion object {
        private const val TAG = "PseudoCerebellum"

        @Volatile
        private var instance: PseudoCerebellum? = null

        /**
         * 获取单例
         */
        fun getInstance(context: Context): PseudoCerebellum {
            return instance ?: synchronized(this) {
                instance ?: PseudoCerebellum(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 释放单例（测试用）
         */
        fun destroy() {
            instance?.stopMonitor()
            instance = null
        }
    }
}

/**
 * 尖峰信息
 */
data class SpikeInfo(
    val type: SpikeType,
    val gpuLoad: Float,
    val memoryUsedMb: Int,
    val cpuLoad: Float,
    val temperature: Float,
    val deltaMs: Long,        // 暴涨时间跨度
    val severity: Float       // 严重程度 0~1
)

/**
 * 尖峰类型
 */
enum class SpikeType {
    GPU_SPIKE,         // GPU负载脉冲
    MEMORY_SURGE,      // 内存占用暴涨
    TEMPERATURE_RUSH,  // 温度急升
    COMBINED_SPIKE     // 组合尖峰
}
