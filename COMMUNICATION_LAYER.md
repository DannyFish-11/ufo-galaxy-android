# Android 通信层实现文档

## 概述

本文档描述了 UFO³ Galaxy Android Agent 的完整通信层实现，包括 WebSocket 客户端、AIP v2.0 协议封装、设备注册和心跳机制。

## 架构

```
DeviceManager (设备管理器)
    ↓
WebSocketClient (WebSocket 客户端)
    ↓
AIPMessage (AIP v2.0 消息封装)
    ↓
Galaxy Gateway (主系统网关)
```

## 核心组件

### 1. WebSocketClient.kt (290 行)

**功能**：
- 连接到 Galaxy Gateway
- 发送和接收消息
- 自动重连机制（最多 10 次，每次延迟递增）
- 心跳保持（每 30 秒）
- 连接状态管理

**关键特性**：
- 使用 OkHttp 实现 WebSocket
- 支持文本和二进制消息
- 自动发送设备注册消息
- 连接超时：10 秒
- 读写超时：30 秒
- Ping 间隔：20 秒

**连接状态**：
- `DISCONNECTED` - 未连接
- `CONNECTING` - 连接中
- `CONNECTED` - 已连接
- `RECONNECTING` - 重连中
- `ERROR` - 错误

**使用示例**：
```kotlin
val client = WebSocketClient(
    gatewayUrl = "ws://192.168.1.100:8000/ws",
    deviceId = "android_device_001",
    onMessageReceived = { message -> 
        // 处理接收到的消息
    },
    onConnectionStateChanged = { state ->
        // 处理连接状态变化
    }
)
client.connect()
```

### 2. AIPMessage.kt (309 行)

**功能**：
- AIP (Agent Interaction Protocol) v2.0 消息封装
- 支持 10 种消息类型
- 消息验证和解析
- JSON 序列化和反序列化

**支持的消息类型**：
1. **基础消息**
   - `DEVICE_REGISTER` - 设备注册
   - `DEVICE_REGISTER_ACK` - 设备注册确认
   - `HEARTBEAT` - 心跳
   - `HEARTBEAT_ACK` - 心跳确认

2. **任务消息**
   - `TASK_REQUEST` - 任务请求
   - `TASK_RESPONSE` - 任务响应
   - `TASK_STATUS` - 任务状态

3. **控制消息**
   - `COMMAND` - 命令
   - `COMMAND_RESPONSE` - 命令响应

4. **数据消息**
   - `DATA_TRANSFER` - 数据传输
   - `DATA_ACK` - 数据确认

5. **多媒体消息**
   - `MEDIA_TRANSFER` - 多媒体传输
   - `MEDIA_ACK` - 多媒体确认

6. **工具发现消息**
   - `TOOL_DISCOVERY` - 工具发现
   - `TOOL_REGISTER` - 工具注册

7. **错误消息**
   - `ERROR` - 错误

**消息格式**：
```json
{
  "version": "2.0",
  "type": "device_register",
  "device_id": "android_device_001",
  "timestamp": 1706112000000,
  "payload": {
    "device_type": "android",
    "capabilities": {
      "screen_capture": true,
      "input_control": true,
      "accessibility": true,
      "voice_input": true,
      "webrtc": true
    }
  }
}
```

**使用示例**：
```kotlin
// 创建设备注册消息
val message = AIPMessage.createDeviceRegister(
    deviceId = "android_device_001",
    deviceType = "android",
    capabilities = mapOf(
        "screen_capture" to true,
        "input_control" to true,
        "accessibility" to true
    )
)

// 发送消息
client.sendMessage(message.toJSON())

// 解析消息
val receivedMessage = AIPMessage.fromJSON(jsonObject)
```

### 3. DeviceManager.kt (293 行)

**功能**：
- 管理设备注册
- 管理心跳机制
- 管理设备状态
- 处理来自 Gateway 的消息
- 注册工具

**关键特性**：
- 自动获取设备 ID（使用 Android ID）
- 自动发送设备注册消息
- 自动注册工具（android_control）
- 支持自定义消息处理器
- 提供设备信息查询

**设备能力**：
- `screen_capture` - 屏幕截图
- `input_control` - 输入控制
- `accessibility` - 无障碍服务
- `voice_input` - 语音输入
- `app_management` - 应用管理

**使用示例**：
```kotlin
val deviceManager = DeviceManager(
    context = applicationContext,
    gatewayUrl = "ws://192.168.1.100:8000/ws"
)

// 初始化
deviceManager.initialize()

// 连接
deviceManager.connect()

// 注册消息处理器
deviceManager.registerMessageHandler("custom_message") { payload ->
    // 处理自定义消息
}

// 发送消息
val message = AIPMessage.createTaskResponse(
    deviceId = deviceManager.getDeviceInfo()["device_id"] as String,
    taskId = "task_001",
    success = true,
    result = "Task completed"
)
deviceManager.sendMessage(message)

// 获取设备信息
val info = deviceManager.getDeviceInfo()
```

## 通信流程

### 1. 连接和注册流程

```
1. Android Agent 启动
   ↓
2. DeviceManager.initialize()
   ↓
3. DeviceManager.connect()
   ↓
4. WebSocketClient.connect()
   ↓
5. WebSocket 连接成功
   ↓
6. 自动发送 DEVICE_REGISTER 消息
   ↓
7. Galaxy Gateway 返回 DEVICE_REGISTER_ACK
   ↓
8. DeviceManager 标记为已注册
   ↓
9. 自动注册工具（TOOL_REGISTER）
   ↓
10. 开始心跳（每 30 秒）
```

