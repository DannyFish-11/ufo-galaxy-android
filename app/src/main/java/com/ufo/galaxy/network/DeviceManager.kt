package com.ufo.galaxy.network

import com.ufo.galaxy.protocol.AIPMessage
import com.ufo.galaxy.protocol.MessageType
import com.ufo.galaxy.protocol.TaskStatus
import com.ufo.galaxy.protocol.ResultStatus

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 设备管理器
 * 
 * 功能：
 * 1. 管理设备注册
 * 2. 管理心跳机制
 * 3. 管理设备状态
 * 4. 处理来自 Gateway 的消息
 * 
 * @author UFO Galaxy Team
 * @version 2.0.0
 * @date 2026-01-24
 */
class DeviceManager(
    private val context: Context,
    private val gatewayUrl: String
) {
    
    companion object {
        @Volatile
        private var instance: DeviceManager? = null
        
        fun getInstance(context: Context): DeviceManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceManager(
                    context.applicationContext,
                    "ws://localhost:8765/ws"
                ).also { instance = it }
            }
        }
    }
    
    private val TAG = "DeviceManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var webSocketClient: WebSocketClient? = null
    private var _deviceId: String = ""
    val deviceId: String
        get() = _deviceId
    
    @Volatile
    private var isRegistered = false
    
    private val messageHandlers = mutableMapOf<String, (JSONObject) -> Unit>()
    
    /**
     * 初始化
     */
    fun initialize() {
        _deviceId = generateDeviceId()
        Log.i(TAG, "Device ID: $deviceId")
        
        webSocketClient = WebSocketClient(
            gatewayUrl = gatewayUrl,
            deviceId = deviceId,
            onMessageReceived = ::handleMessage,
            onConnectionStateChanged = ::handleConnectionStateChanged
        )
    }
    
    /**
     * 连接到 Gateway
     */
    fun connect() {
        webSocketClient?.connect()
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        webSocketClient?.disconnect()
        isRegistered = false
    }
    
    /**
     * 注册消息处理器
     */
    fun registerMessageHandler(messageType: String, handler: (JSONObject) -> Unit) {
        messageHandlers[messageType] = handler
    }
    
    /**
     * 取消注册消息处理器
     */
    fun unregisterMessageHandler(messageType: String) {
        messageHandlers.remove(messageType)
    }
    
    /**
     * 发送消息
     */
    fun sendMessage(message: AIPMessage): Boolean {
        return webSocketClient?.sendMessage(message.toJSON()) ?: false
    }
    
    /**
     * 发送原始消息
     */
    fun sendRawMessage(message: String): Boolean {
        return webSocketClient?.sendRawMessage(message) ?: false
    }
    
    /**
     * 处理接收到的消息
     */
    private fun handleMessage(message: JSONObject) {
        val aipMessage = AIPMessage.fromJSON(message)
        if (aipMessage == null) {
            Log.w(TAG, "Failed to parse AIP message")
            return
        }
        
        Log.d(TAG, "Received message type: ${aipMessage.type.value}")
        
        when (aipMessage.type) {
            MessageType.DEVICE_REGISTER_ACK -> {
                handleDeviceRegisterAck(aipMessage)
            }
            MessageType.DEVICE_HEARTBEAT_ACK -> {
                Log.d(TAG, "Heartbeat acknowledged")
            }
            MessageType.TASK_ASSIGN -> {
                handleTaskRequest(aipMessage)
            }
            MessageType.COMMAND -> {
                handleCommand(aipMessage)
            }
            else -> {
                // 调用注册的消息处理器
                val handler = messageHandlers[aipMessage.type.value]
                if (handler != null) {
                    handler(aipMessage.payload)
                } else {
                    Log.w(TAG, "No handler for message type: ${aipMessage.type.value}")
                }
            }
        }
    }
    
    /**
     * 处理设备注册确认
     */
    private fun handleDeviceRegisterAck(message: AIPMessage) {
        val success = message.payload.optBoolean("success", false)
        if (success) {
            isRegistered = true
            Log.i(TAG, "Device registered successfully")
            
            // 注册工具
            registerTools()
        } else {
            val error = message.payload.optString("error", "Unknown error")
            Log.e(TAG, "Device registration failed: $error")
        }
    }
    
    /**
     * 处理任务请求
     */
    private fun handleTaskRequest(message: AIPMessage) {
        val taskId = message.payload.optString("task_id")
        val taskType = message.payload.optString("task_type")
        
        Log.i(TAG, "Received task request: $taskId ($taskType)")
        
        // 调用注册的任务处理器
        val handler = messageHandlers["task_request"]
        if (handler != null) {
            handler(message.payload)
        } else {
            Log.w(TAG, "No handler for task_request")
        }
    }
    
    /**
     * 发送任务结果
     */
    fun sendTaskResult(taskId: String, result: JSONObject) {
        val success = result.optString("status") == "success"
        val status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED
        val responseMessage = AIPMessage.createTaskResult(
            deviceId = deviceId,
            taskId = taskId,
            status = status
        )
        sendMessage(responseMessage)
    }
    
    /**
     * 处理命令
     */
    private fun handleCommand(message: AIPMessage) {
        val commandId = message.payload.optString("command_id")
        val commandType = message.payload.optString("command_type")
        
        Log.i(TAG, "Received command: $commandId ($commandType)")
        
        // 调用注册的命令处理器
        val handler = messageHandlers["command"]
        if (handler != null) {
            handler(message.payload)
        } else {
            Log.w(TAG, "No handler for command")
        }
    }
    
    /**
     * 发送命令结果
     */
    fun sendCommandResult(commandId: String, result: JSONObject) {
        val success = result.optString("status") == "success"
        val status = if (success) ResultStatus.SUCCESS else ResultStatus.FAILURE
        val responseMessage = AIPMessage.createCommandResult(
            deviceId = deviceId,
            commandId = commandId,
            status = status,
            result = result.toString()
        )
        sendMessage(responseMessage)
    }
    
    /**
     * 处理连接状态变化
     */
    private fun handleConnectionStateChanged(state: WebSocketClient.ConnectionState) {
        Log.i(TAG, "Connection state changed: $state")
        
        when (state) {
            WebSocketClient.ConnectionState.CONNECTED -> {
                // 连接成功后会自动发送注册消息
            }
            WebSocketClient.ConnectionState.DISCONNECTED -> {
                isRegistered = false
            }
            WebSocketClient.ConnectionState.ERROR -> {
                isRegistered = false
            }
            else -> {}
        }
    }
    
    /**
     * 注册工具
     */
    private fun registerTools() {
        // 注册 Android 控制工具
        val toolPayload = JSONObject().apply {
            put("tool_name", "android_control")
            put("tool_type", "device_control")
            put("capabilities", org.json.JSONArray(listOf(
                "screen_capture",
                "input_control",
                "accessibility",
                "voice_input",
                "app_management"
            )))
            put("endpoint", "local://android_control")
        }
        val toolMessage = JSONObject().apply {
            put("type", "tool_register")
            put("device_id", deviceId)
            put("payload", toolPayload)
        }
        webSocketClient?.sendMessage(toolMessage)
    }
    
    /**
     * 获取设备 ID
     */
    private fun generateDeviceId(): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "android_${System.currentTimeMillis()}"
        } catch (e: Exception) {
            "android_${System.currentTimeMillis()}"
        }
    }
    
    /**
     * 获取设备信息
     */
    fun getDeviceInfo(): Map<String, Any> {
        return mapOf(
            "device_id" to deviceId,
            "device_type" to "android",
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_version" to Build.VERSION.RELEASE,
            "sdk_version" to Build.VERSION.SDK_INT,
            "is_registered" to isRegistered,
            "is_connected" to (webSocketClient?.isConnected() ?: false)
        )
    }
    
    /**
     * 是否已注册
     */
    fun isRegistered(): Boolean = isRegistered
    
    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() ?: false
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        webSocketClient?.cleanup()
        scope.cancel()
    }
    
    /**
     * 发送设备状态
     */
    fun sendDeviceStatus(status: JSONObject) {
        val statusMessage = JSONObject().apply {
            put("type", "device_status")
            put("device_id", deviceId)
            put("status", status)
            put("timestamp", System.currentTimeMillis())
        }
        webSocketClient?.sendMessage(statusMessage)
    }
    
    /**
     * 发送原始消息
     */
    fun sendRawMessage(message: JSONObject) {
        webSocketClient?.sendMessage(message)
    }
}
