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
 * 1. 通过 [AIPMessageBuilder] 统一消息格式构建，所有出站消息强制使用 v3 信封
 *    （version="3.0", protocol="AIP/1.0"）和 v3 消息类型名称。
 * 2. 通过 [ServerConfig] 集中管理 WebSocket 路径，支持按优先级自动回退
 * 3. 增强的能力声明
 * 4. 支持 MCP 工具注册
 * 5. Legacy 消息类型输入通过 [AIPMessageBuilder.toV3Type] 自动规范化为 v3 名称。
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

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to UFO Galaxy.")
            this@EnhancedAIPClient.webSocket = webSocket
            reconnectJob?.cancel()
            sendEnhancedRegistration()
            startHeartbeatLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            handleInboundMessage(text)
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
        Log.i(TAG, "Connecting to Galaxy at $wsUrl...")
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
     * 发送增强的注册消息
     *
     * Builds a v3 AIP envelope via [AIPMessageBuilder] with type
     * [AIPMessageBuilder.MessageType.DEVICE_REGISTER] and sends it directly
     * over the WebSocket.  The v3 envelope fields (`protocol`, `version`,
     * `device_id`, `device_type`, `message_id`, `timestamp`) are always
     * present in the wire message.
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

        webSocket?.send(aipMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Registration failed.")
        Log.i(TAG, "Enhanced registration message sent.")
        // Send capability report after registration
        sendCapabilityReport()
    }

    /**
     * 发送能力上报（通过 [AIPMessageBuilder.buildCapabilityReport] 构建并直接发送 v3 消息）
     *
     * Sent immediately after registration.  Payload includes `platform`,
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
        val aipMessage = AIPMessageBuilder.buildCapabilityReport(
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        webSocket?.send(aipMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Capability report not sent.")
        Log.i(TAG, "Capability report sent.")
    }

    /**
     * 处理来自服务端的消息（使用 [AIPMessageBuilder] 统一解析）
     */
    private fun handleInboundMessage(text: String) {
        try {
            // Normalise to AIP/1.0 field names via the shared builder/parser
            val ourMessage = AIPMessageBuilder.parse(text) ?: run {
                Log.w(TAG, "Failed to parse inbound message")
                return
            }

            val msgType = ourMessage.getString("type")
            // Normalise inbound legacy type names to v3 so the when branches only
            // need to handle authoritative v3 type strings.
            val v3MsgType = AIPMessageBuilder.toV3Type(msgType)
            val payload = ourMessage.getJSONObject("payload")

            when (v3MsgType) {
                AIPMessageBuilder.MessageType.TASK_ASSIGN -> {
                    val command = payload.optString("command", payload.optString("action"))
                    val params = payload.optJSONObject("params") ?: JSONObject()
                    Log.i(TAG, "Executing command from Galaxy: $command")

                    val result = executeAndroidCommand(command, params)
                    sendCommandResult(command, result)
                }
                AIPMessageBuilder.MessageType.HEARTBEAT -> {
                    sendHeartbeat()
                }
                else -> Log.w(TAG, "Unhandled message type: $msgType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling inbound message: ${e.message}", e)
        }
    }

    /**
     * 执行 Android 命令
     */
    private fun executeAndroidCommand(command: String, params: JSONObject): JSONObject {
        return commandExecutor.executeCommand(command, params)
    }

    /**
     * 发送命令执行结果（v3 type: [AIPMessageBuilder.MessageType.COMMAND_RESULT]）
     */
    private fun sendCommandResult(command: String, result: JSONObject) {
        val aipMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.COMMAND_RESULT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = result
        )
        webSocket?.send(aipMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Result not sent.")
    }

    /**
     * 发送心跳（v3 type: [AIPMessageBuilder.MessageType.HEARTBEAT]）
     *
     * The AIP v3 envelope (`protocol`, `version`, `device_id`, `device_type`,
     * `message_id`, `timestamp`) is built via [AIPMessageBuilder] and sent
     * directly over the WebSocket.
     */
    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("status", "online")
        }
        val aipMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        webSocket?.send(aipMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Heartbeat not sent.")
    }

    /**
     * 发送自定义消息。
     *
     * Any legacy [messageType] string is normalised to its v3 equivalent via
     * [AIPMessageBuilder.toV3Type] before the envelope is built, so the wire
     * message always carries an authoritative v3 type name and the full v3
     * envelope (`version="3.0"`, `protocol="AIP/1.0"`).
     */
    fun sendMessage(messageType: String, payload: JSONObject) {
        val v3Type = AIPMessageBuilder.toV3Type(messageType)
        val aipMessage = AIPMessageBuilder.build(
            messageType = v3Type,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        webSocket?.send(aipMessage.toString()) ?: Log.e(TAG, "WebSocket is null. Message not sent.")
    }
}
