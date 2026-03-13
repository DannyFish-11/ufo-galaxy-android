# Galaxy Android Client v2.5.0

## 🌌 UFO Galaxy - Android 客户端

L4 级自主性智能系统的 Android 客户端，支持多设备协同、自主控制、实时通信。

### ✨ 功能特性

#### 🤖 智能体系统
- **Agent 核心** - 多 Agent 架构，支持任务分发和执行
- **自主性服务** - 自动化操作，无需人工干预
- **自然语言输入** - 语音和文字输入理解

#### 📱 设备控制
- **无障碍服务** - 完整的设备自动化控制
- **多设备协调** - 同时控制多个设备
- **任务执行器** - 智能任务调度和执行

#### 🌐 通信协议
- **AIP 协议** - 高效的设备间通信协议
- **WebSocket** - 实时双向通信
- **WebRTC** - 实时视频流传输
- **Tailscale** - 安全的跨设备 VPN 连接

#### 🎨 用户界面
- **灵动岛 UI** - 类似 iOS 的灵动岛交互
- **悬浮窗** - 随时随地唤醒控制面板
- **书法卷轴风格** - 独特的展开动画

### 📦 模块结构

```
app/src/main/java/com/ufo/galaxy/
├── ai/                    # AI 能力 (GUI理解、自主学习)
├── agent/                 # Agent 系统
├── api/                   # API 客户端
├── automation/            # 自动化服务
├── autonomy/              # 自主性管理
├── client/                # 客户端核心
├── command/               # 命令处理
├── communication/         # 通用通信器
├── config/                # 配置管理
├── coordination/          # 多设备协调
├── core/                  # Agent 核心
├── executor/              # 任务执行器
├── input/                 # 自然语言输入
├── network/               # 网络层 (Tailscale, WebSocket)
├── nodes/                 # 节点系统
├── protocol/              # 通信协议
├── service/               # 后台服务
├── task/                  # 任务管理
├── ui/                    # 用户界面
├── utils/                 # 工具类
└── webrtc/                # WebRTC 屏幕 共享
```

### 🚀 快速开始

#### 1. 克隆仓库
```bash
git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
cd ufo-galaxy-android
```

#### 2. 构建 APK
```bash
./gradlew assembleDebug
```

#### 3. 安装到设备
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### 4. 配置权限
- 无障碍服务: 设置 → 无障碍 → Galaxy → 开启
- 悬浮窗权限: 设置 → 应用 → Galaxy → 悬浮窗 → 允许

