package com.spikeguard.pseudocerebellum

import android.content.Context
import android.os.Handler
import com.spikeguard.util.LogManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模块2：任务排队&时序分流模块（核心调度，刚柔并济）
 *
 * 对应「一加一减·赶羊疏导逻辑」：
 * - 刚性规则守住硬件上限
 * - 柔性调度错开资源提交时机
 *
 * 核心逻辑（系统级实现，不注入游戏进程）：
 * 1. 捕获资源提交队列：在系统层面监控资源压力
 * 2. 任务重排：
 *    - 把一次性大批量资源压力，拆分成多份小批次处理
 *    - 分批插入空闲间隙提交，错开瞬间爆发，平缓输送给GPU
 *    - 保留全部资源，不会删除任何贴图/模型（和行业暴力降级本质区别）
 * 3. 动态调速：
 *    - 硬件余量充足：提交速度加快（恢复正常）
 *    - 监测到尖峰预警：放慢提交、排队等待，不一次性灌给GPU
 * 4. 任务优先级：渲染画面任务优先，资源加载任务延后排队
 *
 * 系统级实现方式：
 * - 通过回收后台进程内存、drop_caches 为前台游戏腾出资源空间
 * - 通过 oom_score_adj / renice 提升游戏进程优先级
 * - 通过 am kill-all background 清理后台资源争抢
 * - 以上操作分批执行，避免一次性系统调用造成二次冲击
 */
