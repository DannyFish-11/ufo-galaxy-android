package com.ufo.galaxy.network

import android.content.Context
import android.util.Log
import com.ufo.galaxy.config.AppSettings
import com.ufo.galaxy.config.ServerConfig
import com.ufo.galaxy.protocol.AIPMessageBuilder
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitPayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Galaxy Gateway WebSocket 客户端
 *
 * 负责与 ufo-galaxy-realization-v2 Gateway 建立 WebSocket 连接，并在
 * 跨设备模式（[crossDeviceEnabled]=true）下发送 [TaskSubmitPayload]。
 *
 * serverUrl 通过 [AppSettings] 统一注入，不硬编码。
 *
 * 主要接口：
 * - [setCrossDeviceEnabled] 切换跨设备模式（true → connect；false → disconnect）
 * - [sendTaskSubmit] 向 Gateway 发送 TASK_SUBMIT 消息
 * - [connectionState] 可观察的连接状态 [ConnectionState]
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class GalaxyWebSocketClient(
    private val context: Context
) {

    // ──────────────────────────────────────────────────────────────────────────
    // 连接状态
    // ──────────────────────────────────────────────────────────────────────────

    /** WebSocket 连接状态枚举 */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISABLED   // crossDeviceEnabled = false，连接被主动禁用
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISABLED)
    /** 可观察的当前连接状态，UI 层可订阅此 StateFlow 更新显示。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // ──────────────────────────────────────────────────────────────────────────
    // 内部字段
    // ──────────────────────────────────────────────────────────────────────────

    private val TAG = "GalaxyWSClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val settings by lazy { AppSettings(context) }

    /** Current WebSocket instance (null when not connected). */
    @Volatile private var webSocket: WebSocket? = null

    /** Cross-device switch state (driven by [setCrossDeviceEnabled]). */
    @Volatile private var crossDeviceEnabled: Boolean = false

    /** Current WS path candidate index; advanced on connection failure. */
    private var wsPathIndex = 0

    /**
     * Trace identifier for the current WS session.
     *
     * A fresh ID is generated each time a new connection is opened ([onOpen]).
     * All messages sent within a session reuse this ID so they can be correlated
     * end-to-end.  Expose via [getTraceId] for callers (e.g. WebRTCManager).
     */
    @Volatile private var sessionTraceId: String = AIPMessageBuilder.generateTraceId()

    /** Returns the trace identifier for the current WS session. */
    fun getTraceId(): String = sessionTraceId

    /** Derive route_mode from the cross-device switch state. */
    private fun currentRouteMode(): String =
        if (crossDeviceEnabled) AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE
        else AIPMessageBuilder.ROUTE_MODE_LOCAL

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // 长连接不超时
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    /** 设备 ID（用于 WS 路径和能力上报） */
    private val deviceId: String by lazy {
        val configured = com.ufo.galaxy.config.AppConfig.getString("agent.id", "")
        if (configured.isNotBlank()) configured
        else "android_${android.os.Build.MODEL.replace(" ", "_")}"
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 切换跨设备模式。
     *
     * - [enabled]=true  → 更新 [AppSettings.crossDeviceEnabled] 为 true，并调用 [connect]
     * - [enabled]=false → 更新 [AppSettings.crossDeviceEnabled] 为 false，并调用 [disconnect]
     */
    fun setCrossDeviceEnabled(enabled: Boolean) {
        crossDeviceEnabled = enabled
        settings.crossDeviceEnabled = enabled
        Log.i(TAG, "跨设备模式 ${if (enabled) "已启用 → connect" else "已禁用 → disconnect"}")
        if (enabled) connect() else disconnect()
    }

    /**
     * 建立 WebSocket 连接（仅在 [crossDeviceEnabled]=true 时生效）。
     */
    fun connect() {
        if (!crossDeviceEnabled) {
            Log.d(TAG, "跨设备模式未启用，跳过 connect()")
            _connectionState.value = ConnectionState.DISABLED
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            Log.d(TAG, "WebSocket 已连接或正在连接，跳过重复 connect()")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val baseUrl = settings.galaxyGatewayUrl.trimEnd('/')
            // 移除已有的路径后缀（/ws/*），EnhancedAIPClient 的兼容做法
            .replace(Regex("/ws(/.*)?$"), "")
        val wsUrl = ServerConfig.buildEffectiveWsUrl(baseUrl, deviceId, wsPathIndex)

        Log.i(TAG, "🔗 正在连接到 Gateway: $wsUrl (path[$wsPathIndex])")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Device-ID", deviceId)
            .build()
        okHttpClient.newWebSocket(request, wsListener)
    }

    /**
     * 断开 WebSocket 连接。
     */
    fun disconnect() {
        Log.i(TAG, "🔌 断开 Gateway WebSocket")
        webSocket?.close(1000, "cross_device_disabled")
        webSocket = null
        _connectionState.value = ConnectionState.DISABLED
    }

    /**
     * 向 Gateway 发送 [TaskSubmitPayload]（AIP TASK_SUBMIT）。
     *
     * 仅在 [crossDeviceEnabled]=true 且 WebSocket 已连接时发送。
     *
     * @param goal   用户自然语言输入（目标描述）
     * @param source 来源标识，例如 "text_input" 或 "voice_input"
     * @return true 表示成功发送，false 表示未发送（未连接或未启用跨设备）
     */
    fun sendTaskSubmit(goal: String, source: String = "text_input"): Boolean {
        if (!crossDeviceEnabled) {
            Log.w(TAG, "sendTaskSubmit 被忽略：跨设备未启用")
            return false
        }
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "sendTaskSubmit 被忽略：WebSocket 未连接")
            return false
        }

        val payload = TaskSubmitPayload(
            goal = goal,
            deviceId = deviceId,
            source = source
        )
        // 包裹为 AIP v3 消息信封
        val envelope = AIPMessageBuilder.build(
            messageType = MsgType.TASK_SUBMIT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload.toJson(),
            traceId = sessionTraceId,
            routeMode = currentRouteMode()
        )

        return try {
            val sent = ws.send(envelope.toString())
            if (sent) Log.i(TAG, "📤 TASK_SUBMIT 已发送: goal=$goal")
            else Log.w(TAG, "📤 TASK_SUBMIT 发送失败（ws.send 返回 false）")
            sent
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendTaskSubmit 异常", e)
            false
        }
    }

    /** 当前是否处于已连接状态的便捷判断。 */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    // ──────────────────────────────────────────────────────────────────────────
    // WebSocket 监听器
    // ──────────────────────────────────────────────────────────────────────────

    private val wsListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Refresh trace ID for this new session.
            sessionTraceId = AIPMessageBuilder.generateTraceId()
            Log.i(TAG, "✅ Gateway WebSocket 已连接 [trace_id=$sessionTraceId route_mode=${currentRouteMode()}]")
            this@GalaxyWebSocketClient.webSocket = webSocket
            _connectionState.value = ConnectionState.CONNECTED
            wsPathIndex = 0  // 重置路径下标：下次优先用最高优先级路径
            sendCapabilityReport(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "📨 Gateway 消息: ${text.take(120)}")
            try {
                val msg = AIPMessageBuilder.parse(text) ?: return
                val type = msg.optString("type")
                when (type) {
                    MsgType.TASK_ASSIGN, MsgType.GOAL_EXECUTION -> {
                        // 下行任务分配：在此处记录，执行链路由 GalaxyConnectionService 处理
                        Log.i(TAG, "⬇️  收到来自 Gateway 的 $type")
                    }
                    MsgType.HEARTBEAT -> Log.d(TAG, "💓 heartbeat ack")
                    else -> Log.d(TAG, "📨 消息类型: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "消息解析失败", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "⚠️ Gateway WebSocket 关闭中: code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "❌ Gateway WebSocket 已关闭: code=$code reason=$reason")
            this@GalaxyWebSocketClient.webSocket = null
            if (crossDeviceEnabled) {
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.DISABLED
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ Gateway WebSocket 连接失败 (path[$wsPathIndex]): ${t.message}")
            this@GalaxyWebSocketClient.webSocket = null
            if (crossDeviceEnabled) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // 尝试下一个 WS 路径（遵循运行时有效路径列表）
                wsPathIndex = (wsPathIndex + 1) % ServerConfig.effectiveWsPaths.size
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.DISABLED
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ──────────────────────────────────────────────────────────────────────────

    private var reconnectJob: Job? = null

    /** 5 秒后重试连接（仅在跨设备模式启用时）。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(5_000)
            if (crossDeviceEnabled && webSocket == null) {
                Log.i(TAG, "🔄 重试连接到 Gateway...")
                connect()
            }
        }
    }

    /** 连接成功后发送能力上报，让 Gateway 记录本设备支持的操作。
     *
     * When [crossDeviceEnabled] is `true` the payload is extended with a
     * `capability_schema` array (built by [DeviceRegistry.buildAllCapabilitySchemas])
     * so the server routing layer knows the exec_mode for each capability.
     * The legacy `supported_actions` list is always included for backward
     * compatibility with older server versions.
     */
    private fun sendCapabilityReport(ws: WebSocket) {
        val registry = com.ufo.galaxy.device.DeviceRegistry.getInstance(context)
        val capabilities = registry.capabilities.value
        val schemas = if (crossDeviceEnabled) registry.buildAllCapabilitySchemas() else emptyList()

        val payload = JSONObject().apply {
            put("platform", "android")
            put("version", "2.5.0")
            put("cross_device_enabled", crossDeviceEnabled)
            put("supported_actions", org.json.JSONArray(capabilities))
            if (crossDeviceEnabled && schemas.isNotEmpty()) {
                val schemaArray = org.json.JSONArray()
                schemas.forEach { schemaArray.put(it.toJson()) }
                put("capability_schema", schemaArray)
            }
        }
        val msg = AIPMessageBuilder.build(
            messageType = MsgType.CAPABILITY_REPORT,
            sourceNodeId = deviceId,
            targetNodeId = "Galaxy",
            payload = payload,
            traceId = sessionTraceId,
            routeMode = currentRouteMode()
        )
        ws.send(msg.toString())
        if (crossDeviceEnabled && schemas.isNotEmpty()) {
            val localCount  = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_LOCAL }
            val remoteCount = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_REMOTE }
            val bothCount   = schemas.count { it.execMode == com.ufo.galaxy.device.DeviceRegistry.EXEC_MODE_BOTH }
            Log.i(TAG, "📋 能力上报已发送 [count=${capabilities.size} local=$localCount remote=$remoteCount both=$bothCount trace_id=$sessionTraceId route_mode=${currentRouteMode()}]")
        } else {
            Log.i(TAG, "📋 能力上报已发送 [trace_id=$sessionTraceId route_mode=${currentRouteMode()}]")
        }
    }

    /** 清理资源（一般在 Application.onTerminate 或测试中调用）。 */
    fun cleanup() {
        disconnect()
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
}
