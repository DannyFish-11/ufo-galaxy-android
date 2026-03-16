# UFO Galaxy Android 客户端

**版本: v3.0.0**

L4 级自主性智能系统 Android 客户端，实现书法卷轴式 UI 和系统级 AI 交互。

> **v3.0.0 起客户端已完全切换为 AIP v3-only 模式**（`version="3.0"`, `protocol="AIP/1.0"`）。  
> v2 集成方请参阅 [从 v2 升级](#从-v2-升级) 章节。

## 仓库关系

本仓库是 [ufo-galaxy-realization](https://github.com/DannyFish-11/ufo-galaxy-realization) 主仓库的 Android 客户端子模块。

- **主仓库**: https://github.com/DannyFish-11/ufo-galaxy-realization
- **版本同步**: v3.0.0
- **同步时间**: 2026-03-16

## 功能特性

### 核心功能
- **书法卷轴式 UI** - 一展一收的写意风格交互
- **灵动岛悬浮窗** - 系统级常驻 AI 入口
- **实时通信** - WebSocket 连接 Galaxy 服务器
- **语音输入** - 支持语音识别输入
- **开机自启** - 系统启动时自动连接
- **WebRTC 多候选信令** - 支持 trickle/batch ICE 候选和 TURN 中继回退（Round 6）

### UI 组件
- `ScrollPaperContainer` - 书法卷轴容器
- `ChatScreen` - 聊天界面
- `DynamicIslandIndicator` - 灵动岛指示器

### 服务
- `GalaxyConnectionService` - 后台连接服务
- `FloatingWindowService` - 悬浮窗服务
- `BootReceiver` - 开机启动接收器

## 环境要求

- **Android Studio**: Arctic Fox (2020.3.1) 或更高版本
- **JDK**: 17 或更高版本
- **Android SDK**: API 26 (Android 8.0) 或更高版本
- **Kotlin**: 1.9.21
- **Gradle**: 8.4

## 快速开始

### 1. 克隆仓库
```bash
git clone https://github.com/DannyFish-11/ufo-galaxy-realization.git
cd ufo-galaxy-realization/android_client
```

### 2. 配置服务器地址（推荐：App 内配置，无需改代码/重打包）

从 v2.1 起，你可以直接在 App 内配置网关，**无需修改 `app/build.gradle` 或重打包 APK**：

1. 打开 App → 顶栏 ⚙ 图标 → **网络与诊断** 设置界面
2. 填入 **主机/IP**（支持 Tailscale 100.x.x.x）和 **端口**
3. 点击 **保存并重连** 即可

> **Tailscale 用户**：点击 **一键填入 Tailscale** 自动检测本机 Tailscale IP，或点击 **自动探测** 扫描常见 Tailscale 网段发现网关。

如果你需要在编译时指定默认地址（用于预配置 APK），仍可修改 `app/build.gradle`：
```gradle
buildConfigField "String", "GALAXY_SERVER_URL", '"ws://YOUR_SERVER_IP:8765"'
```
或在 `assets/config.properties` 中设置（不需要重编译）：
```properties
galaxy_gateway_url=ws://100.64.0.1:8765
rest_base_url=http://100.64.0.1:8765
```

### 3. 使用 Android Studio 打开
1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择 `android_client` 目录
4. 等待 Gradle 同步完成

### 4. 构建 APK
```bash
# 使用脚本
chmod +x build_apk.sh
./build_apk.sh

# 或使用 Gradle
./gradlew assembleDebug
```

### 5. 安装到设备
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
android_client/
├── app/
│   ├── src/main/
│   │   ├── java/com/ufo/galaxy/
│   │   │   ├── UFOGalaxyApplication.kt    # 应用入口
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt        # 主 Activity
│   │   │   │   ├── viewmodel/
│   │   │   │   │   └── MainViewModel.kt   # 主 ViewModel
│   │   │   │   ├── components/
│   │   │   │   │   ├── ScrollPaperContainer.kt  # 书法卷轴
│   │   │   │   │   └── ChatScreen.kt      # 聊天界面
│   │   │   │   └── theme/
│   │   │   │       ├── Theme.kt           # 主题配置
│   │   │   │       └── Type.kt            # 字体配置
│   │   │   ├── service/
│   │   │   │   ├── GalaxyConnectionService.kt  # 连接服务
│   │   │   │   ├── FloatingWindowService.kt    # 悬浮窗服务
│   │   │   │   └── BootReceiver.kt        # 开机启动
│   │   │   ├── network/
│   │   │   │   └── GalaxyWebSocketClient.kt    # WebSocket 客户端
│   │   │   └── data/
│   │   │       └── Models.kt              # 数据模型
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml            # 字符串资源
│   │   │   │   ├── colors.xml             # 颜色资源
│   │   │   │   └── themes.xml             # 主题资源
│   │   │   └── drawable/
│   │   │       ├── ic_notification.xml    # 通知图标
│   │   │       └── dynamic_island_bg.xml  # 灵动岛背景
│   │   └── AndroidManifest.xml            # 清单文件
│   ├── build.gradle                       # 模块构建配置
│   └── proguard-rules.pro                 # ProGuard 规则
├── build.gradle                           # 项目构建配置
├── settings.gradle                        # 项目设置
├── gradle.properties                      # Gradle 属性
├── build_apk.sh                           # APK 打包脚本
└── README.md                              # 本文档
```

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 网络通信 |
| RECORD_AUDIO | 语音输入 |
| CAMERA | 拍照功能 |
| SYSTEM_ALERT_WINDOW | 悬浮窗显示 |
| FOREGROUND_SERVICE | 后台服务 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| VIBRATE | 触觉反馈 |
| POST_NOTIFICATIONS | 通知显示 |

## 配置说明

### 优先级（Config Priority）

网关地址按以下优先级加载（高→低）：
1. **App 内设置**（SharedPreferences）— 推荐，无需改代码/重打包
2. **assets/config.properties** — 预配置 APK 的便捷选项
3. **编译时默认值**（`BuildConfig.GALAXY_SERVER_URL`）

### App 内配置（v2.1 新增，推荐方式）

打开 App → 顶栏 ⚙ → **网络与诊断** 界面：

| 字段 | 说明 | 示例 |
|------|------|------|
| 主机/IP | 网关服务器 IP 或域名 | `100.64.0.1` |
| 端口 | 网关端口 | `8765` |
| 使用 TLS | 开启 `wss://` / `https://` | 默认关 |
| 允许自签名 | 调试环境信任自签名证书 | **仅限内网** |
| 设备 ID | 上报到服务端的设备标识 | 留空用系统默认 |
| REST 基础 URL | 留空则由主机+端口自动推导 | |
| 指标上报端点 | 可选，留空则仅本地日志 | `http://100.64.0.1:9090/metrics` |

### 服务器配置（编译时）
在 `app/build.gradle` 中配置:
```gradle
defaultConfig {
    buildConfigField "String", "GALAXY_SERVER_URL", '"ws://192.168.1.100:8765"'
}

buildTypes {
    release {
        buildConfigField "String", "GALAXY_SERVER_URL", '"wss://galaxy.ufo.ai:8765"'
    }
}
```

### Tailscale 配置
UFO Galaxy 支持通过 [Tailscale](https://tailscale.com/) 私有 VPN 实现跨设备通信：
1. 在服务端和 Android 设备上均安装并登录 Tailscale
2. 在 App 内 ⚙ 设置界面点击 **一键填入 Tailscale** 自动填入本机 IP
3. 或点击 **自动探测** 扫描常见 Tailscale 网段 (`100.64.0.0/10`) 发现网关
4. 点击 **保存并重连** 完成配置

> 无需修改任何代码或重新打包 APK。

### 签名配置
Release 构建需要签名密钥:
```gradle
signingConfigs {
    release {
        storeFile file("keystore.jks")
        storePassword "your_password"
        keyAlias "ufo_galaxy"
        keyPassword "your_password"
    }
}
```

## 开发指南

### 添加新功能
1. 在 `data/Models.kt` 中定义数据模型
2. 在 `network/` 中实现网络逻辑
3. 在 `ui/components/` 中创建 UI 组件
4. 在 `ui/viewmodel/` 中管理状态

### 自定义主题
编辑 `ui/theme/Theme.kt` 修改配色方案:
```kotlin
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2C2C2C),           // 墨色
    primaryContainer = Color(0xFFF5F0E6),   // 宣纸色
    secondary = Color(0xFF8B4513),          // 朱砂色
    // ...
)
```

## 常见问题

### Q: 悬浮窗不显示？
A: 请在系统设置中授予 UFO Galaxy 悬浮窗权限。

### Q: 无法连接服务器？
A: 检查服务器地址配置和网络连接，确保服务器正在运行。

### Q: 语音输入不工作？
A: 请授予麦克风权限，并确保设备支持语音识别。

## 许可证

MIT License

---

## 🔗 关联仓库

本 Android 客户端与以下服务端仓库配合使用：

- **服务端**: [ufo-galaxy-realization-v2](https://github.com/DannyFish-11/ufo-galaxy-realization-v2)
- **当前版本**: v2.0.3
- **协议版本**: AIP v2.0

### 版本兼容性

| Android 版本 | 服务端版本 | 协议版本 | 状态 |
|-------------|-----------|---------|------|
| v2.0.1 | v2.0.3 | AIP v2.0 | ✅ 兼容 |
| v2.0.1 | v2.0.4+ | AIP v3.0 | ✅ 兼容 |


---

## 📱 最新版本

| 组件 | 版本 | 状态 |
|------|------|------|
| 服务端 | v2.0.4 | ✅ 已发布 |
| Android 客户端 | v2.0.1 | ✅ 已发布 |
| 协议版本 | AIP v3.0 | ✅ 兼容 |

### 快速开始

1. 克隆服务端仓库
```bash
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2
./deploy.sh
```

2. 构建 Android 客户端
```bash
./gradlew assembleDebug
```

3. 安装到设备
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```


---

## 🌐 分布式部署

UFO Galaxy V2 支持分布式部署，可以将任意设备设为主节点：

### 支持的部署模式

| 模式 | 说明 |
|------|------|
| 云服务器主节点 | 云服务器作为主节点，本地设备作为工作节点 |
| 本地电脑主节点 | 本地电脑作为主节点，云服务器作为备用 |
| 多主节点集群 | 多个主节点负载均衡 |

### 配置方法

```bash
# 主节点
export UFO_NODE_ID="master"
export UFO_NODE_ROLE="coordinator"
./start.sh

# 工作节点
export UFO_NODE_ID="worker-$(hostname)"
export UFO_NODE_ROLE="worker"
export MASTER_URL="ws://master-host:8765"
./start.sh --worker
```


---

## 📋 设备注册流程

### Android 设备注册

1. **安装 APK**
   - 下载并安装 UFO Galaxy 客户端

2. **配置服务器地址**
   - 打开应用 → 设置
   - 输入服务器地址：
     - 本地网络: `ws://192.168.x.x:8765`
     - Tailscale: `ws://100.x.x.x:8765`
     - 云服务器: `wss://your-domain.com:8765`

3. **自动注册**
   - 应用启动后自动发送注册消息
   - 设备 ID、类型、能力自动上报

### 其他设备注册

| 设备类型 | 注册方式 |
|----------|----------|
| Windows | `python register_device.py` |
| Linux | `python register_device.py` |
| macOS | `python register_device.py` |


---

## 🖥️ 可视化管理

### 服务端界面

| 界面 | 地址 |
|------|------|
| 控制面板 | http://服务器IP:8080 |
| **设备管理** | http://服务器IP:8080/devices |
| API 文档 | http://服务器IP:8080/docs |

### 设备管理功能

- 📱 可视化注册设备
- 📋 查看所有设备状态
- 🟢 实时在线/离线监控
- ⚡ 发送控制命令


---

## 🎮 交互系统

### 唤醒方式

| 方式 | 说明 |
|------|------|
| **边缘滑动** | 从屏幕右侧边缘向左滑动 |
| **点击灵动岛** | 点击顶部灵动岛展开 |
| **长按灵动岛** | 打开主应用 |

### 交互功能

| 功能 | 说明 |
|------|------|
| **语音输入** | 点击麦克风按钮说话 |
| **打字输入** | 在输入框输入命令 |
| **悬浮窗** | 随时随地唤起交互 |
| **灵动岛** | 极客风展开动画 |

### UI 风格

```
┌──────────────────────────────────────┐
│                                      │
│         ┌────────────────┐           │
│         │  UFO Galaxy ●  │  <- 灵动岛 │
│         └────────────────┘           │
│                                      │
│    ┌────────────────────────────┐   │
│    │                            │   │
│    │   用户: 今天天气怎么样      │   │
│    │                            │   │
│    │   系统: 今天晴天，温度...   │   │
│    │                            │   │
│    │   ┌──────────────────────┐ │   │
│    │   │ 说点什么...          │ │   │
│    │   └──────────────────────┘ │   │
│    │                            │   │
│    │   [🎤]          [发送]     │   │
│    │                            │   │
│    └────────────────────────────┘   │
│                                      │
└──────────────────────────────────────┘
```

### 与主仓库配合

1. 启动主仓库服务
   ```bash
   cd ufo-galaxy-realization-v2
   ./start.sh
   ```

2. 配置 Android 连接
   - 打开应用设置
   - 输入服务器地址
   - 连接成功后即可交互


---

## 🔄 V2 后端兼容性 (AIP v3.0)

### 单一 Android 客户端声明

本仓库 (`ufo-galaxy-android`) 是 **唯一的 Android 客户端实现**。所有 Android 能力上报、任务执行和诊断遥测均以本 APK 为准。

### 服务端端点要求

Android 客户端要求 V2 后端暴露以下 WebSocket 端点（AIP v3.0 协议）：

| 端点 | 用途 |
|------|------|
| `/ws/android` | Android 客户端统一接入端点 |
| `/ws/device/{device_id}` | 按设备 ID 点对点通信 |

### 端对端握手流程

Android 客户端与 V2 后端的完整交互序列：

```
Android 客户端                        V2 后端 (Galaxy)
      |                                     |
      |── WebSocket connect /ws/android ──▶ |
      |                                     |
      |── capability_report ───────────────▶|  ← AIP v3.0，包含 platform、
      |   {platform, device_id,             |    device_id、supported_actions、
      |    supported_actions, version}       |    version；供 Loop 3 推断能力差距
      |                                     |
      |◀─ registration_ack ─────────────── |
      |                                     |
      |── heartbeat (每 30 秒) ────────────▶|
      |◀─ heartbeat_ack ────────────────── |
      |                                     |
      |◀─ task_assign ─────────────────── |  ← 服务端下发任务
      |                                     |
      |── task_result ────────────────────▶|  ← 成功结果
      |   或                               |
      |── diagnostics_payload ────────────▶|  ← 失败时上报，供 Loop 1 & Loop 2
      |   {error_type, error_context,       |    分类重复失败、触发自修复或学习
      |    task_id, node_name}              |
      |                                     |
```

### 能力上报示例（capability_report）

```json
{
  "type": "capability_report",
  "version": "3.0",
  "platform": "android",
  "device_id": "<android_id>",
  "supported_actions": [
    "location", "camera", "sensor_data", "automation",
    "notification", "sms", "phone_call", "contacts",
    "calendar", "voice_input", "screen_capture", "app_control"
  ]
}
```

### 诊断载荷示例（diagnostics_payload）

任务失败时通过 `GalaxyWebSocketClient.sendDiagnostics()` 上报：

```json
{
  "type": "diagnostics_payload",
  "device_id": "<android_id>",
  "error_type": "permission_denied",
  "error_context": "Camera permission not granted on step 1",
  "task_id": "task-abc-123",
  "node_name": "android_agent_01"
}
```

服务端需处理的诊断字段：

| 字段 | 说明 |
|------|------|
| `error_type` | 错误分类（如 `network_timeout`、`permission_denied`、`execution_failed`） |
| `error_context` | 错误详情或堆栈摘要 |
| `task_id` | 失败任务的唯一标识 |
| `node_name` | 上报诊断的 Android 节点名称 |

---



本 Android 客户端配合 Galaxy 主系统使用：

### 主系统启动

```bash
# 克隆主系统
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2

# 启动系统
./galaxy.sh daemon

# 配置系统
open http://localhost:8080/config
```

### 三级优先模型

Galaxy 支持三级优先 LLM 模型：

| 优先级 | 模型 | 权重 |
|--------|------|------|
| 1 | GPT-4o | 60% |
| 2 | DeepSeek | 30% |
| 3 | Groq | 10% |

### 7×24 运行

Galaxy 支持 7×24 小时无间断运行：

- 自动启动
- 崩溃自动重启
- 健康检查
- 心跳监控


---

## 🚀 完整部署流程

### 1. 主系统安装 (一次配置)

```bash
# 克隆主系统
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2

# 一键安装 (自动配置开机自启动)
./install.sh    # Linux/macOS
install.bat     # Windows

# 完成！主系统现在 7×24 运行，开机自启动
```

### 2. Android 客户端安装

```bash
# 克隆 Android 仓库
git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
cd ufo-galaxy-android

# 配置服务器地址 (编辑 app/build.gradle)
# buildConfigField "String", "GALAXY_SERVER_URL", '"ws://你的服务器IP:8765"'

# 构建 APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开始使用

- **主系统**: 按 F12 键唤醒交互界面
- **Android**: 从屏幕右侧边缘滑动唤醒灵动岛


---

## 🧠 记忆系统

Galaxy 主系统拥有完整的记忆功能：

### 访问记忆中心

```
http://服务器IP:8080/memory
```

### 记忆类型

- **对话历史**: 自动保存所有对话
- **长期记忆**: 记住重要的事实、偏好、事件
- **用户偏好**: 学习并记住你的设置

### 使用示例

```
用户: 记住我喜欢喝咖啡
Galaxy: 好的，我已经记住了。

用户: 我喜欢什么？
Galaxy: 根据我的记忆，你喜欢喝咖啡。
```


---

## 🔀 AI 智能路由

Galaxy 主系统拥有 AI 驱动的智能路由：

### 访问路由中心

```
http://服务器IP:8080/router
```

### 工作原理

```
用户输入 → AI 分析任务 → 选择最佳模型 → 执行
```

### 优化目标

- **速度优先**: 选择最快的模型
- **成本优先**: 选择最便宜的模型
- **质量优先**: 选择最好的模型
- **平衡模式**: 综合考虑



---

## 📋 Android 能力就绪检查 (PR7)

### 概述

应用启动时和自主执行前会自动执行三项能力就绪检查：

| 检查项 | 说明 | 降级影响 |
|--------|------|----------|
| **本地模型就绪** | MobileVLM 和 SeeClick 模型文件存在且完整 | 本地推理不可用，降级为纯网络模式 |
| **无障碍服务** | HardwareKeyListener 无障碍服务已启用 | 无法捕获截图和执行 UI 操作 |
| **悬浮窗权限** | 已授予 SYSTEM_ALERT_WINDOW 权限 | 灵动岛悬浮窗不显示 |

### 降级模式 UI

任何检查未通过时，主界面顶部会显示橙色状态横幅（非阻断式），列出具体未就绪项。
所有功能仍可使用，横幅仅作提示。

### 手动验证步骤

**1. 本地模型检查**
- 在不含模型文件的设备上启动 → 横幅显示"本地模型未就绪"
- 复制/下载模型文件后重启 → 横幅消失

**2. 无障碍服务检查**
- 在 设置 → 无障碍 中禁用 UFO Galaxy → 横幅显示"无障碍服务未启用"
- 重新启用后回到前台 (onResume) → 横幅消失

**3. 悬浮窗权限检查**
- 在 设置 → 悬浮窗 中撤销权限 → 横幅显示"悬浮窗权限未授予"
- FloatingWindowService / EnhancedFloatingService 不崩溃，发送通知引导授权
- 点击通知跳转到悬浮窗权限设置页面
- 授权后回到前台 → 横幅消失

**4. Capability Report 元数据**
- 开启跨设备模式，在 WebSocket 消息中检查 `capability_report`
- 必须包含 `model_ready`、`accessibility_ready`、`overlay_ready` 字段

**5. 前台服务保活**
- 后台运行 GalaxyConnectionService → 系统杀死进程 → 服务通过 `START_STICKY` 自动重启
- BootReceiver 在设备重启后自动拉起 GalaxyConnectionService

### 能力报告示例

```json
{
  "goal_execution_enabled": true,
  "local_model_enabled": true,
  "cross_device_enabled": true,
  "parallel_execution_enabled": false,
  "device_role": "phone",
  "model_ready": true,
  "accessibility_ready": true,
  "overlay_ready": true
}
```


---

## 🔒 跨设备开关硬约束 (Round 4)

### 概述

跨设备开关（Cross-Device Switch）是一个**硬约束**：关闭时，所有跨设备的 WebSocket 连接、任务调度和信令均被强制拦截，不会静默降级。

### 关闭（OFF）行为

| 操作 | 行为 |
|------|------|
| `GalaxyWebSocketClient.connect()` | 无操作（no-op），不建立 WS 连接 |
| `GalaxyWebSocketClient.sendJson()` | **立即返回 false**，写入带 `trace_id` 的警告日志，不进入离线队列 |
| `InputRouter.route()` | 强制走本地路径（`RouteMode.LOCAL`），不上行 WS，不报错 |
| `MultiDeviceCoordinator.dispatchParallel()` | 立即返回全失败结果（`success=false`，`error="cross_device_disabled"`），不调用调度函数 |
| `RuntimeController.start()` | 应在 UI 层判断；若未调用则不建立任何跨设备状态 |

### 错误日志格式

当 `crossDeviceEnabled=false` 时，`sendJson` 拦截日志的格式为：

```
W/GalaxyWebSocket: [WS:BLOCKED] sendJson rejected: cross_device=off trace_id=<uuid> type=<msg_type> reason=cross_device_disabled
```

`MultiDeviceCoordinator` 拦截日志格式为：

```
W/MultiDeviceCoordinator: [COORD] dispatchParallel blocked: cross_device=off trace_id=<uuid> group_id=<id> reason=cross_device_disabled
```

### 唯一调度路径规则

本应用**只有一条合法的跨设备调度路径**：

```
用户输入 → InputRouter → GalaxyWebSocketClient (task_submit) → Galaxy Gateway
```

其他调度入口（如旧版 `MessageRouter`、直接调用 `GalaxyWebSocketClient.send()`）受相同的 `crossDeviceEnabled` 开关约束。**不存在绕过该开关的调度路径。**

### 开启（ON）行为

开启时，原有 AIP v3、trace/route_mode、能力 schema 上报行为完全保持不变。

### 手动验证步骤

1. 将跨设备开关设为 OFF → 发送任何消息 → 确认消息走本地路径，Logcat 无 `[WS:UPLINK]` 日志
2. 将跨设备开关设为 OFF → 查看 Logcat → 无 `[WS:CONNECT]` 尝试
3. 将跨设备开关设为 ON → 正常连接和任务下发，行为同前几轮 PR
4. 运行单元测试：`./gradlew test --tests "com.ufo.galaxy.network.CrossDeviceSwitchTest"` 和 `./gradlew test --tests "com.ufo.galaxy.coordination.MultiDeviceCoordinatorTest"`

---

## 📡 WebRTC / TURN 多候选鲁棒性 (Round 6)

### 概述

Round 6 为 WebRTC 信令层增加了**多 ICE 候选（trickle/batch）** 和 **TURN 中继回退**支持，确保在 NAT/防火墙环境下仍能可靠建立 P2P 连接。

### 候选优先级顺序

候选按以下顺序应用（与服务端 Round 6 策略一致）：

| 优先级 | 类型 | 说明 |
|--------|------|------|
| 1（最高）| `relay` | TURN 中继候选，穿透 NAT/防火墙 |
| 2 | `srflx` | STUN 服务器反射候选 |
| 3 | `host` | 直接本地候选 |

### 新增信令消息格式

**多候选 + TURN 配置（Round 6）**：

```json
{
  "type": "ice_candidates",
  "candidates": [
    { "candidate": "candidate:0 ... typ host",  "sdpMid": "0", "sdpMLineIndex": 0 },
    { "candidate": "candidate:1 ... typ srflx", "sdpMid": "0", "sdpMLineIndex": 0 },
    { "candidate": "candidate:2 ... typ relay", "sdpMid": "0", "sdpMLineIndex": 0 }
  ],
  "turn_config": {
    "urls": ["turn:100.64.0.1:3478", "turns:100.64.0.1:5349"],
    "username": "galaxy_user",
    "credential": "s3cr3t"
  },
  "trace_id": "<UUID>"
}
```

旧版单候选格式（`type: "ice_candidate"`）继续支持，无需任何改动。

### TURN 配置

在 `assets/config.properties` 或 App 内配置界面中设置 TURN 服务器：

```properties
turn_server_url=turn:100.64.0.1:3478
turn_username=galaxy_user
turn_credential=s3cr3t
```

未配置 TURN 时，客户端仍可正常使用 STUN 候选；TURN 回退逻辑仅在收到 `relay` 类型候选时触发。

### TURN 回退策略

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `MAX_FALLBACK_ATTEMPTS` | 3 | 最大重试次数，超出后触发 `onError` 回调 |
| `FALLBACK_BACKOFF_MS` | `[1000, 2000, 4000]` ms | 指数退避间隔 |
| `CONNECTION_TIMEOUT_MS` | 10 000 ms | 触发回退前等待直连超时 |

当 ICE 直连失败时，`WebRTCSignalingClient.triggerTurnFallback()` 切换为仅使用 relay 候选并重新建立连接。

### 错误上报

所有错误均携带 `trace_id`，格式如下：

```
[trace=<UUID>] TURN fallback exhausted after 3 attempts
[trace=<UUID>] Gateway error: ICE gathering timeout
[trace=<UUID>] Signaling WS failure: Connection refused
```

### 相关文件

| 文件 | 职责 |
|------|------|
| `webrtc/SignalingMessage.kt` | 信令消息模型（多候选、TURN 配置、trace_id、error）|
| `webrtc/TurnConfig.kt` | TURN 服务器配置模型 |
| `webrtc/IceCandidateManager.kt` | 去重、优先级排序、TURN 回退 |
| `webrtc/WebRTCSignalingClient.kt` | OkHttp WebSocket 信令客户端 |
| `test/webrtc/SignalingMessageTest.kt` | JSON 序列化、多候选、TURN 配置单元测试 |
| `test/webrtc/IceCandidateManagerTest.kt` | 去重、优先级、回退、错误路径单元测试 |

### 运行测试

```bash
./gradlew test --tests "com.ufo.galaxy.webrtc.SignalingMessageTest"
./gradlew test --tests "com.ufo.galaxy.webrtc.IceCandidateManagerTest"
```


---

## PR-C4: TaskSubmitPayload & MsgType 对齐（AIP v3）

### 概述

本次变更将 `TaskSubmitPayload` 的结构与 AIP v3 服务端 schema 完全对齐，并统一客户端所有消息类型常量为 v3 命名，消除遗留 v2/legacy 类型字符串带来的路由风险。

### 核心变更

#### 1. `TaskSubmitPayload` v3 对齐

新增 `task_id` 字段（与 `AipMessage.correlation_id` 对应，方便服务端在 payload 层面关联请求与回复）：

```kotlin
data class TaskSubmitPayload(
    val task_text: String,   // 用户自然语言指令
    val device_id: String,   // 设备唯一标识
    val session_id: String,  // 会话级别标识
    val task_id: String = "", // ← PR-C4 新增：任务 ID，对应 AipMessage.correlation_id
    val context: TaskSubmitContext = TaskSubmitContext()
)
```

`validate()` 方法在发送前校验所有必填字段（`task_text`、`device_id`、`session_id` 均非空）。校验失败时 `InputRouter` 通过 `onError` 回调而非静默丢弃。

#### 2. `AipMessage` 信封 `protocol` 字段

所有出站 AIP 消息均携带 `protocol = "AIP/1.0"`（默认值，原有代码无感知升级）：

```kotlin
data class AipMessage(
    val type: MsgType,
    val payload: Any,
    val protocol: String = "AIP/1.0", // ← PR-C4 新增
    val version: String = "3.0",
    ...
)
```

序列化后的 JSON 示例：

```json
{
  "type": "task_submit",
  "protocol": "AIP/1.0",
  "version": "3.0",
  "correlation_id": "<task-uuid>",
  "device_id": "android_...",
  "payload": {
    "task_text": "打开微信",
    "device_id": "android_...",
    "session_id": "<session-uuid>",
    "task_id": "<task-uuid>"
  }
}
```

#### 3. `MsgType.LEGACY_TYPE_MAP` 与 `toV3Type()`

`MsgType` companion 对象新增 LEGACY_TYPE_MAP 供旧代码或外部输入做类型规范化：

| Legacy 字符串   | v3 等价             |
|-----------------|---------------------|
| `registration`  | `device_register`   |
| `register`      | `device_register`   |
| `heartbeat`     | `heartbeat`         |
| `command`       | `task_assign`       |
| `command_result`| `command_result`    |

使用示例：

```kotlin
val v3Type = MsgType.toV3Type("registration") // → "device_register"
```

新代码必须直接使用 `MsgType.XXX` 常量；`toV3Type()` 仅用于规范化外部（遗留/第三方）输入。

### 相关文件

| 文件 | 变更摘要 |
|------|----------|
| `protocol/AipModels.kt` | `AipMessage` 增加 `protocol` 字段；`TaskSubmitPayload` 增加 `task_id`、`validate()`；`MsgType` 增加 `LEGACY_TYPE_MAP`、`toV3Type()` |
| `input/InputRouter.kt` | `sendViaWebSocket` 传入 `task_id`；发送前调用 `TaskSubmitPayload.validate()` |
| `network/MessageRouter.kt` | `sendViaWebSocket` 传入 `task_id` |
| `test/protocol/TaskSubmitV3Test.kt` | 新增：v3 shape、envelope 字段、type 常量、LEGACY_TYPE_MAP、负向测试 |
| `test/protocol/AipModelsTest.kt` | 新增 `protocol` 字段断言、`task_id` 字段测试 |
| `test/input/InputRouterTest.kt` | 新增：发送 JSON 包含 `task_id`、`protocol`、`version` 断言 |

### 运行测试

```bash
./gradlew test --tests "com.ufo.galaxy.protocol.TaskSubmitV3Test"
./gradlew test --tests "com.ufo.galaxy.protocol.AipModelsTest"
./gradlew test --tests "com.ufo.galaxy.input.InputRouterTest"
```


---

## 从 v2 升级

本节为从 v2 客户端迁移到 v3 的集成方提供简明升级指南。

### 主要破坏性变更

| 项目 | v2 行为 | v3 行为 |
|------|---------|---------|
| 消息类型 | `"registration"`, `"command"` 等遗留字符串 | `"device_register"`, `"task_assign"` 等 v3 常量（见 `MsgType` 枚举） |
| 信封字段 | `protocol` 字段可能缺失 | **强制** `protocol="AIP/1.0"`, `version="3.0"` |
| `AIPMessageBuilder` | v3 字段为可选（`includeV3=false` 允许） | v3 字段**始终**包含（`includeV3` 默认 `true`，无法关闭） |
| `capability_report` | 无必填字段校验 | payload **必须**含 `platform`、`supported_actions`、`version` |
| `TaskSubmitPayload` | 无 `task_id` 字段 | **新增** `task_id`（与 `AipMessage.correlation_id` 对应） |
| `EnhancedAIPClient` | 无 Microsoft 兼容层 | 默认开启 `ms_*` 补充字段（可通过 `microsoftMappingEnabled=false` 关闭） |

### 迁移步骤

1. **替换消息类型字符串**：将所有硬编码的遗留类型字符串替换为 `MsgType` 枚举值；
   或在读取外部输入时通过 `MsgType.toV3Type("registration")` 规范化。

2. **检查 `capability_report` payload**：确保 payload 含 `platform`、`supported_actions` 数组和 `version` 字符串。

3. **`TaskSubmitPayload` 中补充 `task_id`**：从 `AipMessage.correlation_id` 复制或生成唯一 ID；
   发送前调用 `payload.validate()` 确认必填字段完整。

4. **`EnhancedAIPClient` Microsoft 映射**：若对接非 Microsoft 端点，请设置 `microsoftMappingEnabled = false`。

---

## PR-C5: 文档/示例 & 自动化测试收尾（AIP v3）

### 概述

PR-C5 是 C1–C4 系列的收尾 PR，补全文档多语言示例、统一 example 测试覆盖、并完成 CI 卫生检查。

### AIP v3 信封格式（所有消息通用）

所有出站消息均包含以下顶层字段：

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "<msg_type>",
  "source_node": "<device_id>",
  "target_node": "Galaxy",
  "timestamp": 1710000000,
  "message_id": "a1b2c3d4",
  "device_id":  "<device_id>",
  "device_type": "Android_Agent",
  "payload": { ... }
}
```

### 核心消息类型示例

#### device_register

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "device_register",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp": 1710000000,
  "message_id": "a1b2c3d4",
  "device_id":  "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "platform":    "android",
    "os_version":  "14",
    "hardware": {
      "manufacturer": "Google",
      "model":  "Pixel 8",
      "device": "husky"
    },
    "tools": [
      "location", "camera", "sensor_data", "automation",
      "notification", "sms", "phone_call", "contacts",
      "calendar", "voice_input", "screen_capture", "app_control"
    ],
    "capabilities": {
      "nlu": false,
      "hardware_control": true,
      "sensor_access":    true,
      "network_access":   true,
      "ui_automation":    true
    }
  }
}
```

#### heartbeat

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "heartbeat",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp": 1710000030,
  "message_id": "b2c3d4e5",
  "device_id":  "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": { "status": "online" }
}
```

#### capability_report

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "capability_report",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp": 1710000001,
  "message_id": "c3d4e5f6",
  "device_id":  "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "platform": "android",
    "supported_actions": [
      "location", "camera", "sensor_data", "automation",
      "notification", "sms", "phone_call", "contacts",
      "calendar", "voice_input", "screen_capture", "app_control"
    ],
    "version": "3.0.0"
  }
}
```

#### task_assign（下行，服务端 → 设备）

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "task_assign",
  "source_node": "Galaxy",
  "target_node": "android_pixel8_01",
  "timestamp": 1710000060,
  "message_id": "d4e5f6a7",
  "payload": {
    "task_id": "task-uuid-001",
    "goal":    "打开微信并发送「你好」",
    "constraints": ["不得访问联系人列表"],
    "max_steps": 10,
    "require_local_agent": true
  }
}
```

