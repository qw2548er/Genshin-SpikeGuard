package com.spikeguard.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志管理器
 *
 * 支持：
 * 1. 分级日志（DEBUG/INFO/WARN/ERROR）
 * 2. 文件日志记录
 * 3. 日志大小限制和轮转
 * 4. 包含性能指标记录
 */
class LogManager private constructor(private val context: Context) {

    private var logToFile = true
    private var maxLogSizeMb = 50
    private var logLevel = LogLevel.INFO

    private var currentLogFile: File? = null
    private var initialized = AtomicBoolean(false)

    enum class LogLevel(val value: Int) {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3)
    }

    fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            try {
                val logDir = File(context.filesDir, "logs")
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                // 检查现有日志文件大小
                rotateLogIfNeeded(logDir)

                val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                val dateStr = dateFormat.format(Date())
                currentLogFile = File(logDir, "spikeguard_$dateStr.log")

                Log.i(TAG, "LogManager initialized: ${currentLogFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LogManager", e)
            }
        }
    }

    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }

    fun setLogToFile(enabled: Boolean) {
        logToFile = enabled
    }

    fun d(tag: String, message: String) {
        if (logLevel.value <= LogLevel.DEBUG.value) {
            Log.d(tag, message)
            writeToFile("D", tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (logLevel.value <= LogLevel.INFO.value) {
            Log.i(tag, message)
            writeToFile("I", tag, message)
        }
    }

    fun w(tag: String, message: String) {
        if (logLevel.value <= LogLevel.WARN.value) {
            Log.w(tag, message)
            writeToFile("W", tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (logLevel.value <= LogLevel.ERROR.value) {
            Log.e(tag, message, throwable)
            val fullMessage = if (throwable != null) {
                "$message\n${android.util.Log.getStackTraceString(throwable)}"
            } else {
                message
            }
            writeToFile("E", tag, fullMessage)
        }
    }

    /**
     * 记录性能指标
     */
    fun logMetrics(type: String, data: Map<String, Any>) {
        val dataStr = data.entries.joinToString(", ") { "${it.key}=${it.value}" }
        i("Metrics", "[$type] $dataStr")
    }

    private fun writeToFile(level: String, tag: String, message: String) {
        if (!logToFile || currentLogFile == null) return

        try {
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val timestamp = timeFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $message\n"

            // 检查日志大小
            checkLogSize()

            currentLogFile?.appendText(logLine)
        } catch (e: Exception) {
            // 日志写入失败不应该影响主流程
        }
    }

    private fun checkLogSize() {
        val file = currentLogFile ?: return
        if (file.exists()) {
            val sizeMb = file.length() / (1024 * 1024)
            if (sizeMb >= maxLogSizeMb) {
                // 重命名旧文件
                val backupFile = File(file.parent, file.name + ".old")
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                file.renameTo(backupFile)

                // 创建新文件
                file.createNewFile()
            }
        }
    }

    private fun rotateLogIfNeeded(logDir: File) {
        try {
            val logFiles = logDir.listFiles { _, name ->
                name.startsWith("spikeguard_") && name.endsWith(".log")
            } ?: return

            // 保留最近7天的日志
            val maxAge = 7 * 24 * 60 * 60 * 1000L
            val now = System.currentTimeMillis()

            logFiles.forEach { file ->
                if (now - file.lastModified() > maxAge) {
                    file.delete()
                    Log.i(TAG, "Deleted old log file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
        }
    }

    /**
     * 获取日志文件列表
     */
    fun getLogFiles(): List<File> {
        val logDir = File(context.filesDir, "logs")
        return logDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        try {
            val logDir = File(context.filesDir, "logs")
            logDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }

    companion object {
        private const val TAG = "LogManager"

        @Volatile
        private var instance: LogManager? = null

        fun getInstance(context: Context): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
