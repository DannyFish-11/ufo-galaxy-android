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
| v2.5.0 | 2026-02-21 | 合并两个仓库优点，系统性升级 |
| v2.2.0 | 2026-02-20 | 添加 Agent 系统、自主性服务 |
| v1.0.0 | 2026-01-01 | 初始版本 |

### 📄 许可证

MIT License

---

**Galaxy** - L4 级自主性智能系统
