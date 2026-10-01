package com.spikeguard.pseudocerebellum

import android.content.Context
import android.os.Handler
import com.spikeguard.util.LogManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块3：资源隔离缓冲模块
 *
 * 职责：
 * 1. 在APK内存空间开辟独立缓冲池，预加载资源放到缓冲池，不直接一次性送入GPU
 * 2. 资源隔离：新场景加载资源，和当前正在渲染的画面资源做内存隔离，避免互相抢占内存
 * 3. 资源复用管理：已经加载过的贴图资源缓存，避免重复加载造成二次冲击
 *
 * 系统级实现方式（不注入游戏进程）：
 * - 通过 drop_caches + compact_memory 为前台游戏预腾出连续内存空间
 * - 通过清理后台进程隔离内存占用
 * - 通过 swappiness 调节减少内存抖动
 * - 通过 min_free_kbytes 调节最低空闲内存水位
 * - 资源复用：跟踪内存压力周期，在低压力期提前回收缓存
 *
 * 重要：不删除任何游戏资源，只是在系统层面管理内存分配
 */
class ResourceBuffer(
    private val context: Context,
    private val handler: Handler
) {

    private val logManager = LogManager.getInstance(context)

    @Volatile private var running = false
    private val bufferPrepared = AtomicBoolean(false)

    // 缓冲池占用率（0~1）
    @Volatile private var bufferOccupancy = 0f

    // 资源复用缓存（系统级：已加载资源的内存区域标记）
    private val resourceCache = mutableMapOf<String, Long>()  // tag -> timestamp
    private val maxCacheSize = 50

    // 内存水位参数
    private var originalMinFreeKbytes: String? = null
    private var originalSwappiness: String? = null

    /**
     * 启动缓冲模块
     */
    fun start() {
        if (running) return
        running = true
        logManager.i(TAG, "ResourceBuffer started")
    }

    /**
     * 停止缓冲模块
     */
    fun stop() {
        running = false
        // 恢复原始内存参数
        restoreMemoryParams()
        resourceCache.clear()
        bufferOccupancy = 0f
        logManager.i(TAG, "ResourceBuffer stopped")
    }

    /**
     * 场景加载前准备缓冲
     *
     * 核心逻辑：
     * 1. 保存原始内存参数
     * 2. 调节 min_free_kbytes 提高最低空闲内存水位
     * 3. 调节 swappiness 减少swap抖动
     * 4. drop_caches + compact_memory 腾出连续空间
     * 5. 清理后台进程隔离内存
     *
     * 这样新场景加载时，有充足的连续内存可用，避免内存碎片导致的贴图加载失败
     */
    fun prepareForSceneLoad() {
        if (!running) return
        logManager.i(TAG, "Preparing resource buffer for scene load...")

        handler.post {
            try {
                // 步骤1：保存原始参数
                saveOriginalParams()

                // 步骤2：提高最低空闲内存水位（刚性保护）
                // 让系统保留更多空闲内存，避免场景加载时内存不足
                executeRootCommand("echo 8192 > /proc/sys/vm/min_free_kbytes")

                // 步骤3：降低swappiness（减少swap，优先使用物理内存）
                executeRootCommand("echo 10 > /proc/sys/vm/swappiness")

                // 步骤4：回收缓存并压缩内存（柔性缓冲）
                // 分批执行，错开系统调用峰值
                executeRootCommand("echo 3 > /proc/sys/vm/drop_caches")
                Thread.sleep(100)
                executeRootCommand("echo 1 > /proc/sys/vm/compact_memory")

                // 步骤5：清理后台进程，隔离内存占用
                executeRootCommand("am kill-all background")

                bufferPrepared.set(true)
                bufferOccupancy = 0f

                logManager.i(TAG, "Resource buffer prepared: min_free_kbytes=8192, " +
                        "swappiness=10, caches dropped, background killed")
            } catch (e: Exception) {
                logManager.e(TAG, "Failed to prepare buffer", e)
            }
        }
    }

    /**
     * 预警等级回调：提前缓冲隔离
     */
    fun onWarningLevel() {
        if (!running) return
        logManager.i(TAG, "Warning level - pre-emptive buffer isolation")

        handler.post {
            try {
                // 轻量级缓冲：只回收页缓存，不清理后台（避免过度干预）
                executeRootCommand("echo 1 > /proc/sys/vm/drop_caches")
                executeRootCommand("echo 1 > /proc/sys/vm/compact_memory")
                logManager.i(TAG, "Light buffer isolation done")
            } catch (e: Exception) {
                logManager.e(TAG, "Warning level buffer failed", e)
            }
        }
    }

    /**
     * 正常等级回调：释放缓冲，恢复正常
     */
    fun onNormalLevel() {
        if (!running) return
        if (!bufferPrepared.get()) return

        logManager.i(TAG, "Normal level - releasing buffer, restoring params")
        handler.post {
            restoreMemoryParams()
            bufferPrepared.set(false)
        }
    }

    /**
     * 标记资源已加载（资源复用管理）
     * 避免重复加载造成二次冲击
     */
    fun markResourceLoaded(tag: String) {
        if (resourceCache.size >= maxCacheSize) {
            // 移除最旧的
            val oldest = resourceCache.entries.minByOrNull { it.value }
            oldest?.let { resourceCache.remove(it.key) }
        }
        resourceCache[tag] = System.currentTimeMillis()
    }

    /**
     * 检查资源是否已加载（避免重复加载）
     */
    fun isResourceCached(tag: String): Boolean {
        return resourceCache.containsKey(tag)
    }

    /**
     * 获取缓冲池占用率
     */
    fun getBufferOccupancy(): Float {
        // 根据系统可用内存计算占用率
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            val usedRatio = 1f - (mi.availMem.toFloat() / mi.totalMem.toFloat())
            bufferOccupancy = usedRatio
            usedRatio
        } catch (e: Exception) {
            bufferOccupancy
        }
    }

    /**
     * 保存原始内存参数
     */
    private fun saveOriginalParams() {
        try {
            if (originalMinFreeKbytes == null) {
                originalMinFreeKbytes = readSysfs("/proc/sys/vm/min_free_kbytes")
            }
            if (originalSwappiness == null) {
                originalSwappiness = readSysfs("/proc/sys/vm/swappiness")
            }
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to save original params", e)
        }
    }

    /**
     * 恢复原始内存参数
     */
    private fun restoreMemoryParams() {
        try {
            originalMinFreeKbytes?.let {
                executeRootCommand("echo $it > /proc/sys/vm/min_free_kbytes")
            }
            originalSwappiness?.let {
                executeRootCommand("echo $it > /proc/sys/vm/swappiness")
            }
            logManager.i(TAG, "Memory params restored: min_free=$originalMinFreeKbytes, " +
                    "swappiness=$originalSwappiness")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to restore memory params", e)
        }
    }

    /**
     * 读取sysfs文件
     */
    private fun readSysfs(path: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("su -c cat $path")
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 执行Root命令（带异常捕获，不传播崩溃）
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

    companion object {
        private const val TAG = "PC_ResourceBuffer"
    }
}
