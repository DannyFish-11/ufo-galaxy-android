package com.ufo.galaxy.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AIP v3.0 - Agent Interaction Protocol (统一版本)
 * 
 * 此文件是 UFO Galaxy Android Agent 的协议单一事实来源。
 * 与服务端 galaxy_gateway/protocol/aip_v3.py 完全对齐。
 * 
 * 协议版本: 3.0
 * 最后更新: 2026-02-02
 */

// ============================================================================
// 设备类型定义 (与服务端完全对齐)
// ============================================================================

enum class DeviceType(val value: String) {
    // === 移动端 ===
    ANDROID_PHONE("android_phone"),
    ANDROID_TABLET("android_tablet"),
    ANDROID_TV("android_tv"),
    ANDROID_CAR("android_car"),
    ANDROID_WEAR("android_wear"),
    
    IOS_PHONE("ios_phone"),
    IOS_TABLET("ios_tablet"),
    IOS_WATCH("ios_watch"),
    
    // === 桌面端 ===
    WINDOWS_DESKTOP("windows_desktop"),
    WINDOWS_LAPTOP("windows_laptop"),
    WINDOWS_WSL("windows_wsl"),
    
    MACOS_DESKTOP("macos_desktop"),
    MACOS_LAPTOP("macos_laptop"),
    
    LINUX_DESKTOP("linux_desktop"),
    LINUX_SERVER("linux_server"),
    LINUX_RASPBERRY("linux_raspberry"),
    
    // === 云端 ===
    CLOUD_HUAWEI("cloud_huawei"),
    CLOUD_ALIYUN("cloud_aliyun"),
    CLOUD_TENCENT("cloud_tencent"),
    CLOUD_AWS("cloud_aws"),
    CLOUD_AZURE("cloud_azure"),
    
    // === 嵌入式/IoT ===
    EMBEDDED_ESP32("embedded_esp32"),
    EMBEDDED_ARDUINO("embedded_arduino"),
    IOT_GENERIC("iot_generic"),
    
    // === 容器/虚拟 ===
    CONTAINER_DOCKER("container_docker"),
    VIRTUAL_VM("virtual_vm"),
    
    // === 通用 ===
    UNKNOWN("unknown");
    