#### 5. 连接服务器
- 配置服务器地址 (如: http://192.168.1.100:8080)
- 点击连接

### 📐 应用 Namespace 与入口 Activity

| 项目 | 值 |
|------|----|
| **Gradle namespace** | `com.ufo.galaxy` |
| **applicationId** | `com.ufo.galaxy` |
| **Launcher Activity** | `com.ufo.galaxy.MainActivity` |
| **Build script** | `app/build.gradle` (Groovy DSL) |

启动器入口为 `com.ufo.galaxy.MainActivity`（Jetpack Compose 界面）。  
`com.ufo.galaxy.client` 包下保留的 `LegacyEntryActivity` 为历史遗留入口，不再作为启动器。

---

## 🔌 服务器 URL 配置与端点回退

### 配置文件

编辑 `app/src/main/assets/config.properties` 来设置服务器地址，**不要**将任何 ws 路径写入该值——客户端会自动拼接：

```properties
# WebSocket 基础地址（仅主机 + 端口）
galaxy.gateway.url=ws://100.x.x.x:8000

# REST HTTP 基础地址
rest.base.url=http://100.x.x.x:8000
```

### WebSocket 路径回退顺序

`ServerConfig.WS_PATHS` 定义了按优先级排列的 WebSocket 路径候选列表：

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 (最高) | `/ws/android/{id}` | **AndroidBridge 主路径**（realization-v2 标准路由） |
| 2 | `/ws/device/{id}` | 含设备 ID 的通用回退路径 |
| 3 | `/ws/android` | 通用 Android 路径 |
| 4 | `/ws/ufo3/{id}` | 传统 UFO³ 路径（兼容旧版服务器） |

连接失败时，`AIPClient` 和 `EnhancedAIPClient` 会自动尝试下一个路径。

### REST API 回退

`GalaxyApiClient` 的设备注册、心跳和发现方法首先尝试 **v1 路由**，若服务器返回 `404` 则自动回退到**旧版路由**：

| 方法 | v1 路由（优先） | 旧版路由（回退） |
|------|----------------|-----------------|
| `registerDevice` | `POST /api/v1/devices/register` | `POST /api/devices/register` |
| `sendDeviceHeartbeat` | `POST /api/v1/devices/heartbeat` | `POST /api/devices/heartbeat` |
| `discoverDevices` | `GET  /api/v1/devices/discover` | `GET  /api/devices/discover` |

### AIP 消息格式（AIP v3 标准）

所有出站消息由 `AIPMessageBuilder.build()` 统一构建。消息同时包含 **AIP/1.0** 必需字段（向后兼容）和 **v3** 附加字段（最大化服务器兼容性）：

```json
{
  "protocol": "AIP/1.0",
  "type": "device_register",
  "source_node": "android_abc12345",
  "target_node": "server",
  "timestamp": 1700000000,
  "message_id": "a1b2c3d4",
  "payload": { "device_id": "android_abc12345", "capabilities": ["screen", "touch", "..."] },
  "version": "3.0",
  "device_id": "android_abc12345",
  "device_type": "Android_Agent"
}
```

#### 出站消息类型（AIPMessageBuilder.MessageType 常量）

| 常量 | 消息 `type` 值 | 说明 |
|------|----------------|------|
| `MessageType.DEVICE_REGISTER` | `device_register` | 设备注册（连接建立时发送） |
| `MessageType.HEARTBEAT` | `heartbeat` | 每 30 秒心跳 |
| `MessageType.CAPABILITY_REPORT` | `capability_report` | 注册后立即发送，上报设备能力 |
| `MessageType.TASK_ASSIGN` | `task_assign` | 服务端下发任务（入站） |
| `MessageType.COMMAND_RESULT` | `command_result` | 任务执行结果（出站） |

#### Legacy 消息类型映射

旧代码使用 `"registration"` / `"register"` 等字符串，已通过 `AIPMessageBuilder.LEGACY_TYPE_MAP` 映射为 v3 类型名。**新代码必须直接使用 `MessageType` 常量**。

| Legacy 字符串 | v3 等价 |
|---|---|
| `registration` | `device_register` |
| `register` | `device_register` |
| `command` | `task_assign` |

关键字段说明：
- `message_id` – 每条消息的唯一标识，由 `AIPMessageBuilder` 自动生成（8位UUID）
- `device_id` – 设备唯一标识（格式：`android_xxxxxxxx`），同时出现在顶层和 `payload` 中
- `device_type` – AIP 智能体类型标识（顶层 `"Android_Agent"`；`payload.device_type` 为 OS 类型 `"android"`）
- `capabilities` – 设备能力列表，由 `DeviceRegistry` 自动收集
- `timestamp` – Unix 时间戳（秒）

`capability_report` payload 必须包含 `platform`、`supported_actions`、`version` 三个字段，用于服务端 `CapabilityRegistry` 同步。

入站消息由 `AIPMessageBuilder.parse()` 统一解析，支持三种格式：AIP/1.0 原生、Microsoft Galaxy 格式以及 v3 格式。

### 🏗 通信栈架构

```
GalaxyClient  (统一入口)
    └── DeviceCommunication  (WebSocket 管理 + 心跳 + 入站规范化)
            └── AIPMessageBuilder  (消息构建/解析/规范化)
            └── ServerConfig  (URL 路径管理)

AIPClient / EnhancedAIPClient  (兼容/回退)
    └── AIPMessageBuilder  (消息构建/解析)
    └── ServerConfig  (URL 路径管理)

Node50Client  (legacy Node 50 连接)
    └── AIPMessageBuilder  (消息构建/解析)
    └── ServerConfig  (URL 路径管理，/ws/device/{id} 优先)
    └── AIPProtocol  (常量引用，设备信息/能力查询)
```

优先使用 `GalaxyClient + DeviceCommunication` 作为核心通信栈。`AIPClient`、`EnhancedAIPClient` 和 `Node50Client` 仅用于特定格式兼容/回退场景。所有出站消息均通过 `AIPMessageBuilder.build()` 构建；所有入站消息均通过 `AIPMessageBuilder.parse()` 规范化为 AIP/1.0 字段。

---

### 📋 权限要求

| 权限 | 用途 |
|------|------|
| 无障碍服务 | 设备自动化控制 |
| 悬浮窗 | 灵动岛和悬浮窗显示 |
| 网络 | 与服务器通信 |
| 麦克风 | 语音输入 (可选) |
| 相机 | 视频通话 (可选) |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` (API ≤ 30) | 蓝牙开关控制（Android 11 及以下） |
| `BLUETOOTH_CONNECT` (API 31+) | 蓝牙开关控制（Android 12+，需在运行时授予） |
| `CHANGE_WIFI_STATE` | WiFi 开关（仅 Android 9 及以下有效；Android 10+ 通过系统设置面板操作） |
| `WRITE_SETTINGS` | 屏幕亮度调节（需用户在"修改系统设置"中手动授权） |

#### ⚠️ WiFi 开关限制（Android 10+）

从 Android 10 (API 29) 起，非系统应用无法通过 `WifiManager.setWifiEnabled()` 直接控制 WiFi。  
本应用的处理方式：
- **Android 10+**: 自动打开系统 WiFi 设置面板 (`Settings.Panel.ACTION_WIFI`)，由用户手动确认开关。命令返回 `{ "status": "pending_user_action", "manual_required": true }`。
- **Android 9 及以下**: 直接通过 `WifiManager` 切换。

#### ⚠️ 蓝牙开关限制（Android 12+）

从 Android 12 (API 31) 起，蓝牙开关需要运行时权限 `BLUETOOTH_CONNECT`。  
如果权限未授予，命令返回 `{ "status": "error", "permission_required": "android.permission.BLUETOOTH_CONNECT" }`。

### 🔗 相关仓库

- **主仓库**: [ufo-galaxy-realization-v2](https://github.com/DannyFish-11/ufo-galaxy-realization-v2)
- **Android 客户端**: [ufo-galaxy-android](https://github.com/DannyFish-11/ufo-galaxy-android)

### 📚 文档

- [Android ↔ Server 集成指南](docs/ANDROID_BRIDGE_INTEGRATION.md) – WS 路径、消息类型、注册/能力上报流程、NL 调度链路

### 📊 版本历史

| 版本 | 日期 | 更新内容 |
|------|------|----------|
| v2.5.4 | 2026-03-13 | 跨设备开关修复：新增 `SystemControlHelper`（WiFi/蓝牙/音量/亮度统一实现）；`AndroidCommandExecutor.toggleWifi` 改为 Android 10+ 系统面板回退；新增 `toggle_bluetooth` 命令；`AutonomyManager` 补全所有 `TaskExecutor` 调用的缺失方法（setWiFi/setBluetooth/setVolume/setBrightness/captureUITree/performHome/performBack/performRecent/openApp/closeApp/switchToApp/clickByText/clickByResourceId/swipe/scroll/getCurrentApp/getDeviceStatus）；修复 `GalaxyApiClient` 悬空引用及重复 `cleanup()` 方法；新增蓝牙/WiFi/WRITE_SETTINGS 权限声明 |
| v2.5.3 | 2026-03-11 | Android↔realization-v2 系统性对接对齐：ServerConfig.WS_PATHS 主路径切换至 `/ws/android/{id}`（AndroidBridge 标准路由）；AIPMessageBuilder 新增 MessageType 常量（device_register/heartbeat/capability_report/task_assign/command_result）及 LEGACY_TYPE_MAP；AIPClient/EnhancedAIPClient/Node50Client 注册消息统一使用 device_register；DeviceRegistry 新增 createCapabilityReportMessage()；DeviceCommunication/AIPClient/EnhancedAIPClient 注册后自动发送 capability_report；新增 docs/ANDROID_BRIDGE_INTEGRATION.md |
| v2.5.2 | 2026-03-07 | AIP v3 全栈系统性对齐：DeviceCommunication 入站消息通过 AIPMessageBuilder.parse() 统一规范化；EnhancedAIPClient 注册消息通过 AIPMessageBuilder.build() 构建 v3 信封后转换；Node50Client 切换 ServerConfig.buildWsUrl() + AIPMessageBuilder；GalaxyAgentV2/TaskExecutor 切换 AIPMessageBuilder；AIPProtocol 废弃标注 |
| v2.5.1 | 2026-03-07 | AIP v3 系统性对齐：message_id 统一、DeviceCommunication 使用 ServerConfig 路径回退、DeviceRegistry 消息通过 AIPMessageBuilder 构建 |
| v2.5.0 | 2026-02-21 | 合并两个仓库优点，系统性升级 |
| v2.2.0 | 2026-02-20 | 添加 Agent 系统、自主性服务 |
| v1.0.0 | 2026-01-01 | 初始版本 |

### 📄 许可证

MIT License

---

**Galaxy** - L4 级自主性智能系统
