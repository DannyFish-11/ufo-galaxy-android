package com.ufo.galaxy.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * UFO Galaxy - 设备注册管理器
 * 
 * 对齐服务端 device_registry.py
 * 提供完整的设备注册、发现、能力协商功能
 * 
 * @author UFO Galaxy Team
 * @version 2.0
 */
class DeviceRegistry(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceRegistry"
        
        @Volatile
        private var instance: DeviceRegistry? = null
        
        fun getInstance(context: Context): DeviceRegistry {
            return instance ?: synchronized(this) {
                instance ?: DeviceRegistry(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 设备信息
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()
    
    // 设备状态
    private val _deviceStatus = MutableStateFlow<DeviceStatus>(DeviceStatus.OFFLINE)
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()
    
    // 能力列表
    private val _capabilities = MutableStateFlow<List<String>>(emptyList())
    val capabilities: StateFlow<List<String>> = _capabilities.asStateFlow()
    
    // 分组和标签
    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()
    
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 初始化设备注册
     */
    fun initialize(): DeviceInfo {
        val info = collectDeviceInfo()
        _deviceInfo.value = info
        _capabilities.value = info.capabilities
        _groups.value = info.groups
        _tags.value = info.tags
        return info
    }
    
    /**
     * 收集设备信息
     */
    private fun collectDeviceInfo(): DeviceInfo {
        val deviceId = generateDeviceId()
        val capabilities = collectCapabilities()
        
        return DeviceInfo(
            deviceId = deviceId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceType = "android",
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            device = Build.DEVICE,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            sdkVersion = Build.VERSION.SDK_INT,
            appVersion = getAppVersion(),
            capabilities = capabilities,
            groups = listOf("mobile", "android"),
            tags = listOf("android", "mobile", "auto-registered"),
            metadata = mapOf(
                "board" to Build.BOARD,
                "hardware" to Build.HARDWARE,
                "product" to Build.PRODUCT,
                "fingerprint" to Build.FINGERPRINT
            )
        )
    }
    
    /**
     * 生成唯一的设备 ID
     */
    private fun generateDeviceId(): String {
        // 使用 Android ID 作为基础
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        
        return "android_${androidId.take(8)}"
    }
    
    /**
     * 获取应用版本
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
    
    /**
     * 收集设备能力
     */
    private fun collectCapabilities(): List<String> {
        val capabilities = mutableListOf<String>(
            // 基础能力
            "screen",
            "touch",
            "keyboard",
            
            // UI 自动化
            "ui_automation",
            "screen_capture",
            "app_control",
            "system_control",
            "text_input",
            "gesture_simulation",
            
            // 自然语言
            "natural_language"
        )
        
        // 检查摄像头
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            capabilities.add("camera")
            capabilities.add("camera_front")
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                capabilities.add("camera_back")
            }
        }
        
        // 检查麦克风
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            capabilities.add("microphone")
            capabilities.add("voice_input")
        }
        
        // 检查蓝牙
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            capabilities.add("bluetooth")
        }
        
        // 检查 NFC
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
            capabilities.add("nfc")
        }
        
        // 检查 GPS
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            capabilities.add("gps")
            capabilities.add("location")
        }
        
        // 检查传感器
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)) {
            capabilities.add("accelerometer")
        }
        
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
            capabilities.add("gyroscope")
        }
        
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)) {
            capabilities.add("compass")
        }
        
        // 检查网络
        capabilities.add("wifi")
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            capabilities.add("mobile_data")
        }
        
        return capabilities.sorted()
    }
    
    /**
     * 创建注册消息
     */
    fun createRegisterMessage(): JSONObject {
        val info = _deviceInfo.value ?: initialize()
        
        return JSONObject().apply {
            put("type", "register")
            put("action", "device_register")
            put("device_id", info.deviceId)
            put("timestamp", System.currentTimeMillis())
            put("message_id", UUID.randomUUID().toString().take(8))
            
            val payload = JSONObject().apply {
                put("device_id", info.deviceId)
                put("device_type", info.deviceType)
                put("device_name", info.deviceName)
                put("manufacturer", info.manufacturer)
                put("model", info.model)
                put("brand", info.brand)
                put("os_version", info.osVersion)
                put("sdk_version", info.sdkVersion)
                put("app_version", info.appVersion)
                
                put("capabilities", JSONArray(info.capabilities))
                put("groups", JSONArray(info.groups))
                put("tags", JSONArray(info.tags))
                
                put("metadata", JSONObject(info.metadata))
            }
            
            put("payload", payload)
        }
    }
    
    /**
     * 创建心跳消息
     */
    fun createHeartbeatMessage(): JSONObject {
        val info = _deviceInfo.value ?: initialize()
        
        return JSONObject().apply {
            put("type", "heartbeat")
            put("device_id", info.deviceId)
            put("timestamp", System.currentTimeMillis())
            put("message_id", UUID.randomUUID().toString().take(8))
            
            val payload = JSONObject().apply {
                put("status", _deviceStatus.value.value)
                put("capabilities_count", _capabilities.value.size)
            }
            
            put("payload", payload)
        }
    }
    
    /**
     * 更新设备状态
     */
    fun updateStatus(status: DeviceStatus) {
        _deviceStatus.value = status
    }
    
    /**
     * 添加能力
     */
    fun addCapability(capability: String) {
        val current = _capabilities.value.toMutableList()
        if (capability !in current) {
            current.add(capability)
            _capabilities.value = current.sorted()
        }
    }
    
    /**
     * 移除能力
     */
    fun removeCapability(capability: String) {
        val current = _capabilities.value.toMutableList()
        current.remove(capability)
        _capabilities.value = current
    }
    
    /**
     * 添加标签
     */
    fun addTag(tag: String) {
        val current = _tags.value.toMutableList()
        if (tag !in current) {
            current.add(tag)
            _tags.value = current
        }
    }
    
    /**
     * 添加分组
     */
    fun addToGroup(group: String) {
        val current = _groups.value.toMutableList()
        if (group !in current) {
            current.add(group)
            _groups.value = current
        }
    }
    
    /**
     * 获取设备 ID
     */
    fun getDeviceId(): String {
        return _deviceInfo.value?.deviceId ?: initialize().deviceId
    }
    
    /**
     * 检查能力是否可用
     */
    fun hasCapability(capability: String): Boolean {
        return _capabilities.value.contains(capability)
    }
}

/**
 * 设备信息
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val osVersion: String,
    val sdkVersion: Int,
    val appVersion: String,
    val capabilities: List<String>,
    val groups: List<String>,
    val tags: List<String>,
    val metadata: Map<String, Any>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("device_type", deviceType)
            put("manufacturer", manufacturer)
            put("model", model)
            put("brand", brand)
            put("device", device)
            put("os_version", osVersion)
            put("sdk_version", sdkVersion)
            put("app_version", appVersion)
            put("capabilities", JSONArray(capabilities))
            put("groups", JSONArray(groups))
            put("tags", JSONArray(tags))
            put("metadata", JSONObject(metadata))
        }
    }
}

/**
 * 设备状态
 */
enum class DeviceStatus(val value: String) {
    OFFLINE("offline"),
    ONLINE("online"),
    BUSY("busy"),
    ERROR("error")
}
