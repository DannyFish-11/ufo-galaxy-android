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

## 消息协议（AIP v3 标准）

所有出站消息统一通过 `AIPMessageBuilder.build()` 构建，保证以下字段始终存在：

| 字段 | 说明 | 示例 |
|------|------|------|
| `protocol` | 协议标识 | `"AIP/1.0"` |
| `version` | v3 版本号 | `"3.0"` |
| `type` | 消息类型 | `"register"` / `"heartbeat"` / `"command"` |
| `source_node` | 发送方设备 ID | `"android_abc12345"` |
| `target_node` | 目标节点 | `"server"` |
| `device_id` | 设备唯一标识 (v3) | `"android_abc12345"` |
| `device_type` | AIP 智能体类型 | `"Android_Agent"` |
| `message_id` | 消息唯一标识 | `"a1b2c3d4"` |
| `timestamp` | Unix 时间戳（秒） | `1700000000` |
| `payload` | 业务数据 | `{...}` |

### 设备注册消息

```json
{
    "protocol": "AIP/1.0",
    "version": "3.0",
    "type": "register",
    "source_node": "android_abc12345",
    "target_node": "server",
    "device_id": "android_abc12345",
    "device_type": "Android_Agent",
    "message_id": "a1b2c3d4",
    "timestamp": 1700000000,
    "action": "device_register",
    "payload": {
        "device_id": "android_abc12345",
        "device_type": "android",
        "device_name": "Samsung Galaxy S21",
        "manufacturer": "Samsung",
        "model": "Galaxy S21",
        "os_version": "Android 13",
        "capabilities": ["screen", "touch", "camera", "ui_automation", "..."],
        "groups": ["mobile", "android"],
        "tags": ["android", "mobile", "auto-registered"]
    }
}
```

### 心跳消息

```json
{
    "protocol": "AIP/1.0",
    "version": "3.0",
    "type": "heartbeat",
    "source_node": "android_abc12345",
    "target_node": "server",
    "device_id": "android_abc12345",
    "device_type": "Android_Agent",
    "message_id": "e5f6g7h8",
    "timestamp": 1700000030,
    "payload": {
        "status": "online",
        "capabilities_count": 15
    }
}
```

### 命令消息

```json
{
    "protocol": "AIP/1.0",
    "version": "3.0",
    "type": "command",
    "source_node": "android_abc12345",
    "target_node": "server",
    "device_id": "android_abc12345",
    "device_type": "Android_Agent",
    "message_id": "xyz789ab",
    "timestamp": 1700000060,
    "action": "click",
    "payload": {
        "action": "click",
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

- 完全对齐服务端 `device_registry.py`（AIP v3 标准字段）
- 完全对齐服务端 `device_communication.py`（`/ws/device/{id}` 优先）
- 完全对齐服务端 `system_integration.py`
- 向后兼容现有 AIP/1.0 协议（三种格式自动解析）
- REST API 优先使用 `/api/v1/devices/*`，自动回退到旧版路径

## 文件结构

```
app/src/main/java/com/ufo/galaxy/
├── GalaxyClient.kt              # 客户端集成管理器（核心入口）
├── device/
│   └── DeviceRegistry.kt        # 设备注册管理器（AIPMessageBuilder 构建消息）
├── communication/
│   └── DeviceCommunication.kt   # 统一通信管理器（ServerConfig 路径回退 + AIPMessageBuilder 入站规范化）
├── protocol/
│   ├── AIPMessageBuilder.kt     # AIP v3 消息构建器（统一字段，核心）
│   └── AIPProtocol.kt           # AIP/1.0 兼容层（已废弃消息构建方法，保留常量与设备信息工具）
├── config/
│   └── ServerConfig.kt          # URL 配置（/ws/device/{id} 优先）
├── client/
│   ├── AIPClient.kt             # AIP/1.0 兼容客户端（回退用）
│   ├── EnhancedAIPClient.kt     # Microsoft Galaxy 格式兼容（回退用，AIPMessageBuilder 构建注册信封）
│   └── Node50Client.kt          # Node 50 传统客户端（ServerConfig + AIPMessageBuilder）
├── agent/
│   └── GalaxyAgentV2.kt         # Agent 主控（AIPMessageBuilder 构建响应/错误消息）
├── executor/
│   └── TaskExecutor.kt          # 任务执行器（读取规范化 AIP/1.0 字段）
├── api/
│   └── GalaxyApiClient.kt       # REST API 客户端（v1 优先 + 旧版回退）
└── example/
    └── UsageExample.kt          # 使用示例
```

## 版本历史

- V2.5.2 - AIP v3 全栈系统性对齐：
  - `DeviceCommunication.handleMessage()` 通过 `AIPMessageBuilder.parse()` 规范化所有入站消息（AIP/1.0、Microsoft Galaxy、v3）
  - `EnhancedAIPClient.sendEnhancedRegistration()` 通过 `AIPMessageBuilder.build()` 构建 v3 信封后转换为 Microsoft Galaxy 格式
  - `Node50Client` 切换到 `ServerConfig.buildWsUrl()` + `AIPMessageBuilder`（`/ws/device/{id}` 优先）
  - `GalaxyAgentV2` 响应/错误消息通过 `AIPMessageBuilder.build()` 构建
  - `TaskExecutor` 直接读取 AIP/1.0 规范字段（`message_id`、`payload`）
  - `AIPProtocol` 消息构建/解析方法标注为废弃，指向 `AIPMessageBuilder`
- V2.1 - AIP v3 系统性对齐：message_id 统一、路径回退、AIPMessageBuilder 统一消息构建
- V2.0 - 完全对齐服务端协议
- V1.0 - 初始版本
