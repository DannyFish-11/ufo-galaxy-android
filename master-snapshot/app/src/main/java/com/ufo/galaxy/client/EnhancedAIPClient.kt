package com.ufo.galaxy.client

import android.util.Log
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.protocol.AIPMessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 增强版 AIP 客户端
 *
 * 新增功能：
 * 1. 支持微软 Galaxy 的消息格式
 * 2. 通过 [AIPMessageBuilder] 统一消息格式转换（AIP/1.0 <-> Microsoft AIP）
 * 3. 通过 [ServerConfig] 集中管理 WebSocket 路径，支持按优先级自动回退
 * 4. 增强的能力声明
 * 5. 支持 MCP 工具注册
 */
class EnhancedAIPClient(
    private val deviceId: String,
    private val galaxyUrl: String,  // 微软 Galaxy 的 WebSocket 地址
    private val context: android.content.Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val commandExecutor = AndroidCommandExecutor(context)
    private val TAG = "EnhancedAIPClient"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isRegistered = false

    // Index into ServerConfig.WS_PATHS used for the current connection attempt
    private var wsPathIndex = 0

    // 消息类型映射：我们的格式 -> 微软格式（仅用于 Microsoft Galaxy 兼容层）
    private val typeMapping = mapOf(
        AIPMessageBuilder.MessageType.DEVICE_REGISTER to "REGISTER",
        AIPMessageBuilder.MessageType.TASK_ASSIGN     to "TASK",
        AIPMessageBuilder.MessageType.COMMAND_RESULT  to "COMMAND_RESULTS",
        "status_update"                               to "TASK_END",
        AIPMessageBuilder.MessageType.HEARTBEAT       to "HEARTBEAT"
    )

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to Microsoft UFO Galaxy.")
            this@EnhancedAIPClient.webSocket = webSocket
            reconnectJob?.cancel()
            sendEnhancedRegistration()
            startHeartbeatLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            handleMicrosoftAIPMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "Connection closing: $code / $reason")
            heartbeatJob?.cancel()
            this@EnhancedAIPClient.webSocket = null
            isRegistered = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Connection failed (path index $wsPathIndex): ${t.message}", t)
            heartbeatJob?.cancel()
            this@EnhancedAIPClient.webSocket = null
            isRegistered = false
            // Advance to the next candidate path before reconnecting
            wsPathIndex = (wsPathIndex + 1) % ServerConfig.WS_PATHS.size
            startReconnectLoop()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Connection closed: $code / $reason")
            heartbeatJob?.cancel()
            this@EnhancedAIPClient.webSocket = null
            isRegistered = false
            startReconnectLoop()
        }
    }

    fun connect() {
        val wsUrl = ServerConfig.buildWsUrl(galaxyUrl, deviceId, wsPathIndex)
        val request = Request.Builder().url(wsUrl).build()
        Log.i(TAG, "Connecting to Microsoft Galaxy at $wsUrl...")
        client.newWebSocket(request, wsListener)
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect requested")
        webSocket = null
        isRegistered = false
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive) {
                Log.i(TAG, "Attempting to reconnect in 5 seconds...")
                delay(5000)
                if (webSocket == null) {
                    connect()
                } else {
                    break
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (webSocket != null) {
                    sendHeartbeat()
                    Log.d(TAG, "Heartbeat sent.")
                } else {
                    break
                }
            }
        }
    }

    /**
     * 发送增强的注册消息（符合微软 Galaxy 的 AgentProfile 格式）
     *
     * The AIP v3 envelope (`device_id`, `device_type`, `message_id`,
     * `timestamp`, `version`) is built via [AIPMessageBuilder] first so that
     * these fields are always present and consistent.  The resulting message is
     * then converted to Microsoft Galaxy wire format via [convertToMicrosoftAIP].
     */
    private fun sendEnhancedRegistration() {
        val registrationPayload = JSONObject().apply {
            put("platform", "android")
            put("os_version", android.os.Build.VERSION.RELEASE)
            put("hardware", JSONObject().apply {
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("device", android.os.Build.DEVICE)
            })
            put("tools", JSONArray().apply {
                // Android 特有能力
                put("location")
                put("camera")
                put("sensor_data")
                put("automation")
                put("notification")
                put("sms")
                put("phone_call")
                put("contacts")
                put("calendar")
                // 增强能力
                put("voice_input")
                put("screen_capture")
                put("app_control")
            })
            put("capabilities", JSONObject().apply {
                put("nlu", false)  // Android 端不做 NLU，交给 Galaxy
                put("hardware_control", true)
                put("sensor_access", true)
                put("network_access", true)
                put("ui_automation", true)
            })
        }

        val aipMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = registrationPayload
        )

        val microsoftMessage = convertToMicrosoftAIP(aipMessage)
        webSocket?.send(microsoftMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Registration failed.")
        Log.i(TAG, "Enhanced registration message sent to Microsoft Galaxy.")
        // Send capability report after registration
        sendCapabilityReport()
    }

    /**
     * 发送能力上报（通过 [AIPMessageBuilder] 构建 capability_report 后转换为微软格式）
     *
     * Sent immediately after registration so the server CapabilityRegistry can
     * record the device's supported actions.  Payload includes `platform`,
     * `supported_actions`, and `version` as required by the AndroidBridge.
     */
    private fun sendCapabilityReport() {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", JSONArray().apply {
                put("location"); put("camera"); put("sensor_data"); put("automation")
                put("notification"); put("sms"); put("phone_call"); put("contacts")
                put("calendar"); put("voice_input"); put("screen_capture"); put("app_control")
            })
            put("version", "2.5.0")
        }
        val ourMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.CAPABILITY_REPORT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        val microsoftMessage = convertToMicrosoftAIP(ourMessage)
        webSocket?.send(microsoftMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Capability report not sent.")
        Log.i(TAG, "Capability report sent.")
    }

    /**
     * 将我们的 AIP/1.0 消息转换为微软 AIP 格式
     */
    private fun convertToMicrosoftAIP(ourMessage: JSONObject): JSONObject {
        val messageType = typeMapping[ourMessage.getString("type")] ?: "TASK"

        return JSONObject().apply {
            put("message_type", messageType)
            put("agent_id", ourMessage.optString("source_node", deviceId))
            put("session_id", ourMessage.optLong("timestamp", System.currentTimeMillis()))
            put("payload", ourMessage.getJSONObject("payload"))
        }
    }

    /**
     * 处理来自微软 Galaxy 的消息（使用 [AIPMessageBuilder] 统一解析）
     */
    private fun handleMicrosoftAIPMessage(text: String) {
        try {
            // Normalise to AIP/1.0 field names via the shared builder/parser
            val ourMessage = AIPMessageBuilder.parse(text) ?: run {
                Log.w(TAG, "Failed to parse inbound message")
                return
            }

            val msgType = ourMessage.getString("type")
            val payload = ourMessage.getJSONObject("payload")

            when (msgType) {
                "command" -> {
                    val command = payload.optString("command", payload.optString("action"))
                    val params = payload.optJSONObject("params") ?: JSONObject()
                    Log.i(TAG, "Executing command from Galaxy: $command")

                    val result = executeAndroidCommand(command, params)
                    sendCommandResult(command, result)
                }
                "heartbeat" -> {
                    sendHeartbeat()
                }
                else -> Log.w(TAG, "Unhandled message type: $msgType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Microsoft AIP message: ${e.message}", e)
        }
    }

    /**
     * 执行 Android 命令
     */
    private fun executeAndroidCommand(command: String, params: JSONObject): JSONObject {
        return commandExecutor.executeCommand(command, params)
    }

    /**
     * 发送命令执行结果（通过 [AIPMessageBuilder] 构建 AIP/1.0 消息后转换为微软格式）
     */
    private fun sendCommandResult(command: String, result: JSONObject) {
        val ourMessage = AIPMessageBuilder.build(
            messageType = "command_result",
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = result
        )

        val microsoftMessage = convertToMicrosoftAIP(ourMessage)
        webSocket?.send(microsoftMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Result not sent.")
    }

    /**
     * 发送心跳
     *
     * The AIP/1.0 envelope is built via [AIPMessageBuilder] first, then
     * converted to Microsoft Galaxy format so that `message_id`, `timestamp`,
     * and other v3-compatible fields are always present and consistent.
     */
    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("status", "online")
        }
        val ourMessage = AIPMessageBuilder.build(
            messageType = "heartbeat",
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        val microsoftMessage = convertToMicrosoftAIP(ourMessage)
        webSocket?.send(microsoftMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Heartbeat not sent.")
    }

    /**
     * 发送自定义消息（通过 [AIPMessageBuilder] 构建后自动转换为微软格式）
     */
    fun sendMessage(messageType: String, payload: JSONObject) {
        val ourMessage = AIPMessageBuilder.build(
            messageType = messageType,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )

        val microsoftMessage = convertToMicrosoftAIP(ourMessage)
        webSocket?.send(microsoftMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Message not sent.")
    }
}
