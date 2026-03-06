package com.ufo.galaxy

import android.content.Context
import com.ufo.galaxy.communication.DeviceCommunication
import com.ufo.galaxy.communication.DeviceMessage
import com.ufo.galaxy.communication.CommandMessage
import com.ufo.galaxy.device.DeviceRegistry
import com.ufo.galaxy.device.DeviceStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/**
 * UFO Galaxy - 客户端集成管理器
 * 
 * 统一管理所有客户端组件：
 * - 设备注册
 * - 通信管理
 * - 能力协商
 * - 命令执行
 * 
 * 对齐服务端 system_integration.py
 * 
 * @author UFO Galaxy Team
 * @version 2.0
 */
class GalaxyClient private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "GalaxyClient"
        
        @Volatile
        private var instance: GalaxyClient? = null
        
        fun getInstance(context: Context): GalaxyClient {
            return instance ?: synchronized(this) {
                instance ?: GalaxyClient(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 组件
    private val deviceRegistry: DeviceRegistry = DeviceRegistry.getInstance(context)
    private lateinit var communication: DeviceCommunication
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 连接状态
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 消息流
    val messages: SharedFlow<DeviceMessage> get() = communication.messages
    val commands: SharedFlow<CommandMessage> get() = communication.commands
    
    // 设备信息
    val deviceInfo: StateFlow<com.ufo.galaxy.device.DeviceInfo?> get() = deviceRegistry.deviceInfo
    val deviceStatus: StateFlow<DeviceStatus> get() = deviceRegistry.deviceStatus
    val capabilities: StateFlow<List<String>> get() = deviceRegistry.capabilities
    
    // 监听器
    private var listener: Listener? = null
    
    /**
     * 连接状态
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }
    
    /**
     * 监听器接口
     */
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(message: DeviceMessage)
        fun onCommand(command: CommandMessage)
        fun onError(error: String)
    }
    
    /**
     * 初始化
     */
    fun initialize(): GalaxyClient {
        // 初始化设备注册
        deviceRegistry.initialize()
        
        // 初始化通信管理器
        communication = DeviceCommunication(deviceRegistry)
        
        // 设置通信监听器
        communication.setListener(object : DeviceCommunication.Listener {
            override fun onConnected() {
                _connectionState.value = ConnectionState.CONNECTED
                listener?.onConnected()
            }
            
            override fun onDisconnected() {
                _connectionState.value = ConnectionState.DISCONNECTED
                listener?.onDisconnected()
            }
            
            override fun onMessage(message: DeviceMessage) {
                listener?.onMessage(message)
            }
            
            override fun onCommand(command: CommandMessage) {
                handleCommand(command)
            }
            
            override fun onError(error: String) {
                _connectionState.value = ConnectionState.ERROR
                listener?.onError(error)
            }
        })
        
        // 注册命令处理器
        registerCommandHandlers()
        
        return this
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: Listener): GalaxyClient {
        this.listener = listener
        return this
    }
    
    /**
     * 连接到服务器
     */
    fun connect(serverUrl: String): GalaxyClient {
        _connectionState.value = ConnectionState.CONNECTING
        communication.connect(serverUrl)
        return this
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        communication.disconnect()
    }
    
    /**
     * 发送文本消息
     */
    fun sendText(text: String): Boolean {
        return communication.sendText(text)
    }
    
    /**
     * 发送命令
     */
    suspend fun sendCommand(action: String, payload: JSONObject): JSONObject {
        return communication.sendCommand(action, payload)
    }
    
    /**
     * 获取设备 ID
     */
    fun getDeviceId(): String = deviceRegistry.getDeviceId()
    
    /**
     * 检查能力
     */
    fun hasCapability(capability: String): Boolean = deviceRegistry.hasCapability(capability)
    
    /**
     * 添加能力
     */
    fun addCapability(capability: String) = deviceRegistry.addCapability(capability)
    
    /**
     * 添加标签
     */
    fun addTag(tag: String) = deviceRegistry.addTag(tag)
    
    /**
     * 添加分组
     */
    fun addToGroup(group: String) = deviceRegistry.addToGroup(group)
    
    /**
     * 注册命令处理器
     */
    private fun registerCommandHandlers() {
        // 点击命令 - 委托给 AutonomyService
        communication.registerHandler("click") { payload ->
            val service = com.ufo.galaxy.autonomy.AutonomyService.getInstance()
            if (service != null) {
                val actionParams = JSONObject().apply {
                    if (payload.has("node_id")) put("node_id", payload.getInt("node_id"))
                    if (payload.has("x")) put("x", payload.getInt("x"))
                    if (payload.has("y")) put("y", payload.getInt("y"))
                }
                val action = JSONObject().apply {
                    put("type", "tap")
                    put("params", actionParams)
                }
                service.executeAction(action)
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("action", "click")
                    put("message", "AutonomyService 未启用")
                }
            }
        }

        // 滑动命令
        communication.registerHandler("swipe") { payload ->
            val service = com.ufo.galaxy.autonomy.AutonomyService.getInstance()
            if (service != null) {
                val actionParams = JSONObject().apply {
                    put("x1", payload.optInt("startX", 0))
                    put("y1", payload.optInt("startY", 0))
                    put("x2", payload.optInt("endX", 0))
                    put("y2", payload.optInt("endY", 0))
                    put("duration", payload.optInt("duration", 300))
                }
                val action = JSONObject().apply {
                    put("type", "swipe")
                    put("params", actionParams)
                }
                service.executeAction(action)
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("action", "swipe")
                    put("message", "AutonomyService 未启用")
                }
            }
        }

        // 输入文本命令
        communication.registerHandler("input_text") { payload ->
            val service = com.ufo.galaxy.autonomy.AutonomyService.getInstance()
            if (service != null) {
                val actionParams = JSONObject().apply {
                    put("text", payload.optString("text", ""))
                    if (payload.has("node_id")) put("node_id", payload.getInt("node_id"))
                }
                val action = JSONObject().apply {
                    put("type", "input_text")
                    put("params", actionParams)
                }
                service.executeAction(action)
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("action", "input_text")
                    put("message", "AutonomyService 未启用")
                }
            }
        }

        // 截图命令 - 返回 UI 树
        communication.registerHandler("screenshot") { payload ->
            val service = com.ufo.galaxy.autonomy.AutonomyService.getInstance()
            if (service != null) {
                service.captureUITree()
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("action", "screenshot")
                    put("message", "AutonomyService 未启用")
                }
            }
        }
        
        // 获取状态命令
        communication.registerHandler("get_status") { payload ->
            JSONObject().apply {
                put("success", true)
                put("device_id", getDeviceId())
                put("status", deviceStatus.value.value)
                put("capabilities", capabilities.value)
            }
        }
    }
    
    /**
     * 处理命令
     */
    private fun handleCommand(command: CommandMessage) {
        listener?.onCommand(command)
    }
    
    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = communication.isConnected()
}

/**
 * Galaxy 客户端构建器
 */
class GalaxyClientBuilder(private val context: Context) {
    private var listener: GalaxyClient.Listener? = null
    private var serverUrl: String? = null
    
    fun setListener(listener: GalaxyClient.Listener): GalaxyClientBuilder {
        this.listener = listener
        return this
    }
    
    fun setServerUrl(url: String): GalaxyClientBuilder {
        this.serverUrl = url
        return this
    }
    
    fun build(): GalaxyClient {
        val client = GalaxyClient.getInstance(context)
        client.initialize()
        
        listener?.let { client.setListener(it) }
        
        serverUrl?.let { client.connect(it) }
        
        return client
    }
}
