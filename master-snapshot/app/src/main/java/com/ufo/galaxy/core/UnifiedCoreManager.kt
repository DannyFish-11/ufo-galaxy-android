package com.ufo.galaxy.core

import android.content.Context
import android.util.Log
import com.ufo.galaxy.network.DeviceManager
import com.ufo.galaxy.config.GalaxyConfig
import kotlinx.coroutines.*
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
 * 3. GalaxyConfig - 配置管理
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
    
    private val agentCore: AgentCore by lazy { AgentCore.getInstance(context) }
    private val deviceManager: DeviceManager by lazy { DeviceManager.getInstance(context) }
    private val config: GalaxyConfig by lazy { GalaxyConfig.getInstance(context) }
    
    // ========================================================================
    // 状态管理
    // ========================================================================
    
    private val isInitialized = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 回调
    private val stateListeners = ConcurrentHashMap<String, StateListener>()
    
    // ========================================================================
    // 状态枚举
    // ========================================================================
    
    enum class SystemState {
        INITIALIZING,
        INITIALIZED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR,
        SHUTDOWN
    }
    
    interface StateListener {
        fun onStateChanged(state: SystemState)
        fun onError(error: String)
    }
    
    // ========================================================================
    // 初始化
    // ========================================================================
    
    /**
     * 初始化统一核心管理器
     */
    suspend fun initialize(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized.get()) {
                    return@withContext Result.success(true)
                }
                
                Log.i(TAG, "Initializing UnifiedCoreManager...")
                notifyStateChange(SystemState.INITIALIZING)
                
                // 1. 初始化 AgentCore
                agentCore.initialize()
                Log.d(TAG, "AgentCore initialized")
                
                // 2. 初始化 DeviceManager
                deviceManager.initialize()
                Log.d(TAG, "DeviceManager initialized")
                
                // 3. 启动状态同步
                startStatusSync()
                
                isInitialized.set(true)
                notifyStateChange(SystemState.INITIALIZED)
                Log.i(TAG, "UnifiedCoreManager initialized successfully")
                
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UnifiedCoreManager", e)
                notifyStateChange(SystemState.ERROR)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 连接到服务器
     */
    suspend fun connect(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    initialize()
                }
                
                notifyStateChange(SystemState.CONNECTING)
                
                // 使用 DeviceManager 连接
                deviceManager.connect()
                
                isConnected.set(true)
                notifyStateChange(SystemState.CONNECTED)
                
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                notifyStateChange(SystemState.DISCONNECTED)
                Result.failure(e)
            }
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
                delay(30000) // 每 30 秒同步一次
            }
        }
    }
    
    /**
     * 同步设备状态
     */
    private suspend fun syncDeviceStatus() {
        val status = collectDeviceStatus()
        deviceManager.sendDeviceStatus(status)
    }
    
    /**
     * 收集设备状态
     */
    private fun collectDeviceStatus(): JSONObject {
        return JSONObject().apply {
            put("device_id", config.getDeviceId())
            put("timestamp", System.currentTimeMillis())
            put("battery", getBatteryLevel())
            put("network", getNetworkStatus())
            put("memory", getMemoryUsage())
        }
    }
    
    /**
     * 获取电池电量
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 获取网络状态
     */
    private fun getNetworkStatus(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                capabilities == null -> "disconnected"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * 获取内存使用情况
     */
    private fun getMemoryUsage(): JSONObject {
        return try {
            val runtime = Runtime.getRuntime()
            JSONObject().apply {
                put("total", runtime.totalMemory())
                put("free", runtime.freeMemory())
                put("used", runtime.totalMemory() - runtime.freeMemory())
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    // ========================================================================
    // 任务执行
    // ========================================================================
    
    /**
     * 执行任务
     */
    suspend fun executeTask(taskType: String, payload: JSONObject): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                agentCore.handleTask(taskType, mapOf("payload" to payload.toString()))
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }
    
    // ========================================================================
    // 状态通知
    // ========================================================================
    
    /**
     * 添加状态监听器
     */
    fun addStateListener(id: String, listener: StateListener) {
        stateListeners[id] = listener
    }
    
    /**
     * 移除状态监听器
     */
    fun removeStateListener(id: String) {
        stateListeners.remove(id)
    }
    
    /**
     * 通知状态变化
     */
    private fun notifyStateChange(state: SystemState) {
        stateListeners.values.forEach { listener ->
            try {
                listener.onStateChanged(state)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify state listener", e)
            }
        }
    }
    
    // ========================================================================
    // 清理
    // ========================================================================
    
    /**
     * 关闭管理器
     */
    fun shutdown() {
        scope.cancel()
        isInitialized.set(false)
        isConnected.set(false)
        notifyStateChange(SystemState.SHUTDOWN)
        Log.i(TAG, "UnifiedCoreManager shutdown")
    }
    
    /**
     * 获取初始化状态
     */
    fun isInitialized(): Boolean = isInitialized.get()
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = isConnected.get()
}
