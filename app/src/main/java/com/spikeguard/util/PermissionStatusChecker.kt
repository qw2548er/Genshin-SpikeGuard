package com.spikeguard.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.spikeguard.core.PermissionMode

/**
 * 权限状态检测器
 *
 * 所有检测在后台线程执行，带超时机制，绝不阻塞主线程
 * 检测结果通过回调返回
 */
class PermissionStatusChecker(private val context: Context) {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * 权限状态
     */
    data class PermissionStatus(
        val mode: PermissionMode,
        val available: Boolean,
        val message: String
    )

    /**
     * 检查权限状态（异步，不阻塞主线程）
     * @param callback 结果回调，在主线程执行
     */
    fun checkPermissionStatus(mode: PermissionMode, callback: (PermissionStatus) -> Unit) {
        // 启动后台线程
        ensureHandlerThread()

        handler?.post {
            val status = when (mode) {
                PermissionMode.ROOT -> checkRootStatus()
                PermissionMode.SHIZUKU -> checkShizukuStatus()
                PermissionMode.NONE -> PermissionStatus(PermissionMode.NONE, true, "纯日志模式")
            }

            // 回调到主线程
            android.os.Handler(context.mainLooper).post {
                callback(status)
            }
        }
    }

    /**
     * 检查Root状态（在后台线程调用）
     */
    private fun checkRootStatus(): PermissionStatus {
        return try {
            // 轻量检查：su二进制是否存在
            val suPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/su",
                "/data/local/su"
            )

            val hasSuBinary = suPaths.any { java.io.File(it).exists() }

            if (!hasSuBinary) {
                PermissionStatus(PermissionMode.ROOT, false, "未检测到Root环境")
            } else {
                // 尝试实际执行su验证（带超时）
                val result = tryExecuteSu()
                if (result) {
                    PermissionStatus(PermissionMode.ROOT, true, "Root 权限正常")
                } else {
                    PermissionStatus(PermissionMode.ROOT, false, "Root 权限未授予或不可用")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root status check failed", e)
            PermissionStatus(PermissionMode.ROOT, false, "Root 检测异常: ${e.message}")
        }
    }

    /**
     * 尝试执行su验证（带超时）
     */
    private fun tryExecuteSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c echo root_check")
            val reader = process.inputStream.bufferedReader()
            val output = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 2000) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    output.append(line)
                } else {
                    Thread.sleep(50)
                }
            }

            process.destroy()
            output.contains("root_check")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查Shizuku状态（在后台线程调用）
     */
    private fun checkShizukuStatus(): PermissionStatus {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)

            val hasShizuku = packages.any { it.packageName == SHIZUKU_PACKAGE }
            val hasSui = packages.any { it.packageName == SUI_PACKAGE }

            if (!hasShizuku && !hasSui) {
                PermissionStatus(PermissionMode.SHIZUKU, false, "未安装 Shizuku/Sui")
            } else {
                // 检查Shizuku服务是否运行（通过ActivityManager，不需要Shizuku权限）
                val serviceRunning = isShizukuServiceRunning()
                if (serviceRunning) {
                    PermissionStatus(PermissionMode.SHIZUKU, true, "Shizuku 服务运行中")
                } else {
                    PermissionStatus(PermissionMode.SHIZUKU, false, "Shizuku 服务未启动")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku status check failed", e)
            PermissionStatus(PermissionMode.SHIZUKU, false, "Shizuku 检测异常")
        }
    }

    /**
     * 检查Shizuku服务是否运行（使用ActivityManager，不需要Root/Shizuku）
     */
    private fun isShizukuServiceRunning(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = am.getRunningServices(100)
            for (service in runningServices) {
                if (service.service.packageName == SHIZUKU_PACKAGE ||
                    service.service.packageName == SUI_PACKAGE) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            // 如果获取运行服务失败，假设服务未运行
            false
        }
    }

    private fun ensureHandlerThread() {
        if (handlerThread == null || handler == null) {
            handlerThread = HandlerThread("PermissionChecker")
            handlerThread?.start()
            handler = Handler(handlerThread!!.looper)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            // 忽略
        }
        handlerThread = null
        handler = null
    }

    companion object {
        private const val TAG = "PermissionStatusChecker"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SUI_PACKAGE = "rikka.sui"
    }
}
