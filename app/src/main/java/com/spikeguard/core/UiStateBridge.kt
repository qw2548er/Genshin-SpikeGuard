package com.spikeguard.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * UI状态跨进程广播器
 *
 * 由于GuardService运行在独立进程:guard，而MainActivity/FloatingWindow在主进程，
 * MessageBus是进程内单例，无法跨进程通信。
 *
 * 此桥接器监听服务进程的MessageBus事件，转化为全局Broadcast发送到主进程UI。
 *
 * 广播Action: "com.spikeguard.action.UI_STATE_UPDATE"
 * 数据通过Intent extras传递
 */
class UiStateBridge(private val context: Context) {

    private val bus = MessageBus.getInstance()

    /**
     * 启动桥接：订阅服务进程事件，转为广播发出
     */
    fun start() {
        // 转发UI状态更新
        bus.subscribe(EventType.UI_STATE_UPDATE) { event ->
            broadcastUiState(event.data)
        }

        // 转发服务启动事件
        bus.subscribe(EventType.SERVICE_STARTED) { event ->
            broadcastServiceEvent("started", event.data)
        }

        // 转发服务停止事件
        bus.subscribe(EventType.SERVICE_STOPPED) { event ->
            broadcastServiceEvent("stopped", event.data)
        }

        // 转发保护触发事件
        bus.subscribe(EventType.PROTECTION_TRIGGERED) { event ->
            broadcastProtectionEvent("triggered", event.data)
        }

        // 转发保护解除事件
        bus.subscribe(EventType.PROTECTION_RELEASED) { event ->
            broadcastProtectionEvent("released", event.data)
        }

        // 转发静默模式变化
        bus.subscribe(EventType.SILENT_MODE_CHANGED) { event ->
            broadcastSilentModeEvent(event.data)
        }

        // 转发模式变化
        bus.subscribe(EventType.MODE_CHANGED) { event ->
            broadcastModeEvent(event.data)
        }

        Log.i(TAG, "UiStateBridge started - cross-process broadcast enabled")
    }

    /**
     * 停止桥接
     */
    fun stop() {
        // MessageBus的subscribe无法单独取消，这里只是标记
        Log.i(TAG, "UiStateBridge stopped")
    }

    /**
     * 广播UI状态更新
     */
    private fun broadcastUiState(data: Map<String, Any>) {
        try {
            val intent = Intent(ACTION_UI_STATE_UPDATE).apply {
                setPackage(context.packageName)
                // 逐个添加基本类型
                data.forEach { (key, value) ->
                    when (value) {
                        is Int -> putExtra(key, value)
                        is Float -> putExtra(key, value)
                        is Long -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                        is String -> putExtra(key, value)
                        is Double -> putExtra(key, value.toFloat())
                    }
                }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast UI state", e)
        }
    }

    /**
     * 广播服务事件
     */
    private fun broadcastServiceEvent(type: String, data: Map<String, Any>) {
        try {
            val intent = Intent(ACTION_SERVICE_EVENT).apply {
                setPackage(context.packageName)
                putExtra("event_type", type)
                data.forEach { (key, value) ->
                    when (value) {
                        is Int -> putExtra(key, value)
                        is Float -> putExtra(key, value)
                        is Long -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                        is String -> putExtra(key, value)
                    }
                }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast service event", e)
        }
    }

    /**
     * 广播保护事件
     */
    private fun broadcastProtectionEvent(type: String, data: Map<String, Any>) {
        try {
            val intent = Intent(ACTION_PROTECTION_EVENT).apply {
                setPackage(context.packageName)
                putExtra("event_type", type)
                data.forEach { (key, value) ->
                    when (value) {
                        is Int -> putExtra(key, value)
                        is Float -> putExtra(key, value)
                        is Long -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                        is String -> putExtra(key, value)
                    }
                }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast protection event", e)
        }
    }

    /**
     * 广播静默模式变化
     */
    private fun broadcastSilentModeEvent(data: Map<String, Any>) {
        try {
            val intent = Intent(ACTION_SILENT_MODE_CHANGED).apply {
                setPackage(context.packageName)
                data.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putExtra(key, value)
                        is Long -> putExtra(key, value)
                        is String -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                    }
                }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast silent mode event", e)
        }
    }

    /**
     * 广播模式变化
     */
    private fun broadcastModeEvent(data: Map<String, Any>) {
        try {
            val intent = Intent(ACTION_MODE_CHANGED).apply {
                setPackage(context.packageName)
                data.forEach { (key, value) ->
                    when (value) {
                        is String -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                    }
                }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast mode event", e)
        }
    }

    companion object {
        private const val TAG = "UiStateBridge"

        const val ACTION_UI_STATE_UPDATE = "com.spikeguard.action.UI_STATE_UPDATE"
        const val ACTION_SERVICE_EVENT = "com.spikeguard.action.SERVICE_EVENT"
        const val ACTION_PROTECTION_EVENT = "com.spikeguard.action.PROTECTION_EVENT"
        const val ACTION_SILENT_MODE_CHANGED = "com.spikeguard.action.SILENT_MODE_CHANGED"
        const val ACTION_MODE_CHANGED = "com.spikeguard.action.MODE_CHANGED"
    }
}
