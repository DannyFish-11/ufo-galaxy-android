package com.ufo.galaxy.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ufo.galaxy.protocol.AIPMessageBuilder
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

        /** Execution mode: capability runs only on the local device. */
        const val EXEC_MODE_LOCAL = "local"

        /** Execution mode: capability requires routing to a remote server. */
        const val EXEC_MODE_REMOTE = "remote"

        /** Execution mode: capability can run on the local device or remotely. */
        const val EXEC_MODE_BOTH = "both"

        @Volatile
        private var instance: DeviceRegistry? = null
        
        fun getInstance(context: Context): DeviceRegistry {
            return instance ?: synchronized(this) {
                instance ?: DeviceRegistry(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Build the structured capability schema for a single [action].
         *
         * Each schema describes the action's parameter shape, return value,
         * semantic version, and [exec_mode] so that the server's routing layer
         * can accurately decide whether to dispatch execution locally or remotely.
         *
         * @param action  Capability name as reported in `supported_actions`.
         * @return        [CapabilitySchema] with all required fields populated.
         */
        fun buildCapabilitySchema(action: String): CapabilitySchema {
            return when (action) {
                "screen_capture" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply {
                        put("type", "string")
                        put("description", "Base64-encoded screenshot PNG")
                    },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "touch" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("x", JSONObject().apply { put("type", "number"); put("description", "X coordinate in pixels") })
                            put("y", JSONObject().apply { put("type", "number"); put("description", "Y coordinate in pixels") })
                        })
                        put("required", JSONArray().apply { put("x"); put("y") })
                    },
                    returns = JSONObject().apply { put("type", "boolean"); put("description", "True if the touch event was delivered") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "screen" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply { put("type", "object"); put("description", "Screen dimensions and orientation") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "keyboard" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("text", JSONObject().apply { put("type", "string"); put("description", "Text to type") })
                        })
                        put("required", JSONArray().apply { put("text") })
                    },
                    returns = JSONObject().apply { put("type", "boolean") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "text_input" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("text", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().apply { put("text") })
                    },
                    returns = JSONObject().apply { put("type", "boolean") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "ui_automation" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply { put("type", "string"); put("description", "UI action to perform") })
                            put("target", JSONObject().apply { put("type", "string"); put("description", "Target element selector") })
                        })
                    },
                    returns = JSONObject().apply { put("type", "object"); put("description", "Action execution result") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui", "accessibility")
                )
                "app_control" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        val commandEnum = JSONArray().apply { put("launch"); put("stop"); put("bring_to_front") }
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("package_name", JSONObject().apply { put("type", "string"); put("description", "Android package name") })
                            put("command", JSONObject().apply { put("type", "string"); put("enum", commandEnum) })
                        })
                    },
                    returns = JSONObject().apply { put("type", "boolean") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "system")
                )
                "system_control" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply { put("type", "string"); put("description", "System command (e.g. volume_up, back, home)") })
                        })
                        put("required", JSONArray().apply { put("command") })
                    },
                    returns = JSONObject().apply { put("type", "boolean") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "system")
                )
                "gesture_simulation" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("gesture", JSONObject().apply { put("type", "string"); put("description", "Gesture type (swipe, pinch, etc.)") })
                            put("points", JSONObject().apply { put("type", "array"); put("description", "Gesture path points") })
                        })
                    },
                    returns = JSONObject().apply { put("type", "boolean") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "ui")
                )
                "natural_language" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("text", JSONObject().apply { put("type", "string"); put("description", "Natural language instruction") })
                            put("context", JSONObject().apply { put("type", "object"); put("description", "Optional execution context") })
                        })
                        put("required", JSONArray().apply { put("text") })
                    },
                    returns = JSONObject().apply { put("type", "object"); put("description", "Parsed command or LLM response") },
                    version = "1.0",
                    execMode = EXEC_MODE_BOTH,
                    tags = listOf("android", "nlp")
                )
                "camera", "camera_front", "camera_back" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray().apply { put("jpeg"); put("png") }) })
                        })
                    },
                    returns = JSONObject().apply { put("type", "string"); put("description", "Base64-encoded image") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "camera")
                )
                "microphone", "voice_input" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("duration_ms", JSONObject().apply { put("type", "number"); put("description", "Recording duration in milliseconds") })
                        })
                    },
                    returns = JSONObject().apply { put("type", "string"); put("description", "Transcribed text or audio data") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "audio")
                )
                "bluetooth" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply { put("type", "array"); put("description", "List of nearby Bluetooth devices") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "connectivity")
                )
                "nfc" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply { put("type", "object"); put("description", "NFC tag data") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "connectivity")
                )
                "gps", "location" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("latitude", JSONObject().apply { put("type", "number") })
                            put("longitude", JSONObject().apply { put("type", "number") })
                            put("accuracy", JSONObject().apply { put("type", "number") })
                        })
                    },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "location")
                )
                "accelerometer", "gyroscope", "compass" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("x", JSONObject().apply { put("type", "number") })
                            put("y", JSONObject().apply { put("type", "number") })
                            put("z", JSONObject().apply { put("type", "number") })
                        })
                    },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "sensor")
                )
                "wifi" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply { put("type", "object"); put("description", "WiFi connection state and SSID") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "connectivity")
                )
                "mobile_data" -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
                    returns = JSONObject().apply { put("type", "object"); put("description", "Mobile data connection state") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL,
                    tags = listOf("android", "hardware", "connectivity")
                )
                else -> CapabilitySchema(
                    action = action,
                    params = JSONObject().apply { put("type", "object") },
                    returns = JSONObject().apply { put("type", "object") },
                    version = "1.0",
                    execMode = EXEC_MODE_LOCAL
                )
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
     *
     * Built via [AIPMessageBuilder] so that AIP/1.0 + v3 envelope fields
     * (`protocol`, `version`, `device_id`, `device_type`, `message_id`,
     * `timestamp`) are always present and consistent.  The payload reuses
     * [DeviceInfo.toJson] to avoid duplicating field assignments.
     *
     * The outbound `type` is [AIPMessageBuilder.MessageType.DEVICE_REGISTER]
     * to match the server AndroidBridge's expected message type.
     */
    fun createRegisterMessage(): JSONObject {
        val info = _deviceInfo.value ?: initialize()

        return AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.DEVICE_REGISTER,
            sourceNodeId = info.deviceId,
            targetNodeId = "server",
            payload = info.toJson(),
            deviceType = "Android_Agent"
        )
    }

    /**
     * 创建能力上报消息
     *
     * Must be sent immediately after receiving a registration ACK so that the
     * server's CapabilityRegistry can record the device's full capability set.
     *
     * Required payload fields:
     * - `platform`          – OS platform string (`"android"`)
     * - `supported_actions` – array of capability names (legacy, always present for backward compatibility)
     * - `version`           – client/app version string
     *
     * When [crossDeviceEnabled] is `true`, a `capability_schema` array is also
     * included.  Each entry contains the structured schema for one capability:
     * `action`, `params`, `returns`, `version`, `exec_mode`, and optional `tags`.
     * This lets the server route engine dispatch each capability to the correct
     * execution target without guessing.
     *
     * @param crossDeviceEnabled  When `true`, the structured `capability_schema`
     *                            is appended to the payload.  Must be `false` (or
     *                            omitted) for local-only sessions so that the
     *                            payload remains minimal.
     */
    fun createCapabilityReportMessage(crossDeviceEnabled: Boolean = false): JSONObject {
        val info = _deviceInfo.value ?: initialize()

        val payload = JSONObject().apply {
            put("platform", "android")
            put("supported_actions", JSONArray(_capabilities.value))
            put("version", info.appVersion)
            if (crossDeviceEnabled) {
                val schemas = buildAllCapabilitySchemas()
                val schemaArray = JSONArray()
                schemas.forEach { schemaArray.put(it.toJson()) }
                put("capability_schema", schemaArray)
            }
        }

        return AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.CAPABILITY_REPORT,
            sourceNodeId = info.deviceId,
            targetNodeId = "server",
            payload = payload,
            deviceType = "Android_Agent"
        )
    }

    /**
     * Build structured schemas for all currently registered capabilities.
     *
     * The returned list mirrors [capabilities] in order.  Each [CapabilitySchema]
     * entry includes `exec_mode` so that callers (e.g. `GalaxyWebSocketClient`)
     * can log exec_mode distribution and include the schema in the
     * `capability_report` payload when cross-device is enabled.
     *
     * This method reads the current value of [_capabilities], so calling it after
     * a [rebuildCapabilities] call reflects any runtime permission changes.
     */
    fun buildAllCapabilitySchemas(): List<CapabilitySchema> =
        _capabilities.value.map { buildCapabilitySchema(it) }

    /**
     * Re-collect device capabilities from the system.
     *
     * Call this after a runtime permission grant/revoke so that [capabilities]
     * and any subsequent [capability_report] reflect the new permission state
     * without requiring an app restart.
     */
    fun rebuildCapabilities() {
        _capabilities.value = collectCapabilities()
    }

    /**
     * 创建心跳消息
     *
     * Built via [AIPMessageBuilder] for consistent envelope fields.
     */
    fun createHeartbeatMessage(): JSONObject {
        val info = _deviceInfo.value ?: initialize()

        val payload = JSONObject().apply {
            put("status", _deviceStatus.value.value)
            put("capabilities_count", _capabilities.value.size)
        }

        return AIPMessageBuilder.build(
            messageType = AIPMessageBuilder.MessageType.HEARTBEAT,
            sourceNodeId = info.deviceId,
            targetNodeId = "server",
            payload = payload,
            deviceType = "Android_Agent"
        )
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

/**
 * Structured schema for a single device capability.
 *
 * Sent as part of the `capability_report` payload when cross-device mode is
 * active so that the server routing layer can make accurate execution decisions.
 *
 * @property action    Capability identifier matching an entry in `supported_actions`.
 * @property params    JSON Schema object describing accepted input parameters.
 * @property returns   JSON Schema object (or description) of the return value.
 * @property version   Semantic version of this capability's interface.
 * @property execMode  Execution target: [DeviceRegistry.EXEC_MODE_LOCAL],
 *                     [DeviceRegistry.EXEC_MODE_REMOTE], or [DeviceRegistry.EXEC_MODE_BOTH].
 * @property tags      Optional device/OS constraint hints (e.g. `"android"`, `"hardware"`).
 */
class CapabilitySchema(
    val action: String,
    val params: JSONObject,
    val returns: JSONObject,
    val version: String,
    val execMode: String,
    val tags: List<String> = emptyList()
) {
    /** Serialise to a [JSONObject] ready for inclusion in the `capability_schema` array. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("params", params)
        put("returns", returns)
        put("version", version)
        put("exec_mode", execMode)
        if (tags.isNotEmpty()) put("tags", JSONArray(tags))
    }
}
