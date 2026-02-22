# UFO Galaxy Android Client V2

## 概述

UFO Galaxy Android 客户端，完全对齐服务端协议。

### 新增功能 (V2.0)

1. **设备注册管理器** (`DeviceRegistry`)
   - 自动收集设备信息
   - 唯一设备 ID 生成
   - 设备能力收集
   - 分组和标签管理

2. **统一通信管理器** (`DeviceCommunication`)
   - WebSocket 实时通信
   - 心跳保活
   - 消息确认机制
   - 命令执行和响应

3. **客户端集成管理器** (`GalaxyClient`)
   - 统一管理所有组件
   - 简单易用的 API
   - 命令处理器注册

## 快速开始

### 基本使用

```kotlin
// 创建客户端
val client = GalaxyClient.getInstance(context)

// 初始化
client.initialize()

// 设置监听器
client.setListener(object : GalaxyClient.Listener {
    override fun onConnected() {
        println("已连接")
    }
    
    override fun onDisconnected() {
        println("已断开")
    }
    
    override fun onMessage(message: DeviceMessage) {
        println("消息: ${message.action}")
    }
    
    override fun onCommand(command: CommandMessage) {
        println("命令: ${command.action}")
    }
    
    override fun onError(error: String) {
        println("错误: $error")
    }
})

// 连接
client.connect("http://server:8888")

// 发送消息
client.sendText("你好")
```

### 使用构建器

```kotlin
val client = GalaxyClient.Builder(context)
    .setServerUrl("http://server:8888")
    .setListener(listener)
    .build()
```

### 发送命令

```kotlin
// 发送命令并等待响应
val payload = JSONObject().apply {
    put("x", 100)
    put("y", 200)
}

val response = client.sendCommand("click", payload)
```

### 检查能力

```kotlin
// 检查设备能力
if (client.hasCapability("camera")) {
    // 有摄像头
}

// 添加能力
client.addCapability("custom_capability")

// 添加标签
client.addTag("test_device")

// 添加分组
client.addToGroup("development")
```

## 消息协议

### 设备注册消息

```json
{
    "type": "register",
    "action": "device_register",
    "device_id": "android_abc12345",
    "timestamp": 1234567890,
    "message_id": "abc12345",
    "payload": {
        "device_id": "android_abc12345",
        "device_type": "android",
        "device_name": "Samsung Galaxy S21",
        "manufacturer": "Samsung",
        "model": "Galaxy S21",
        "os_version": "Android 13",
        "capabilities": ["screen", "touch", "camera", ...],
        "groups": ["mobile", "android"],
        "tags": ["android", "mobile"]
    }
}
```

### 心跳消息

```json
{
    "type": "heartbeat",
    "device_id": "android_abc12345",
    "timestamp": 1234567890,
    "payload": {
        "status": "online"
    }
}
```

### 命令消息

```json
{
    "type": "command",
    "action": "click",
    "device_id": "android_abc12345",
    "timestamp": 1234567890,
    "message_id": "xyz789",
    "payload": {
        "x": 100,
        "y": 200
    }
}
```

## 设备能力

自动收集的能力：

| 能力 | 说明 |
|------|------|
| screen | 屏幕 |
| touch | 触摸 |
| keyboard | 键盘 |
| camera | 摄像头 |
| microphone | 麦克风 |
| bluetooth | 蓝牙 |
| nfc | NFC |
| gps | GPS |
| wifi | WiFi |
| accelerometer | 加速度计 |
| gyroscope | 陀螺仪 |

## 兼容性

- 完全兼容服务端 `device_registry.py`
- 完全兼容服务端 `device_communication.py`
- 完全兼容服务端 `system_integration.py`
- 向后兼容现有 AIP/1.0 协议

## 文件结构

```
app/src/main/java/com/ufo/galaxy/
├── GalaxyClient.kt              # 客户端集成管理器
├── device/
│   └── DeviceRegistry.kt        # 设备注册管理器
├── communication/
│   └── DeviceCommunication.kt   # 统一通信管理器
├── example/
│   └── UsageExample.kt          # 使用示例
└── ... (现有文件保持不变)
```

## 版本历史

- V2.0 - 完全对齐服务端协议
- V1.0 - 初始版本
