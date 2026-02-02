package com.ufo.galaxy.coordination

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UFO Galaxy - 多设备协调器
 * 
 * 功能：
 * 1. 设备发现与注册
 * 2. 任务分发与协调
 * 3. 状态同步
 * 4. 跨设备操作
 * 
 * 版本：1.0.0
 * 日期：2026-02-02
 */
class MultiDeviceCoordinator(private val context: Context) {
    
    companion object {
        private const val TAG = "MultiDeviceCoordinator"
        private const val HEARTBEAT_INTERVAL = 30000L  // 30 秒
        private const val DEVICE_TIMEOUT = 90000L      // 90 秒
    }
    
    // 服务端连接
    private var serverUrl: String = ""
    private var apiKey: String = ""
    
    // 当前设备信息
    private lateinit var currentDevice: DeviceInfo
    
    // 已发现的设备
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices
    
    // 协调状态
    private val _coordinationState = MutableStateFlow(CoordinationState.DISCONNECTED)
    val coordinationState: StateFlow<CoordinationState> = _coordinationState
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 心跳任务
    private var heartbeatJob: Job? = null
    
    /**
     * 初始化协调器
     */
    fun initialize(serverUrl: String, apiKey: String, deviceInfo: DeviceInfo) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
        this.currentDevice = deviceInfo
        
        Log.d(TAG, "Initialized with server: $serverUrl, device: ${deviceInfo.deviceId}")
    }
    
    /**
     * 连接到协调服务
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _coordinationState.value = CoordinationState.CONNECTING
            
            // 注册设备
            val success = registerDevice()
            
            if (success) {
                _coordinationState.value = CoordinationState.CONNECTED
                startHeartbeat()
                discoverDevices()
                true
            } else {
                _coordinationState.value = CoordinationState.DISCONNECTED
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _coordinationState.value = CoordinationState.DISCONNECTED
            false
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        heartbeatJob?.cancel()
        scope.launch {
            unregisterDevice()
        }
        _coordinationState.value = CoordinationState.DISCONNECTED
    }
    
    // ============================================================================
    // 设备管理
    // ============================================================================
    
    /**
     * 注册设备
     */
    private suspend fun registerDevice(): Boolean {
        val requestBody = JSONObject().apply {
            put("device_id", currentDevice.deviceId)
            put("device_type", currentDevice.deviceType)
            put("device_name", currentDevice.deviceName)
            put("capabilities", JSONArray(currentDevice.capabilities))
            put("os_version", currentDevice.osVersion)
            put("app_version", currentDevice.appVersion)
        }
        
        val response = sendRequest("$serverUrl/api/devices/register", requestBody)
        return response?.optBoolean("success", false) ?: false
    }
    
    /**
     * 注销设备
     */
    private suspend fun unregisterDevice(): Boolean {
        val requestBody = JSONObject().apply {
            put("device_id", currentDevice.deviceId)
        }
        
        val response = sendRequest("$serverUrl/api/devices/unregister", requestBody)
        return response?.optBoolean("success", false) ?: false
    }
    
    /**
     * 发现设备
     */
    suspend fun discoverDevices(): List<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            val response = sendRequest("$serverUrl/api/devices/list", JSONObject())
            
            val deviceList = mutableListOf<DeviceInfo>()
            response?.optJSONArray("devices")?.let { devicesArray ->
                for (i in 0 until devicesArray.length()) {
                    val deviceJson = devicesArray.getJSONObject(i)
                    deviceList.add(DeviceInfo.fromJson(deviceJson))
                }
            }
            
            _devices.value = deviceList
            Log.d(TAG, "Discovered ${deviceList.size} devices")
            deviceList
        } catch (e: Exception) {
            Log.e(TAG, "Device discovery failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    sendHeartbeat()
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat() {
        val requestBody = JSONObject().apply {
            put("device_id", currentDevice.deviceId)
            put("timestamp", System.currentTimeMillis())
            put("status", "online")
        }
        
        sendRequest("$serverUrl/api/devices/heartbeat", requestBody)
    }
    
    // ============================================================================
    // 任务协调
    // ============================================================================
    
    /**
     * 分发任务到指定设备
     */
    suspend fun dispatchTask(targetDeviceId: String, task: CoordinationTask): TaskResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("source_device_id", currentDevice.deviceId)
                put("target_device_id", targetDeviceId)
                put("task_id", task.taskId)
                put("task_type", task.taskType)
                put("payload", task.payload)
                put("priority", task.priority)
                put("timeout", task.timeout)
            }
            
            val response = sendRequest("$serverUrl/api/tasks/dispatch", requestBody)
            
            if (response?.optBoolean("success", false) == true) {
                TaskResult(
                    taskId = task.taskId,
                    status = TaskStatus.DISPATCHED,
                    result = response.optJSONObject("result")
                )
            } else {
                TaskResult(
                    taskId = task.taskId,
                    status = TaskStatus.FAILED,
                    error = response?.optString("error", "Unknown error")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task dispatch failed: ${e.message}")
            TaskResult(
                taskId = task.taskId,
                status = TaskStatus.FAILED,
                error = e.message
            )
        }
    }
    
    /**
     * 广播任务到所有设备
     */
    suspend fun broadcastTask(task: CoordinationTask): List<TaskResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TaskResult>()
        
        for (device in _devices.value) {
            if (device.deviceId != currentDevice.deviceId) {
                val result = dispatchTask(device.deviceId, task)
                results.add(result)
            }
        }
        
        results
    }
    
    /**
     * 查询任务状态
     */
    suspend fun queryTaskStatus(taskId: String): TaskResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("task_id", taskId)
            }
            
            val response = sendRequest("$serverUrl/api/tasks/status", requestBody)
            
            TaskResult(
                taskId = taskId,
                status = TaskStatus.valueOf(response?.optString("status", "UNKNOWN") ?: "UNKNOWN"),
                result = response?.optJSONObject("result"),
                error = response?.optString("error")
            )
        } catch (e: Exception) {
            TaskResult(
                taskId = taskId,
                status = TaskStatus.UNKNOWN,
                error = e.message
            )
        }
    }
    
    // ============================================================================
    // 跨设备操作
    // ============================================================================
    
    /**
     * 在远程设备上执行操作
     */
    suspend fun executeOnDevice(
        targetDeviceId: String,
        action: String,
        params: JSONObject
    ): JSONObject? = withContext(Dispatchers.IO) {
        val task = CoordinationTask(
            taskId = "exec_${System.currentTimeMillis()}",
            taskType = "execute",
            payload = JSONObject().apply {
                put("action", action)
                put("params", params)
            }
        )
        
        val result = dispatchTask(targetDeviceId, task)
        result.result
    }
    
    /**
     * 同步状态到所有设备
     */
    suspend fun syncState(state: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val task = CoordinationTask(
            taskId = "sync_${System.currentTimeMillis()}",
            taskType = "sync",
            payload = state
        )
        
        val results = broadcastTask(task)
        results.all { it.status == TaskStatus.DISPATCHED || it.status == TaskStatus.COMPLETED }
    }
    
    /**
     * 请求远程设备截图
     */
    suspend fun requestScreenshot(targetDeviceId: String): ByteArray? = withContext(Dispatchers.IO) {
        val result = executeOnDevice(targetDeviceId, "screenshot", JSONObject())
        result?.optString("data")?.let { base64 ->
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        }
    }
    
    // ============================================================================
    // 工具方法
    // ============================================================================
    
    private suspend fun sendRequest(url: String, body: JSONObject): JSONObject? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                JSONObject(response)
            } else {
                Log.e(TAG, "Request failed: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error: ${e.message}")
            null
        }
    }
}