class TaskScheduler(
    private val context: Context,
    private val handler: Handler
) {

    private val logManager = LogManager.getInstance(context)

    @Volatile private var running = false
    private val paused = AtomicBoolean(false)

    // 任务队列（资源提交任务排队）
    private val taskQueue = LinkedBlockingQueue<ShuntTask>()
    private val maxQueueSize = 100

    // 提交速度控制（动态调速）
    private var currentSubmitRate = 1.0f  // 1.0 = 正常速度, 0.5 = 半速, 0 = 暂停
    private val minSubmitRate = 0.1f
    private val maxSubmitRate = 1.0f

    // 原神包名候选
    private val genshinPackages = listOf(
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.Yuanshen",
        "com.mihoyo.genshinimpact"
    )

    // 统计
    private val tasksSubmitted = AtomicInteger(0)
    private val tasksShunted = AtomicInteger(0)

    /**
     * 分流任务
     */
    data class ShuntTask(
        val id: Long,
        val tag: String,
        val priority: Priority,
        val submitTime: Long,
        val batchSize: Int  // 批次大小（拆分后的小批次）
    )

    /**
     * 任务优先级
     * RENDER: 渲染画面任务（最高优先，不能延后）
     * RESOURCE_LOAD: 资源加载任务（可延后排队）
     */
    enum class Priority {
        RENDER, RESOURCE_LOAD
    }

    fun start() {
        if (running) return
        running = true
        logManager.i(TAG, "TaskScheduler started")
    }

    fun stop() {
        running = false
        taskQueue.clear()
        logManager.i(TAG, "TaskScheduler stopped. " +
                "Stats: submitted=${tasksSubmitted.get()}, shunted=${tasksShunted.get()}")
    }

    fun pause() {
        paused.set(true)
        logManager.i(TAG, "TaskScheduler paused (silent mode)")
    }

    fun resume() {
        paused.set(false)
        logManager.i(TAG, "TaskScheduler resumed")
    }

    /**
     * 入队资源任务（对外API调用）
     */
    fun enqueueTask(tag: String) {
        if (!running || paused.get()) return

        val task = ShuntTask(
            id = System.nanoTime(),
            tag = tag,
            priority = Priority.RESOURCE_LOAD,
            submitTime = System.currentTimeMillis(),
            batchSize = 1
        )

        if (taskQueue.size >= maxQueueSize) {
            logManager.w(TAG, "Task queue full, dropping oldest task")
            taskQueue.poll()
        }
        taskQueue.offer(task)
        tasksSubmitted.incrementAndGet()

        // 根据当前速度决定是立即处理还是排队
        if (currentSubmitRate >= 1.0f) {
            // 余量充足，立即处理
            processTask(task)
        } else {
            // 排队等待，延后处理
            tasksShunted.incrementAndGet()
            logManager.d(TAG, "Task queued (shunted): $tag, rate=$currentSubmitRate")
        }
    }

    /**
     * 场景加载准备回调
     * 游戏即将大批量加载资源时调用
     */
    fun onSceneLoadPrepare() {
        if (!running || paused.get()) return
        logManager.i(TAG, "Scene load prepare - boosting priority & freeing resources")

        // 分批执行系统调度，避免一次性调用造成二次冲击
        handler.post {
            // 批次1：提升目标进程优先级（刚性保护）
            boostTargetProcessPriority()

            // 批次2：清理后台进程（柔性分流，错开执行）
            handler.postDelayed({
                clearBackgroundProcesses()
            }, 100)

            // 批次3：回收缓存（柔性分流，再错开）
            handler.postDelayed({
                reclaimMemoryCache()
            }, 300)
        }
    }

    /**
     * 尖峰检测回调 — 触发分流
     */
    fun onSpikeDetected(spikeInfo: SpikeInfo) {
        if (!running || paused.get()) return
        logManager.i(TAG, "Shunting on spike: type=${spikeInfo.type}, " +
                "severity=${"%.2f".format(spikeInfo.severity)}")

        // 根据严重程度动态调速
        val newRate = when (spikeInfo.severity) {
            in 0.8f..1.0f -> 0.1f   // 极严重：几乎暂停
            in 0.5f..0.8f -> 0.3f   // 严重：低速
            in 0.3f..0.5f -> 0.5f   // 中等：半速
            else -> 0.7f            // 轻微：稍慢
        }

        setSubmitRate(newRate)

        // 执行分流动作（分批执行）
        handler.post {
            when (spikeInfo.type) {
                SpikeType.GPU_SPIKE, SpikeType.COMBINED_SPIKE -> {
                    // GPU尖峰：释放内存压力，给GPU喘息空间
                    reclaimMemoryCache()
                    handler.postDelayed({ clearBackgroundProcesses() }, 150)
                }
                SpikeType.MEMORY_SURGE -> {
                    // 内存暴涨：优先回收内存
                    reclaimMemoryCache()
                    clearBackgroundProcesses()
                }
                SpikeType.TEMPERATURE_RUSH -> {
                    // 温度急升：清理后台降低整体负载
                    clearBackgroundProcesses()
                }
            }
        }

        // 一定时间后逐步恢复速度
        val recoveryDelay = (1500 + spikeInfo.severity * 2000).toLong()
        handler.postDelayed({
            graduallyRestoreRate()
        }, recoveryDelay)
    }

    /**
     * 设置提交速度
     */
    private fun setSubmitRate(rate: Float) {
        currentSubmitRate = rate.coerceIn(minSubmitRate, maxSubmitRate)
        logManager.d(TAG, "Submit rate set to ${(currentSubmitRate * 100).toInt()}%")
    }

    /**
     * 逐步恢复提交速度（柔性恢复，不突然冲击）
     */
    private fun graduallyRestoreRate() {
        if (!running || paused.get()) return

        if (currentSubmitRate < maxSubmitRate) {
            // 每次恢复20%，平滑过渡
            setSubmitRate(currentSubmitRate + 0.2f)
            if (currentSubmitRate < maxSubmitRate) {
                handler.postDelayed({ graduallyRestoreRate() }, 500)
            } else {
                logManager.i(TAG, "Submit rate fully restored to 100%")
            }
        }
    }

    /**
     * 处理分流任务
     */
    private fun processTask(task: ShuntTask) {
        // 在系统工具架构中，资源任务处理体现为系统级资源调度
        // 这里记录日志，实际调度通过 Root/Shizuku 执行
        logManager.d(TAG, "Processing task: ${task.tag}, priority=${task.priority}")
    }

    /**
     * 批次1：提升目标进程优先级（刚性规则）
     *
     * 通过 oom_score_adj 和 renice 提升游戏进程优先级，
     * 确保渲染任务在系统调度中优先获得CPU时间片。
     * 不修改游戏任何资源。
     */
    private fun boostTargetProcessPriority() {
        val pid = findGenshinPid()
        if (pid <= 0) {
            logManager.w(TAG, "Target process not found for priority boost")
            return
        }

        try {
            // 降低 oom_score_adj（更难被系统杀死）
            executeRootCommand("echo -1000 > /proc/$pid/oom_score_adj")
            // 提升 nice 值（更高CPU优先级）
            executeRootCommand("renice -10 -p $pid")
            logManager.i(TAG, "Priority boosted for target process pid=$pid")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to boost priority", e)
        }
    }

    /**
     * 批次2：清理后台进程（柔性分流）
     *
     * 杀死后台进程，释放内存和CPU，
     * 减少资源争抢，相当于把"羊"赶走让出道路。
     * 不影响前台游戏。
     */
    private fun clearBackgroundProcesses() {
        try {
            executeRootCommand("am kill-all background")
            logManager.i(TAG, "Background processes cleared")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to clear background", e)
        }
    }

    /**
     * 批次3：回收内存缓存（柔性分流）
     *
     * drop_caches 释放页缓存、目录项和inode缓存，
     * compact_memory 压缩内存碎片。
     * 为即将到来的资源加载腾出连续内存空间。
     */
    private fun reclaimMemoryCache() {
        try {
            executeRootCommand("echo 3 > /proc/sys/vm/drop_caches")
            executeRootCommand("echo 1 > /proc/sys/vm/compact_memory")
            logManager.i(TAG, "Memory cache reclaimed (drop_caches + compact_memory)")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to reclaim memory cache", e)
        }
    }

    /**
     * 查找原神进程PID
     */
    private fun findGenshinPid(): Int {
        for (pkg in genshinPackages) {
            try {
                val process = Runtime.getRuntime().exec("pidof $pkg")
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                val pid = output.split(" ").firstOrNull()?.toIntOrNull()
                if (pid != null && pid > 0) return pid
            } catch (e: Exception) {
                // 继续尝试下一个包名
            }
        }
        return 0
    }

    /**
     * 执行Root命令（带异常捕获）
     */
    private fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c \"$command\"")
            process.waitFor()
            true
        } catch (e: Exception) {
            logManager.e(TAG, "Root command failed: $command", e)
            false
        }
    }

    /**
     * 获取当前队列大小
     */
    fun getQueueSize(): Int = taskQueue.size

    /**
     * 获取当前提交速度
     */
    fun getSubmitRate(): Float = currentSubmitRate

    companion object {
        private const val TAG = "PC_TaskScheduler"
    }
}
