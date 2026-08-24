package com.spikeguard.core

import android.os.Handler
import android.os.Looper
import android.os.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 消息队列总线
 * 解势化架构核心：各模块通过消息队列异步通信，完全隔离
 *
 * 设计原则：
 * 1. 发布者与订阅者完全解耦
 * 2. 事件处理异步执行，不阻塞发布线程
 * 3. 每个模块独立处理消息，异常不影响其他模块
 * 4. 支持按事件类型订阅
 */
class MessageBus private constructor() {

    private val subscribers = ConcurrentHashMap<EventType, CopyOnWriteArrayList<(GuardEvent) -> Unit>>()
    private val eventQueue = LinkedBlockingQueue<GuardEvent>(1024)
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    // 后台处理线程
    private var dispatcherThread: Thread? = null

    /**
     * 启动消息总线
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            dispatcherThread = Thread({ dispatchLoop() }, "MessageBus-Dispatcher").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 停止消息总线
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            eventQueue.clear()
            dispatcherThread?.interrupt()
            dispatcherThread = null
        }
    }

    /**
     * 发布事件
     */
    fun publish(event: GuardEvent) {
        if (!running.get()) return
        // 如果队列满了，丢弃最旧的事件（保护服务自身稳定性）
        if (eventQueue.size >= 1000) {
            eventQueue.poll()
        }
        eventQueue.offer(event)
    }

    /**
     * 便捷发布方法
     */
    fun publish(type: EventType, vararg pairs: Pair<String, Any>) {
        publish(GuardEvent.create(type, *pairs))
    }

    /**
     * 订阅事件
     */
    fun subscribe(type: EventType, listener: (GuardEvent) -> Unit) {
        subscribers.getOrPut(type) { CopyOnWriteArrayList() }.add(listener)
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(type: EventType, listener: (GuardEvent) -> Unit) {
        subscribers[type]?.remove(listener)
    }

    /**
     * 调度循环
     */
    private fun dispatchLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val event = eventQueue.take()
                dispatchEvent(event)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // 异常不终止总线
                android.util.Log.e(TAG, "Dispatch error", e)
            }
        }
    }

    /**
     * 分发事件到订阅者
     */
    private fun dispatchEvent(event: GuardEvent) {
        val listeners = subscribers[event.type] ?: return
        CoroutineScope(Dispatchers.Default).launch {
            listeners.forEach { listener ->
                try {
                    // UI事件在主线程回调
                    if (isUiEvent(event.type)) {
                        handler.post { listener(event) }
                    } else {
                        listener(event)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Subscriber error for ${event.type}", e)
                    // 单个订阅者异常不影响其他订阅者
                }
            }
        }
    }

    private fun isUiEvent(type: EventType): Boolean {
        return type == EventType.UI_STATE_UPDATE ||
                type == EventType.SCENE_CHANGED ||
                type == EventType.RISK_LEVEL_CHANGED ||
                type == EventType.SERVICE_STARTED ||
                type == EventType.SERVICE_STOPPED ||
                type == EventType.MODE_CHANGED
    }

    companion object {
        private const val TAG = "MessageBus"

        @Volatile
        private var instance: MessageBus? = null

        fun getInstance(): MessageBus {
            return instance ?: synchronized(this) {
                instance ?: MessageBus().also { instance = it }
            }
        }
    }
}