#### command_result（上行，设备 → 服务端）

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "command_result",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp": 1710000062,
  "message_id": "e5f6a7b8",
  "device_id":  "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "task_id": "task-uuid-001",
    "step_id": "1",
    "action":  "tap",
    "status":  "success"
  }
}
```

#### task_submit（上行，用户发起）

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "task_submit",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp": 1710000055,
  "message_id": "f6a7b8c9",
  "device_id":  "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "task_text":  "帮我打开导航去最近的星巴克",
    "device_id":  "android_pixel8_01",
    "session_id": "sess-20260316-001",
    "task_id":    "task-uuid-055",
    "context": {
      "locale":         "zh-CN",
      "app_foreground": "com.android.launcher3"
    }
  }
}
```

### Microsoft 兼容映射示例（EnhancedAIPClient）

当 `microsoftMappingEnabled = true`（默认）时，每条出站消息在 v3 信封基础上**追加** `ms_*` 补充字段：

```json
{
  "protocol":      "AIP/1.0",
  "version":       "3.0",
  "type":          "device_register",
  "source_node":   "android_pixel8_01",
  "target_node":   "Galaxy",
  "timestamp":     1710000000,
  "message_id":    "a1b2c3d4",
  "device_id":     "android_pixel8_01",
  "device_type":   "Android_Agent",
  "payload":       { "platform": "android", "..." : "..." },
  "ms_message_type": "REGISTER",
  "ms_agent_id":     "android_pixel8_01",
  "ms_session_id":   1710000000000
}
```

