# Android Multi-Transport Integration Guide

## 新增组件

| 文件 | 职责 |
|------|------|
| `transport/AipTransportManager.kt` | 统一传输入口（类似 V2 的 AIPTransport） |
| `transport/MqttGatewayClient.kt` | MQTT 传输适配器 |
| `transport/BleGatewayClient.kt` | BLE 传输适配器 |

## 集成步骤

### 1. 初始化传输管理器（Application.onCreate）

```kotlin
class GalaxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 创建 WebSocket 客户端（已有）
        val wsClient = GalaxyWebSocketClient(serverUrl = "wss://gateway:9000")

        // 2. 创建 MQTT 客户端（新增）
        val mqttClient = MqttGatewayClient(
            context = this,
            brokerHost = "mqtt.example.com",
            brokerPort = 1883,
            deviceId = deviceId,
            useTls = true,
        )
        mqttClient.connect()

        // 3. 创建 BLE 客户端（新增）
        val bleClient = BleGatewayClient(this)
        bleClient.startScan { address, name ->
            Log.i("BLE", "Found: $name ($address)")
            // TODO: Show device picker UI
        }

        // 4. 注册到统一传输管理器
        val transportManager = AipTransportManager.getInstance()
        transportManager.registerAdapter("websocket", wsClient)
        transportManager.registerAdapter("mqtt", mqttClient)
        transportManager.registerAdapter("ble", bleClient)
    }
}
```

### 2. 发送消息时指定传输

```kotlin
// 通过 WebSocket（默认）
val msg1 = """
    {"type":"device.ping","transport":"websocket","target":"win_001"}
""".trimIndent()
transportManager.sendJson(msg1)

// 通过 MQTT
transportManager.sendJson("""
    {"type":"device.ping","transport":"mqtt","target":"win_001"}
""".trimIndent())

// 通过 BLE（附近设备）
transportManager.sendJson("""
    {"type":"device.ping","transport":"ble","target":"wear_001"}
""".trimIndent())
```

### 3. Gradle 依赖（新增）

```gradle
// MQTT (HiveMQ)
implementation 'com.hivemq:hivemq-mqtt-client:1.3.3'

// BLE (Android 原生，无需额外依赖)
// 只需在 AndroidManifest.xml 中添加权限
```

### 4. AndroidManifest.xml 权限

```xml
<!-- BLE -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />

<!-- MQTT (网络) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 传输选择策略

| 场景 | 推荐传输 | 原因 |
|------|---------|------|
| WiFi 可用，网关可达 | WebSocket | 最低延迟，全双工 |
| WiFi 可用，网关不可达 | MQTT | 通过 MQTT broker 中转 |
| 无 WiFi，附近有设备 | BLE | 点对点，无需网络基础设施 |
| 户外/离线 | BLE | 完全离线可用 |

## 当前状态

- ✅ WebSocket：生产就绪
- 🆕 MQTT：已实现，需测试 broker 连接
- 🆕 BLE：已实现，需测试设备配对
- 🔄 自动传输选择：后续优化（根据网络状况自动切换）