    companion object {
        fun fromString(value: String): DeviceType {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

enum class DevicePlatform(val value: String) {
    ANDROID("android"),
    IOS("ios"),
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
    CLOUD("cloud"),
    EMBEDDED("embedded"),
    UNKNOWN("unknown");
    
    companion object {
        fun fromString(value: String): DevicePlatform {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

// ============================================================================
// 消息类型定义 (与服务端完全对齐)
// ============================================================================

enum class MessageType(val value: String) {
    // === 设备管理 ===
    DEVICE_REGISTER("device_register"),
    DEVICE_REGISTER_ACK("device_register_ack"),
    DEVICE_UNREGISTER("device_unregister"),
    DEVICE_HEARTBEAT("heartbeat"),
    DEVICE_HEARTBEAT_ACK("heartbeat_ack"),
    DEVICE_STATUS("device_status"),
    DEVICE_CAPABILITIES("device_capabilities"),
    
    // === 任务调度 ===
    TASK_SUBMIT("task_submit"),
    TASK_ASSIGN("task_assign"),
    TASK_STATUS("task_status"),
    TASK_RESULT("task_result"),
    TASK_CANCEL("task_cancel"),
    TASK_PROGRESS("task_progress"),
    TASK_END("task_end"),
    
    // === 命令执行 ===
    COMMAND("command"),
    COMMAND_RESULT("command_result"),
    COMMAND_BATCH("command_batch"),
    
    // === GUI 操作 ===
    GUI_CLICK("gui_click"),
    GUI_SWIPE("gui_swipe"),
    GUI_INPUT("gui_input"),
    GUI_SCROLL("gui_scroll"),
    GUI_SCREENSHOT("gui_screenshot"),
    GUI_ELEMENT_QUERY("gui_element_query"),
    GUI_ELEMENT_WAIT("gui_element_wait"),
    GUI_SCREEN_CONTENT("gui_screen_content"),
    
    // === 屏幕/媒体 ===
    SCREEN_CAPTURE("screen_capture"),
    SCREEN_STREAM_START("screen_stream_start"),
    SCREEN_STREAM_STOP("screen_stream_stop"),
    SCREEN_STREAM_DATA("screen_stream_data"),
    
    // === 文件操作 ===
    FILE_READ("file_read"),
    FILE_WRITE("file_write"),
    FILE_DELETE("file_delete"),
    FILE_LIST("file_list"),
    FILE_TRANSFER("file_transfer"),
    
    // === 进程管理 ===
    PROCESS_START("process_start"),
    PROCESS_STOP("process_stop"),
    PROCESS_LIST("process_list"),
    PROCESS_STATUS("process_status"),
    
    // === 协调同步 ===
    COORD_SYNC("coord_sync"),
    COORD_BROADCAST("coord_broadcast"),
    COORD_LOCK("coord_lock"),
    COORD_UNLOCK("coord_unlock"),
    
    // === 节点管理 ===
    NODE_ACTIVATE("node_activate"),
    NODE_WAKEUP("node_wakeup"),
    NODE_SLEEP("node_sleep"),
    
    // === 事件广播 ===
    EVENT_BROADCAST("event_broadcast"),
    
    // === 错误处理 ===
    ERROR("error"),
    ERROR_RECOVERY("error_recovery");
    
    companion object {
        fun fromString(value: String): MessageType? {
            return values().find { it.value == value }
        }
    }
}

enum class TaskStatus(val value: String) {
    PENDING("pending"),
    RUNNING("running"),
    CONTINUE("continue"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");
    
    companion object {
        fun fromString(value: String): TaskStatus? {
            return values().find { it.value == value }
        }
    }
}

enum class ResultStatus(val value: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    SKIPPED("skipped"),
    TIMEOUT("timeout"),
    NONE("none");
    
    companion object {
        fun fromString(value: String): ResultStatus? {
            return values().find { it.value == value }
        }
    }
}

// ============================================================================
// 设备能力标志
// ============================================================================

object DeviceCapability {
    const val NONE = 0
    
    // 基础能力
    const val NETWORK = 1 shl 0
    const val STORAGE = 1 shl 1
    const val COMPUTE = 1 shl 2
    
    // GUI 能力
    const val GUI_READ = 1 shl 3
    const val GUI_WRITE = 1 shl 4
    const val GUI_SCREENSHOT = 1 shl 5
    const val GUI_STREAM = 1 shl 6
    
    // 输入能力
    const val INPUT_TOUCH = 1 shl 7
    const val INPUT_KEYBOARD = 1 shl 8
    const val INPUT_MOUSE = 1 shl 9
    const val INPUT_VOICE = 1 shl 10
    
    // 传感器
    const val SENSOR_GPS = 1 shl 11
    const val SENSOR_CAMERA = 1 shl 12
    const val SENSOR_MIC = 1 shl 13
    const val SENSOR_MOTION = 1 shl 14
    
    // 系统能力
    const val SYSTEM_SHELL = 1 shl 15
    const val SYSTEM_ROOT = 1 shl 16
    const val SYSTEM_INSTALL = 1 shl 17
    const val SYSTEM_NOTIFICATION = 1 shl 18
    
    // 通信能力
    const val COMM_BLUETOOTH = 1 shl 19
    const val COMM_NFC = 1 shl 20
    const val COMM_WIFI_DIRECT = 1 shl 21
    
    /**
     * 获取 Android 设备的默认能力
     */
    fun getAndroidDefaultCapabilities(): Int {
        return NETWORK or STORAGE or COMPUTE or
               GUI_READ or GUI_WRITE or GUI_SCREENSHOT or
               INPUT_TOUCH or INPUT_VOICE or
               SENSOR_GPS or SENSOR_CAMERA or SENSOR_MIC or SENSOR_MOTION or
               SYSTEM_NOTIFICATION or
               COMM_BLUETOOTH or COMM_NFC or COMM_WIFI_DIRECT
    }
}

// ============================================================================
// 核心数据结构
// ============================================================================

data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    
    fun toJSON(): JSONObject = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
    }
    
    companion object {
        fun fromJSON(json: JSONObject): Rect = Rect(
            x = json.optInt("x", 0),
            y = json.optInt("y", 0),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0)
        )
    }
}

data class UIElement(
    val elementId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val bounds: Rect? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isFocusable: Boolean = false,
    val isEnabled: Boolean = true,
    val isChecked: Boolean = false
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        elementId?.let { put("element_id", it) }
        className?.let { put("class_name", it) }
        text?.let { put("text", it) }
        contentDescription?.let { put("content_description", it) }
        viewId?.let { put("view_id", it) }
        bounds?.let { put("bounds", it.toJSON()) }
        put("is_clickable", isClickable)
        put("is_editable", isEditable)
        put("is_focusable", isFocusable)
        put("is_enabled", isEnabled)
        put("is_checked", isChecked)
    }
}

data class DeviceInfo(
    val deviceId: String,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
    val name: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val sdkVersion: Int? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val capabilities: Int = 0
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("device_type", deviceType.value)
        put("platform", platform.value)
        name?.let { put("name", it) }
        model?.let { put("model", it) }
        osVersion?.let { put("os_version", it) }
        sdkVersion?.let { put("sdk_version", it) }
        screenWidth?.let { put("screen_width", it) }
        screenHeight?.let { put("screen_height", it) }
        put("capabilities", capabilities)
    }
    
    companion object {
        fun createForAndroid(deviceId: String): DeviceInfo {
            val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
            return DeviceInfo(
                deviceId = deviceId,
                deviceType = DeviceType.ANDROID_PHONE,
                platform = DevicePlatform.ANDROID,
                name = android.os.Build.DEVICE,
                model = android.os.Build.MODEL,
                osVersion = android.os.Build.VERSION.RELEASE,
                sdkVersion = android.os.Build.VERSION.SDK_INT,
                screenWidth = displayMetrics.widthPixels,
                screenHeight = displayMetrics.heightPixels,
                capabilities = DeviceCapability.getAndroidDefaultCapabilities()
            )
        }
    }
}

data class Command(
    val commandId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val toolType: String = "action",
    val parameters: JSONObject = JSONObject(),
    val timeout: Int = 30
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("command_id", commandId)
        put("tool_name", toolName)
        put("tool_type", toolType)
        put("parameters", parameters)
        put("timeout", timeout)
    }
    
    companion object {
        fun fromJSON(json: JSONObject): Command = Command(
            commandId = json.optString("command_id", UUID.randomUUID().toString()),
            toolName = json.getString("tool_name"),
            toolType = json.optString("tool_type", "action"),
            parameters = json.optJSONObject("parameters") ?: JSONObject(),
            timeout = json.optInt("timeout", 30)
        )
    }
}

data class CommandResult(
    val commandId: String,
    val status: ResultStatus = ResultStatus.NONE,
    val result: Any? = null,
    val error: String? = null,
    val executionTime: Double = 0.0
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("command_id", commandId)
        put("status", status.value)
        result?.let { put("result", it) }
        error?.let { put("error", it) }
        put("execution_time", executionTime)
    }
}