v3 字段（`protocol`、`version`、`type` 等）**始终保持不变**；`ms_*` 字段为纯补充，不影响 v3 路由。

在代码中关闭 Microsoft 映射（适用于非 Microsoft 端点）：

```kotlin
val client = EnhancedAIPClient(
    deviceId   = "android_pixel8_01",
    galaxyUrl  = "wss://your-gateway-host:8765",
    context    = applicationContext
)
client.microsoftMappingEnabled = false  // 关闭后出站消息为纯 v3，无 ms_* 字段
client.connect()
```

### v3 消息类型映射表

| v3 消息类型         | Microsoft `ms_message_type` | 方向    | 说明                       |
|---------------------|-----------------------------|---------|----------------------------|
| `device_register`   | `REGISTER`                  | 上行    | 设备注册握手               |
| `heartbeat`         | `HEARTBEAT`                 | 上行    | 周期性保活（每 30 秒）     |
| `capability_report` | `CAPABILITY_REPORT`         | 上行    | 能力声明，注册成功后立即发 |
| `task_assign`       | `TASK`                      | 下行    | 服务端下发任务             |
| `command_result`    | `COMMAND_RESULTS`           | 上行    | 步骤级执行结果             |
| `task_submit`       | —                           | 上行    | 用户发起任务请求           |
| `task_result`       | —                           | 上行    | 任务级完成结果             |
| `task_cancel`       | —                           | 下行    | 服务端取消任务             |
| `cancel_result`     | —                           | 上行    | 取消确认                   |

### 相关文件

| 文件 | 变更摘要 |
|------|----------|
| `README.md` | 版本升级至 v3.0.0；新增从 v2 升级指南；补全所有 6 种核心消息类型示例 |
| `docs/CHANGELOG.md` | 新增：C1–C5 全系列 v3 迁移变更记录 |
| `docs/AIP_V3_EXAMPLES.md` | 新增：完整 AIP v3 示例载荷文档 |
| `test/protocol/ExamplePayloadsTest.kt` | 新增：6 种核心消息类型可运行示例测试 |
| `test/client/MicrosoftMappingExampleTest.kt` | 新增：Microsoft 映射开关示例测试 |

### 运行测试

```bash
./gradlew test --tests "com.ufo.galaxy.protocol.ExamplePayloadsTest"
./gradlew test --tests "com.ufo.galaxy.client.MicrosoftMappingExampleTest"
# 运行全部协议测试
./gradlew test --tests "com.ufo.galaxy.protocol.*"
```
