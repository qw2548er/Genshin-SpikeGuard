package com.spikeguard.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spikeguard.service.GuardService

/**
 * 开机自启动接收器
 *
 * 设备重启后自动启动守护服务
 * 可在设置中关闭
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed received")

            // 检查是否启用自启动
            val prefs = context.getSharedPreferences("spikeguard_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", false)

            if (autoStart) {
                Log.i(TAG, "Auto start enabled, starting GuardService")
                try {
                    val serviceIntent = Intent(context, GuardService::class.java).apply {
                        action = GuardService.ACTION_START
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot", e)
                }
            } else {
                Log.i(TAG, "Auto start disabled")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
