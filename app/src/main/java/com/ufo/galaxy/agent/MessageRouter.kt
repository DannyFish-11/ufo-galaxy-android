package com.ufo.galaxy.agent

import android.content.Context
import android.util.Log
import com.ufo.galaxy.config.AppSettings
import com.ufo.galaxy.network.GalaxyWebSocketClient

/**
 * 统一消息路由器（MessageRouter）
 *
 * 所有来自 MainActivity 和 EnhancedFloatingService 的用户输入均必须经过此路由器。
 * 路由规则：
 * - [AppSettings.crossDeviceEnabled] = true  → 通过 [GalaxyWebSocketClient] 发送
 *   TaskSubmitPayload（AIP TASK_SUBMIT）到 Gateway（跨设备链路）
 * - [AppSettings.crossDeviceEnabled] = false → 调用 [GalaxyAgentV2.sendCommand]
 *   走本地执行链路（Node50 / 本地 pipeline）
 *
 * 使用示例：
 * ```kotlin
 * val router = MessageRouter(context, wsClient, localAgent)
 * router.route("打开微信", source = "text_input")
 * ```
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class MessageRouter(
    private val context: Context,
    private val wsClient: GalaxyWebSocketClient,
    private val localAgent: GalaxyAgentV2
) {

    private val TAG = "MessageRouter"
    private val settings by lazy { AppSettings(context) }

    /**
     * 将用户输入路由到正确的执行链路。
     *
     * @param text   用户输入的自然语言文本
     * @param source 来源标识（默认 "text_input"，语音时传 "voice_input"）
     */
    fun route(text: String, source: String = "text_input") {
        if (text.isBlank()) {
            Log.w(TAG, "route() 忽略空输入")
            return
        }

        if (settings.crossDeviceEnabled) {
            Log.i(TAG, "📡 跨设备模式 → sendTaskSubmit: $text")
            val sent = wsClient.sendTaskSubmit(text, source)
            if (!sent) {
                Log.w(TAG, "⚠️ WebSocket 未连接，任务提交失败（跨设备模式）。如需本地执行，请关闭跨设备开关。")
            }
        } else {
            Log.i(TAG, "🏠 本地模式 → localAgent.sendCommand: $text")
            localAgent.sendCommand(text)
        }
    }
}