// ============================================================================
// 数据类
// ============================================================================

/**
 * 设备信息
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceType: String,
    val deviceName: String,
    val capabilities: List<String>,
    val osVersion: String,
    val appVersion: String,
    val lastSeen: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("device_id", deviceId)
            put("device_type", deviceType)
            put("device_name", deviceName)
            put("capabilities", JSONArray(capabilities))
            put("os_version", osVersion)
            put("app_version", appVersion)
            put("last_seen", lastSeen)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): DeviceInfo {
            val capabilities = mutableListOf<String>()
            json.optJSONArray("capabilities")?.let { arr ->
                for (i in 0 until arr.length()) {
                    capabilities.add(arr.getString(i))
                }
            }
            
            return DeviceInfo(
                deviceId = json.getString("device_id"),
                deviceType = json.optString("device_type", "unknown"),
                deviceName = json.optString("device_name", "Unknown Device"),
                capabilities = capabilities,
                osVersion = json.optString("os_version", ""),
                appVersion = json.optString("app_version", ""),
                lastSeen = json.optLong("last_seen", System.currentTimeMillis())
            )
        }
    }
}

/**
 * 协调状态
 */
enum class CoordinationState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * 协调任务
 */
data class CoordinationTask(
    val taskId: String,
    val taskType: String,
    val payload: JSONObject,
    val priority: Int = 5,
    val timeout: Long = 60000
)

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,
    DISPATCHED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMEOUT,
    UNKNOWN
}

/**
 * 任务结果
 */
data class TaskResult(
    val taskId: String,
    val status: TaskStatus,
    val result: JSONObject? = null,
    val error: String? = null
)
