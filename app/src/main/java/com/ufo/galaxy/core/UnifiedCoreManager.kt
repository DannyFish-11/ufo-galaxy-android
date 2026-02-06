package com.ufo.galaxy.core

import android.content.Context
import android.util.Log
import com.ufo.galaxy.network.DeviceManager
import com.ufo.galaxy.network.WebSocketClient
import com.ufo.galaxy.protocol.NodeProtocol
import com.ufo.galaxy.service.DeviceStatusSyncService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UFO Galaxy Android - 统一核心管理器
 * ===================================
 * 
 * 融合性整合所有核心模块：
 * 1. AgentCore - 节点和任务管理
 * 2. DeviceManager - 设备状态管理
 * 3. WebSocketClient - 服务器通信
 * 4. NodeProtocol - 节点通信协议
 * 5. DeviceStatusSyncService - 状态同步
 * 
 * 作者：Manus AI
 * 日期：2026-02-06
 */
class UnifiedCoreManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedCoreManager"
        
        @Volatile
        private var instance: UnifiedCoreManager? = null
        
        fun getInstance(context: Context): UnifiedCoreManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedCoreManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ========================================================================
    // 核心组件
    // ========================================================================
    
    private val agentCore: AgentCore by lazy { AgentCore(context) }
    private val deviceManager: DeviceManager by lazy { DeviceManager.getInstance(context) }
    private val webSocketClient: WebSocketClient by lazy { WebSocketClient.getInstance() }
    private val nodeProtocol: NodeProtocol by lazy { NodeProtocol() }
    
    // ========================================================================
    // 状态管理
    // ========================================================================
    
    private val isInitialized = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 服务器配置
    private var serverUrl: String = ""
    private var deviceApiUrl: String = ""
    
    // 回调
    private val stateListeners = ConcurrentHashMap<String, StateListener>()
    
    // ========================================================================
    // 初始化
    // ========================================================================
    
    /**
     * 初始化统一核心管理器
     */
    suspend fun initialize(serverUrl: String, deviceApiPort: Int = 8766): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized.get()) {
                    Log.w(TAG, "Already initialized")
                    return@withContext Result.success(true)
                }
                
                Log.i(TAG, "Initializing UnifiedCoreManager...")
                
                this@UnifiedCoreManager.serverUrl = serverUrl
                this@UnifiedCoreManager.deviceApiUrl = serverUrl.replace(Regex(":\\d+"), ":$deviceApiPort")
                
                // 1. 初始化 AgentCore
                agentCore.initialize()
                Log.i(TAG, "AgentCore initialized")
                
                // 2. 初始化 DeviceManager
                deviceManager.initialize()
                Log.i(TAG, "DeviceManager initialized")
                
                // 3. 连接 WebSocket
                connectToServer()
                
                // 4. 启动状态同步
                startStatusSync()
                
                // 5. 注册设备到服务器
                registerDevice()
                
                isInitialized.set(true)
                notifyStateChange(SystemState.READY)
                
                Log.i(TAG, "UnifiedCoreManager initialized successfully")
                Result.success(true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                notifyStateChange(SystemState.ERROR, e.message)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 连接到服务器
     */
    private suspend fun connectToServer() {
        try {
            val wsUrl = serverUrl.replace("http", "ws") + "/ws"
            webSocketClient.connect(wsUrl, object : WebSocketClient.MessageListener {
                override fun onConnected() {
                    isConnected.set(true)
                    notifyStateChange(SystemState.CONNECTED)
                    Log.i(TAG, "WebSocket connected")
                }
                
                override fun onMessage(message: String) {
                    handleServerMessage(message)
                }
                
                override fun onDisconnected() {
                    isConnected.set(false)
                    notifyStateChange(SystemState.DISCONNECTED)
                    Log.w(TAG, "WebSocket disconnected")
                    
                    // 自动重连
                    scope.launch {
                        delay(5000)
                        if (!isConnected.get()) {
                            connectToServer()
                        }
                    }
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "WebSocket error: $error")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
        }
    }
    
    /**
     * 启动状态同步
     */
    private fun startStatusSync() {
        scope.launch {
            while (isActive) {
                try {
                    syncDeviceStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Status sync failed", e)
                }
                delay(10000) // 每 10 秒同步一次
            }
        }
    }
    
    /**
     * 注册设备到服务器
     */
    private suspend fun registerDevice() {
        try {
            val deviceInfo = collectDeviceInfo()
            val registered = agentCore.registerToGalaxy(serverUrl)
            
            if (registered) {
                Log.i(TAG, "Device registered to Galaxy")
                
                // 发送设备信息到设备状态 API
                sendDeviceInfo(deviceInfo)
            } else {
                Log.w(TAG, "Device registration failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Device registration error", e)
        }
    }
    
    // ========================================================================
    // 消息处理
    // ========================================================================
    
    /**
     * 处理服务器消息
     */
    private fun handleServerMessage(message: String) {
        scope.launch {
            try {
                val json = JSONObject(message)
                val messageType = json.optString("type", "")
                
                when (messageType) {
                    "request" -> handleRequest(json)
                    "event" -> handleEvent(json)
                    "command" -> handleCommand(json)
                    "ping" -> sendPong()
                    else -> Log.w(TAG, "Unknown message type: $messageType")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message", e)
            }
        }
    }
    
    /**
     * 处理请求
     */
    private suspend fun handleRequest(json: JSONObject) {
        val requestId = json.optString("id", "")
        val action = json.optString("action", "")
        val params = json.optJSONObject("params") ?: JSONObject()
        
        Log.d(TAG, "Handling request: $action")
        
        val result = when (action) {
            "execute_task" -> executeTask(params)
            "get_status" -> getSystemStatus()
            "get_device_info" -> collectDeviceInfo()
            "capture_screen" -> captureScreen()
            "execute_command" -> executeCommand(params)
            else -> JSONObject().apply {
                put("success", false)
                put("error", "Unknown action: $action")
            }
        }
        
        // 发送响应
        sendResponse(requestId, result)
    }
    
    /**
     * 处理事件
     */
    private fun handleEvent(json: JSONObject) {
        val eventType = json.optString("event_type", "")
        val data = json.optJSONObject("data") ?: JSONObject()
        
        Log.d(TAG, "Handling event: $eventType")
        
        when (eventType) {
            "config_update" -> updateConfig(data)
            "node_status_change" -> handleNodeStatusChange(data)
            else -> Log.w(TAG, "Unknown event type: $eventType")
        }
    }
    
    /**
     * 处理命令
     */
    private suspend fun handleCommand(json: JSONObject) {
        val command = json.optString("command", "")
        
        Log.d(TAG, "Handling command: $command")
        
        when (command) {
            "restart" -> restart()
            "shutdown" -> shutdown()
            "reconnect" -> connectToServer()
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }
    
    // ========================================================================
    // 任务执行
    // ========================================================================
    
    /**
     * 执行任务
     */
    suspend fun executeTask(params: JSONObject): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val taskDescription = params.optString("task", "")
                val context = params.optJSONObject("context")?.let { 
                    jsonToMap(it) 
                } ?: emptyMap()
                
                agentCore.handleTask(taskDescription, context)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }
            }
        }
    }
    
    /**
     * 执行命令
     */
    private suspend fun executeCommand(params: JSONObject): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val command = params.optString("command", "")
                val args = params.optJSONArray("args")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                
                // 通过 ADB Self 节点执行
                val node33 = agentCore.getNode("33")
                if (node33 != null) {
                    val result = node33.execute(mapOf(
                        "command" to command,
                        "args" to args
                    ))
                    JSONObject().apply {
                        put("success", true)
                        put("result", result)
                    }
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "ADB node not available")
                    }
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }
            }
        }
    }
    
    // ========================================================================
    // 状态管理
    // ========================================================================
    
    /**
     * 获取系统状态
     */
    fun getSystemStatus(): JSONObject {
        return JSONObject().apply {
            put("initialized", isInitialized.get())
            put("connected", isConnected.get())
            put("device_id", agentCore.getDeviceId())
            put("registered", agentCore.isRegistered())
            put("nodes", agentCore.getNodesStatus())
            put("device_status", deviceManager.getStatus())
            put("timestamp", System.currentTimeMillis())
        }
    }
    
    /**
     * 收集设备信息
     */
    fun collectDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("device_id", agentCore.getDeviceId())
            put("device_type", "android")
            put("model", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("sdk_version", android.os.Build.VERSION.SDK_INT)
            
            // 设备能力
            put("capabilities", JSONArray().apply {
                put("camera")
                put("microphone")
                put("bluetooth")
                put("nfc")
                put("wifi")
                put("gps")
                put("accelerometer")
                put("gyroscope")
            })
            
            // 节点信息
            put("nodes", agentCore.getNodesStatus())
        }
    }
    
    /**
     * 同步设备状态
     */
    private suspend fun syncDeviceStatus() {
        if (!isConnected.get()) return
        
        val status = getSystemStatus()
        sendMessage(JSONObject().apply {
            put("type", "status_update")
            put("data", status)
        })
    }
    
    /**
     * 发送设备信息到设备状态 API
     */
    private suspend fun sendDeviceInfo(deviceInfo: JSONObject) {
        // 这里可以通过 HTTP 发送到设备状态 API
        // 暂时通过 WebSocket 发送
        sendMessage(JSONObject().apply {
            put("type", "device_register")
            put("data", deviceInfo)
        })
    }
    
    // ========================================================================
    // 屏幕捕获
    // ========================================================================
    
    /**
     * 捕获屏幕
     */
    private suspend fun captureScreen(): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                // 通过 Accessibility Service 或 MediaProjection 捕获
                // 这里返回占位信息
                JSONObject().apply {
                    put("success", true)
                    put("message", "Screen capture requires MediaProjection permission")
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }
            }
        }
    }
    
    // ========================================================================
    // 通信
    // ========================================================================
    
    /**
     * 发送消息
     */
    private fun sendMessage(message: JSONObject) {
        if (isConnected.get()) {
            webSocketClient.send(message.toString())
        }
    }
    
    /**
     * 发送响应
     */
    private fun sendResponse(requestId: String, result: JSONObject) {
        sendMessage(JSONObject().apply {
            put("type", "response")
            put("id", requestId)
            put("data", result)
        })
    }
    
    /**
     * 发送 Pong
     */
    private fun sendPong() {
        sendMessage(JSONObject().apply {
            put("type", "pong")
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    // ========================================================================
    // 配置更新
    // ========================================================================
    
    private fun updateConfig(data: JSONObject) {
        Log.i(TAG, "Config updated: $data")
        // 处理配置更新
    }
    
    private fun handleNodeStatusChange(data: JSONObject) {
        Log.i(TAG, "Node status changed: $data")
        // 处理节点状态变化
    }
    
    // ========================================================================
    // 生命周期
    // ========================================================================
    
    /**
     * 重启
     */
    suspend fun restart() {
        Log.i(TAG, "Restarting...")
        shutdown()
        delay(1000)
        initialize(serverUrl)
    }
    
    /**
     * 关闭
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down...")
        
        scope.cancel()
        webSocketClient.disconnect()
        agentCore.shutdown()
        deviceManager.shutdown()
        
        isInitialized.set(false)
        isConnected.set(false)
        
        notifyStateChange(SystemState.STOPPED)
        
        Log.i(TAG, "Shutdown complete")
    }
    
    // ========================================================================
    // 状态监听
    // ========================================================================
    
    interface StateListener {
        fun onStateChanged(state: SystemState, message: String?)
    }
    
    enum class SystemState {
        INITIALIZING,
        READY,
        CONNECTED,
        DISCONNECTED,
        ERROR,
        STOPPED
    }
    
    fun addStateListener(key: String, listener: StateListener) {
        stateListeners[key] = listener
    }
    
    fun removeStateListener(key: String) {
        stateListeners.remove(key)
    }
    
    private fun notifyStateChange(state: SystemState, message: String? = null) {
        stateListeners.values.forEach { listener ->
            try {
                listener.onStateChanged(state, message)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    // ========================================================================
    // 工具方法
    // ========================================================================
    
    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            map[key] = json.get(key)
        }
        return map
    }
}
