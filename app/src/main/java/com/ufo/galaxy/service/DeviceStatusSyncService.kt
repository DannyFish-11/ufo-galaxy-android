package com.ufo.galaxy.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.ufo.galaxy.ui.DeviceStatusPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * UFO Galaxy - 设备状态同步服务
 * 
 * 功能：
 * 1. 定期将设备状态同步到服务器
 * 2. 接收服务器的状态更新推送
 * 3. 维护 WebSocket 连接
 * 4. 支持断线重连
 * 
 * @author Manus AI
 * @version 2.0
 * @date 2026-02-06
 */
class DeviceStatusSyncService : Service() {
    
    companion object {
        private const val TAG = "DeviceStatusSyncService"
        private const val SYNC_INTERVAL = 10000L // 10秒同步一次
        private const val HEARTBEAT_INTERVAL = 30000L // 30秒心跳一次
        private const val RECONNECT_DELAY = 5000L // 5秒后重连
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    // 连接状态
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    // Binder
    inner class LocalBinder : Binder() {
        fun getService(): DeviceStatusSyncService = this@DeviceStatusSyncService
    }
    
    private val binder = LocalBinder()
    
    // 状态
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // 设备状态面板
    private var statusPanel: DeviceStatusPanel? = null
    
    // 网络客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    
    // 服务器配置
    private var serverUrl: String = ""
    private var deviceId: String = ""
    
    // 协程
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DeviceStatusSyncService created")
        statusPanel = DeviceStatusPanel(applicationContext)
        statusPanel?.startMonitoring()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            serverUrl = it.getStringExtra("server_url") ?: ""
            deviceId = it.getStringExtra("device_id") ?: android.os.Build.MODEL
        }
        
        if (serverUrl.isNotEmpty()) {
            startSync()
        }
        
        return START_STICKY
    }
    
    /**
     * 配置服务器
     */
    fun configure(serverUrl: String, deviceId: String) {
        this.serverUrl = serverUrl
        this.deviceId = deviceId
    }
    
    /**
     * 开始同步
     */
    fun startSync() {
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "Server URL not configured")
            return
        }
        
        Log.i(TAG, "Starting sync with server: $serverUrl")
        
        // 注册设备
        scope.launch {
            registerDevice()
        }
        
        // 连接 WebSocket
        connectWebSocket()
        
        // 启动定期同步
        syncJob = scope.launch {
            while (isActive) {
                syncStatus()
                delay(SYNC_INTERVAL)
            }
        }
        
        // 启动心跳
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }
    
    /**
     * 停止同步
     */
    fun stopSync() {
        syncJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * 注册设备
     */
    private suspend fun registerDevice() {
        try {
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("device_name", android.os.Build.MODEL)
                put("device_type", "android")
                put("category", "mobile")
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
                put("app_version", "2.0.0")
            }
            
            val request = Request.Builder()
                .url("$serverUrl/devices/register")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Device registered successfully")
                } else {
                    Log.e(TAG, "Failed to register device: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device: ${e.message}")
        }
    }
    
    /**
     * 同步状态
     */
    private suspend fun syncStatus() {
        if (_connectionState.value != ConnectionState.Connected) {
            return
        }
        
        try {
            val status = statusPanel?.toJson() ?: return
            
            val updateJson = JSONObject().apply {
                put("hardware", status.getJSONObject("camera"))
                put("is_online", true)
                put("is_connected_to_server", true)
                put("extra_data", status)
            }
            
            val request = Request.Builder()
                .url("$serverUrl/devices/$deviceId/status")
                .put(updateJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to sync status: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing status: ${e.message}")
        }
    }
    
    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat() {
        try {
            val request = Request.Builder()
                .url("$serverUrl/devices/$deviceId/heartbeat")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Heartbeat sent")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed: ${e.message}")
        }
    }
    
    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket() {
        _connectionState.value = ConnectionState.Connecting
        
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws/status"
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.Connected
                reconnectAttempts = 0
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                scheduleReconnect()
            }
        })
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")
            val data = json.optJSONObject("data")
            
            when (event) {
                "device_status_updated" -> {
                    // 处理其他设备的状态更新
                    Log.d(TAG, "Received status update: $data")
                }
                "command" -> {
                    // 处理服务器发送的命令
                    val command = data?.optString("command")
                    Log.i(TAG, "Received command: $command")
                    // TODO: 执行命令
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
        }
    }
    
    /**
     * 安排重连
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            return
        }
        
        reconnectAttempts++
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempts")
        
        scope.launch {
            delay(RECONNECT_DELAY * reconnectAttempts)
            connectWebSocket()
        }
    }
    
    /**
     * 发送消息到服务器
     */
    fun sendMessage(message: JSONObject) {
        webSocket?.send(message.toString())
    }
    
    /**
     * 获取当前设备状态
     */
    fun getCurrentStatus(): JSONObject? {
        return statusPanel?.toJson()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopSync()
        statusPanel?.cleanup()
        scope.cancel()
        Log.i(TAG, "DeviceStatusSyncService destroyed")
    }
}
