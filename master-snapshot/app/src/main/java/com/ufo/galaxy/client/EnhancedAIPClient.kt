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
 * 2. 通过 [ServerConfig] 集中管理 WebSocket 路径，支持按优先级自动回退。
 * 3. 增强的能力声明，包括 Android 原生能力和扩展能力。
 * 4. **Microsoft 兼容层（PR-C3）**：通过 [applyMicrosoftMapping] 在最外层为 v3 信封
 *    追加 `ms_*` 补充字段，供 Microsoft Galaxy 集成消费方使用。v3 信封字段本身
 *    始终保持不变（`protocol`、`version`、`type`、`source_node` 等）。
 *    可通过 [microsoftMappingEnabled] 开关控制此行为（默认开启）。
 *
 * ## Microsoft 兼容映射行为（[microsoftMappingEnabled] = true，默认值）
 * 每条出站 v3 消息在发送前会追加以下三个 `ms_*` 键：
 * - `ms_message_type`：Microsoft 协议的消息类型字符串（见 [microsoftTypeMapping]）
 * - `ms_agent_id`：`source_node` 的别名（Microsoft 字段名）
 * - `ms_session_id`：`timestamp` 的毫秒值别名（Microsoft 约定）
 *
 * ## 禁用兼容映射（[microsoftMappingEnabled] = false）
 * 关闭后，出站消息为纯 v3 载荷，不含任何 `ms_*` 字段，适用于非 Microsoft 端点。
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

    /**
     * Controls the Microsoft-compatibility mapping layer applied to all outbound messages.
     *
     * When `true` (default for Microsoft endpoints), [applyMicrosoftMapping] is invoked
     * after [AIPMessageBuilder.build], augmenting each v3 envelope with three `ms_*`
     * supplementary headers expected by Microsoft Galaxy integration consumers.
     * The v3 envelope itself (`protocol`, `version`, `type`, `source_node`, etc.) is
     * **never** modified or removed.
     *
     * Set to `false` for non-Microsoft endpoints where a raw v3 payload is preferred.
     */
    var microsoftMappingEnabled: Boolean = true

    /**
     * Microsoft compatibility mapping: v3 type name → Microsoft `ms_message_type` value.
     *
     * Keys are the authoritative v3 type names defined in [AIPMessageBuilder.MessageType].
     * Values are the Microsoft Galaxy wire-type strings.  This map is intentionally minimal
     * (one entry per v3 message type) to reduce future drift.
     */
    private val microsoftTypeMapping: Map<String, String> = mapOf(
        AIPMessageBuilder.MessageType.DEVICE_REGISTER   to "REGISTER",
        AIPMessageBuilder.MessageType.HEARTBEAT         to "HEARTBEAT",
        AIPMessageBuilder.MessageType.CAPABILITY_REPORT to "CAPABILITY_REPORT",
        AIPMessageBuilder.MessageType.TASK_ASSIGN       to "TASK",
        AIPMessageBuilder.MessageType.COMMAND_RESULT    to "COMMAND_RESULTS"
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
     * The v3 envelope is built via [AIPMessageBuilder] first so that all required
     * fields (`protocol`, `version`, `device_id`, `device_type`, `message_id`,
     * `timestamp`) are always present and consistent.  When [microsoftMappingEnabled]
     * is `true`, [applyMicrosoftMapping] is applied at the outermost layer to add
     * the `ms_*` supplementary headers without altering any v3 envelope field.
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

        sendWire(aipMessage, "Registration")
        Log.i(TAG, "Enhanced registration message sent to Microsoft Galaxy.")
        // Send capability report after registration
        sendCapabilityReport()
    }

    /**
     * 发送能力上报（v3 type: [AIPMessageBuilder.MessageType.CAPABILITY_REPORT]）
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
        sendWire(ourMessage, "Capability report")
        Log.i(TAG, "Capability report sent.")
    }

    /**
     * Apply Microsoft-compatibility headers to a fully-formed v3 envelope message.
     *
     * This function is **non-destructive**: all v3 envelope fields (`protocol`,
     * `version`, `type`, `source_node`, `target_node`, `timestamp`, `message_id`,
     * `payload`, `device_id`, `device_type`) are preserved intact.
     * The following Microsoft-specific keys are **added** as supplementary headers:
     *
     *   - `ms_message_type` – Microsoft wire-type string (see [microsoftTypeMapping])
     *   - `ms_agent_id`     – alias for `source_node` (Microsoft field name)
     *   - `ms_session_id`   – alias for `timestamp` in milliseconds (Microsoft convention)
     *
     * @param v3Message A v3 envelope produced by [AIPMessageBuilder.build].
     * @return A new [JSONObject] with all original fields plus the three `ms_*` headers.
     */
    private fun applyMicrosoftMapping(v3Message: JSONObject): JSONObject {
        val v3Type = v3Message.optString("type")
        val msType = microsoftTypeMapping[v3Type]
        if (msType == null) {
            Log.w(TAG, "applyMicrosoftMapping: no ms_message_type mapping for v3 type '$v3Type'; falling back to uppercase")
        }
        return JSONObject(v3Message.toString()).apply {
            put("ms_message_type", msType ?: v3Type.uppercase())
            put("ms_agent_id", v3Message.optString("source_node", deviceId))
            put("ms_session_id", v3Message.optLong("timestamp") * 1000L)
        }
    }

    /**
     * Send a v3 envelope over the WebSocket wire.
     *
     * If [microsoftMappingEnabled] is `true`, [applyMicrosoftMapping] is applied at
     * the outermost layer to augment the message with Microsoft-specific headers.
     * The v3 envelope is always built and validated by [AIPMessageBuilder] before
     * this method is called; the mapping is purely additive.
     *
     * @param message   Fully-formed v3 envelope from [AIPMessageBuilder.build].
     * @param errorTag  Short label used in the error log when the WebSocket is null.
     */
    private fun sendWire(message: JSONObject, errorTag: String = "Message") {
        val wire = if (microsoftMappingEnabled) applyMicrosoftMapping(message) else message
        webSocket?.send(wire.toString()) ?: Log.e(TAG, "WebSocket is null. $errorTag not sent.")
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
     * 发送命令执行结果（v3 type: [AIPMessageBuilder.MessageType.COMMAND_RESULT]）
     */
    private fun sendCommandResult(command: String, result: JSONObject) {
        val aipMessage = AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.COMMAND_RESULT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = result
        )
        sendWire(aipMessage, "Result")
    }

    /**
     * 发送心跳（v3 type: [AIPMessageBuilder.MessageType.HEARTBEAT]）
     *
     * The v3 envelope (`protocol`, `version`, `device_id`, `device_type`,
     * `message_id`, `timestamp`) is built via [AIPMessageBuilder] and sent
     * via [sendWire], which optionally applies [applyMicrosoftMapping].
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
        sendWire(aipMessage, "Heartbeat")
    }

    /**
     * 发送自定义消息。
     *
     * Any legacy [messageType] string is normalised to its v3 equivalent via
     * [AIPMessageBuilder.toV3Type] before the envelope is built, so the wire
     * message always carries an authoritative v3 type name and the full v3
     * envelope (`version="3.0"`, `protocol="AIP/1.0"`).  The Microsoft mapping
     * layer is applied last via [sendWire] when [microsoftMappingEnabled] is `true`.
     */
    fun sendMessage(messageType: String, payload: JSONObject) {
        val v3Type = AIPMessageBuilder.toV3Type(messageType)
        val aipMessage = AIPMessageBuilder.build(
            messageType = v3Type,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload
        )
        sendWire(aipMessage)
    }
}