### 2. 任务执行流程

```
1. Galaxy Gateway 发送 TASK_REQUEST
   ↓
2. DeviceManager 接收并解析
   ↓
3. 发送 TASK_STATUS (processing)
   ↓
4. 执行任务（调用相应节点）
   ↓
5. 发送 TASK_RESPONSE (结果)
```

### 3. 心跳流程

```
1. WebSocketClient 每 30 秒发送 HEARTBEAT
   ↓
2. Galaxy Gateway 返回 HEARTBEAT_ACK
   ↓
3. 保持连接活跃
```

### 4. 重连流程

```
1. 连接断开
   ↓
2. WebSocketClient 检测到断开
   ↓
3. 等待 5 秒 × 重连次数
   ↓
4. 尝试重新连接
   ↓
5. 最多重连 10 次
   ↓
6. 如果失败，标记为 ERROR 状态
```

## 配置

### Gateway URL 配置

在 `MainActivity.kt` 或配置文件中设置：

```kotlin
val gatewayUrl = "ws://192.168.1.100:8000/ws"
```

### 心跳间隔配置

在 `WebSocketClient.kt` 中修改：

```kotlin
delay(30000) // 30 秒
```

### 重连配置

在 `WebSocketClient.kt` 中修改：

```kotlin
private val maxReconnectAttempts = 10
private val reconnectDelayMs = 5000L
```

## 依赖

### build.gradle (app)

```gradle
dependencies {
    // OkHttp (WebSocket)
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    
    // Kotlin Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    
    // JSON
    implementation 'org.json:json:20230227'
}
```

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 集成到 MainActivity

```kotlin
class MainActivity : ComponentActivity() {
    
    private lateinit var deviceManager: DeviceManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 DeviceManager
        deviceManager = DeviceManager(
            context = applicationContext,
            gatewayUrl = "ws://192.168.1.100:8000/ws"
        )
        deviceManager.initialize()
        
        // 连接到 Gateway
        deviceManager.connect()
        
        // 注册自定义消息处理器
        deviceManager.registerMessageHandler("screenshot_request") { payload ->
            // 处理截图请求
            handleScreenshotRequest(payload)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        deviceManager.cleanup()
    }
    
    private fun handleScreenshotRequest(payload: JSONObject) {
        // 实现截图逻辑
    }
}
```

## 测试

### 1. 连接测试

```kotlin
// 测试连接
deviceManager.connect()

// 检查连接状态
val isConnected = deviceManager.isConnected()
Log.i("Test", "Is connected: $isConnected")
```

### 2. 消息发送测试

```kotlin
// 发送测试消息
val message = AIPMessage.createDataTransfer(
    deviceId = deviceId,
    dataType = "test",
    data = "Hello, Galaxy!"
)
val success = deviceManager.sendMessage(message)
Log.i("Test", "Message sent: $success")
```

### 3. 消息接收测试

```kotlin
// 注册测试消息处理器
deviceManager.registerMessageHandler("test_message") { payload ->
    Log.i("Test", "Received test message: $payload")
}
```

## 故障排除

### 1. 连接失败

**问题**：无法连接到 Gateway

**解决方案**：
- 检查 Gateway URL 是否正确
- 检查网络连接
- 检查防火墙设置
- 检查 Gateway 是否正在运行

### 2. 消息未接收

**问题**：发送消息后没有收到响应

**解决方案**：
- 检查消息格式是否正确
- 检查设备是否已注册
- 检查 Gateway 日志

### 3. 频繁重连

**问题**：连接频繁断开和重连

**解决方案**：
- 检查网络稳定性
- 增加心跳间隔
- 检查 Gateway 负载

## 性能优化

### 1. 消息批处理

对于大量消息，可以考虑批处理：

```kotlin
val messages = listOf(message1, message2, message3)
messages.forEach { deviceManager.sendMessage(it) }
```

### 2. 连接池复用

OkHttp 自动管理连接池，无需额外配置。

### 3. 心跳优化

根据网络环境调整心跳间隔：

```kotlin
// 稳定网络：60 秒
// 不稳定网络：30 秒
// 移动网络：20 秒
```

## 安全性

### 1. 使用 WSS (WebSocket Secure)

```kotlin
val gatewayUrl = "wss://your-domain.com/ws"
```

### 2. 设备认证

在设备注册时添加认证信息：

```kotlin
val message = AIPMessage.createDeviceRegister(
    deviceId = deviceId,
    deviceType = "android",
    capabilities = mapOf(
        "auth_token" to "your_auth_token"
    )
)
```

## 未来增强

1. **支持二进制消息** - 用于多媒体传输
2. **支持消息压缩** - 减少带宽使用
3. **支持离线队列** - 离线时缓存消息
4. **支持 P2P 连接** - 设备间直连
5. **支持 WebRTC** - 低延迟屏幕共享

## 版本历史

- **v2.0.0** (2026-01-24)
  - 完整实现 WebSocket 客户端
  - 完整实现 AIP v2.0 协议
  - 完整实现设备注册和心跳机制
  - 支持自动重连
  - 支持自定义消息处理器

## 贡献者

- UFO Galaxy Team

## 许可证

MIT License