// ============================================================================
// AIP v3.0 消息定义
// ============================================================================

data class AIPMessageV3(
    val version: String = "3.0",
    val messageId: String = UUID.randomUUID().toString(),
    val correlationId: String? = null,
    val type: MessageType,
    val deviceId: String,
    val deviceType: DeviceType? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val taskStatus: TaskStatus? = null,
    val commands: List<Command> = emptyList(),
    val results: List<CommandResult> = emptyList(),
    val payload: JSONObject = JSONObject(),
    val error: String? = null
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("version", version)
        put("message_id", messageId)
        correlationId?.let { put("correlation_id", it) }
        put("type", type.value)
        put("device_id", deviceId)
        deviceType?.let { put("device_type", it.value) }
        put("timestamp", timestamp)
        taskId?.let { put("task_id", it) }
        taskStatus?.let { put("task_status", it.value) }
        if (commands.isNotEmpty()) {
            put("commands", JSONArray().apply {
                commands.forEach { put(it.toJSON()) }
            })
        }
        if (results.isNotEmpty()) {
            put("results", JSONArray().apply {
                results.forEach { put(it.toJSON()) }
            })
        }
        put("payload", payload)
        error?.let { put("error", it) }
    }
    
    override fun toString(): String = toJSON().toString()
    
    companion object {
        fun fromJSON(json: JSONObject): AIPMessageV3? {
            return try {
                val typeStr = json.getString("type")
                val type = MessageType.fromString(typeStr) ?: return null
                
                val commands = mutableListOf<Command>()
                json.optJSONArray("commands")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        commands.add(Command.fromJSON(arr.getJSONObject(i)))
                    }
                }
                
                AIPMessageV3(
                    version = json.optString("version", "3.0"),
                    messageId = json.optString("message_id", UUID.randomUUID().toString()),
                    correlationId = json.optString("correlation_id", null),
                    type = type,
                    deviceId = json.getString("device_id"),
                    deviceType = json.optString("device_type", null)?.let { DeviceType.fromString(it) },
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    taskId = json.optString("task_id", null),
                    taskStatus = json.optString("task_status", null)?.let { TaskStatus.fromString(it) },
                    commands = commands,
                    payload = json.optJSONObject("payload") ?: JSONObject(),
                    error = json.optString("error", null)
                )
            } catch (e: Exception) {
                null
            }
        }
        
        fun fromString(jsonString: String): AIPMessageV3? {
            return try {
                fromJSON(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
        
        // ============ 快捷消息构造函数 ============
        
        fun createRegister(deviceId: String, deviceInfo: DeviceInfo): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.DEVICE_REGISTER,
                deviceId = deviceId,
                deviceType = deviceInfo.deviceType,
                payload = JSONObject().apply {
                    put("device_info", deviceInfo.toJSON())
                }
            )
        }
        
        fun createHeartbeat(deviceId: String): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.DEVICE_HEARTBEAT,
                deviceId = deviceId
            )
        }
        
        fun createTaskResult(
            deviceId: String,
            taskId: String,
            status: TaskStatus,
            results: List<CommandResult> = emptyList()
        ): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.TASK_RESULT,
                deviceId = deviceId,
                taskId = taskId,
                taskStatus = status,
                results = results
            )
        }
        
        fun createScreenContent(deviceId: String, elements: JSONArray): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.GUI_SCREEN_CONTENT,
                deviceId = deviceId,
                payload = JSONObject().apply {
                    put("elements", elements)
                    put("element_count", elements.length())
                }
            )
        }
        
        fun createScreenshot(deviceId: String, imageBase64: String, width: Int, height: Int): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.SCREEN_CAPTURE,
                deviceId = deviceId,
                payload = JSONObject().apply {
                    put("image", imageBase64)
                    put("width", width)
                    put("height", height)
                    put("format", "jpeg")
                }
            )
        }
        
        fun createCommandResult(
            deviceId: String,
            commandId: String,
            status: ResultStatus,
            result: Any? = null,
            error: String? = null
        ): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.COMMAND_RESULT,
                deviceId = deviceId,
                results = listOf(CommandResult(
                    commandId = commandId,
                    status = status,
                    result = result,
                    error = error
                ))
            )
        }
        
        fun createError(deviceId: String, error: String, correlationId: String? = null): AIPMessageV3 {
            return AIPMessageV3(
                type = MessageType.ERROR,
                deviceId = deviceId,
                correlationId = correlationId,
                error = error
            )
        }
    }
}

// 类型别名，用于向后兼容
typealias AIPMessage = AIPMessageV3
