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
| 1 (最高) | `/ws/device/{id}` | 推荐路径，含设备 ID |
| 2 | `/ws/android` | 通用 Android 路径 |
| 3 | `/ws/ufo3/{id}` | 传统 UFO³ 路径（兼容旧版服务器） |

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
  "type": "registration",
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

关键字段说明：
- `message_id` – 每条消息的唯一标识，由 `AIPMessageBuilder` 自动生成（8位UUID）
- `device_id` – 设备唯一标识（格式：`android_xxxxxxxx`），同时出现在顶层和 `payload` 中
- `device_type` – AIP 智能体类型标识（顶层 `"Android_Agent"`；`payload.device_type` 为 OS 类型 `"android"`）
- `capabilities` – 设备能力列表，由 `DeviceRegistry` 自动收集
- `timestamp` – Unix 时间戳（秒）

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

### 🔗 相关仓库

- **主仓库**: [ufo-galaxy-realization-v2](https://github.com/DannyFish-11/ufo-galaxy-realization-v2)
- **Android 客户端**: [ufo-galaxy-android](https://github.com/DannyFish-11/ufo-galaxy-android)

### 📊 版本历史

| 版本 | 日期 | 更新内容 |
|------|------|----------|
| v2.5.2 | 2026-03-07 | AIP v3 全栈系统性对齐：DeviceCommunication 入站消息通过 AIPMessageBuilder.parse() 统一规范化；EnhancedAIPClient 注册消息通过 AIPMessageBuilder.build() 构建 v3 信封后转换；Node50Client 切换 ServerConfig.buildWsUrl() + AIPMessageBuilder；GalaxyAgentV2/TaskExecutor 切换 AIPMessageBuilder；AIPProtocol 废弃标注 |
| v2.5.1 | 2026-03-07 | AIP v3 系统性对齐：message_id 统一、DeviceCommunication 使用 ServerConfig 路径回退、DeviceRegistry 消息通过 AIPMessageBuilder 构建 |
| v2.5.0 | 2026-02-21 | 合并两个仓库优点，系统性升级 |
| v2.2.0 | 2026-02-20 | 添加 Agent 系统、自主性服务 |
| v1.0.0 | 2026-01-01 | 初始版本 |

### 📄 许可证

MIT License

---

**Galaxy** - L4 级自主性智能系统
